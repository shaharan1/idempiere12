package org.mycompany.requisition.rfqwinner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;

/**
 * Data and rules of the Recommend Winner window opened from the Document
 * Approval Form.
 * <ul>
 * <li>The button is offered only for a <b>comparative statement</b>: an RFQ whose
 * requisition has document base type CS or POR and that has at least one
 * supplier response.</li>
 * <li>Who gets the button is controlled from the approval matrix window through
 * {@code UT_ApprovalMatrix.IsRecommendWinner} (see db/07_recommend_winner.sql).</li>
 * <li>One row per product and supplier, as in the RFQ Response screen. The
 * approver edits Negotiate Price, Winner and Comment; everything else is
 * read only.</li>
 * </ul>
 */
public class RecommendWinnerService {

	private static final CLogger log = CLogger.getCLogger(RecommendWinnerService.class);

	/** One product/supplier row of the window. */
	public static class Line {
		public int rfqLineId, responseId, lineQtyId;
		public String product = "", supplier = "", manufacturer = "", brand = "", origin = "", comment = "";
		public BigDecimal rfqQty = BigDecimal.ZERO, availableQty = BigDecimal.ZERO, quotedQty = BigDecimal.ZERO;
		public BigDecimal quotedPrice = BigDecimal.ZERO, negotiatePrice = BigDecimal.ZERO;
		public BigDecimal vatPct = BigDecimal.ZERO;
		public int rank;
		public boolean winner;
		/** Price the total and the rank are based on. */
		public BigDecimal effectivePrice() {
			return negotiatePrice != null && negotiatePrice.signum() > 0 ? negotiatePrice : quotedPrice;
		}
		public BigDecimal total() {
			return effectivePrice().multiply(quotedQty).setScale(2, RoundingMode.HALF_UP);
		}
	}

	/** One entry of the History records panel. */
	public static class History {
		public Timestamp created;
		public String user = "", supplier = "", product = "", comment = "";
		public BigDecimal quotedQty, quotedPrice, negotiatePrice;
		public int rank;
		public boolean winner;
	}

	// ------------------------------------------------------------ which RFQ, may the user

	/**
	 * The RFQ behind a ticked approval document, or 0. The grid may list the RFQ
	 * itself (matched on its document number) or the requisition that raised it.
	 */
	public static int findRfqId(int documentId, String documentNo) {
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		String docNoExpr = hasColumn("C_RfQ", "DocumentNo") ? "COALESCE(r.DocumentNo, r.Name)" : "r.Name";
		int id = 0;
		if (documentNo != null && documentNo.trim().length() > 0)
			id = DB.getSQLValue(null, "SELECT MAX(r.C_RfQ_ID) FROM C_RfQ r WHERE r.AD_Client_ID=? AND r.IsActive='Y' "
					+ "AND " + docNoExpr + "=?", clientId, documentNo.trim());
		if (id <= 0 && documentId > 0)
			id = DB.getSQLValue(null, "SELECT MAX(r.C_RfQ_ID) FROM C_RfQ r WHERE r.AD_Client_ID=? AND r.IsActive='Y' "
					+ "AND r.M_Requisition_ID=?", clientId, documentId);
		return Math.max(id, 0);
	}

	/** True when this RFQ is a comparative statement that already has supplier responses. */
	public static boolean isComparativeStatement(int rfqId) {
		if (rfqId <= 0)
			return false;
		String bt = DB.getSQLValueString(null, "SELECT dt.DocBaseType FROM C_RfQ r "
				+ "JOIN M_Requisition rq ON rq.M_Requisition_ID=r.M_Requisition_ID "
				+ "JOIN C_DocType dt ON dt.C_DocType_ID=rq.C_DocType_ID WHERE r.C_RfQ_ID=?", rfqId);
		if (!("CS".equals(bt) || "POR".equals(bt)))
			return false;
		return DB.getSQLValue(null, "SELECT COUNT(*) FROM C_RfQResponse WHERE C_RfQ_ID=? AND IsActive='Y'", rfqId) > 0;
	}

