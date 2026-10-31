package org.mycompany.requisition.rfqapproval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.compiere.model.MSequence;
import org.compiere.model.MTable;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Trx;

/**
 * All SQL and rules of the RFQ approval screen (Recommend Winner, Comparative
 * Statement, Approval History). The ZK form only draws and calls this class.
 * <p>
 * Reuses what already exists:
 * <ul>
 * <li>winner flag = standard {@code C_RfQResponse.IsSelectedWinner}</li>
 * <li>live approval state = {@code UT_ApprovalHistory} (same table and the same
 * approval inbox the Purchase Order approval uses), keyed by
 * {@code C_DocType_ID} + {@code DocumentNo}</li>
 * <li>audit trail / comments / justifications = {@code AD_Note} on the C_RfQ
 * record (visible in the record Notes tab)</li>
 * <li>approver routing = {@code UT_ApprovalMatrix} (submitter -&gt; supervisor)</li>
 * <li>price/total rules = same as {@code WRFQResponseDialog.recalc()}: effective
 * price = negotiate price if &gt; 0 else quoted price; discount % is taken on
 * (sub total + VAT); grand = that - discount + transport + others.</li>
 * </ul>
 */
public class RfQApprovalService {

	public static final String T_DOC = "C_RFQ";
	/** Live approval state: the standard approval inbox table already in use. */
	public static final String T_HISTORY = "UT_ApprovalHistory";

	public static final String A_RECOMMEND = "RECOMMEND";
	public static final String A_APPROVE = "APPROVE";
	public static final String A_REJECT = "REJECT";
	public static final String A_FORWARD = "FORWARD";
	public static final String A_SUBMIT = "SUBMIT";

	/** UT_ApprovalHistory.Status values (same codes the PO approval form reads). */
	public static final String S_PENDING = "P";
	public static final String S_APPROVED = "A";
	public static final String S_REJECTED = "R";

	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final CLogger log = CLogger.getCLogger(RfQApprovalService.class);

	// ------------------------------------------------------------ model

	public static class RfqRow {
		public int id;
		public String docNo;
		public String name;
		public Timestamp date;
		public int supplierCount;
		public String winner;      // null until recommended
		public String lastAction;  // null = not submitted, else P/A/R
		public String status() {
			if (S_APPROVED.equals(lastAction)) return "Approved";
			if (S_REJECTED.equals(lastAction)) return "Rejected";
			if (S_PENDING.equals(lastAction)) return "Pending Approval";
			return "Not Submitted";
		}
	}

	public static class Supplier {
		public int responseId, bpartnerId;
		public String name, phone;
		public boolean winner;
		public boolean pctDiscount;
		public BigDecimal discountInput = BigDecimal.ZERO;
		public BigDecimal subTotal = BigDecimal.ZERO, vat = BigDecimal.ZERO;
		public BigDecimal discount = BigDecimal.ZERO, transport = BigDecimal.ZERO, other = BigDecimal.ZERO;
		/** Sub total - discount + transport + other (VAT not included, like the printed CS). */
		public BigDecimal grand = BigDecimal.ZERO;
		/** Grand + VAT. Same number the response entry stores in XX_GrandTotal. */
		public BigDecimal ownership = BigDecimal.ZERO;
	}

	public static class Item {
		public int rfqLineId;
		public String prNo = "", name = "", uom = "";
		public BigDecimal qty = BigDecimal.ZERO;
		public BigDecimal lastPrice;
		public Map<Integer, BigDecimal> unit = new HashMap<Integer, BigDecimal>();
		public Map<Integer, BigDecimal> total = new HashMap<Integer, BigDecimal>();
		public Map<Integer, BigDecimal> vat = new HashMap<Integer, BigDecimal>();
		public Set<Integer> lowest = new LinkedHashSet<Integer>();
	}

	public static class Comparison {
		public int rfqId;
		public String csNo = "";
		public Timestamp csDate;
		public String requisitionNos = "", raisers = "", terms = "";
		public String preparedBy = "", preparedTitle = "";
		public Timestamp preparedDate;
		public String recommendedBy = "", recommendedTitle = "";
		public Timestamp recommendedDate;
		public String approvedBy = "", approvedTitle = "";
		public Timestamp approvedDate;
		public List<Supplier> suppliers = new ArrayList<Supplier>();
		public List<Item> items = new ArrayList<Item>();

		public Supplier winner() {
			for (Supplier s : suppliers)
				if (s.winner) return s;
			return null;
		}
	}

	public static class HistoryLine {
		public Timestamp date;
		public String user, action, comment, toUser;
	}

	// ------------------------------------------------------------ list

	private static String docNoExpr() {
		return hasColumn("C_RfQ", "DocumentNo") ? "COALESCE(r.DocumentNo, r.Name)" : "r.Name";
	}

	private static boolean hasColumn(String table, String column) {
		return DB.getSQLValue(null, "SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)", table, column) > 0;
	}

	private static String lastActionSql() {
		return "(SELECT h.Status FROM " + T_HISTORY + " h WHERE h.AD_Client_ID=r.AD_Client_ID "
				+ "AND h.DocumentNo=" + docNoExpr() + " AND h.C_DocType_ID=(" + docTypeSql() + ") "
				+ "ORDER BY h.Updated DESC LIMIT 1)";
	}

	/** Document type of the requisition that raised the RFQ (C_RfQ has no own doc type). */
	private static String docTypeSql() {
		return "SELECT rq.C_DocType_ID FROM M_Requisition rq WHERE rq.M_Requisition_ID=r.M_Requisition_ID";
	}

