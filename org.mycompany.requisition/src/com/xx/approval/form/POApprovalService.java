package com.xx.approval.form;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.compiere.model.MOrder;
import org.compiere.model.MSequence;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;

/**
 * Approval logic for Purchase Orders.
 * <p>
 * Routing rule, as agreed with the business. A purchase order is offered to user
 * U when either
 * <ol>
 * <li>the <em>last</em> log row for it is a forward to U, or</li>
 * <li>it has never been decided and a row in {@code XX_ReqDocType_Supervisor}
 * maps its document type and its creator to U as supervisor, with the grand total
 * inside the mapped band. An {@code UpperLimit} of zero or less means no upper
 * bound, and a null {@code LowerLimit} means zero.</li>
 * </ol>
 * <p>
 * The word <em>last</em> matters. An earlier version required that the order had
 * no log rows at all, which meant that a rejected order could never reappear:
 * both branches became false the moment the rejection was written, so not even
 * the original supervisor could pick it up again. Deciding from the last row
 * instead means a rejection returns the order to the queue it came from.
 * <p>
 * Every statement is parameterised.
 */
public class POApprovalService {

	private static final CLogger log = CLogger.getCLogger(POApprovalService.class);

	/** Set when the last query failed, so the form can say so rather than showing zero. */
	private static volatile String lastError;

	/** The failure message of the most recent query, or null when it worked. */
	public static String getLastError() {
		return lastError;
	}

	/**
	 * Loads rows from XX_Test instead of C_Order.
	 * <p>
	 * Mapping: Document No = Value, Business Partner = Name, Receive Date = Created,
	 * Document Type = C_DocType_ID, Prepared By = AD_User_ID, Amount = 0 because
	 * the table has no amount. A row is visible when its Supervisor_ID is the
	 * logged-in user, which is what makes the list role-wise. {@code orderId}
	 * carries the XX_Test_ID and {@link Row#fromTestTable} is set, because that
	 * number is not a purchase order number.
	 */
	public static List<Row> loadXxTest(int clientId, int userId, int orgId, int docTypeId,
			Timestamp from, Timestamp to, String docNo, String bp) {

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT t.XX_Test_ID, org.Name, t.Created, dt.Name, t.C_DocType_ID, t.Value, t.Name, u.Name, t.AD_User_ID ")
		   .append("FROM XX_Test t ")
		   .append("JOIN AD_Org org ON org.AD_Org_ID = t.AD_Org_ID ")
		   .append("LEFT JOIN C_DocType dt ON dt.C_DocType_ID = t.C_DocType_ID ")
		   .append("LEFT JOIN AD_User u ON u.AD_User_ID = t.AD_User_ID ")
		   .append("WHERE t.AD_Client_ID = ? AND t.IsActive = 'Y' AND t.Supervisor_ID = ? ");

		List<Object> p = new ArrayList<Object>();
		p.add(clientId);
		p.add(userId);

		if (orgId > 0) { sql.append(" AND t.AD_Org_ID = ? "); p.add(Integer.valueOf(orgId)); }
		if (docTypeId > 0) { sql.append(" AND t.C_DocType_ID = ? "); p.add(Integer.valueOf(docTypeId)); }
		if (from != null) { sql.append(" AND t.Created >= ? "); p.add(from); }
		if (to != null) {
			// inclusive of the whole of the chosen day, so that picking the 23rd
			// does not silently exclude a row written at 4pm on the 23rd
			sql.append(" AND t.Created < ? ");
			p.add(new Timestamp(to.getTime() + 24L * 60L * 60L * 1000L));
		}
		if (docNo != null && docNo.trim().length() > 0) {
			sql.append(" AND UPPER(t.Value) LIKE ? ");
			p.add("%" + docNo.trim().toUpperCase() + "%");
		}
		if (bp != null && bp.trim().length() > 0) {
			sql.append(" AND UPPER(t.Name) LIKE ? ");
			p.add("%" + bp.trim().toUpperCase() + "%");
		}
		sql.append("ORDER BY t.Created, t.XX_Test_ID");

		List<Row> rows = new ArrayList<Row>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		lastError = null;
		try {
			ps = DB.prepareStatement(sql.toString(), null);
			DB.setParameters(ps, p);
			rs = ps.executeQuery();
			while (rs.next()) {
				Row r = new Row();
				r.orderId = rs.getInt(1);   r.org = rs.getString(2);
				r.date = rs.getTimestamp(3); r.docType = rs.getString(4);
				r.docTypeId = rs.getInt(5);  r.documentNo = rs.getString(6);
				r.bp = rs.getString(7);      r.amount = BigDecimal.ZERO;
				r.preparedBy = rs.getString(8); r.createdBy = rs.getInt(9);
				r.fromTestTable = true;
				rows.add(r);
			}
		} catch (Exception e) {
			log.log(Level.SEVERE, "The XX_Test approval queue could not be read: " + sql, e);
			lastError = e.getMessage();
		} finally {
			DB.close(rs, ps);
		}
		return rows;
	}