	/**
	 * Whether the signed-in user may recommend a winner on this RFQ: the approval
	 * matrix must carry a row for the RFQ's document type naming the user (as
	 * approver or as supervisor) with IsRecommendWinner ticked. When the matrix
	 * column has not been created yet the old behaviour stays: everyone may.
	 */
	public static boolean mayRecommend(int rfqId) {
		if (!hasColumn("UT_ApprovalMatrix", "IsRecommendWinner"))
			return true;
		int userId = Env.getAD_User_ID(Env.getCtx());
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		int docType = DB.getSQLValue(null, "SELECT rq.C_DocType_ID FROM C_RfQ r "
				+ "JOIN M_Requisition rq ON rq.M_Requisition_ID=r.M_Requisition_ID WHERE r.C_RfQ_ID=?", rfqId);
		if (docType <= 0)
			return false;
		return DB.getSQLValue(null, "SELECT COUNT(*) FROM UT_ApprovalMatrix WHERE C_DocType_ID=? "
				+ "AND IsRecommendWinner='Y' AND IsActive='Y' AND AD_Client_ID IN (0,?) "
				+ "AND (Supervisor_ID=? OR AD_User_ID=?)", docType, clientId, userId, userId) > 0;
	}

	/** Header text of the window: the RFQ's document number. */
	public static String rfqNo(int rfqId) {
		String docNoExpr = hasColumn("C_RfQ", "DocumentNo") ? "COALESCE(r.DocumentNo, r.Name)" : "r.Name";
		String s = DB.getSQLValueString(null, "SELECT " + docNoExpr + " FROM C_RfQ r WHERE r.C_RfQ_ID=?", rfqId);
		return s == null ? "" : s;
	}

	// ------------------------------------------------------------ rows