	/** RFQ's document type id (inherited from its requisition), or 0. */
	public static int rfqDocTypeId(int rfqId) {
		return DB.getSQLValue(null, "SELECT rq.C_DocType_ID FROM C_RfQ r "
				+ "JOIN M_Requisition rq ON rq.M_Requisition_ID=r.M_Requisition_ID WHERE r.C_RfQ_ID=?", rfqId);
	}

	/**
	 * RFQs whose suppliers have all completed their response.
	 * @param pendingOnly true = hide Approved / Rejected
	 */
	public List<RfqRow> listRfqs(boolean pendingOnly) {
		List<RfqRow> list = new ArrayList<RfqRow>();
		StringBuilder sql = new StringBuilder();
		sql.append("SELECT r.C_RfQ_ID, ").append(docNoExpr()).append(", r.Name, r.Created, ");
		sql.append("(SELECT COUNT(*) FROM C_RfQResponse x WHERE x.C_RfQ_ID=r.C_RfQ_ID AND x.IsActive='Y'), ");
		sql.append("(SELECT bp.Name FROM C_RfQResponse w JOIN C_BPartner bp ON bp.C_BPartner_ID=w.C_BPartner_ID ");
		sql.append("  WHERE w.C_RfQ_ID=r.C_RfQ_ID AND w.IsActive='Y' AND w.IsSelectedWinner='Y' LIMIT 1), ");
		sql.append(lastActionSql()).append(" AS la ");
		sql.append("FROM C_RfQ r WHERE r.AD_Client_ID=? AND r.IsActive='Y' ");
		sql.append("AND EXISTS (SELECT 1 FROM C_RfQResponse a WHERE a.C_RfQ_ID=r.C_RfQ_ID AND a.IsActive='Y') ");
		sql.append("AND NOT EXISTS (SELECT 1 FROM C_RfQResponse b WHERE b.C_RfQ_ID=r.C_RfQ_ID ");
		sql.append("  AND b.IsActive='Y' AND b.IsComplete='N') ");
		if (pendingOnly)
			sql.append("AND (").append(lastActionSql()).append(" IS NULL OR ")
				.append(lastActionSql()).append(" = '").append(S_PENDING).append("') ");
		sql.append("ORDER BY r.C_RfQ_ID DESC");
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql.toString(), null);
			ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			rs = ps.executeQuery();
			while (rs.next()) {
				RfqRow r = new RfqRow();
				r.id = rs.getInt(1);
				r.docNo = rs.getString(2);
				r.name = rs.getString(3);
				r.date = rs.getTimestamp(4);
				r.supplierCount = rs.getInt(5);
				r.winner = rs.getString(6);
				r.lastAction = rs.getString(7);
				list.add(r);
			}
		} catch (Exception e) {
			log.severe("listRfqs: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return list;
	}

	// ------------------------------------------------------------ comparison

	public Comparison load(int rfqId) {
		Comparison c = new Comparison();
		c.rfqId = rfqId;
		loadHeader(c);
		loadSuppliers(c);
		loadItems(c);
		loadPrices(c);
		computeTotals(c);
		loadSignatures(c);
		return c;
	}

	private void loadHeader(Comparison c) {
		String sql = "SELECT " + docNoExpr() + ", r.Created, r.Help, u.Name, u.Title "
				+ "FROM C_RfQ r LEFT JOIN AD_User u ON u.AD_User_ID=r.CreatedBy WHERE r.C_RfQ_ID=?";
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, c.rfqId);
			rs = ps.executeQuery();
			if (rs.next()) {
				c.csNo = nvl(rs.getString(1));
				c.csDate = rs.getTimestamp(2);
				c.preparedDate = c.csDate;
				c.terms = nvl(rs.getString(3));
				c.preparedBy = nvl(rs.getString(4));
				c.preparedTitle = nvl(rs.getString(5));
			}
		} catch (Exception e) {
			log.severe("loadHeader: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
	}

	private void loadSuppliers(Comparison c) {
		String sql = "SELECT x.C_RfQResponse_ID, x.C_BPartner_ID, bp.Name, "
				+ "COALESCE(NULLIF(u.Phone,''), NULLIF(bpl.Phone,''), ''), x.IsSelectedWinner, "
				+ "COALESCE(x.XX_TransportCost,0), COALESCE(x.XX_OthersCost,0), "
				+ "COALESCE(x.XX_IsGrossDiscountPct,'N'), COALESCE(x.XX_GrossDiscount,0) "
				+ "FROM C_RfQResponse x JOIN C_BPartner bp ON bp.C_BPartner_ID=x.C_BPartner_ID "
				+ "LEFT JOIN AD_User u ON u.AD_User_ID=x.AD_User_ID "
				+ "LEFT JOIN C_BPartner_Location bpl ON bpl.C_BPartner_Location_ID=x.C_BPartner_Location_ID "
				+ "WHERE x.C_RfQ_ID=? AND x.IsActive='Y' ORDER BY x.C_RfQResponse_ID";
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, c.rfqId);
			rs = ps.executeQuery();
			while (rs.next()) {
				Supplier s = new Supplier();
				s.responseId = rs.getInt(1);
				s.bpartnerId = rs.getInt(2);
				s.name = nvl(rs.getString(3));
				s.phone = nvl(rs.getString(4));
				s.winner = "Y".equals(rs.getString(5));
				s.transport = nz(rs.getBigDecimal(6));
				s.other = nz(rs.getBigDecimal(7));
				s.pctDiscount = "Y".equals(rs.getString(8));
				s.discountInput = nz(rs.getBigDecimal(9));
				c.suppliers.add(s);
			}
		} catch (Exception e) {
			log.severe("loadSuppliers: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
	}

	private void loadItems(Comparison c) {
		String sql = "SELECT l.C_RfQLine_ID, COALESCE(rq.DocumentNo,''), COALESCE(p.Name, l.Description, ''), "
				+ "COALESCE(NULLIF(uom.UOMSymbol,''), uom.Name, ''), "
				+ "COALESCE(NULLIF(lq.Qty,0), rql.Qty, 0), "
				+ "(SELECT ol.PriceActual FROM C_OrderLine ol JOIN C_Order o ON o.C_Order_ID=ol.C_Order_ID "
				+ "  WHERE ol.M_Product_ID=l.M_Product_ID AND o.IsSOTrx='N' AND o.DocStatus IN ('CO','CL') "
				+ "  ORDER BY o.DateOrdered DESC, ol.C_OrderLine_ID DESC LIMIT 1), "
				+ "COALESCE(ru.Name,'') "
				+ "FROM C_RfQLine l "
				+ "LEFT JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID "
				+ "LEFT JOIN M_RequisitionLine rql ON rql.M_RequisitionLine_ID=l.XX_RequisitionLine_ID "
				+ "LEFT JOIN M_Requisition rq ON rq.M_Requisition_ID=rql.M_Requisition_ID "
				+ "LEFT JOIN AD_User ru ON ru.AD_User_ID=rq.AD_User_ID "
				+ "LEFT JOIN (SELECT C_RfQLine_ID, MIN(C_RfQLineQty_ID) AS id FROM C_RfQLineQty "
				+ "           WHERE IsActive='Y' GROUP BY C_RfQLine_ID) f ON f.C_RfQLine_ID=l.C_RfQLine_ID "
				+ "LEFT JOIN C_RfQLineQty lq ON lq.C_RfQLineQty_ID=f.id "
				+ "LEFT JOIN C_UOM uom ON uom.C_UOM_ID=lq.C_UOM_ID "
				+ "WHERE l.C_RfQ_ID=? AND l.IsActive='Y' ORDER BY l.Line, l.C_RfQLine_ID";
		Set<String> prs = new LinkedHashSet<String>();
		Set<String> raisers = new LinkedHashSet<String>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, c.rfqId);
			rs = ps.executeQuery();
			while (rs.next()) {
				Item it = new Item();
				it.rfqLineId = rs.getInt(1);
				it.prNo = nvl(rs.getString(2));
				it.name = nvl(rs.getString(3));
				it.uom = nvl(rs.getString(4));
				it.qty = nz(rs.getBigDecimal(5));
				it.lastPrice = rs.getBigDecimal(6);
				if (it.prNo.length() > 0) prs.add(it.prNo);
				String r = nvl(rs.getString(7));
				if (r.length() > 0) raisers.add(r);
				c.items.add(it);
			}
		} catch (Exception e) {
			log.severe("loadItems: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		c.requisitionNos = join(prs);
		c.raisers = join(raisers);
	}

	private void loadPrices(Comparison c) {
		Map<Integer, Item> byLine = new HashMap<Integer, Item>();
		for (Item it : c.items) byLine.put(it.rfqLineId, it);
		String sql = "SELECT x.C_RfQResponse_ID, sl.C_RfQLine_ID, COALESCE(sq.Price,0), "
				+ "COALESCE(sq.XX_NegotiatePrice,0), COALESCE(sq.XX_QuotationQty,0), COALESCE(sq.XX_VATAmt,0) "
				+ "FROM C_RfQResponse x "
				+ "JOIN C_RfQResponseLine sl ON sl.C_RfQResponse_ID=x.C_RfQResponse_ID AND sl.IsActive='Y' "
				+ "JOIN C_RfQResponseLineQty sq ON sq.C_RfQResponseLine_ID=sl.C_RfQResponseLine_ID AND sq.IsActive='Y' "
				+ "WHERE x.C_RfQ_ID=? AND x.IsActive='Y'";
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, c.rfqId);
			rs = ps.executeQuery();
			while (rs.next()) {
				Item it = byLine.get(rs.getInt(2));
				if (it == null) continue;
				Integer resp = Integer.valueOf(rs.getInt(1));
				if (it.unit.containsKey(resp)) continue; // one LineQty per line expected
				BigDecimal price = rs.getBigDecimal(3);
				BigDecimal neg = rs.getBigDecimal(4);
				BigDecimal unit = neg.signum() > 0 ? neg : price;
				BigDecimal qty = rs.getBigDecimal(5).signum() > 0 ? rs.getBigDecimal(5) : it.qty;
				if (unit.signum() <= 0) continue; // supplier did not quote this item
				it.unit.put(resp, unit);
				it.total.put(resp, unit.multiply(qty).setScale(2, RoundingMode.HALF_UP));
				it.vat.put(resp, rs.getBigDecimal(6));
			}
		} catch (Exception e) {
			log.severe("loadPrices: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		for (Item it : c.items) { // lowest unit price per item (ties all highlighted)
			BigDecimal min = null;
			for (BigDecimal u : it.unit.values())
				if (min == null || u.compareTo(min) < 0) min = u;
			if (min != null)
				for (Map.Entry<Integer, BigDecimal> e : it.unit.entrySet())
					if (e.getValue().compareTo(min) == 0) it.lowest.add(e.getKey());
		}
	}

	private void computeTotals(Comparison c) {
		for (Supplier s : c.suppliers) {
			Integer k = Integer.valueOf(s.responseId);
			for (Item it : c.items) {
				if (it.total.get(k) != null) s.subTotal = s.subTotal.add(it.total.get(k));
				if (it.vat.get(k) != null) s.vat = s.vat.add(it.vat.get(k));
			}
			s.discount = s.pctDiscount
					? s.subTotal.add(s.vat).multiply(s.discountInput).divide(HUNDRED, 2, RoundingMode.HALF_UP)
					: s.discountInput;
			s.grand = s.subTotal.subtract(s.discount).add(s.transport).add(s.other);
			s.ownership = s.grand.add(s.vat);
		}
	}

	/** Recommended by / Approved by = who did it according to the history. */
	private void loadSignatures(Comparison c) {
		for (HistoryLine h : history(c.rfqId)) { // newest first
			if (A_RECOMMEND.equals(h.action) && c.recommendedBy.length() == 0)
				fillSign(c, h, true);
			if (A_APPROVE.equals(h.action) && c.approvedBy.length() == 0)
				fillSign(c, h, false);
		}
	}

	private void fillSign(Comparison c, HistoryLine h, boolean recommend) {
		String title = nvl(DB.getSQLValueString(null,
				"SELECT Title FROM AD_User WHERE Name=? AND AD_Client_ID IN (0,?) ORDER BY AD_Client_ID DESC",
				h.user, Env.getAD_Client_ID(Env.getCtx())));
		if (recommend) { c.recommendedBy = nvl(h.user); c.recommendedTitle = title; c.recommendedDate = h.date; }
		else { c.approvedBy = nvl(h.user); c.approvedTitle = title; c.approvedDate = h.date; }
	}

	// ------------------------------------------------------------ Last PO history

	/** Last purchase orders of the product behind one RFQ line: {PO No, date, vendor, qty, price}. */
	public List<String[]> lastPoHistory(int rfqLineId) {
		List<String[]> out = new ArrayList<String[]>();
		String sql = "SELECT o.DocumentNo, o.DateOrdered, bp.Name, ol.QtyOrdered, ol.PriceActual "
				+ "FROM C_RfQLine l JOIN C_OrderLine ol ON ol.M_Product_ID=l.M_Product_ID "
				+ "JOIN C_Order o ON o.C_Order_ID=ol.C_Order_ID JOIN C_BPartner bp ON bp.C_BPartner_ID=o.C_BPartner_ID "
				+ "WHERE l.C_RfQLine_ID=? AND o.IsSOTrx='N' AND o.DocStatus IN ('CO','CL') "
				+ "ORDER BY o.DateOrdered DESC, ol.C_OrderLine_ID DESC LIMIT 5";
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, rfqLineId);
			rs = ps.executeQuery();
			java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("dd/MM/yyyy");
			java.text.DecimalFormat nf = new java.text.DecimalFormat("#,##0.00");
			while (rs.next())
				out.add(new String[] { rs.getString(1), df.format(rs.getTimestamp(2)), rs.getString(3),
						nf.format(rs.getBigDecimal(4)), nf.format(rs.getBigDecimal(5)) });
		} catch (Exception e) {
			log.severe("lastPoHistory: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return out;
	}

	// ------------------------------------------------------------ decisions

	/** Mark one supplier as winner (only one per RFQ) and log it. */
	public void recommendWinner(int rfqId, int responseId, String remarks) throws Exception {
		String trxName = Trx.createTrxName("RfQWin");
		Trx trx = Trx.get(trxName, true);
		try {
			if (DB.getSQLValue(trxName, "SELECT COUNT(*) FROM C_RfQResponse WHERE C_RfQResponse_ID=? "
					+ "AND C_RfQ_ID=? AND IsActive='Y' AND IsComplete='Y'", responseId, rfqId) == 0)
				throw new Exception("Only a completed response of this RFQ can be recommended.");
			DB.executeUpdateEx("UPDATE C_RfQResponse SET IsSelectedWinner='N' WHERE C_RfQ_ID=?",
					new Object[] { rfqId }, trxName);
			DB.executeUpdateEx("UPDATE C_RfQResponse SET IsSelectedWinner='Y' WHERE C_RfQResponse_ID=?",
					new Object[] { responseId }, trxName);
			trx.commit();
		} catch (Exception e) {
			trx.rollback();
			throw e;
		} finally {
			trx.close();
		}
		String bp = DB.getSQLValueString(null, "SELECT bp.Name FROM C_RfQResponse x "
				+ "JOIN C_BPartner bp ON bp.C_BPartner_ID=x.C_BPartner_ID WHERE x.C_RfQResponse_ID=?", responseId);
		logHistory(rfqId, A_RECOMMEND, "Winner: " + bp + (isFilled(remarks) ? " | " + remarks.trim() : ""), 0);
	}

	/**
	 * Per-product recommendation. One winner for each RFQ line, stored on the
	 * standard {@code C_RfQResponseLine.IsSelectedWinner}; every quote's position
	 * is written to {@code C_RfQResponseLineQty.Ranking}. A vendor that is not
	 * the lowest price for a product must be justified for that product.
	 *
	 * @param lineWinners    RFQ line id -&gt; winning C_RfQResponse id
	 * @param justifications RFQ line id -&gt; reason when the winner is not cheapest
	 * @param remarks        overall remark for the audit trail
	 */
	public void recommendLines(int rfqId, Map<Integer, Integer> lineWinners,
			Map<Integer, String> justifications, String remarks) throws Exception {
		if (lineWinners == null || lineWinners.isEmpty())
			throw new Exception("Select a vendor for at least one product.");

		Comparison c = load(rfqId);
		Map<Integer, Item> items = new HashMap<Integer, Item>();
		for (Item it : c.items)
			items.put(Integer.valueOf(it.rfqLineId), it);

		List<RankedQuote> ranked = rankQuotes(rfqId);
		Map<String, RankedQuote> rankByLineResp = new HashMap<String, RankedQuote>();
		for (RankedQuote q : ranked)
			rankByLineResp.put(q.rfqLineId + ":" + q.responseId, q);

		// ---- validate before touching the database
		Set<Integer> seenLines = new HashSet<Integer>();
		for (Map.Entry<Integer, Integer> e : lineWinners.entrySet()) {
			Integer lineId = e.getKey();
			Integer respId = e.getValue();
			if (lineId == null || respId == null || respId.intValue() <= 0)
				continue;
			Item it = items.get(lineId);
			if (it == null)
				throw new Exception("The selected product is not part of this RFQ.");
			if (!seenLines.add(lineId))
				throw new Exception("Only one vendor may be recommended for each product.");
			RankedQuote q = rankByLineResp.get(lineId + ":" + respId);
			if (q == null)
				throw new Exception("The selected vendor did not quote " + it.name + ".");
			if (q.rank > 1) {
				String just = justifications == null ? null : justifications.get(lineId);
				if (!isFilled(just))
					throw new Exception("A justification is required for " + it.name + " because "
							+ q.vendorName + " is not the lowest price (rank " + q.rank + ").");
			}
		}
		if (seenLines.isEmpty())
			throw new Exception("Select a vendor for at least one product.");

		// ---- write winners and ranks in one transaction
		String trxName = Trx.createTrxName("RfQRec");
		Trx trx = Trx.get(trxName, true);
		try {
			DB.executeUpdateEx("UPDATE C_RfQResponseLine SET IsSelectedWinner='N' "
					+ "WHERE C_RfQLine_ID IN (SELECT C_RfQLine_ID FROM C_RfQLine WHERE C_RfQ_ID=?)",
					new Object[] { Integer.valueOf(rfqId) }, trxName);

			for (RankedQuote q : ranked)
				DB.executeUpdateEx("UPDATE C_RfQResponseLineQty q SET Ranking=? "
						+ "FROM C_RfQResponseLine sl WHERE q.C_RfQResponseLine_ID=sl.C_RfQResponseLine_ID "
						+ "AND sl.C_RfQLine_ID=? AND sl.C_RfQResponse_ID=?",
						new Object[] { Integer.valueOf(q.rank), Integer.valueOf(q.rfqLineId),
								Integer.valueOf(q.responseId) }, trxName);

			for (Map.Entry<Integer, Integer> e : lineWinners.entrySet()) {
				Integer lineId = e.getKey();
				Integer respId = e.getValue();
				if (lineId == null || respId == null || respId.intValue() <= 0)
					continue;
				DB.executeUpdateEx("UPDATE C_RfQResponseLine SET IsSelectedWinner='Y' "
						+ "WHERE C_RfQLine_ID=? AND C_RfQResponse_ID=?",
						new Object[] { lineId, respId }, trxName);
			}

			// Overall winner flag: any supplier that won at least one product.
			DB.executeUpdateEx("UPDATE C_RfQResponse SET IsSelectedWinner='N' WHERE C_RfQ_ID=?",
					new Object[] { Integer.valueOf(rfqId) }, trxName);
			DB.executeUpdateEx("UPDATE C_RfQResponse SET IsSelectedWinner='Y' WHERE C_RfQ_ID=? "
					+ "AND C_RfQResponse_ID IN (SELECT sl.C_RfQResponse_ID FROM C_RfQResponseLine sl "
					+ "  JOIN C_RfQLine l ON l.C_RfQLine_ID=sl.C_RfQLine_ID "
					+ "  WHERE l.C_RfQ_ID=? AND sl.IsSelectedWinner='Y')",
					new Object[] { Integer.valueOf(rfqId), Integer.valueOf(rfqId) }, trxName);
			trx.commit();
		} catch (Exception e) {
			trx.rollback();
			throw e;
		} finally {
			trx.close();
		}

		// ---- audit trail
		StringBuilder note = new StringBuilder();
		for (Map.Entry<Integer, Integer> e : lineWinners.entrySet()) {
			Item it = items.get(e.getKey());
			RankedQuote q = rankByLineResp.get(e.getKey() + ":" + e.getValue());
			if (it == null || q == null)
				continue;
			if (note.length() > 0)
				note.append(" | ");
			note.append(it.name).append(" -> ").append(q.vendorName).append(" @ ")
					.append(q.price.stripTrailingZeros().toPlainString()).append(" rank ").append(q.rank);
			String just = justifications == null ? null : justifications.get(e.getKey());
			if (isFilled(just))
				note.append(" [").append(just.trim()).append("]");
		}
		if (isFilled(remarks))
			note.append(" | Remarks: ").append(remarks.trim());
		logHistory(rfqId, A_RECOMMEND, "Per product: " + note, 0);
	}

	/** Existing per-product winners: RFQ line id -&gt; winning response id. */
	public static Map<Integer, Integer> lineWinners(int rfqId) {
		Map<Integer, Integer> m = new HashMap<Integer, Integer>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT sl.C_RfQLine_ID, sl.C_RfQResponse_ID "
					+ "FROM C_RfQResponseLine sl JOIN C_RfQLine l ON l.C_RfQLine_ID=sl.C_RfQLine_ID "
					+ "WHERE l.C_RfQ_ID=? AND sl.IsSelectedWinner='Y' AND sl.IsActive='Y'", null);
			ps.setInt(1, rfqId);
			rs = ps.executeQuery();
			while (rs.next())
				m.put(Integer.valueOf(rs.getInt(1)), Integer.valueOf(rs.getInt(2)));
		} catch (Exception e) {
			log.severe("lineWinners: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return m;
	}

	/** Per-product winner names for the grid: RFQ line id -&gt; vendor name. */
	public static Map<Integer, String> lineWinnerNames(int rfqId) {
		Map<Integer, String> m = new HashMap<Integer, String>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT sl.C_RfQLine_ID, bp.Name "
					+ "FROM C_RfQResponseLine sl "
					+ "JOIN C_RfQLine l ON l.C_RfQLine_ID=sl.C_RfQLine_ID "
					+ "JOIN C_RfQResponse x ON x.C_RfQResponse_ID=sl.C_RfQResponse_ID "
					+ "JOIN C_BPartner bp ON bp.C_BPartner_ID=x.C_BPartner_ID "
					+ "WHERE l.C_RfQ_ID=? AND sl.IsSelectedWinner='Y' AND sl.IsActive='Y'", null);
			ps.setInt(1, rfqId);
			rs = ps.executeQuery();
			while (rs.next())
				m.put(Integer.valueOf(rs.getInt(1)), rs.getString(2));
		} catch (Exception e) {
			log.severe("lineWinnerNames: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return m;
	}

	public void approve(int rfqId, String message) throws Exception {
		int winners = DB.getSQLValue(null,
				"SELECT (SELECT COUNT(*) FROM C_RfQResponse WHERE C_RfQ_ID=? AND IsSelectedWinner='Y' AND IsActive='Y') "
				+ "+ (SELECT COUNT(*) FROM C_RfQResponseLine sl "
				+ "     JOIN C_RfQLine l ON l.C_RfQLine_ID=sl.C_RfQLine_ID "
				+ "     WHERE l.C_RfQ_ID=? AND sl.IsSelectedWinner='Y' AND sl.IsActive='Y')",
				rfqId, rfqId);
		if (winners <= 0)
			throw new Exception("Recommend a winner before approving.");
		logHistory(rfqId, A_APPROVE, message, 0);
	}

	public void reject(int rfqId, String message) throws Exception {
		if (!isFilled(message))
			throw new Exception("A message is required when rejecting.");
		logHistory(rfqId, A_REJECT, message, 0);
	}

	public void forward(int rfqId, int toUserId, String message) throws Exception {
		if (toUserId <= 0)
			throw new Exception("Choose a user to forward to.");
		if (toUserId == Env.getAD_User_ID(Env.getCtx()))
			throw new Exception("An RFQ cannot be forwarded to yourself.");
		logHistory(rfqId, A_FORWARD, message, toUserId);
	}

	/** Records that a completed RFQ was submitted into the approval chain. */
	public void submitForApproval(int rfqId, String message) throws Exception {
		logHistory(rfqId, A_SUBMIT, isFilled(message) ? message : "RFQ completed and submitted for approval", 0);
	}

	public List<KeyNamePair> userOptions() {
		List<KeyNamePair> l = new ArrayList<KeyNamePair>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT AD_User_ID, Name FROM AD_User WHERE IsActive='Y' "
					+ "AND AD_Client_ID IN (0,?) AND Name IS NOT NULL ORDER BY Name", null);
			ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			rs = ps.executeQuery();
			while (rs.next())
				l.add(new KeyNamePair(rs.getInt(1), rs.getString(2)));
		} catch (Exception e) {
			log.severe("userOptions: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return l;
	}

	/**
	 * Audit trail of an RFQ: every action and its comment, newest first. The
	 * entries live in {@code AD_Note} on the C_RfQ record, so they are also
	 * visible in the record's Notes tab. When nothing has been written yet the
	 * current {@code UT_ApprovalHistory} state is shown instead.
	 */
	public List<HistoryLine> history(int rfqId) {
		List<HistoryLine> l = new ArrayList<HistoryLine>();
		int tableId = MTable.getTable_ID("C_RfQ");
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT n.Created, u.Name, n.Description, n.Reference, tu.Name "
					+ "FROM AD_Note n LEFT JOIN AD_User u ON u.AD_User_ID=n.CreatedBy "
					+ "LEFT JOIN AD_User tu ON CAST(tu.AD_User_ID AS varchar)=n.Reference "
					+ "WHERE n.AD_Client_ID=? AND n.AD_Table_ID=? AND n.Record_ID=? AND n.IsActive='Y' "
					+ "ORDER BY n.Created DESC", null);
			ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			ps.setInt(2, tableId);
			ps.setInt(3, rfqId);
			rs = ps.executeQuery();
			while (rs.next()) {
				HistoryLine h = new HistoryLine();
				h.date = rs.getTimestamp(1);
				h.user = rs.getString(2);
				String d = rs.getString(3) == null ? "" : rs.getString(3);
				int p = d.indexOf(" - ");
				h.action = p > 0 ? d.substring(0, p) : d;
				h.comment = p > 0 ? d.substring(p + 3) : "";
				h.toUser = rs.getString(5);
				l.add(h);
			}
		} catch (Exception e) {
			log.severe("history: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}

		if (l.isEmpty()) {
			try {
				ps = DB.prepareStatement("SELECT h.Updated, cu.Name, h.Status, ck.Name FROM " + T_HISTORY + " h "
						+ "LEFT JOIN AD_User cu ON cu.AD_User_ID=h.CreatedBy "
						+ "LEFT JOIN AD_User ck ON CAST(ck.AD_User_ID AS varchar)=h.Checked "
						+ "WHERE h.AD_Client_ID=? AND h.DocumentNo=(SELECT " + docNoExpr()
						+ " FROM C_RfQ r WHERE r.C_RfQ_ID=?) AND h.C_DocType_ID=(SELECT rq.C_DocType_ID "
						+ "FROM C_RfQ r JOIN M_Requisition rq ON rq.M_Requisition_ID=r.M_Requisition_ID "
						+ "WHERE r.C_RfQ_ID=?) ORDER BY h.Updated DESC", null);
				ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
				ps.setInt(2, rfqId);
				ps.setInt(3, rfqId);
				rs = ps.executeQuery();
				while (rs.next()) {
					HistoryLine h = new HistoryLine();
					h.date = rs.getTimestamp(1);
					h.user = rs.getString(2);
					h.action = statusText(rs.getString(3));
					h.comment = "";
					h.toUser = rs.getString(4);
					l.add(h);
				}
			} catch (Exception e) {
				log.severe("history status: " + e.getMessage());
			} finally {
				DB.close(rs, ps);
			}
		}
		return l;
	}

	private static String statusText(String code) {
		if (S_APPROVED.equals(code)) return "Approved";
		if (S_REJECTED.equals(code)) return "Rejected";
		if (S_PENDING.equals(code)) return "Pending Approval";
		return code == null ? "" : code;
	}

	private static String statusFor(String action) {
		if (A_APPROVE.equals(action)) return S_APPROVED;
		if (A_REJECT.equals(action)) return S_REJECTED;
		return S_PENDING;
	}

	/**
	 * The approver a submitted RFQ should go to: the UT_ApprovalMatrix row for
	 * this document type and submitter, else the first matrix row for the
	 * document type, else the submitter's supervisor, else the submitter.
	 */
	public static int resolveApprover(int rfqId) {
		int userId = Env.getAD_User_ID(Env.getCtx());
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		int docTypeId = rfqDocTypeId(rfqId);
		int approver = 0;
		if (docTypeId > 0) {
			approver = DB.getSQLValue(null, "SELECT COALESCE(Supervisor_ID, AD_User_ID) FROM UT_ApprovalMatrix "
					+ "WHERE C_DocType_ID=? AND AD_User_ID=? AND IsActive='Y' AND AD_Client_ID IN (0,?) "
					+ "ORDER BY UT_ApprovalMatrix_ID LIMIT 1", docTypeId, userId, clientId);
			if (approver <= 0)
				approver = DB.getSQLValue(null, "SELECT COALESCE(Supervisor_ID, AD_User_ID) FROM UT_ApprovalMatrix "
						+ "WHERE C_DocType_ID=? AND IsActive='Y' AND AD_Client_ID IN (0,?) "
						+ "ORDER BY UT_ApprovalMatrix_ID LIMIT 1", docTypeId, clientId);
		}
		if (approver <= 0)
			approver = DB.getSQLValue(null, "SELECT Supervisor_ID FROM AD_User WHERE AD_User_ID=?", userId);
		return approver > 0 ? approver : userId;
	}

	/**
	 * Writes the live approval state (status + current approver) into
	 * {@code UT_ApprovalHistory} so the RFQ appears in the same approval inbox
	 * as purchase orders, and records the action with its comment in
	 * {@code AD_Note} for the audit trail.
	 */
	private void logHistory(int rfqId, String action, String message, int toUserId) throws Exception {
		int docTypeId = rfqDocTypeId(rfqId);
		String docNo = DB.getSQLValueString(null,
				"SELECT " + docNoExpr() + " FROM C_RfQ r WHERE r.C_RfQ_ID=?", rfqId);
		if (docNo == null) docNo = "";
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		int orgId = Env.getAD_Org_ID(Env.getCtx());
		int userId = Env.getAD_User_ID(Env.getCtx());

		String status = statusFor(action);
		int checked;
		if (A_FORWARD.equals(action))
			checked = toUserId;
		else if (A_APPROVE.equals(action) || A_REJECT.equals(action))
			checked = userId;
		else
			checked = resolveApprover(rfqId);

		String trxName = Trx.createTrxName("RfQHist");
		Trx trx = Trx.get(trxName, true);
		try {
			int updated = DB.executeUpdateEx(
					"UPDATE UT_ApprovalHistory SET Status=?, Checked=?, Updated=now(), UpdatedBy=?, IsActive='Y' "
							+ "WHERE AD_Client_ID=? AND DocumentNo=? AND C_DocType_ID=?",
					new Object[] { status, String.valueOf(checked), Integer.valueOf(userId),
							Integer.valueOf(clientId), docNo, Integer.valueOf(docTypeId) }, trxName);
			if (updated == 0) {
				int id = MSequence.getNextID(0, "UT_ApprovalHistory", trxName);
				DB.executeUpdateEx("INSERT INTO UT_ApprovalHistory (UT_ApprovalHistory_UU, AD_Client_ID, AD_Org_ID, "
						+ "IsActive, Created, CreatedBy, Updated, UpdatedBy, C_DocType_ID, DocumentNo, Status, Checked) "
						+ "VALUES (?,?,?,'Y',now(),?,now(),?,?,?,?,?)",
						new Object[] { String.valueOf(id), Integer.valueOf(clientId), Integer.valueOf(orgId),
								Integer.valueOf(userId), Integer.valueOf(userId), Integer.valueOf(docTypeId), docNo,
								status, String.valueOf(checked) }, trxName);
			}
			logNote(trxName, clientId, orgId, userId, rfqId, action, message, toUserId);
			trx.commit();
		} catch (Exception e) {
			trx.rollback();
			throw e;
		} finally {
			trx.close();
		}
	}

	/** AD_Note audit row on the RFQ: "[ACTION] - comment", forwarded user in Reference. */
	private void logNote(String trxName, int clientId, int orgId, int userId, int rfqId,
			String action, String message, int toUserId) throws Exception {
		int tableId = MTable.getTable_ID("C_RfQ");
		if (tableId <= 0)
			return;
		String text = action + (isFilled(message) ? " - " + message.trim() : "");
		int noteId = MSequence.getNextID(0, "AD_Note", trxName);
		DB.executeUpdateEx("INSERT INTO AD_Note (AD_Note_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, "
				+ "Updated, UpdatedBy, AD_User_ID, AD_Message_ID, Reference, AD_Table_ID, Record_ID, Processed, "
				+ "Processing, Description, AD_Note_UU) VALUES (?,?,?,'Y',now(),?,now(),?,?,?,?,?,?,'N','N',?,?)",
				new Object[] { Integer.valueOf(noteId), Integer.valueOf(clientId), Integer.valueOf(orgId),
						Integer.valueOf(userId), Integer.valueOf(userId), Integer.valueOf(userId),
						Integer.valueOf(463), toUserId > 0 ? String.valueOf(toUserId) : null,
						Integer.valueOf(tableId), Integer.valueOf(rfqId), text,
						java.util.UUID.randomUUID().toString() }, trxName);
	}

	// ------------------------------------------------------------ small helpers

	/**
	 * True when the RFQ is a Comparative Statement candidate.
	 * <p>
	 * {@code C_RfQ} has no {@code C_DocType_ID} column (verified against the
	 * database). An RFQ inherits its document type from the requisition it was
	 * raised from, through {@code C_RfQ.M_Requisition_ID}, so the check goes
	 * C_RfQ -&gt; M_Requisition -&gt; C_DocType.
	 * <p>
	 * The requested rule is DocBaseType {@code CS}. No {@code CS} document type
	 * exists in this database yet; every requisition-backed RFQ carries
	 * {@code POR} (Purchase Requisition), so both bases are accepted and the
	 * comparison stays available for the RFQs actually in use.
	 */
	public static boolean isCsDocType(int rfqId) {
		String bt = rfqDocBaseType(rfqId);
		return "CS".equals(bt) || "POR".equals(bt);
	}

	/** Document type base type of the requisition behind the RFQ, or "" when none. */
	public static String rfqDocBaseType(int rfqId) {
		if (rfqId <= 0)
			return "";
		return nvl(DB.getSQLValueString(null,
				"SELECT dt.DocBaseType FROM C_RfQ r "
				+ "JOIN M_Requisition rq ON rq.M_Requisition_ID=r.M_Requisition_ID "
				+ "JOIN C_DocType dt ON dt.C_DocType_ID=rq.C_DocType_ID "
				+ "WHERE r.C_RfQ_ID=?", rfqId));
	}

	/** Per-product ranking DTO. */
	public static class RankedQuote {
		public int rfqLineId, responseId, bpartnerId, rank;
		public BigDecimal price;
		public String vendorName;
	}

	/** Per-product ranking of vendor quotations (ascending price, ties share rank). */
	public static List<RankedQuote> rankQuotes(int rfqId) {
		List<RankedQuote> out = new ArrayList<RankedQuote>();
		RfQApprovalService svc = new RfQApprovalService();
		Comparison c = svc.load(rfqId);
		for (Item it : c.items) {
			List<RankedQuote> lineQuotes = new ArrayList<RankedQuote>();
			for (Supplier s : c.suppliers) {
				Integer k = Integer.valueOf(s.responseId);
				BigDecimal u = it.unit.get(k);
				if (u == null) continue;
				RankedQuote q = new RankedQuote();
				q.rfqLineId = it.rfqLineId;
				q.responseId = s.responseId;
				q.bpartnerId = s.bpartnerId;
				q.vendorName = s.name;
				q.price = u;
				lineQuotes.add(q);
			}
			lineQuotes.sort((a, b) -> a.price.compareTo(b.price));
			int r = 0; BigDecimal prev = null; int pos = 0;
			for (RankedQuote q : lineQuotes) {
				pos++;
				if (prev == null || q.price.compareTo(prev) != 0) { r = pos; prev = q.price; }
				q.rank = r;
				out.add(q);
			}
		}
		return out;
	}

	static String nvl(String s) { return s == null ? "" : s; }
	static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
	static boolean isFilled(String s) { return s != null && s.trim().length() > 0; }
	private static String join(Set<String> s) {
		StringBuilder b = new StringBuilder();
		for (String x : s) { if (b.length() > 0) b.append(", "); b.append(x); }
		return b.toString();
	}
}