	/** One line of the approval queue. */
	public static class Row {
		public int orderId;
		public String org;
		public Timestamp date;
		public String docType;
		public int docTypeId;
		public String documentNo;
		public String bp;
		public BigDecimal amount;
		public String preparedBy;
		public int createdBy;
		/**
		 * True when this row came from XX_Test rather than from C_Order.
		 * <p>
		 * It matters because {@code orderId} then holds an XX_Test_ID, and those
		 * number ranges overlap: XX_Test 1000006 is not order 1000006, it is a
		 * different document entirely. Passing such a value to a method that
		 * expects a C_Order_ID completes an unrelated purchase order, so anything
		 * that acts on a document has to refuse such a row.
		 */
		public boolean fromTestTable;
	}

	/**
	 * Documents waiting for this user's approval.
	 *
	 * @param clientId   tenant
	 * @param userId     the approver
	 * @param orgId      organization filter, 0 for all
	 * @param docTypeId  document type filter, 0 for all
	 * @param from       inclusive lower bound on the order date, null for none
	 * @param to         inclusive upper bound on the order date, null for none
	 * @param docNo      document number fragment, null or empty for all
	 * @param bp         business partner name fragment, null or empty for all
	 * @param minAmount  minimum grand total, null for no minimum
	 */
	public static List<Row> load(int clientId, int userId, int orgId, int docTypeId,
			Timestamp from, Timestamp to, String docNo, String bp, BigDecimal minAmount) {

		String sql = buildSql(orgId, docTypeId, from, to, docNo, bp, minAmount);
		List<Object> p = new ArrayList<Object>();
		p.add(clientId);
		p.add(userId);          // forwarded to
		p.add(userId);          // mapped supervisor
		p.addAll(optionalParams(orgId, docTypeId, from, to, docNo, bp, minAmount));
		return query(sql, p);
	}

