package org.mycompany.requisition.approval.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.PO;
import org.mycompany.requisition.approval.model.ApprovalAction;
import org.mycompany.requisition.approval.model.ApprovalHistoryEntry;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.StepStatus;

/**
 * The only writer of {@code XX_APPROVAL_HISTORY}.
 * <p>
 * The audit trail of an approval is the one thing in this framework that must
 * never be edited. This class therefore exposes no update and no delete at all -
 * there is no method a caller could misuse. Rows are inserted inside the caller's
 * transaction, so the history of a failed transition is rolled back together with
 * the transition itself and cannot disagree with it.
 *
 * @see WorkflowEngine
 */
public class HistoryWriter extends BaseConfigService {

	public HistoryWriter() throws ConfigException {
		super("XX_APPROVAL_HISTORY");
	}

	/**
	 * Records one state change.
	 * <p>
	 * Exactly one row is written per state change, as the design requires: the
	 * row is the reason the chain can never drift away from what is stored.
	 *
	 * @param transactionId transaction the row belongs to
	 * @param step          the step the action was taken on, may be null for
	 *                      document level events such as submit
	 * @param action        what was done
	 * @param userId        who did it
	 * @param roleId        the role they were acting in
	 * @param comment       free text, mandatory for reject and return
	 * @param when          moment of the action
	 * @param trxName       caller transaction
	 * @return id of the inserted row
	 */
	public int append(int transactionId, ApprovalStep step, ApprovalAction action, int userId, int roleId,
			String comment, Timestamp when, String trxName) throws ConfigException {

		if (transactionId <= 0)
			throw new ConfigException("The history row has no transaction.");
		if (action == null)
			throw new ConfigException("The history row has no action.");

		PO po = newRecord(trxName);
		set(po, "XX_PROCESS_TRANSACTION_ID", Integer.valueOf(transactionId));

		if (step != null) {
			set(po, "XX_APPROVAL_STEP_ID", Integer.valueOf(step.getId()));
			set(po, "LEVELNO", Integer.valueOf(step.getLevelNo()));
			set(po, "LEVELNAME", trim(step.getLevel() == null ? null : step.getLevel().getLevelName()));
			set(po, "STATUS", step.getStatus() == null ? null : step.getStatus().name());
		}

		set(po, "AD_USER_ID", userId > 0 ? Integer.valueOf(userId) : null);
		set(po, "AD_ROLE_ID", roleId > 0 ? Integer.valueOf(roleId) : null);
		set(po, "ACTION", action.name());
		set(po, "ACTIONDATE", when == null ? new Timestamp(System.currentTimeMillis()) : when);
		set(po, "COMMENT", trim(comment));
		set(po, "DURATION", minutesSince(step, when));

		save(po, trxName);
		return idOf(po);
	}

	/**
	 * How long the step was open, in whole minutes, or null when it is still open
	 * or the step has no opening date. Stored so that a report never has to
	 * recompute it from two timestamps that may be written by different sessions.
	 */
	private Integer minutesSince(ApprovalStep step, Timestamp when) {
		if (step == null || step.getOpenedDate() == null || when == null)
			return null;
		Timestamp end = step.getCompletedDate() != null ? step.getCompletedDate() : when;
		int minutes = ApprovalHistoryEntry.minutesBetween(step.getOpenedDate(), end);
		return minutes < 0 ? Integer.valueOf(0) : Integer.valueOf(minutes);
	}

	/**
	 * Audit rows of a transaction, oldest first.
	 * <p>
	 * Ordered by the moment of the action and then by level, rather than by the
	 * row id alone: a parallel workflow writes several rows in the same
	 * millisecond, and the id order there depends on which approver the database
	 * happened to serve first. Ordering by time first also puts the document level
	 * submit row at the top without relying on how the database sorts nulls.
	 *
	 * @param transactionId transaction to read
	 * @return the rows, never null
	 */
	public List<ApprovalHistoryEntry> list(int transactionId) {
		List<ApprovalHistoryEntry> result = new ArrayList<ApprovalHistoryEntry>();
		if (transactionId <= 0)
			return result;

		String sql = "SELECT XX_APPROVAL_HISTORY_ID, XX_PROCESS_TRANSACTION_ID, XX_APPROVAL_STEP_ID, LEVELNO,"
				+ " LEVELNAME, AD_USER_ID, AD_ROLE_ID, ACTION, ACTIONDATE, COMMENT, STATUS, DURATION"
				+ " FROM XX_APPROVAL_HISTORY WHERE XX_PROCESS_TRANSACTION_ID=? AND AD_Client_ID=?"
				+ " ORDER BY ACTIONDATE, LEVELNO, XX_APPROVAL_HISTORY_ID";

		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, transactionId);
			pstmt.setInt(2, clientId());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ApprovalHistoryEntry entry = new ApprovalHistoryEntry();
				entry.setId(rs.getInt(1));
				entry.setTransactionId(rs.getInt(2));
				int stepId = rs.getInt(3);
				entry.setStepId(rs.wasNull() ? 0 : stepId);
				entry.setLevelNo(rs.getInt(4));
				entry.setLevelName(rs.getString(5));
				entry.setUserId(rs.getInt(6));
				entry.setRoleId(rs.getInt(7));
				entry.setAction(ApprovalAction.of(rs.getString(8)));
				entry.setActionDate(rs.getTimestamp(9));
				entry.setComment(rs.getString(10));
				entry.setStatus(StepStatus.of(rs.getString(11)));
				int duration = rs.getInt(12);
				entry.setDuration(rs.wasNull() ? null : Integer.valueOf(duration));
				result.add(entry);
			}
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}
}
