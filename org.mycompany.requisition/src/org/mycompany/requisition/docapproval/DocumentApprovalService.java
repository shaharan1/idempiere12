package org.mycompany.requisition.docapproval;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.MRequisition;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.Trx;

/**
 * All database access and approval decisions for the Document Approval Form.
 * <p>
 * The form itself only draws and collects input, so every rule about who may
 * approve which document lives here and can be reasoned about in one place.
 * <p>
 * The rules, as agreed with the business:
 * <ul>
 * <li>A document is offered to a user only when a row in
 * {@code XX_ReqDocType_Supervisor} maps that document type and its creator to
 * that user as the supervisor.</li>
 * <li>The amount of a document is the sum of its line amounts, because
 * {@code M_Requisition} has no amount column of its own.</li>
 * <li>{@code UpperLimit} of zero or less means no upper bound and a null
 * {@code LowerLimit} means zero, so a document always falls inside exactly one
 * band unless the data itself contradicts itself.</li>
 * </ul>
 * <p>
 * Every statement is parameterised; no value is ever pasted into SQL text.
 *
 * @author Shaharan Hossain
 */
public class DocumentApprovalService {

	/** Physical table of the supervisor mapping. */
	public static final String T_SUPERVISOR = "XX_REQDOCTYPE_SUPERVISOR";
	/** Physical table of the approval history. */
	public static final String T_HISTORY = "XX_APPROVAL_HISTORY";
	/** The document table this form ships with; others plug in the same way. */
	public static final String T_DOCUMENT = "M_REQUISITION";
	/** Document status that means "still waiting for approval". */
	private static final String PENDING = "DR";

	/** Action words stored in the history table. */
	public static final String ACTION_APPROVE = "APPROVE";
	public static final String ACTION_REJECT = "REJECT";
	public static final String ACTION_FORWARD = "FORWARD";

	private static final CLogger log = CLogger.getCLogger(DocumentApprovalService.class);

	/** The translation of a literal, looked up in the session language. */
	private static String msg(String text) {
		return Msg.translate(Env.getCtx(), text);
	}

	// ------------------------------------------------------------------
	// Lookups for the search panel
	// ------------------------------------------------------------------

	/**
	 * Document types this user could ever receive, that is the ones that have a
	 * supervisor mapping. Offering any other type would return nothing.
	 */
	public List<String> documentTypeOptions() {
		return strings("SELECT DISTINCT dt.Name "
				+ "FROM " + T_SUPERVISOR + " m "
				+ "JOIN C_DocType dt ON dt.C_DocType_ID = m.C_DocType_ID "
				+ "WHERE m.AD_Client_ID = ? AND m.IsActive = 'Y' "
				+ "ORDER BY dt.Name", false);
	}

	/** Organizations that have documents pending approval. */
	public List<String> organizationOptions() {
		return strings("SELECT DISTINCT o.Name "
				+ "FROM " + T_SUPERVISOR + " m "
				+ "JOIN " + T_DOCUMENT + " d ON d.C_DocType_ID = m.C_DocType_ID "
				+ "JOIN AD_Org o ON o.AD_Org_ID = d.AD_Org_ID "
				+ "WHERE m.AD_Client_ID = ? AND m.IsActive = 'Y' "
				+ "ORDER BY o.Name", false);
	}

	/** Users a pending document may be forwarded to, other than the user themselves. */
	public List<String> userOptions() {
		return strings("SELECT Name FROM AD_User "
				+ "WHERE IsActive = 'Y' AND AD_Client_ID IN (0, ?) "
				+ "AND AD_User_ID <> ? ORDER BY Name", true);
	}

	/** Runs a one column query and returns its text values. */
	private List<String> strings(String sql, boolean alsoCurrentUser) {
		List<String> result = new ArrayList<String>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			if (alsoCurrentUser)
				pstmt.setInt(2, Env.getAD_User_ID(Env.getCtx()));
			rs = pstmt.executeQuery();
			while (rs.next())
				result.add(rs.getString(1));
		} catch (SQLException e) {
			log.warning("Lookup failed: " + sql + " -> " + e.getMessage());
			result.clear();
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return result;
	}

	// ------------------------------------------------------------------
	// The grid
	// ------------------------------------------------------------------