	/**
	 * The queue statement, apart from the three leading parameters, which the
	 * caller binds because their order is fixed.
	 * <p>
	 * Kept separate from {@link #load} on purpose: the statement is long, it is
	 * assembled from many fragments, and a single missing bracket turns the whole
	 * grid into a silent empty result. Being able to print it and count its
	 * parentheses without opening a database is what makes that checkable.
	 */
	static String buildSql(int orgId, int docTypeId, Timestamp from, Timestamp to,
			String docNo, String bp, BigDecimal minAmount) {

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT o.C_Order_ID, org.Name, o.DateOrdered, dt.Name, o.C_DocType_ID, o.DocumentNo, ")
		   .append("       bp.Name, o.GrandTotal, u.Name, o.CreatedBy ")
		   .append("FROM C_Order o ")
		   .append("JOIN AD_Org      org ON org.AD_Org_ID = o.AD_Org_ID ")
		   .append("JOIN C_DocType   dt  ON dt.C_DocType_ID = o.C_DocType_ID ")
		   .append("LEFT JOIN C_BPartner bp ON bp.C_BPartner_ID = o.C_BPartner_ID ")
		   .append("LEFT JOIN AD_User   u  ON u.AD_User_ID = o.CreatedBy ")
		   .append("WHERE o.AD_Client_ID = ? AND o.IsSOTrx = 'N' ")
		   .append("  AND o.DocStatus IN ('DR','IP') ")
		   .append("  AND ( ")
		   // the last decision was a forward to this user
		   .append("        ( (SELECT l2.ForwardTo_ID FROM XX_PO_Approval_Log l2 ")
		   .append("           WHERE l2.C_Order_ID = o.C_Order_ID AND l2.IsActive = 'Y' ")
		   .append("           ORDER BY l2.XX_PO_Approval_Log_ID DESC LIMIT 1) = ? ")
		   .append("          AND (SELECT l3.Action FROM XX_PO_Approval_Log l3 ")
		   .append("           WHERE l3.C_Order_ID = o.C_Order_ID AND l3.IsActive = 'Y' ")
		   .append("           ORDER BY l3.XX_PO_Approval_Log_ID DESC LIMIT 1) = 'FORWARD' ) ")
		   .append("     OR ( ")
		   // never decided, and the mapping sends it to this user
		   .append("        ( (SELECT COUNT(*) FROM XX_PO_Approval_Log l1 ")
		   .append("           WHERE l1.C_Order_ID = o.C_Order_ID AND l1.IsActive = 'Y') = 0 ")
		   .append("          AND EXISTS (SELECT 1 FROM XX_ReqDocType_Supervisor m ")
		   .append("               WHERE m.IsActive = 'Y' ")
		   .append("                 AND m.AD_Client_ID = o.AD_Client_ID ")
		   .append("                 AND m.C_DocType_ID = o.C_DocType_ID ")
		   .append("                 AND m.AD_User_ID = o.CreatedBy ")
		   .append("                 AND m.Supervisor_ID = ? ")
		   .append("                 AND o.GrandTotal >= COALESCE(m.LowerLimit, 0) ")
		   .append("                 AND (m.UpperLimit IS NULL OR m.UpperLimit <= 0 ")
		   .append("                      OR o.GrandTotal <= m.UpperLimit) ) ) ) ) ");

		if (orgId > 0) { sql.append("  AND o.AD_Org_ID = ? "); }
		if (docTypeId > 0) { sql.append("  AND o.C_DocType_ID = ? "); }
		if (from != null) { sql.append("  AND o.DateOrdered >= ? "); }
		if (to != null) { sql.append("  AND o.DateOrdered <= ? "); }
		if (docNo != null && docNo.trim().length() > 0) {
			sql.append("  AND UPPER(o.DocumentNo) LIKE ? ");
		}
		if (bp != null && bp.trim().length() > 0) {
			sql.append("  AND UPPER(bp.Name) LIKE ? ");
		}
		if (minAmount != null && minAmount.signum() > 0) {
			sql.append("  AND o.GrandTotal >= ? ");
		}
		sql.append("ORDER BY o.DateOrdered, o.DocumentNo");
		return sql.toString();
	}

	/** Optional predicates and their values, in the order {@link #buildSql} uses them. */
	private static List<Object> optionalParams(int orgId, int docTypeId, Timestamp from, Timestamp to,
			String docNo, String bp, BigDecimal minAmount) {
		List<Object> p = new ArrayList<Object>();
		if (orgId > 0) { p.add(Integer.valueOf(orgId)); }
		if (docTypeId > 0) { p.add(Integer.valueOf(docTypeId)); }
		if (from != null) { p.add(from); }
		if (to != null) { p.add(to); }
		if (docNo != null && docNo.trim().length() > 0) {
			p.add("%" + docNo.trim().toUpperCase() + "%");
		}
		if (bp != null && bp.trim().length() > 0) {
			p.add("%" + bp.trim().toUpperCase() + "%");
		}
		if (minAmount != null && minAmount.signum() > 0) { p.add(minAmount); }
		return p;
	}

