package org.mycompany.requisition.approval.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.compiere.model.MNote;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.mycompany.requisition.approval.config.NotificationSetting;
import org.mycompany.requisition.approval.config.NotificationSetting.Channel;
import org.mycompany.requisition.approval.config.NotificationSetting.Event;
import org.mycompany.requisition.approval.config.NotificationSetting.SendTo;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.ApprovalTransaction;

/**
 * Sends the notifications configured per process version.
 * <p>
 * Which event reaches whom and through which channel is data, held in
 * {@code XX_NOTIFICATION_SETTING}. No recipient and no channel is written into
 * this class, so a tenant configures its own process and this file never changes.
 *
 * <h3>Delivery</h3>
 * An in application notice is an {@code AD_Note} row, which is what the iDempiere
 * inbox reads. Mail is queued as an {@code AD_UserMail} row with
 * {@code IsDelivered = 'N'}, so the configured mail processor delivers it on its
 * own schedule rather than blocking an approval action on an SMTP round trip. A
 * notification that fails is logged and dropped: an approval that cannot be
 * recorded because a mail server is down would be far worse than a missing mail.
 */
public class NotificationService extends BaseConfigService {

	private static final CLogger log = CLogger.getCLogger(NotificationService.class);

	private static final String T_SETTING = "XX_NOTIFICATION_SETTING";

	public NotificationService() throws ConfigException {
		super(T_SETTING);
	}

	// ─────────────────────────────────────────────────────────────
	//  Reading the configuration
	// ─────────────────────────────────────────────────────────────