	/**
	 * Documents waiting for this user's approval.
	 *
	 * @param from    inclusive lower bound on the document date, null for none
	 * @param to      inclusive upper bound on the document date, null for none
	 * @param orgName organization name, null or empty for all
	 * @param docType document type name, null or empty for all
	 * @param docNo   document number, null or empty for all
	 * @param amount  exact amount, null for none
	 * @return matching documents, newest first
	 */
	public List<ApprovalDocument> listPendingDocuments(Timestamp from, Timestamp to, String orgName,
			String docType, String docNo, BigDecimal amount, String businessPartner) {

		List<ApprovalDocument> result = new ArrayList<ApprovalDocument>();

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT d.M_Requisition_ID, d.DocumentNo, d.C_DocType_ID, dt.Name, ")
		   .append("       d.AD_Org_ID, o.Name, d.DateDoc, d.Created, d.CreatedBy, cu.Name, ")
		   .append("       d.DocStatus, COALESCE(amt.Total, 0) AS Amount, d.RequisitionType, ")
		   .append("       m.IsFinalApprover, bp.Name ")
		   .append("FROM ").append(T_DOCUMENT).append(" d ")
		   .append("JOIN ").append(T_SUPERVISOR).append(" m ")
		   .append("     ON m.C_DocType_ID  = d.C_DocType_ID ")
		   .append("    AND m.AD_User_ID    = d.CreatedBy ")
		   .append("    AND m.Supervisor_ID = ? ")
		   .append("    AND m.AD_Client_ID  = d.AD_Client_ID ")
		   .append("    AND m.IsActive      = 'Y' ")
		   .append("JOIN C_DocType dt ON dt.C_DocType_ID = d.C_DocType_ID ")
		   .append("JOIN AD_Org     o  ON o.AD_Org_ID     = d.AD_Org_ID ")
		   .append("LEFT JOIN AD_User cu ON cu.AD_User_ID = d.CreatedBy ")
		   .append("LEFT JOIN (SELECT M_Requisition_ID, SUM(LineNetAmt) AS Total ")
		   .append("             FROM M_RequisitionLine GROUP BY M_Requisition_ID) amt ")
		   .append("       ON amt.M_Requisition_ID = d.M_Requisition_ID ")
		   .append("LEFT JOIN (SELECT M_Requisition_ID, MIN(C_BPartner_ID) AS BPartner ")
		   .append("             FROM M_RequisitionLine WHERE C_BPartner_ID > 0 ")
		   .append("             GROUP BY M_Requisition_ID) bl ")
		   .append("       ON bl.M_Requisition_ID = d.M_Requisition_ID ")
		   .append("LEFT JOIN C_BPartner bp ON bp.C_BPartner_ID = bl.BPartner ")
		   .append("WHERE d.AD_Client_ID = ? ")
		   .append("  AND d.DocStatus = ? ");

		// The optional filters are appended first so that their placeholders and
		// the three fixed ones can be bound in one pass, in the same order.
		if (from != null)
			sql.append("  AND d.DateDoc >= ? ");
		if (to != null)
			sql.append("  AND d.DateDoc <= ? ");
		if (isFilled(orgName))
			sql.append("  AND o.Name = ? ");
		if (isFilled(docType))
			sql.append("  AND dt.Name = ? ");
		if (isFilled(docNo))
			sql.append("  AND d.DocumentNo LIKE ? ");
		if (isFilled(businessPartner))
			sql.append("  AND LOWER(bp.Name) LIKE ? ");
		if (amount != null)
			sql.append("  AND COALESCE(amt.Total, 0) = ? ");

		// The band is part of the mapping, not of the search panel, so it is always
		// applied: an approver may only see documents their own band covers.
		sql.append("  AND COALESCE(amt.Total, 0) >= COALESCE(m.LowerLimit, 0) ")
		   .append("  AND (m.UpperLimit IS NULL OR m.UpperLimit <= 0 ")
		   .append("       OR COALESCE(amt.Total, 0) <= m.UpperLimit) ")
		   .append("ORDER BY d.DateDoc DESC, d.DocumentNo");

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), null);
			int i = 1;
			pstmt.setInt(i++, Env.getAD_User_ID(Env.getCtx()));
			pstmt.setInt(i++, Env.getAD_Client_ID(Env.getCtx()));
			pstmt.setString(i++, PENDING);
			if (from != null)
				pstmt.setTimestamp(i++, from);
			if (to != null)
				pstmt.setTimestamp(i++, to);
			if (isFilled(orgName))
				pstmt.setString(i++, orgName);
			if (isFilled(docType))
				pstmt.setString(i++, docType);
			if (isFilled(docNo))
				pstmt.setString(i++, docNo + "%");
			if (isFilled(businessPartner))
				pstmt.setString(i++, "%" + businessPartner.toLowerCase() + "%");
			if (amount != null)
				pstmt.setBigDecimal(i++, amount);

			rs = pstmt.executeQuery();
			int serial = 1;
			while (rs.next()) {
				ApprovalDocument d = new ApprovalDocument();
				d.setSerialNo(serial++);
				d.setId(rs.getInt(1));
				d.setDocumentNo(rs.getString(2));
				d.setDocTypeId(rs.getInt(3));
				d.setDocTypeName(rs.getString(4));
				d.setOrgId(rs.getInt(5));
				d.setOrgName(rs.getString(6));
				d.setReceiveDate(rs.getTimestamp(7));
				d.setCreated(rs.getTimestamp(8));
				d.setCreatedBy(rs.getInt(9));
				d.setPreparedBy(rs.getString(10));
				d.setDocStatus(rs.getString(11));
				d.setAmount(rs.getBigDecimal(12));
				d.setSalesType(rs.getString(13));
				d.setFinalApprover("Y".equalsIgnoreCase(rs.getString(14)));
				d.setBusinessPartner(rs.getString(15));
				result.add(d);
			}
		} catch (SQLException e) {
			log.warning("Could not list the pending documents: " + e.getMessage());
			result.clear();
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return result;
	}

	// ------------------------------------------------------------------
	// Decisions
	// ------------------------------------------------------------------

	/** True when the mapping marks this approver as the last one for the band. */
	public boolean isFinalApprover(ApprovalDocument doc) {
		return doc.isFinalApprover();
	}

	/**
	 * The supervisor that takes over when the current approver is not final.
	 * <p>
	 * It is the supervisor named by the mapping row whose {@code AD_User_ID} is the
	 * current approver, for the same document type. Null when the current approver
	 * is the end of the chain.
	 */
	public Integer nextSupervisor(ApprovalDocument doc) {
		Integer next = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT MIN(m.Supervisor_ID) FROM " + T_SUPERVISOR + " m "
					+ "WHERE m.AD_Client_ID = ? AND m.C_DocType_ID = ? "
					+ "  AND m.AD_User_ID = ? AND m.IsActive = 'Y' "
					+ "  AND COALESCE(m.Supervisor_ID, 0) <> ?";
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			pstmt.setInt(2, doc.getDocTypeId());
			pstmt.setInt(3, Env.getAD_User_ID(Env.getCtx()));
			pstmt.setInt(4, Env.getAD_User_ID(Env.getCtx()));
			rs = pstmt.executeQuery();
			if (rs.next()) {
				int value = rs.getInt(1);
				next = rs.wasNull() ? null : Integer.valueOf(value);
			}
		} catch (SQLException e) {
			log.warning("Could not find the next supervisor: " + e.getMessage());
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return next;
	}

	/**
	 * Points the mapping of this document type and creator at a new approver.
	 * <p>
	 * The mapping table is what decides who approves, so forwarding is a change
	 * of that row rather than a copy of the document.
	 *
	 * @return true when at least one mapping row was changed
	 */
	public boolean forward(ApprovalDocument doc, int toUserId) throws ApprovalException {
		if (toUserId <= 0)
			throw new ApprovalException(msg("No user was chosen to forward to."));
		if (toUserId == Env.getAD_User_ID(Env.getCtx()))
			throw new ApprovalException(msg("A document cannot be forwarded to yourself."));

		String trxName = newTrx();
		int updated = 0;
		try {
			for (Integer id : mappingIds(doc, trxName)) {
				PO row = MTable.get(Env.getCtx(), T_SUPERVISOR).getPO(id.intValue(), trxName);
				if (row == null)
					continue;
				set(row, "Supervisor_ID", Integer.valueOf(toUserId));
				row.save();
				updated++;
			}
			commit(trxName);
		} catch (Exception e) {
			rollback(trxName);
			throw new ApprovalException(msg("The document could not be forwarded: ") + e.getMessage());
		} finally {
			closeTrx(trxName);
		}
		if (updated == 0)
			throw new ApprovalException(msg("No supervisor mapping was found for this document."));
		logHistory(doc, ACTION_FORWARD, null, toUserId);
		return true;
	}

	/**
	 * Approves a document.
	 * <p>
	 * When the mapping marks this approver as the last one, the document is put
	 * through the standard iDempiere complete action. Otherwise it is left pending
	 * for the next supervisor.
	 *
	 * @return true when the document was completed
	 */
	public boolean approve(ApprovalDocument doc, String message) throws ApprovalException {
		boolean completed;
		String trxName = newTrx();
		try {
			completed = isFinalApprover(doc) || nextSupervisor(doc) == null;
			if (completed)
				completeDocument(doc, trxName);
			commit(trxName);
		} catch (Exception e) {
			rollback(trxName);
			throw new ApprovalException(msg("The document could not be approved: ") + e.getMessage());
		} finally {
			closeTrx(trxName);
		}
		logHistory(doc, ACTION_APPROVE, message, 0);
		return completed;
	}

	/**
	 * Rejects a document.
	 * <p>
	 * The caller has already insisted on a message: a rejection without a reason
	 * is of no use to the person who raised the document.
	 */
	public void reject(ApprovalDocument doc, String message) throws ApprovalException {
		if (!isFilled(message))
			throw new ApprovalException(msg("A message is required when rejecting a document."));

		String trxName = newTrx();
		try {
			PO po = MTable.get(Env.getCtx(), T_DOCUMENT).getPO(doc.getId(), trxName);
			if (po == null || po.get_ID() <= 0)
				throw new ApprovalException(msg("The document is no longer available."));
			set(po, "DocStatus", DocAction.STATUS_Invalid);
			set(po, "IsApproved", "N");
			if (!po.save())
				throw new ApprovalException(msg("The document could not be rejected."));
			commit(trxName);
		} catch (ApprovalException e) {
			rollback(trxName);
			throw e;
		} catch (Exception e) {
			rollback(trxName);
			throw new ApprovalException(msg("The document could not be rejected: ") + e.getMessage());
		} finally {
			closeTrx(trxName);
		}
		logHistory(doc, ACTION_REJECT, message, 0);
	}

	/** Approver and actions recorded for a document, newest first. */
	public List<ApprovalHistoryLine> history(ApprovalDocument doc) {
		List<ApprovalHistoryLine> result = new ArrayList<ApprovalHistoryLine>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT h.Action, h.ActionDate, h.Comment, u.Name "
					+ "FROM " + T_HISTORY + " h "
					+ "LEFT JOIN AD_User u ON u.AD_User_ID = h.AD_User_ID "
					+ "WHERE h.AD_Client_ID = ? AND h.DocumentTable = ? AND h.DocumentRecord_ID = ? "
					+ "ORDER BY h.ActionDate DESC";
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			pstmt.setString(2, T_DOCUMENT);
			pstmt.setInt(3, doc.getId());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ApprovalHistoryLine line = new ApprovalHistoryLine();
				line.setAction(rs.getString(1));
				line.setActionDate(rs.getTimestamp(2));
				line.setComment(rs.getString(3));
				line.setUserName(rs.getString(4));
				result.add(line);
			}
		} catch (SQLException e) {
			log.warning("Could not read the approval history: " + e.getMessage());
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return result;
	}

	/** File names attached to a document, for the Attachment button. */
	public List<String> attachments(ApprovalDocument doc) {
		List<String> result = new ArrayList<String>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			int tableId = MTable.getTable_ID(T_DOCUMENT);
			String sql = "SELECT FileName FROM AD_Attachment "
					+ "WHERE AD_Client_ID = ? AND AD_Table_ID = ? AND Record_ID = ? ORDER BY FileName";
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			pstmt.setInt(2, tableId);
			pstmt.setInt(3, doc.getId());
			rs = pstmt.executeQuery();
			while (rs.next())
				result.add(rs.getString(1));
		} catch (SQLException e) {
			log.warning("Could not read the attachments: " + e.getMessage());
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return result;
	}

	/**
	 * The id of a user by name.
	 * <p>
	 * The forward dropdown carries names because a name is what an approver
	 * recognises; this turns the chosen name back into the id the mapping stores.
	 *
	 * @return the id, or 0 when there is no such active user
	 */
	public int userIdByName(String name) {
		if (!isFilled(name))
			return 0;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT AD_User_ID FROM AD_User "
					+ "WHERE Name = ? AND IsActive = 'Y' AND AD_Client_ID IN (0, ?) "
					+ "ORDER BY AD_Client_ID DESC";
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setString(1, name);
			pstmt.setInt(2, Env.getAD_Client_ID(Env.getCtx()));
			rs = pstmt.executeQuery();
			if (rs.next())
				return rs.getInt(1);
		} catch (SQLException e) {
			log.warning("Could not resolve the user name: " + e.getMessage());
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return 0;
	}

	// ------------------------------------------------------------------
	// Internals
	// ------------------------------------------------------------------

	/**
	 * Writes one history row, in its own transaction.
	 * <p>
	 * Separate from the work transaction on purpose: the decision has already been
	 * committed by then, so a failure to record it must not undo the decision, only
	 * be reported.
	 */
	private void logHistory(ApprovalDocument doc, String action, String message, int toUserId) {
		String trxName = newTrx();
		try {
			PO po = MTable.get(Env.getCtx(), T_HISTORY).getPO(0, trxName);
			if (po == null)
				throw new ApprovalException(msg("Table " + T_HISTORY + " is not registered."));
			set(po, "AD_Client_ID", Integer.valueOf(Env.getAD_Client_ID(Env.getCtx())));
			set(po, "AD_Org_ID", Integer.valueOf(Env.getAD_Org_ID(Env.getCtx())));
			set(po, "IsActive", "Y");
			set(po, "DocumentTable", T_DOCUMENT);
			set(po, "DocumentRecord_ID", Integer.valueOf(doc.getId()));
			set(po, "DocumentNo", doc.getDocumentNo());
			set(po, "C_DocType_ID", Integer.valueOf(doc.getDocTypeId()));
			set(po, "AD_User_ID", Integer.valueOf(Env.getAD_User_ID(Env.getCtx())));
			set(po, "Action", action);
			set(po, "ActionDate", new Timestamp(System.currentTimeMillis()));
			set(po, "Comment", message);
			if (toUserId > 0)
				set(po, "To_User_ID", Integer.valueOf(toUserId));
			po.save();
			commit(trxName);
		} catch (Exception e) {
			rollback(trxName);
			log.warning("The approval history row could not be written for "
					+ doc.getDocumentNo() + ": " + e.getMessage());
		} finally {
			closeTrx(trxName);
		}
	}

	/**
	 * Puts the document through the standard iDempiere complete action.
	 * <p>
	 * {@code processIt} builds its own {@code ProcessInfo} from the document
	 * itself, so the only thing to supply is which action to run. The action
	 * constants are {@code ACTION_*} and carry the values stored in
	 * {@code M_Requisition.DocAction}, so the normal iDempiere preparation,
	 * rules and logging all run exactly as they would from the window.
	 * <p>
	 * In this build {@code processIt} answers with a boolean, and the document
	 * status is checked as well, because a document can report success and still
	 * be left short of completed.
	 */
	private void completeDocument(ApprovalDocument doc, String trxName) throws Exception {
		MRequisition req = new MRequisition(Env.getCtx(), doc.getId(), trxName);
		if (req.get_ID() <= 0)
			throw new ApprovalException(msg("The document is no longer available."));

		if (!req.processIt(DocAction.ACTION_Complete))
			throw new ApprovalException(msg("The document could not be completed."));
		if (!DocAction.STATUS_Completed.equals(req.getDocStatus()))
			throw new ApprovalException(msg("The document was not completed."));
	}

	private List<Integer> mappingIds(ApprovalDocument doc, String trxName) {
		List<Integer> ids = new ArrayList<Integer>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT XX_ReqDocType_Supervisor_ID FROM " + T_SUPERVISOR
					+ " WHERE AD_Client_ID = ? AND C_DocType_ID = ? AND AD_User_ID = ? AND IsActive = 'Y'";
			pstmt = DB.prepareStatement(sql, trxName);
			pstmt.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			pstmt.setInt(2, doc.getDocTypeId());
			pstmt.setInt(3, doc.getCreatedBy());
			rs = pstmt.executeQuery();
			while (rs.next())
				ids.add(Integer.valueOf(rs.getInt(1)));
		} catch (SQLException e) {
			log.warning("Could not read the supervisor mapping: " + e.getMessage());
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return ids;
	}

	/** Writes a column, tolerating a table that does not have it. */
	private static boolean set(PO po, String columnName, Object value) {
		if (po == null || value == null)
			return false;
		return po.set_ValueOfColumnReturningBoolean(columnName, value);
	}

	private static String newTrx() {
		return T_DOCUMENT + "_Appr_" + System.currentTimeMillis();
	}

	private static void commit(String trxName) {
		Trx trx = Trx.get(trxName, false);
		if (trx != null) {
			trx.commit();
			trx.close();
		}
	}

	private static void rollback(String trxName) {
		Trx trx = Trx.get(trxName, false);
		if (trx != null) {
			trx.rollback();
			trx.close();
		}
	}

	private static void closeTrx(String trxName) {
		Trx trx = Trx.get(trxName, true);
		if (trx != null)
			trx.close();
	}

	static boolean isFilled(String s) {
		return s != null && s.trim().length() > 0;
	}
}