	private static List<Row> query(String sql, List<Object> p) {
		List<Row> rows = new ArrayList<Row>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		lastError = null;
		try {
			ps = DB.prepareStatement(sql, null);
			DB.setParameters(ps, p);
			rs = ps.executeQuery();
			while (rs.next()) {
				Row r = new Row();
				r.orderId = rs.getInt(1);   r.org = rs.getString(2);
				r.date = rs.getTimestamp(3); r.docType = rs.getString(4);
				r.docTypeId = rs.getInt(5);  r.documentNo = rs.getString(6);
				r.bp = rs.getString(7);      r.amount = rs.getBigDecimal(8);
				r.preparedBy = rs.getString(9); r.createdBy = rs.getInt(10);
				rows.add(r);
			}
		} catch (Exception e) {
			// the caller is told, rather than being shown an empty grid that looks
			// exactly like "there is nothing to approve"
			log.log(Level.SEVERE, "The approval queue could not be read: " + sql, e);
			lastError = e.getMessage();
		} finally {
			DB.close(rs, ps);
		}
		return rows;
	}

	/**
	 * The supervisor that takes over from the current approver for a document type.
	 *
	 * @return the user id, or 0 when this approver is the end of the chain
	 */
	public static int nextApprover(int docTypeId, int approverId, int clientId) {
		return DB.getSQLValue(null,
			"SELECT Supervisor_ID FROM XX_ReqDocType_Supervisor WHERE IsActive='Y' AND C_DocType_ID=? "
			+ "AND AD_User_ID=? AND AD_Client_ID=? AND Supervisor_ID IS NOT NULL AND Supervisor_ID<>? "
			+ "ORDER BY XX_ReqDocType_Supervisor_ID LIMIT 1",
			docTypeId, approverId, clientId, approverId);
	}

	/**
	 * True when the mapping names this user as the last approver of the document type.
	 * <p>
	 * Deliberately keyed on the document type and the approver rather than on the
	 * creator: "this person signs off everything of this kind" is a property of the
	 * role they hold, not of one document.
	 */
	public static boolean isFinalApprover(int docTypeId, int approverId, int clientId) {
		return DB.getSQLValue(null,
			"SELECT COUNT(*) FROM XX_ReqDocType_Supervisor WHERE IsActive='Y' AND IsFinalApprover='Y' "
			+ "AND C_DocType_ID=? AND Supervisor_ID=? AND AD_Client_ID=?",
			docTypeId, approverId, clientId) > 0;
	}