	/** Notification settings of one version, ordered by event. */
	public List<NotificationSetting> listSettings(int processVersionId) {
		List<NotificationSetting> result = new ArrayList<NotificationSetting>();
		String sql = "SELECT XX_NOTIFICATION_SETTING_ID, XX_PROCESS_VERSION_ID, Event, SendTo, Channel,"
				+ " Subject, MessageBody, Description FROM " + T_SETTING
				+ " WHERE XX_PROCESS_VERSION_ID=? AND IsActive='Y' ORDER BY Event, SendTo";
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, processVersionId);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				NotificationSetting s = new NotificationSetting();
				s.setId(rs.getInt(1));
				s.setProcessVersionId(rs.getInt(2));
				s.setEvent(Event.of(rs.getString(3)));
				s.setSendTo(SendTo.of(rs.getString(4)));
				s.setChannel(Channel.of(rs.getString(5)));
				s.setSubject(rs.getString(6));
				s.setMessageBody(rs.getString(7));
				s.setDescription(rs.getString(8));
				result.add(s);
			}
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	/** Creates a notification setting. */
	public int create(NotificationSetting setting) throws ConfigException {
		if (setting == null || setting.getProcessVersionId() <= 0)
			throw new ConfigException("The process version of the notification is unknown.");

		final String trxName = newTrx("Notif");
		return inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_SETTING).getPO(0, trxName);
			if (po == null)
				throw new ConfigException("Table " + T_SETTING + " is not registered in iDempiere.");
			po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));
			apply(po, setting);
			save(po, trxName);
			return Integer.valueOf(po.get_ID());
		}).intValue();
	}

	/** Updates an existing notification setting. */
	public void update(NotificationSetting setting) throws ConfigException {
		if (setting == null || setting.getId() <= 0)
			throw new ConfigException("No notification is selected for updating.");

		final String trxName = newTrx("NotifUpd");
		inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_SETTING).getPO(setting.getId(), trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Notification " + setting.getId() + " was not found.");
			apply(po, setting);
			save(po, trxName);
			return null;
		});
	}

	/** Deactivates a notification setting, keeping the row for the audit. */
	public void delete(int settingId) throws ConfigException {
		if (settingId <= 0)
			return;
		final String trxName = newTrx("NotifDel");
		inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_SETTING).getPO(settingId, trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Notification " + settingId + " was not found.");
			set(po, "IsActive", "N");
			save(po, trxName);
			return null;
		});
	}

	private void apply(PO po, NotificationSetting setting) {
		set(po, "XX_PROCESS_VERSION_ID", Integer.valueOf(setting.getProcessVersionId()));
		set(po, "Event", setting.getEvent().name());
		set(po, "SendTo", setting.getSendTo().name());
		set(po, "Channel", setting.getChannel().name());
		set(po, "Subject", trim(setting.getSubject()));
		set(po, "MessageBody", trim(setting.getMessageBody()));
		set(po, "Description", trim(setting.getDescription()));
		set(po, "IsActive", "Y");
	}

	// ─────────────────────────────────────────────────────────────
	//  Sending
	// ─────────────────────────────────────────────────────────────

	/**
	 * Raises an event for a whole transaction.
	 * <p>
	 * Delivered in its own transaction. A notification that cannot be stored must
	 * not roll back the approval action that triggered it - the action is already
	 * correct, and losing it would be worse than losing the notice.
	 *
	 * @param chain the transaction
	 * @param event what happened
	 * @return number of notifications actually stored
	 */
	public int notify(ApprovalTransaction chain, Event event) throws ConfigException {
		return notify(chain, event, null, new java.sql.Timestamp(System.currentTimeMillis()));
	}

	/**
	 * Raises an event for a transaction, optionally about one step.
	 *
	 * @param chain  the transaction
	 * @param event  what happened
	 * @param step   the step the event is about, may be null
	 * @param now    moment of the event, used in the text
	 * @return number of notifications actually stored
	 */
	public int notify(ApprovalTransaction chain, Event event, ApprovalStep step, java.sql.Timestamp now)
			throws ConfigException {

		if (chain == null || event == null || chain.getProcessVersionId() <= 0)
			return 0;

		List<NotificationSetting> settings = listSettings(chain.getProcessVersionId());
		if (settings.isEmpty())
			return 0;

		String subject = expand(defaultSubject(event), chain, step);
		String body = expand(defaultBody(event, chain, step), chain, step);

		int stored = 0;
		// a transaction may match the same recipient through two settings, and one
		// person must not receive the same notice twice
		Set<Integer> alreadyNotified = new LinkedHashSet<Integer>();
		for (NotificationSetting setting : settings) {
			if (setting.getEvent() != event)
				continue;
			for (Integer userId : recipients(setting.getSendTo(), chain, step)) {
				Integer key = Integer.valueOf(userId.intValue() * 1000 + channelRank(setting.getChannel()));
				if (!alreadyNotified.add(key))
					continue;
				if (deliver(userId.intValue(), setting.getChannel(), subject, body))
					stored++;
			}
		}
		return stored;
	}

	/** Who should hear about this event. */
	private List<Integer> recipients(SendTo sendTo, ApprovalTransaction chain, ApprovalStep step) {
		List<Integer> result = new ArrayList<Integer>();
		if (sendTo == null)
			return result;

		switch (sendTo) {
		case APPROVER:
			addAll(result, approversOf(chain));
			break;
		case REQUESTER:
			addIfPositive(result, chain.getRequestedBy());
			break;
		case MANAGER:
			addAll(result, supervisorsOf(chain.getRequestedBy()));
			break;
		case ESCALATION_USER:
			if (step != null)
				addIfPositive(result, escalationTarget(step));
			break;
		case ADMINISTRATOR:
			addAll(result, administrators());
			break;
		default:
			break;
		}
		return result;
	}

	/** Approvers of the steps that are open right now. */
	private List<Integer> approversOf(ApprovalTransaction chain) {
		List<Integer> result = new ArrayList<Integer>();
		for (ApprovalStep step : chain.getSteps())
			if (step.getStatus().isOpen()) {
				if (step.getApproverUserId() > 0)
					addIfPositive(result, step.getApproverUserId());
				if (step.getApproverRoleId() > 0)
					addAll(result, usersOfRole(step.getApproverRoleId()));
			}
		return result;
	}

	/**
	 * The user a step escalates to.
	 * <p>
	 * Once the step has actually been escalated the recorded target wins over the
	 * configured one, because that is the person who was really told.
	 */
	private int escalationTarget(ApprovalStep step) {
		if (step.getEscalatedByUserId() > 0)
			return step.getEscalatedByUserId();
		if (step.getLevel() == null)
			return 0;
		return step.getLevel().getEscalationUserId();
	}

	/**
	 * The supervisor of a user, taken from {@code AD_User.Supervisor_ID}.
	 * <p>
	 * That is the field iDempiere itself uses for the reporting line, so it needs
	 * no configuration of this framework. When a user has no supervisor recorded
	 * the administrators are the fallback, which is better than telling nobody
	 * that a document is stuck.
	 */
	private List<Integer> supervisorsOf(int userId) {
		List<Integer> result = new ArrayList<Integer>();
		if (userId <= 0)
			return result;

		int supervisor = org.compiere.util.DB.getSQLValue(null,
				"SELECT COALESCE(Supervisor_ID, 0) FROM AD_User WHERE AD_User_ID=?", Integer.valueOf(userId));
		if (supervisor > 0 && supervisor != userId) {
			addIfPositive(result, supervisor);
			return result;
		}

		addAll(result, administrators());
		return result;
	}

	/** Users holding the system administrator role, resolved by name. */
	private List<Integer> administrators() {
		List<Integer> result = new ArrayList<Integer>();
		String sql = "SELECT DISTINCT ur.AD_User_ID FROM AD_User_Roles ur"
				+ " INNER JOIN AD_Role r ON r.AD_Role_ID=ur.AD_Role_ID"
				+ " INNER JOIN AD_User u ON u.AD_User_ID=ur.AD_User_ID AND u.IsActive='Y'"
				+ " WHERE ur.IsActive='Y' AND UPPER(r.Name)=UPPER('System Administrator')"
				+ " AND u.AD_Client_ID IN (0,?) ORDER BY ur.AD_User_ID";
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, clientId());
			rs = pstmt.executeQuery();
			while (rs.next())
				addIfPositive(result, rs.getInt(1));
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	private List<Integer> usersOfRole(int roleId) {
		List<Integer> result = new ArrayList<Integer>();
		String sql = "SELECT DISTINCT ur.AD_User_ID FROM AD_User_Roles ur"
				+ " INNER JOIN AD_User u ON u.AD_User_ID=ur.AD_User_ID AND u.IsActive='Y'"
				+ " WHERE ur.AD_Role_ID=? AND ur.IsActive='Y' ORDER BY ur.AD_User_ID";
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, roleId);
			rs = pstmt.executeQuery();
			while (rs.next())
				addIfPositive(result, rs.getInt(1));
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	private void addIfPositive(List<Integer> list, int value) {
		if (value > 0 && !list.contains(Integer.valueOf(value)))
			list.add(Integer.valueOf(value));
	}

	private void addAll(List<Integer> list, List<Integer> values) {
		for (Integer v : values)
			addIfPositive(list, v.intValue());
	}

	/**
	 * Two settings of the same channel count as one notification, notice and mail
	 * are two different things, and a user configured for both gets both.
	 */
	private int channelRank(Channel channel) {
		if (channel == Channel.MAIL)
			return 2;
		if (channel == Channel.NOTICE_AND_MAIL)
			return 3;
		return 1;
	}

	// ─────────────────────────────────────────────────────────────
	//  Delivery
	// ─────────────────────────────────────────────────────────────

	/** Stores the notice and/or the mail, never raising. */
	private boolean deliver(int userId, Channel channel, String subject, String body) throws ConfigException {
		boolean anything = false;
		if (channel == null || channel == Channel.NOTICE || channel == Channel.NOTICE_AND_MAIL)
			anything |= sendNotice(userId, subject, body);
		if (channel == Channel.MAIL || channel == Channel.NOTICE_AND_MAIL)
			anything |= queueMail(userId, subject, body);
		return anything;
	}

	/**
	 * In application notice, which is what the iDempiere inbox reads.
	 * <p>
	 * {@code AD_Message_ID = 0} resolves to the "no message registered" entry, so
	 * the text travels in {@code TextMsg}. That is how iDempiere itself sends a
	 * notice whose text it built itself.
	 */
	private boolean sendNotice(int userId, String subject, String body) throws ConfigException {
		String trxName = newTrx("Notice");
		try {
			MNote note = new MNote(Env.getCtx(), 0, trxName);
			note.setAD_Org_ID(0);
			note.setAD_User_ID(userId);
			note.setAD_Message_ID(0);
			note.setReference(subject);
			note.setTextMsg(body);
			note.saveEx(trxName);
			commit(trxName);
			return true;
		} catch (RuntimeException e) {
			rollback(trxName);
			log.log(java.util.logging.Level.WARNING, "Notice to user " + userId + " could not be stored", e);
			return false;
		}
	}

	/**
	 * Queues the mail in {@code AD_UserMail} for the mail processor.
	 * <p>
	 * Queued rather than sent, so an approval action never waits for an SMTP round
	 * trip and a mail server outage cannot fail an approval.
	 */
	private boolean queueMail(int userId, String subject, String body) throws ConfigException {
		String address = mailAddress(userId);
		if (address == null) {
			log.log(java.util.logging.Level.INFO,
					"No mail address for user " + userId + ", the mail notification was skipped");
			return false;
		}

		String trxName = newTrx("Mail");
		try {
			PO po = MTable.get(ctx(), "AD_UserMail").getPO(0, trxName);
			if (po == null)
				throw new IllegalStateException("Table AD_UserMail is not registered in iDempiere.");
			po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));
			po.set_ValueOfColumnReturningBoolean("AD_User_ID", Integer.valueOf(userId));
			po.set_ValueOfColumnReturningBoolean("RecipientTo", address);
			po.set_ValueOfColumnReturningBoolean("Subject", subject);
			po.set_ValueOfColumnReturningBoolean("MailText", body);
			po.set_ValueOfColumnReturningBoolean("IsDelivered", "N");
			if (!po.save(trxName))
				throw new IllegalStateException("AD_UserMail row was not stored.");
			commit(trxName);
			return true;
		} catch (RuntimeException e) {
			rollback(trxName);
			log.log(java.util.logging.Level.WARNING, "Mail to user " + userId + " could not be queued", e);
			return false;
		}
	}

	/**
	 * Mail address of a user, but only when the user may actually receive mail.
	 * <p>
	 * An address that iDempiere considers unverified or not sendable is skipped
	 * rather than queued, so a rejected approval is not left in the outbox
	 * forever.
	 */
	private String mailAddress(int userId) {
		Properties ctx = Env.getCtx();
		MUser user = MUser.get(ctx, userId);
		if (user == null || !user.isActive())
			return null;
		String address = user.getEMail();
		if (address == null || address.trim().length() == 0)
			return null;
		if (!user.isCanSendEMail())
			return null;
		return address.trim();
	}

	// ─────────────────────────────────────────────────────────────
	//  Text
	// ─────────────────────────────────────────────────────────────

	private String defaultSubject(Event event) {
		switch (event) {
		case APPROVAL_PENDING:
			return "Document {DocumentNo} needs your approval";
		case APPROVAL_REMINDER:
			return "Reminder: document {DocumentNo} is waiting for your approval";
		case APPROVAL_OVERDUE:
			return "Overdue: document {DocumentNo} is waiting for your approval";
		case ESCALATION:
			return "Escalated: document {DocumentNo} is waiting for approval";
		case APPROVAL_COMPLETED:
			return "Document {DocumentNo} has been approved";
		case APPROVAL_REJECTED:
			return "Document {DocumentNo} has been rejected";
		case APPROVAL_RETURNED:
			return "Document {DocumentNo} has been returned";
		case DELEGATION_RECORDED:
			return "Approval for document {DocumentNo} has been delegated";
		default:
			return "Document {DocumentNo}: approval update";
		}
	}

	private String defaultBody(Event event, ApprovalTransaction chain, ApprovalStep step) {
		StringBuilder sb = new StringBuilder();
		sb.append("Document: {DocumentNo}").append('\n');
		if (chain.getAmount() != null)
			sb.append("Amount: {Amount}").append('\n');
		if (step != null)
			sb.append("Level: {LevelNo} - {LevelName}").append('\n');
		sb.append("Requested by: {Requester}").append('\n');
		sb.append("Status: {Status}").append('\n');
		if (step != null && step.getDueDate() != null)
			sb.append("Due: {DueDate}").append('\n');
		sb.append('\n').append("Route: {Route}");
		if (event == Event.APPROVAL_COMPLETED || event == Event.APPROVAL_REJECTED
				|| event == Event.APPROVAL_RETURNED)
			sb.append('\n').append("This document is {Action}.");
		return sb.toString();
	}

	/**
	 * Replaces the placeholders of a configured text.
	 * <p>
	 * A configuration may only use the placeholders it knows, so an unknown one is
	 * left as it is instead of being turned into an error the administrator has to
	 * decode from a log.
	 */
	private String expand(String template, ApprovalTransaction chain, ApprovalStep step) {
		if (template == null)
			return "";
		String result = template;
		result = result.replace("{DocumentNo}", chain.getDisplayName());
		result = result.replace("{Amount}",
				chain.getAmount() == null ? "" : chain.getAmount().toPlainString());
		result = result.replace("{Requester}", userName(chain.getRequestedBy()));
		result = result.replace("{Status}", chain.getStatus().getLabel());
		result = result.replace("{Action}", chain.getLastAction() == null ? "" : chain.getLastAction().getLabel());
		result = result.replace("{LevelNo}", step == null ? "" : String.valueOf(step.getLevelNo()));
		result = result.replace("{LevelName}",
				step == null || step.getLevel() == null || step.getLevel().getLevelName() == null ? ""
						: step.getLevel().getLevelName());
		result = result.replace("{DueDate}", step == null || step.getDueDate() == null ? ""
				: step.getDueDate().toString());
		result = result.replace("{Route}", routeOf(chain));
		return result;
	}

	/** Human readable chain, used by the default body. */
	private String routeOf(ApprovalTransaction chain) {
		StringBuilder sb = new StringBuilder();
		for (ApprovalStep step : chain.getSteps()) {
			if (sb.length() > 0)
				sb.append(" -> ");
			String name = step.getLevel() == null ? null : step.getLevel().getLevelName();
			sb.append(step.getLevelNo()).append(' ').append(name == null ? "" : name);
			sb.append(" (").append(step.getStatus().getLabel()).append(')');
		}
		return sb.length() == 0 ? "no approval required" : sb.toString();
	}

	private String userName(int userId) {
		if (userId <= 0)
			return "";
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_User WHERE AD_User_ID=?", Integer.valueOf(userId));
		return name == null ? String.valueOf(userId) : name;
	}
}
