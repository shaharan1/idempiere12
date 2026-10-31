package org.mycompany.requisition.approval.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.model.SlaState;
import org.mycompany.requisition.approval.model.StepStatus;

/**
 * Watches the service level of the pending steps.
 * <p>
 * {@link ApprovalStep#recomputeSlaState(Timestamp)} derives the state from the
 * clock on every read, so this class never has to decide what "overdue" means - it
 * only has to act on the answer. That split is why a stored SLA state can never
 * disagree with the due date: there is nothing stored that could disagree.
 *
 * <h3>Escalation</h3>
 * Escalation is sent once. The step keeps the moment it was escalated, so a
 * scheduler that runs every minute does not send the same escalation all day.
 * Escalation marks the step and raises the event; the actual delivery belongs to
 * {@link NotificationService}, which reads the notification settings of the
 * process version rather than hard coding who to tell.
 */
public class SlaMonitor extends BaseConfigService {

	private static final String T_STEP = "XX_APPROVAL_STEP";

	/** Access to the step table, reusing the column plumbing of the base class. */
	private final BaseConfigService stepAccess = new BaseConfigService(T_STEP) {
	};

	public SlaMonitor() throws ConfigException {
		super(T_STEP);
	}

	/**
	 * Recomputes the SLA of every open step of a transaction and escalates the
	 * ones that are past their escalation point.
	 * <p>
	 * Safe to call from a screen as often as it likes: only a step whose derived
	 * state actually changed is written, and an escalation that already happened is
	 * never repeated.
	 *
	 * @param transactionId transaction to check
	 * @return the number of steps that changed or were escalated
	 */
	public int refresh(int transactionId) {
		return refresh(transactionId, new Timestamp(System.currentTimeMillis()));
	}

	int refresh(int transactionId, Timestamp now) {
		if (transactionId <= 0)
			return 0;

		ApprovalTransaction chain = loadOrNull(transactionId);
		if (chain == null)
			return 0;

		int changed = 0;
		for (ApprovalStep step : chain.getSteps()) {
			if (!step.getStatus().isOpen())
				continue;

			SlaState before = step.getSlaState();
			step.recomputeSlaState(now);
			if (step.getSlaState() != before) {
				writeSla(step, now);
				changed++;
			}

			if (step.getSlaState() == SlaState.OVERDUE && shouldEscalate(step, now)) {
				if (escalate(step, now))
					changed++;
			}
		}
		return changed;
	}

	/**
	 * Steps of a transaction that a person has to look at now.
	 * <p>
	 * Only the steps whose turn it is. A waiting level that is already past its due
	 * date is not overdue yet, because the clock on it only starts when it opens.
	 */
	public List<ApprovalStep> needingAttention(int transactionId) {
		List<ApprovalStep> result = new ArrayList<ApprovalStep>();
		ApprovalTransaction chain = loadOrNull(transactionId);
		if (chain == null)
			return result;
		Timestamp now = new Timestamp(System.currentTimeMillis());
		for (ApprovalStep step : chain.getSteps()) {
			if (!step.getStatus().isOpen())
				continue;
			step.recomputeSlaState(now);
			if (step.getSlaState().needsAttention())
				result.add(step);
		}
		return result;
	}