	/**
	 * File names attached to a purchase order.
	 * <p>
	 * Read straight from {@code AD_Attachment} rather than through the attachment
	 * cache, so what the approver is told matches what is stored. The name lives in
	 * {@code AD_Attachment.Title}, not in a file name column, which is verified in
	 * this installation.
	 *
	 * @return the titles, or an empty list when the query failed
	 */
	public static List<String> attachments(int orderId) {
		List<String> names = new ArrayList<String>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT Title FROM AD_Attachment "
					+ "WHERE AD_Client_ID = ? AND AD_Table_ID = ? AND Record_ID = ? AND IsActive = 'Y' "
					+ "ORDER BY Title";
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			ps.setInt(2, MOrder.Table_ID);
			ps.setInt(3, orderId);
			rs = ps.executeQuery();
			while (rs.next())
				names.add(rs.getString(1));
		} catch (Exception e) {
			log.log(Level.SEVERE, "The attachments of order " + orderId + " could not be read", e);
		} finally {
			DB.close(rs, ps);
		}
		return names;
	}

	/**
	 * Carries out one decision on one order.
	 * <p>
	 * Approve completes the order when the approver is the final one for that
	 * document type, otherwise it passes the order on and logs who to. Forward
	 * sends it to the chosen user. Reject returns the order to Drafted so that it
	 * can be corrected and raised again, which also puts it back in the queue of
	 * the supervisor it came from.
	 *
	 * @param action APPROVE, REJECT or FORWARD
	 * @return null on success, otherwise a message fit to show the approver
	 */
	public static String act(int orderId, String action, int approverId, int forwardTo, String message) {
		String trxName = Trx.createTrxName("POApproval");
		Trx trx = Trx.get(trxName, true);
		try {
			MOrder order = new MOrder(Env.getCtx(), orderId, trxName);
			if (order.get_ID() == 0)
				return "Order not found";
			int clientId = order.getAD_Client_ID();
			String nextAction = action;
			int nextUser = 0;
			boolean fin = false;

			if ("APPROVE".equals(action)) {
				fin = isFinalApprover(order.getC_DocType_ID(), approverId, clientId);
				if (fin) {
					order.setDocAction(DocAction.ACTION_Complete);
					if (!order.processIt(DocAction.ACTION_Complete)) {
						trx.rollback();
						return "Complete failed: " + order.getProcessMsg();
					}
				} else {
					nextUser = nextApprover(order.getC_DocType_ID(), approverId, clientId);
					if (nextUser <= 0) {
						trx.rollback();
						return "No next supervisor is configured and you are not marked Final Approver";
					}
				}
			} else if ("FORWARD".equals(action)) {
				if (forwardTo <= 0) { trx.rollback(); return "Select a user in Forward To"; }
				if (forwardTo == approverId) { trx.rollback(); return "An order cannot be forwarded to yourself"; }
				nextUser = forwardTo;
			} else if ("REJECT".equals(action)) {
				if (message == null || message.trim().length() == 0) {
					trx.rollback();
					return "Message is required for Reject";
				}
				// returned to Drafted through the document lifecycle rather than by
				// writing DocStatus directly, so the order keeps its postings,
				// conversions and history consistent
				order.setDocStatus(DocAction.STATUS_Drafted);
				order.setDocAction(DocAction.ACTION_Prepare);
				if (!order.processIt(DocAction.ACTION_Prepare)) {
					trx.rollback();
					return "The order could not be returned to Drafted: " + order.getProcessMsg();
				}
			} else {
				trx.rollback();
				return "Unknown action";
			}

			int id = MSequence.getNextID(clientId, "XX_PO_Approval_Log", trxName);
			DB.executeUpdateEx(
				"INSERT INTO XX_PO_Approval_Log (XX_PO_Approval_Log_ID, AD_Client_ID, AD_Org_ID, IsActive, "
				+ "Created, CreatedBy, Updated, UpdatedBy, C_Order_ID, C_DocType_ID, DocumentNo, Amount, "
				+ "Approver_ID, Action, ForwardTo_ID, Message, IsFinal) "
				+ "VALUES (?,?,?,'Y',now(),?,now(),?,?,?,?,?,?,?,?,?,?)",
				new Object[] { Integer.valueOf(id), Integer.valueOf(clientId), Integer.valueOf(order.getAD_Org_ID()),
					Integer.valueOf(approverId), Integer.valueOf(approverId), Integer.valueOf(orderId),
					Integer.valueOf(order.getC_DocType_ID()), order.getDocumentNo(), order.getGrandTotal(),
					Integer.valueOf(approverId), nextAction,
					nextUser > 0 ? Integer.valueOf(nextUser) : null, message, fin ? "Y" : "N" },
				trxName);
			trx.commit();
			return null;
		} catch (Exception e) {
			trx.rollback();
			log.log(Level.SEVERE, "The approval of order " + orderId + " could not be recorded", e);
			return e.getMessage();
		} finally {
			trx.close();
		}
	}
}