	public List<Line> loadLines(int rfqId) {
		List<Line> out = new ArrayList<Line>();
		String sql = "SELECT l.C_RfQLine_ID, x.C_RfQResponse_ID, sq.C_RfQResponseLineQty_ID, "
				+ "COALESCE(p.Name, l.Description, ''), bp.Name, "
				+ "COALESCE(sq.XX_Manufacturer,''), COALESCE(sq.XX_Brand,''), COALESCE(sq.XX_Origin,''), "
				+ "COALESCE(NULLIF(lq.Qty,0), rql.Qty, 0), "
				+ "COALESCE((SELECT SUM(so.QtyOnHand) FROM M_StorageOnHand so WHERE so.M_Product_ID=l.M_Product_ID),0), "
				+ "COALESCE(NULLIF(sq.XX_QuotationQty,0), sq.Qty, 0), COALESCE(sq.Price,0), "
				+ "COALESCE(sq.XX_NegotiatePrice,0), COALESCE(t.Rate,0), COALESCE(sq.Ranking,0), "
				+ "sl.IsSelectedWinner, COALESCE(sl.XX_WinnerComment,'') "
				+ "FROM C_RfQResponse x "
				+ "JOIN C_BPartner bp ON bp.C_BPartner_ID=x.C_BPartner_ID "
				+ "JOIN C_RfQResponseLine sl ON sl.C_RfQResponse_ID=x.C_RfQResponse_ID AND sl.IsActive='Y' "
				+ "JOIN C_RfQLine l ON l.C_RfQLine_ID=sl.C_RfQLine_ID AND l.IsActive='Y' "
				+ "JOIN C_RfQResponseLineQty sq ON sq.C_RfQResponseLine_ID=sl.C_RfQResponseLine_ID AND sq.IsActive='Y' "
				+ "LEFT JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID "
				+ "LEFT JOIN M_RequisitionLine rql ON rql.M_RequisitionLine_ID=l.XX_RequisitionLine_ID "
				+ "LEFT JOIN (SELECT C_RfQLine_ID, MIN(C_RfQLineQty_ID) AS id FROM C_RfQLineQty "
				+ "           WHERE IsActive='Y' GROUP BY C_RfQLine_ID) f ON f.C_RfQLine_ID=l.C_RfQLine_ID "
				+ "LEFT JOIN C_RfQLineQty lq ON lq.C_RfQLineQty_ID=f.id "
				+ "LEFT JOIN C_Tax t ON t.C_Tax_ID=sq.XX_Tax_ID "
				+ "WHERE x.C_RfQ_ID=? AND x.IsActive='Y' "
				+ "ORDER BY l.Line, l.C_RfQLine_ID, bp.Name";
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, rfqId);
			rs = ps.executeQuery();
			while (rs.next()) {
				Line r = new Line();
				r.rfqLineId = rs.getInt(1);
				r.responseId = rs.getInt(2);
				r.lineQtyId = rs.getInt(3);
				r.product = rs.getString(4);
				r.supplier = rs.getString(5);
				r.manufacturer = rs.getString(6);
				r.brand = rs.getString(7);
				r.origin = rs.getString(8);
				r.rfqQty = nz(rs.getBigDecimal(9));
				r.availableQty = nz(rs.getBigDecimal(10));
				r.quotedQty = nz(rs.getBigDecimal(11));
				r.quotedPrice = nz(rs.getBigDecimal(12));
				r.negotiatePrice = nz(rs.getBigDecimal(13));
				r.vatPct = nz(rs.getBigDecimal(14));
				r.rank = rs.getInt(15);
				r.winner = "Y".equals(rs.getString(16));
				r.comment = rs.getString(17);
				out.add(r);
			}
		} catch (Exception e) {
			log.severe("loadLines: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		rank(out);
		return out;
	}

	/**
	 * Ranks every product's quotes, cheapest first, equal prices share a rank.
	 * Runs again whenever a Negotiate Price is edited, because the rank follows
	 * the price actually being paid.
	 */
	public static void rank(List<Line> lines) {
		Map<Integer, List<Line>> byProduct = new HashMap<Integer, List<Line>>();
		for (Line l : lines) {
			List<Line> g = byProduct.get(Integer.valueOf(l.rfqLineId));
			if (g == null) {
				g = new ArrayList<Line>();
				byProduct.put(Integer.valueOf(l.rfqLineId), g);
			}
			g.add(l);
		}
		for (List<Line> g : byProduct.values()) {
			List<Line> sorted = new ArrayList<Line>(g);
			sorted.sort((a, b) -> a.effectivePrice().compareTo(b.effectivePrice()));
			int rank = 0, pos = 0;
			BigDecimal prev = null;
			for (Line l : sorted) {
				pos++;
				if (prev == null || l.effectivePrice().compareTo(prev) != 0) {
					rank = pos;
					prev = l.effectivePrice();
				}
				l.rank = rank;
			}
		}
	}

	// ------------------------------------------------------------ save

	/**
	 * Saves what the approver edited: negotiate price, winner and comment per
	 * row, the recalculated rank, and one history record per row. Exactly one
	 * winner per product; a winner that is not rank 1 needs a comment.
	 */
	public void save(int rfqId, List<Line> lines) throws Exception {
		Map<Integer, Integer> winners = new HashMap<Integer, Integer>();
		for (Line l : lines) {
			if (!l.winner)
				continue;
			if (winners.put(Integer.valueOf(l.rfqLineId), Integer.valueOf(l.responseId)) != null)
				throw new Exception("Only one winner can be chosen for " + l.product + ".");
			if (l.rank > 1 && (l.comment == null || l.comment.trim().length() == 0))
				throw new Exception("A comment is required for " + l.product + ": " + l.supplier
						+ " is not the lowest price (rank " + l.rank + ").");
		}
		if (winners.isEmpty())
			throw new Exception("Tick a winner for at least one product.");

		int userId = Env.getAD_User_ID(Env.getCtx());
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		String trxName = Trx.createTrxName("RfQWin");
		Trx trx = Trx.get(trxName, true);
		try {
			DB.executeUpdateEx("UPDATE C_RfQResponseLine SET IsSelectedWinner='N' WHERE C_RfQLine_ID IN "
					+ "(SELECT C_RfQLine_ID FROM C_RfQLine WHERE C_RfQ_ID=?)", new Object[] { Integer.valueOf(rfqId) },
					trxName);
			for (Line l : lines) {
				DB.executeUpdateEx("UPDATE C_RfQResponseLineQty SET XX_NegotiatePrice=?, Ranking=?, Updated=now(), "
						+ "UpdatedBy=? WHERE C_RfQResponseLineQty_ID=?",
						new Object[] { l.negotiatePrice, Integer.valueOf(l.rank), Integer.valueOf(userId),
								Integer.valueOf(l.lineQtyId) }, trxName);
				DB.executeUpdateEx("UPDATE C_RfQResponseLine SET IsSelectedWinner=?, XX_WinnerComment=?, "
						+ "Updated=now(), UpdatedBy=? WHERE C_RfQLine_ID=? AND C_RfQResponse_ID=?",
						new Object[] { l.winner ? "Y" : "N", trunc(l.comment, 255), Integer.valueOf(userId),
								Integer.valueOf(l.rfqLineId), Integer.valueOf(l.responseId) }, trxName);
				DB.executeUpdateEx("INSERT INTO XX_RfQ_WinnerLog (AD_Client_ID, C_RfQ_ID, C_RfQLine_ID, "
						+ "C_RfQResponse_ID, CreatedBy, BPartnerName, ProductName, QuotedQty, QuotedPrice, "
						+ "NegotiatePrice, Rank, IsWinner, Comment) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
						new Object[] { Integer.valueOf(clientId), Integer.valueOf(rfqId),
								Integer.valueOf(l.rfqLineId), Integer.valueOf(l.responseId), Integer.valueOf(userId),
								l.supplier, l.product, l.quotedQty, l.quotedPrice, l.negotiatePrice,
								Integer.valueOf(l.rank), l.winner ? "Y" : "N", trunc(l.comment, 255) }, trxName);
			}
			// the response header is a winner when it won at least one product
			DB.executeUpdateEx("UPDATE C_RfQResponse SET IsSelectedWinner='N' WHERE C_RfQ_ID=?",
					new Object[] { Integer.valueOf(rfqId) }, trxName);
			DB.executeUpdateEx("UPDATE C_RfQResponse SET IsSelectedWinner='Y' WHERE C_RfQ_ID=? AND C_RfQResponse_ID IN "
					+ "(SELECT sl.C_RfQResponse_ID FROM C_RfQResponseLine sl JOIN C_RfQLine l "
					+ " ON l.C_RfQLine_ID=sl.C_RfQLine_ID WHERE l.C_RfQ_ID=? AND sl.IsSelectedWinner='Y')",
					new Object[] { Integer.valueOf(rfqId), Integer.valueOf(rfqId) }, trxName);
			trx.commit();
		} catch (Exception e) {
			trx.rollback();
			throw e;
		} finally {
			trx.close();
		}
	}

	// ------------------------------------------------------------ history, attachments

	public List<History> history(int rfqId) {
		List<History> out = new ArrayList<History>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT h.Created, u.Name, h.BPartnerName, h.ProductName, h.QuotedQty, "
					+ "h.QuotedPrice, h.NegotiatePrice, h.Rank, h.IsWinner, h.Comment FROM XX_RfQ_WinnerLog h "
					+ "LEFT JOIN AD_User u ON u.AD_User_ID=h.CreatedBy WHERE h.C_RfQ_ID=? "
					+ "ORDER BY h.Created DESC, h.XX_RfQ_WinnerLog_ID", null);
			ps.setInt(1, rfqId);
			rs = ps.executeQuery();
			while (rs.next()) {
				History h = new History();
				h.created = rs.getTimestamp(1);
				h.user = nvl(rs.getString(2));
				h.supplier = nvl(rs.getString(3));
				h.product = nvl(rs.getString(4));
				h.quotedQty = rs.getBigDecimal(5);
				h.quotedPrice = rs.getBigDecimal(6);
				h.negotiatePrice = rs.getBigDecimal(7);
				h.rank = rs.getInt(8);
				h.winner = "Y".equals(rs.getString(9));
				h.comment = nvl(rs.getString(10));
				out.add(h);
			}
		} catch (Exception e) {
			log.warning("history: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return out;
	}

	/** File names attached to the supplier's response (AD_Attachment on C_RfQResponse). */
	public List<String> attachmentNames(int responseId) {
		List<String> out = new ArrayList<String>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT e.Name FROM AD_AttachmentEntry e "
					+ "JOIN AD_Attachment a ON a.AD_Attachment_ID=e.AD_Attachment_ID "
					+ "WHERE a.AD_Table_ID=(SELECT AD_Table_ID FROM AD_Table WHERE TableName='C_RfQResponse') "
					+ "AND a.Record_ID=? ORDER BY e.Name", null);
			ps.setInt(1, responseId);
			rs = ps.executeQuery();
			while (rs.next())
				out.add(rs.getString(1));
		} catch (Exception e) {
			// AD_AttachmentEntry does not exist on installs that keep attachments as one blob
			log.fine("attachmentNames: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return out;
	}

	// ------------------------------------------------------------ helpers

	private static boolean hasColumn(String table, String column) {
		return DB.getSQLValue(null, "SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)", table, column) > 0;
	}

	private static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	private static String trunc(String s, int max) {
		if (s == null)
			return null;
		return s.length() > max ? s.substring(0, max) : s;
	}
}