	/**
	 * Every open step of the tenant that is past its due date.
	 * <p>
	 * This is the entry point for a scheduled job: it returns what needs to be
	 * escalated without changing anything, so a caller can decide whether to run
	 * the escalation or only report it.
	 */
	public List<ApprovalStep> listOverdue() {
		List<ApprovalStep> result = new ArrayList<ApprovalStep>();

		// DueDate is selected because PostgreSQL rejects an ORDER BY on an
		// expression a DISTINCT does not list; the rows are de-duplicated here.
		String sql = "SELECT XX_PROCESS_TRANSACTION_ID, DueDate FROM " + T_STEP
				+ " WHERE IsActive='Y' AND AD_Client_ID=? AND Status='PENDING' AND DueDate IS NOT NULL"
				+ " AND DueDate < ? ORDER BY DueDate, XX_PROCESS_TRANSACTION_ID";

		java.util.Set<Integer> transactions = new java.util.LinkedHashSet<Integer>();
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		Timestamp now = new Timestamp(System.currentTimeMillis());
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, clientId());
			pstmt.setTimestamp(2, now);
			rs = pstmt.executeQuery();
			while (rs.next())
				transactions.add(Integer.valueOf(rs.getInt(1)));
		} catch (java.sql.SQLException e) {
			transactions.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}

		for (Integer transactionId : transactions)
			result.addAll(needingAttention(transactionId.intValue()));
		return result;
	}

	/**
	 * True when the escalation point of the level has been reached.
	 * <p>
	 * The escalation window is measured from the moment the step opened, because a
	 * level that opens late must not be escalated immediately.
	 */
	private boolean shouldEscalate(ApprovalStep step, Timestamp now) {
		if (step.getEscalatedDate() != null)
			return false;
		ApprovalLevel level = step.getLevel();
		if (level == null || !level.isEscalationEnabled())
			return false;
		if (level.getEscalationAfterHours() == null || level.getEscalationAfterHours().intValue() <= 0)
			return false;
		if (step.getOpenedDate() == null)
			return false;
		long window = level.getEscalationAfterHours().longValue() * 60L * 60L * 1000L;
		return step.getOpenedDate().getTime() + window <= now.getTime();
	}

	/**
	 * Marks a step as escalated and raises the event.
	 *
	 * @return true when this call performed the escalation
	 */
	private boolean escalate(ApprovalStep step, Timestamp now) {
		ApprovalLevel level = step.getLevel();
		int escalateTo = level == null ? 0 : level.getEscalationUserId();
		if (escalateTo <= 0 && level != null && level.getEscalationRoleId() > 0)
			escalateTo = firstUserOfRole(level.getEscalationRoleId());

		String trxName = newTrx("SlaEsc");
		try {
			PO po = stepAccess.load(step.getId(), trxName);
			if (po == null)
				return false;

			// re-read the flag inside the transaction, so two schedulers running
			// at the same moment cannot both escalate the same step
			Object already = stepAccess.get(po, "EscalatedDate");
			if (already instanceof Timestamp)
				return false;

			stepAccess.set(po, "EscalatedDate", now);
			stepAccess.set(po, "EscalatedBy_AD_User_ID", escalateTo > 0 ? Integer.valueOf(escalateTo) : null);
			stepAccess.set(po, "SLAState", SlaState.ESCALATED.name());
			stepAccess.save(po, trxName);
			commit(trxName);

			step.setEscalatedDate(now);
			step.setEscalatedByUserId(escalateTo);
			step.setSlaState(SlaState.ESCALATED);
			notifyEscalation(step, escalateTo, now);
			return true;

		} catch (ConfigException e) {
			rollback(trxName);
			log.log(java.util.logging.Level.WARNING,
					"Step " + step.getId() + " could not be escalated: " + e.getMessage(), e);
			return false;
		}
	}

	/** The escalation event, delivered through the notification settings. */
	private void notifyEscalation(ApprovalStep step, int escalateTo, Timestamp now) {
		try {
			ApprovalTransaction chain = new WorkflowEngine().loadById(step.getTransactionId());
			if (chain == null)
				return;
			new NotificationService().notify(chain,
					org.mycompany.requisition.approval.config.NotificationSetting.Event.ESCALATION, step, now);
		} catch (ConfigException e) {
			// the escalation is already recorded, a missing notice must not undo it
			log.log(java.util.logging.Level.WARNING,
					"Escalation notice for step " + step.getId() + " could not be raised", e);
		}
	}

	private void writeSla(ApprovalStep step, Timestamp now) {
		String trxName = newTrx("SlaState");
		try {
			PO po = stepAccess.load(step.getId(), trxName);
			if (po == null)
				return;
			stepAccess.set(po, "SLAState", step.getSlaState().name());
			stepAccess.save(po, trxName);
			commit(trxName);
		} catch (ConfigException e) {
			rollback(trxName);
			log.log(java.util.logging.Level.WARNING,
					"SLA state of step " + step.getId() + " could not be stored", e);
		}
	}

	/** First active user of a role, used when a level escalates to a role. */
	private int firstUserOfRole(int roleId) {
		return org.compiere.util.DB.getSQLValue(null,
				"SELECT MIN(AD_User_ID) FROM AD_User_Roles WHERE AD_Role_ID=? AND IsActive='Y'",
				Integer.valueOf(roleId));
	}

	/**
	 * Open steps of the tenant that have no approver at all.
	 * <p>
	 * A step like this would sit in pending forever without anybody noticing. It
	 * can only happen when a level uses a dynamic responsible type, so it is a
	 * configuration problem and the dashboard reports it as such.
	 */
	public List<ApprovalStep> listUnassigned() {
		List<ApprovalStep> result = new ArrayList<ApprovalStep>();
		String sql = "SELECT XX_PROCESS_TRANSACTION_ID FROM " + T_STEP
				+ " WHERE IsActive='Y' AND AD_Client_ID=? AND Status='"
				+ StepStatus.PENDING.name() + "' AND COALESCE(Approver_AD_User_ID,0)=0"
				+ " AND COALESCE(Approver_AD_Role_ID,0)=0 ORDER BY Created";
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, clientId());
			rs = pstmt.executeQuery();
			while (rs.next())
				result.addAll(unassignedOf(rs.getInt(1)));
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	private List<ApprovalStep> unassignedOf(int transactionId) {
		List<ApprovalStep> result = new ArrayList<ApprovalStep>();
		ApprovalTransaction chain = loadOrNull(transactionId);
		if (chain == null)
			return result;
		for (ApprovalStep step : chain.getSteps())
			if (step.getStatus() == StepStatus.PENDING && step.getApproverUserId() <= 0
					&& step.getApproverRoleId() <= 0)
				result.add(step);
		return result;
	}

	/** Table accessor, so a caller can reuse the step table handle. */
	MTable stepTable() {
		return getTable();
	}

	/**
	 * The transaction, or null when it cannot be read.
	 * <p>
	 * A scheduler walking the tenant must not stop at the first unreadable
	 * transaction, and a screen must not fail because the approval tables are not
	 * installed. Both reasons are served by degrading to null and logging.
	 */
	private ApprovalTransaction loadOrNull(int transactionId) {
		try {
			return new WorkflowEngine().loadById(transactionId);
		} catch (ConfigException e) {
			log.log(java.util.logging.Level.WARNING,
					"Approval transaction " + transactionId + " could not be read", e);
			return null;
		}
	}
}
