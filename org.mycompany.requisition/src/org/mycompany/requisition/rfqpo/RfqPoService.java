package org.mycompany.requisition.rfqpo;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.compiere.model.MBPartner;
import org.compiere.model.MBPartnerLocation;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MWarehouse;
import org.compiere.print.ReportEngine;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Trx;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.service.ApprovalEngineClient;
import org.mycompany.requisition.rfqapproval.RfQApprovalService;

/**
 * Purchase Order from an approved Comparative Statement.
 * <p>
 * One PO per (RFQ, winner supplier). The PO is created Drafted, committed, and then handed to the
 * existing approval engine ({@link ApprovalEngineClient#start}). The engine routes it through the
 * approval matrix, writes the approval history and, after the last approval, completes the PO
 * (ApprovalFinalizer). Nothing here completes the order.
 * <p>
 * Needs db/09_create_po_from_cs.sql (C_Order.XX_RfQ_ID, C_OrderLine.XX_RfQLine_ID) so that a PO can
 * be traced back to its CS and the "Already PO Qty" can be worked out.
 */
public class RfqPoService {

	private static final CLogger log = CLogger.getCLogger(RfqPoService.class);
	private static final BigDecimal HUNDRED = new BigDecimal("100");

	// ------------------------------------------------------------ model

	/** One row of the main grid: a winner supplier of one RFQ. */
	public static class Row {
		public int rfqId, respId, bpId, items;
		public String docNo, name, prNos, bpCode, bpName, address, products, poNos, lastAction;
		public Timestamp date;
		public BigDecimal qty = BigDecimal.ZERO, ordered = BigDecimal.ZERO, total = BigDecimal.ZERO;

		public BigDecimal remaining() {
			return qty.subtract(ordered);
		}

		public String csStatus() {
			if (RfQApprovalService.S_APPROVED.equals(lastAction)) return "Approved";
			if (RfQApprovalService.S_REJECTED.equals(lastAction)) return "Rejected";
			if (RfQApprovalService.S_PENDING.equals(lastAction)) return "Pending Approval";
			return "Not Submitted";
		}

		public boolean csApproved() {
			return RfQApprovalService.S_APPROVED.equals(lastAction);
		}

		public String poStatus() {
			if (ordered.signum() <= 0) return "Not created";
			String p = poNos == null ? "" : " (" + poNos + ")";
			return (remaining().signum() > 0 ? "Partial" : "Created") + p;
		}
	}

	/** One product line of a winner supplier. */
	public static class Line {
		public int rfqLineId, respLineId, reqLineId, productId, uomId, taxId, lineNo;
		public String code = "", name = "", uom = "";
		public BigDecimal rfqQty = BigDecimal.ZERO, already = BigDecimal.ZERO, rate = BigDecimal.ZERO;
		/** Rebate / TariffRate flags of the CS response line. */
		public boolean rebate, tariff;

		public BigDecimal remaining() {
			return rfqQty.subtract(already).max(BigDecimal.ZERO);
		}
	}

	/** One supplier response line of a CS, as shown in the Create PO grid (winner or not). */
	public static class CsLine {
		public int rfqId, rfqLineId, respId, bpId, rank;
		public String bpCode = "", bpName = "", address = "", productCode = "", productName = "", comment = "";
		public BigDecimal price = BigDecimal.ZERO;
		public boolean winner;
	}

	/** Header defaults taken from the requisition and the supplier. */
	public static class Defaults {
		public int orgId, warehouseId, priceListId, paymentTermId;
		public String orgName = "";
		public Timestamp dateRequired;
		/** Transport / Others / Gross Discount of the supplier's CS response (shown in the popup, editable). */
		public BigDecimal transportCost = BigDecimal.ZERO, othersCost = BigDecimal.ZERO, grossDiscount = BigDecimal.ZERO;
		public boolean grossDiscountPct;
	}

	public static class LineReq {
		public Line line;
		public BigDecimal qty;
		public int taxId;
		public boolean rebate, tariff;
	}

	public static class Request {
		public int rfqId, respId, bpId, orgId, warehouseId, docTypeId, priceListId, paymentTermId;
		public String rfqDocNo, description;
		/** Terms and Conditions Type (XX_RFQ_Term) picked in the popup and the (editable) Terms and Conditions text. */
		public int termId;
		public String termsText;
		/** Files attached in the popup; saved as the attachment of the PO. */
		public List<org.zkoss.util.media.Media> attachments = new ArrayList<org.zkoss.util.media.Media>();
		public Timestamp dateOrdered, datePromised;
		/** Transport / Others cost and Gross Discount (a percent when grossDiscountPct, else an amount). */
		public BigDecimal transportCost = BigDecimal.ZERO, othersCost = BigDecimal.ZERO, grossDiscount = BigDecimal.ZERO;
		public boolean grossDiscountPct;
		/** true = complete the PO right away (no further approval: the CS was already approved). */
		public boolean complete;
		public List<LineReq> lines = new ArrayList<LineReq>();
	}

	public static class Result {
		public int orderId;
		public String docNo;
		/** Approval outcome, in words, for the message box. */
		public String approvalMessage;
	}

	/** PO print format (the one set up for Purchase Order) rendered to PDF bytes. */
	public static byte[] orderPdf(int orderId) throws Exception {
		Properties ctx = Env.getCtx();
		ReportEngine re = ReportEngine.get(ctx, ReportEngine.ORDER, orderId);
		if (re == null)
			throw new Exception("No print format is configured for the Purchase Order.");
		File f = null;
		try {
			f = File.createTempFile("PO_" + orderId + "_", ".pdf");
			re.getPDF(f);
			return Files.readAllBytes(f.toPath());
		} finally {
			if (f != null)
				f.delete();
		}
	}

	// ------------------------------------------------------------ Terms and Conditions

	/** Terms and Conditions Types (XX_RFQ_Term), same list as the RFQ Term dropdown. */
	public List<KeyNamePair> termTypes() {
		if (!hasColumn("xx_rfq_term", "xx_rfq_term_id"))
			return new ArrayList<KeyNamePair>();
		return pairs("SELECT XX_RFQ_Term_ID, Name FROM XX_RFQ_Term WHERE IsActive='Y' AND AD_Client_ID IN (0,?) ORDER BY Name");
	}

	/** The Terms and Conditions text of a type. */
	public String termText(int termId) {
		if (termId <= 0 || !hasColumn("xx_rfq_term", "termstext"))
			return "";
		String t = DB.getSQLValueString(null, "SELECT TermsText FROM XX_RFQ_Term WHERE XX_RFQ_Term_ID=?", termId);
		return t == null ? "" : t;
	}

	/** False until db/19_po_terms.sql has been run. */
	public static boolean termColumnsReady() {
		return hasColumn("c_order", "xx_rfq_term_id") && hasColumn("c_order", "xx_termstext");
	}

	// ------------------------------------------------------------ checks

	/** False until db/09_create_po_from_cs.sql has been run. */
	public static boolean columnsReady() {
		return hasColumn("c_order", "xx_rfq_id") && hasColumn("c_orderline", "xx_rfqline_id");
	}

	/** False until db/12_create_po_extra_fields.sql has been run. */
	public static boolean extraColumnsReady() {
		return hasColumn("c_order", "xx_payabletotal") && hasColumn("c_orderline", "xx_isrebate");
	}

	private static boolean hasColumn(String table, String column) {
		return DB.getSQLValue(null, "SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE LOWER(table_name)=? AND LOWER(column_name)=?", table, column) > 0;
	}

	/** Approval state of the CS (UT_ApprovalHistory.Status of the latest action), or null. */
	public static String csState(int rfqId) {
		return DB.getSQLValueString(null,
				"SELECT " + RfQApprovalService.lastActionSql() + " FROM C_RfQ r WHERE r.C_RfQ_ID=?", rfqId);
	}

	// ------------------------------------------------------------ main grid

	/** Every RFQ with a recommended winner AND an approved CS, one row per winner supplier. */
	public List<Row> listRows(boolean pendingOnly) {
		boolean ready = columnsReady();
		String already = ready
				? "(SELECT COALESCE(SUM(ol.QtyOrdered),0) FROM C_OrderLine ol JOIN C_Order o ON o.C_Order_ID=ol.C_Order_ID "
						+ " WHERE ol.XX_RfQLine_ID=l.C_RfQLine_ID AND o.C_BPartner_ID=bp.C_BPartner_ID "
						+ " AND o.IsSOTrx='N' AND o.DocStatus NOT IN ('VO','RE'))"
				: "0";
		String poNos = ready
				? "(SELECT string_agg(o.DocumentNo, ', ' ORDER BY o.C_Order_ID) FROM C_Order o "
						+ " WHERE o.XX_RfQ_ID=r.C_RfQ_ID AND o.C_BPartner_ID=bp.C_BPartner_ID "
						+ " AND o.IsSOTrx='N' AND o.DocStatus NOT IN ('VO','RE'))"
				: "NULL";
		String qty = "COALESCE(NULLIF(q.XX_QuotationQty,0), NULLIF(lq.Qty,0), rql.Qty, 0)";

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT r.C_RfQ_ID, ").append(RfQApprovalService.docNoExpr()).append(", r.Name, r.Created, ");
		sql.append("rs.C_RfQResponse_ID, bp.C_BPartner_ID, bp.Value, bp.Name, ");
		sql.append("(SELECT COALESCE(loc.Address1,'') || CASE WHEN loc.City IS NOT NULL THEN ', ' || loc.City ELSE '' END ");
		sql.append("   FROM C_BPartner_Location bl JOIN C_Location loc ON loc.C_Location_ID=bl.C_Location_ID ");
		sql.append("   WHERE bl.C_BPartner_ID=bp.C_BPartner_ID AND bl.IsActive='Y' ORDER BY bl.C_BPartner_Location_ID LIMIT 1), ");
		sql.append("(SELECT string_agg(DISTINCT x.DocumentNo, ', ') FROM C_RfQLine xl ");
		sql.append("   JOIN M_RequisitionLine xrl ON xrl.M_RequisitionLine_ID=xl.XX_RequisitionLine_ID ");
		sql.append("   JOIN M_Requisition x ON x.M_Requisition_ID=xrl.M_Requisition_ID WHERE xl.C_RfQ_ID=r.C_RfQ_ID), ");
		sql.append("string_agg(COALESCE(p.Name, l.Description, ''), ', ' ORDER BY l.Line), ");
		sql.append("COUNT(DISTINCT sl.C_RfQResponseLine_ID), ");
		sql.append("SUM(").append(qty).append("), SUM(").append(already).append("), ");
		sql.append("SUM(COALESCE(q.XX_LineTotal, ").append(qty)
				.append(" * CASE WHEN COALESCE(q.XX_NegotiatePrice,0)>0 THEN q.XX_NegotiatePrice ELSE q.Price END)), ");
		sql.append(poNos).append(", ").append(RfQApprovalService.lastActionSql()).append(" ");
		sql.append("FROM C_RfQ r ");
		sql.append("JOIN C_RfQLine l ON l.C_RfQ_ID=r.C_RfQ_ID AND l.IsActive='Y' ");
		sql.append("JOIN C_RfQResponseLine sl ON sl.C_RfQLine_ID=l.C_RfQLine_ID AND sl.IsSelectedWinner='Y' AND sl.IsActive='Y' ");
		sql.append("JOIN C_RfQResponse rs ON rs.C_RfQResponse_ID=sl.C_RfQResponse_ID AND rs.IsActive='Y' ");
		sql.append("JOIN C_BPartner bp ON bp.C_BPartner_ID=rs.C_BPartner_ID ");
		sql.append("JOIN C_RfQResponseLineQty q ON q.C_RfQResponseLine_ID=sl.C_RfQResponseLine_ID AND q.IsActive='Y' ");
		sql.append("  AND q.C_RfQResponseLineQty_ID=").append(firstQuote("sl")).append(" ");
		sql.append("LEFT JOIN C_RfQLineQty lq ON lq.C_RfQLineQty_ID=q.C_RfQLineQty_ID ");
		sql.append("LEFT JOIN M_RequisitionLine rql ON rql.M_RequisitionLine_ID=l.XX_RequisitionLine_ID ");
		sql.append("LEFT JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID ");
		sql.append("WHERE r.AD_Client_ID=? AND r.IsActive='Y' ");
		// Only RFQs with Document Status = Completed (same rule as the RFQ window field)
		sql.append("AND ").append(RfQApprovalService.completeSql()).append(" ");
		sql.append("GROUP BY r.C_RfQ_ID, rs.C_RfQResponse_ID, bp.C_BPartner_ID ");
		sql.append("ORDER BY r.C_RfQ_ID DESC, bp.Name");

		List<Row> list = new ArrayList<Row>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql.toString(), null);
			ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			rs = ps.executeQuery();
			while (rs.next()) {
				Row r = new Row();
				r.rfqId = rs.getInt(1);
				r.docNo = rs.getString(2);
				r.name = nvl(rs.getString(3));
				r.date = rs.getTimestamp(4);
				r.respId = rs.getInt(5);
				r.bpId = rs.getInt(6);
				r.bpCode = nvl(rs.getString(7));
				r.bpName = nvl(rs.getString(8));
				r.address = nvl(rs.getString(9));
				r.prNos = nvl(rs.getString(10));
				r.products = nvl(rs.getString(11));
				r.items = rs.getInt(12);
				r.qty = nz(rs.getBigDecimal(13));
				r.ordered = nz(rs.getBigDecimal(14));
				r.total = nz(rs.getBigDecimal(15));
				r.poNos = rs.getString(16);
				r.lastAction = rs.getString(17);
				// Only RFQs whose Comparative Statement is fully approved may be turned into a PO.
				// Not submitted / Pending Approval / Rejected stay out of this list.
				if (!r.csApproved())
					continue;
				if (pendingOnly && r.remaining().signum() <= 0)
					continue;
				list.add(r);
			}
		} catch (Exception e) {
			log.severe("listRows: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return list;
	}

	/** The quote of a response line that counts: the first one with a price. */
	private static String firstQuote(String respLineAlias) {
		return "(SELECT MIN(q2.C_RfQResponseLineQty_ID) FROM C_RfQResponseLineQty q2 "
				+ "WHERE q2.C_RfQResponseLine_ID=" + respLineAlias + ".C_RfQResponseLine_ID "
				+ "AND q2.IsActive='Y' AND COALESCE(q2.Price,0)>0)";
	}

	// ------------------------------------------------------------ lines

	/** Winning lines of one supplier in one RFQ. */
	public List<Line> loadLines(int rfqId, int respId) {
		boolean ready = columnsReady();
		String already = ready
				? "(SELECT COALESCE(SUM(ol.QtyOrdered),0) FROM C_OrderLine ol JOIN C_Order o ON o.C_Order_ID=ol.C_Order_ID "
						+ " WHERE ol.XX_RfQLine_ID=l.C_RfQLine_ID AND o.C_BPartner_ID=rs.C_BPartner_ID "
						+ " AND o.IsSOTrx='N' AND o.DocStatus NOT IN ('VO','RE'))"
				: "0";
		String sql = "SELECT l.C_RfQLine_ID, sl.C_RfQResponseLine_ID, l.Line, l.XX_RequisitionLine_ID, l.M_Product_ID, "
				+ "COALESCE(p.Value,''), COALESCE(p.Name, l.Description, ''), "
				+ "COALESCE(NULLIF(uom.UOMSymbol,''), uom.Name, ''), COALESCE(lq.C_UOM_ID, p.C_UOM_ID, 0), "
				+ "COALESCE(NULLIF(q.XX_QuotationQty,0), NULLIF(lq.Qty,0), rql.Qty, 0), " + already + ", "
				+ "CASE WHEN COALESCE(q.XX_NegotiatePrice,0)>0 THEN q.XX_NegotiatePrice ELSE q.Price END, "
				+ "COALESCE(q.XX_Tax_ID,0), COALESCE(q.XX_IsRebate,'N'), COALESCE(q.XX_IsTariff,'N') "
				+ "FROM C_RfQLine l "
				+ "JOIN C_RfQResponseLine sl ON sl.C_RfQLine_ID=l.C_RfQLine_ID AND sl.IsSelectedWinner='Y' AND sl.IsActive='Y' "
				+ "JOIN C_RfQResponse rs ON rs.C_RfQResponse_ID=sl.C_RfQResponse_ID "
				+ "JOIN C_RfQResponseLineQty q ON q.C_RfQResponseLine_ID=sl.C_RfQResponseLine_ID "
				+ "  AND q.C_RfQResponseLineQty_ID=" + firstQuote("sl") + " "
				+ "LEFT JOIN C_RfQLineQty lq ON lq.C_RfQLineQty_ID=q.C_RfQLineQty_ID "
				+ "LEFT JOIN C_UOM uom ON uom.C_UOM_ID=COALESCE(lq.C_UOM_ID, 0) "
				+ "LEFT JOIN M_RequisitionLine rql ON rql.M_RequisitionLine_ID=l.XX_RequisitionLine_ID "
				+ "LEFT JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID "
				+ "WHERE l.C_RfQ_ID=? AND rs.C_RfQResponse_ID=? AND l.IsActive='Y' "
				+ "ORDER BY l.Line, l.C_RfQLine_ID";
		List<Line> list = new ArrayList<Line>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, rfqId);
			ps.setInt(2, respId);
			rs = ps.executeQuery();
			while (rs.next()) {
				Line x = new Line();
				x.rfqLineId = rs.getInt(1);
				x.respLineId = rs.getInt(2);
				x.lineNo = rs.getInt(3);
				x.reqLineId = rs.getInt(4);
				x.productId = rs.getInt(5);
				x.code = nvl(rs.getString(6));
				x.name = nvl(rs.getString(7));
				x.uom = nvl(rs.getString(8));
				x.uomId = rs.getInt(9);
				x.rfqQty = nz(rs.getBigDecimal(10));
				x.already = nz(rs.getBigDecimal(11));
				x.rate = nz(rs.getBigDecimal(12));
				x.taxId = rs.getInt(13);
				x.rebate = "Y".equals(rs.getString(14));
				x.tariff = "Y".equals(rs.getString(15));
				list.add(x);
			}
		} catch (Exception e) {
			log.severe("loadLines: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return list;
	}

	/**
	 * Every supplier's response lines of one CS: the winner (recommended) supplier first, then the
	 * others; per supplier in product order. Rank = position of the price among the suppliers of the
	 * same product (cheapest first, equal prices share a rank).
	 */
	public List<CsLine> listCsLines(int rfqId) {
		String sql = "SELECT l.C_RfQLine_ID, x.C_RfQResponse_ID, bp.C_BPartner_ID, bp.Value, bp.Name, "
				+ "(SELECT COALESCE(loc.Address1,'') || CASE WHEN loc.City IS NOT NULL THEN ', ' || loc.City ELSE '' END "
				+ "   FROM C_BPartner_Location bl JOIN C_Location loc ON loc.C_Location_ID=bl.C_Location_ID "
				+ "   WHERE bl.C_BPartner_ID=bp.C_BPartner_ID AND bl.IsActive='Y' ORDER BY bl.C_BPartner_Location_ID LIMIT 1), "
				+ "COALESCE(p.Value,''), COALESCE(p.Name, l.Description, ''), "
				+ "CASE WHEN COALESCE(sq.XX_NegotiatePrice,0)>0 THEN sq.XX_NegotiatePrice ELSE COALESCE(sq.Price,0) END, "
				+ "sl.IsSelectedWinner, COALESCE(sl.XX_WinnerComment,'') "
				+ "FROM C_RfQResponse x "
				+ "JOIN C_BPartner bp ON bp.C_BPartner_ID=x.C_BPartner_ID "
				+ "JOIN C_RfQResponseLine sl ON sl.C_RfQResponse_ID=x.C_RfQResponse_ID AND sl.IsActive='Y' "
				+ "JOIN C_RfQLine l ON l.C_RfQLine_ID=sl.C_RfQLine_ID AND l.IsActive='Y' "
				+ "JOIN C_RfQResponseLineQty sq ON sq.C_RfQResponseLine_ID=sl.C_RfQResponseLine_ID AND sq.IsActive='Y' "
				+ "  AND sq.C_RfQResponseLineQty_ID=" + firstQuote("sl") + " "
				+ "LEFT JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID "
				+ "WHERE x.C_RfQ_ID=? AND x.IsActive='Y' "
				+ "ORDER BY bp.Name, x.C_RfQResponse_ID, l.Line, l.C_RfQLine_ID";
		List<CsLine> all = new ArrayList<CsLine>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, rfqId);
			rs = ps.executeQuery();
			while (rs.next()) {
				CsLine c = new CsLine();
				c.rfqId = rfqId;
				c.rfqLineId = rs.getInt(1);
				c.respId = rs.getInt(2);
				c.bpId = rs.getInt(3);
				c.bpCode = nvl(rs.getString(4));
				c.bpName = nvl(rs.getString(5));
				c.address = nvl(rs.getString(6));
				c.productCode = nvl(rs.getString(7));
				c.productName = nvl(rs.getString(8));
				c.price = nz(rs.getBigDecimal(9));
				c.winner = "Y".equals(rs.getString(10));
				c.comment = nvl(rs.getString(11));
				all.add(c);
			}
		} catch (Exception e) {
			log.severe("listCsLines: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}

		// rank per product
		Map<Integer, List<CsLine>> byProduct = new LinkedHashMap<Integer, List<CsLine>>();
		for (CsLine c : all)
			byProduct.computeIfAbsent(Integer.valueOf(c.rfqLineId), k -> new ArrayList<CsLine>()).add(c);
		for (List<CsLine> g : byProduct.values()) {
			List<CsLine> sorted = new ArrayList<CsLine>(g);
			sorted.sort((a, b) -> a.price.compareTo(b.price));
			int rank = 0, pos = 0;
			BigDecimal prev = null;
			for (CsLine c : sorted) {
				pos++;
				if (prev == null || c.price.compareTo(prev) != 0) {
					rank = pos;
					prev = c.price;
				}
				c.rank = rank;
			}
		}

		// group per supplier response, suppliers that won something first
		Map<Integer, List<CsLine>> byResp = new LinkedHashMap<Integer, List<CsLine>>();
		for (CsLine c : all)
			byResp.computeIfAbsent(Integer.valueOf(c.respId), k -> new ArrayList<CsLine>()).add(c);
		List<List<CsLine>> groups = new ArrayList<List<CsLine>>(byResp.values());
		groups.sort((a, b) -> Boolean.compare(b.stream().anyMatch(x -> x.winner), a.stream().anyMatch(x -> x.winner)));
		List<CsLine> out = new ArrayList<CsLine>();
		for (List<CsLine> g : groups)
			out.addAll(g);
		return out;
	}

	// ------------------------------------------------------------ dropdown data

	public Defaults loadDefaults(int rfqId, int bpId) {
		Defaults d = new Defaults();
		d.orgId = DB.getSQLValue(null, "SELECT AD_Org_ID FROM C_RfQ WHERE C_RfQ_ID=?", rfqId);
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT rq.M_Warehouse_ID, rq.M_PriceList_ID, rq.DateRequired "
					+ "FROM C_RfQLine l JOIN M_RequisitionLine rql ON rql.M_RequisitionLine_ID=l.XX_RequisitionLine_ID "
					+ "JOIN M_Requisition rq ON rq.M_Requisition_ID=rql.M_Requisition_ID "
					+ "WHERE l.C_RfQ_ID=? ORDER BY l.Line, l.C_RfQLine_ID LIMIT 1", null);
			ps.setInt(1, rfqId);
			rs = ps.executeQuery();
			if (rs.next()) {
				d.warehouseId = rs.getInt(1);
				d.priceListId = rs.getInt(2);
				d.dateRequired = rs.getTimestamp(3);
			}
		} catch (Exception e) {
			log.warning("loadDefaults: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		if (d.priceListId <= 0)
			d.priceListId = Math.max(0, DB.getSQLValue(null, "SELECT PO_PriceList_ID FROM C_BPartner WHERE C_BPartner_ID=?", bpId));
		d.paymentTermId = Math.max(0, DB.getSQLValue(null, "SELECT PO_PaymentTerm_ID FROM C_BPartner WHERE C_BPartner_ID=?", bpId));
		d.orgName = DB.getSQLValueString(null, "SELECT Name FROM AD_Org WHERE AD_Org_ID=?", d.orgId);
		if (d.orgName == null)
			d.orgName = "";
		return d;
	}

	/** Transport / Others cost and Gross Discount that the supplier gave in its CS response. */
	public void loadResponseCosts(Defaults d, int respId) {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT COALESCE(XX_TransportCost,0), COALESCE(XX_OthersCost,0), "
					+ "COALESCE(XX_IsGrossDiscountPct,'N'), COALESCE(XX_GrossDiscount,0) "
					+ "FROM C_RfQResponse WHERE C_RfQResponse_ID=?", null);
			ps.setInt(1, respId);
			rs = ps.executeQuery();
			if (rs.next()) {
				d.transportCost = nz(rs.getBigDecimal(1));
				d.othersCost = nz(rs.getBigDecimal(2));
				d.grossDiscountPct = "Y".equals(rs.getString(3));
				d.grossDiscount = nz(rs.getBigDecimal(4));
			}
		} catch (Exception e) {
			log.warning("loadResponseCosts: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
	}

	/** Gross discount amount: a percent of (lines + VAT) or a fixed amount. */
	public static BigDecimal discountAmount(BigDecimal linesPlusVat, boolean pct, BigDecimal input) {
		BigDecimal in = input == null ? BigDecimal.ZERO : input;
		return pct ? linesPlusVat.multiply(in).divide(HUNDRED, 2, RoundingMode.HALF_UP) : in;
	}

	public List<KeyNamePair> warehouses() {
		return pairs("SELECT M_Warehouse_ID, Name FROM M_Warehouse WHERE IsActive='Y' AND AD_Client_ID=? ORDER BY Name");
	}

	/** Purchase order document types, the default one first. */
	public List<KeyNamePair> docTypes() {
		return pairs("SELECT C_DocType_ID, Name FROM C_DocType WHERE DocBaseType='POO' AND IsActive='Y' "
				+ "AND AD_Client_ID IN (0,?) ORDER BY IsDefault DESC, Name");
	}

	public List<KeyNamePair> priceLists() {
		return pairs("SELECT M_PriceList_ID, Name FROM M_PriceList WHERE IsSOPriceList='N' AND IsActive='Y' "
				+ "AND AD_Client_ID IN (0,?) ORDER BY Name");
	}

	public List<KeyNamePair> paymentTerms() {
		return pairs("SELECT C_PaymentTerm_ID, Name FROM C_PaymentTerm WHERE IsActive='Y' AND AD_Client_ID IN (0,?) ORDER BY Name");
	}

	/** C_Tax_ID -> {name, rate}. */
	public Map<Integer, Object[]> taxes() {
		Map<Integer, Object[]> m = new LinkedHashMap<Integer, Object[]>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement("SELECT C_Tax_ID, Name, Rate FROM C_Tax WHERE IsActive='Y' "
					+ "AND AD_Client_ID IN (0,?) ORDER BY Name", null);
			ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			rs = ps.executeQuery();
			while (rs.next())
				m.put(Integer.valueOf(rs.getInt(1)), new Object[] { rs.getString(2), nz(rs.getBigDecimal(3)) });
		} catch (Exception e) {
			log.warning("taxes: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return m;
	}

	private List<KeyNamePair> pairs(String sql) {
		List<KeyNamePair> list = new ArrayList<KeyNamePair>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			rs = ps.executeQuery();
			while (rs.next())
				list.add(new KeyNamePair(rs.getInt(1), rs.getString(2)));
		} catch (Exception e) {
			log.warning("pairs: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return list;
	}

	// ------------------------------------------------------------ create

	/**
	 * Creates the Drafted PO, then submits it to the approval engine.
	 * The PO is committed first: the engine reads it with its own connection.
	 */
	public Result createPo(Request req) throws Exception {
		if (!columnsReady())
			throw new Exception("Run db/09_create_po_from_cs.sql first (C_Order.XX_RfQ_ID / C_OrderLine.XX_RfQLine_ID are missing).");
		if (!"CO".equals(DB.getSQLValueString(null,
				"SELECT " + RfQApprovalService.statusSql() + " FROM C_RfQ r WHERE r.C_RfQ_ID=?", req.rfqId)))
			throw new Exception("The Comparative Statement is not Approved yet.");
		if (req.lines.isEmpty())
			throw new Exception("Select at least one line.");
		if (!extraColumnsReady())
			throw new Exception("Run db/12_create_po_extra_fields.sql first (Transport / Others cost, Gross Discount, Rebate, Tariff columns are missing).");
		if (req.transportCost == null) req.transportCost = BigDecimal.ZERO;
		if (req.othersCost == null) req.othersCost = BigDecimal.ZERO;
		if (req.grossDiscount == null) req.grossDiscount = BigDecimal.ZERO;
		if (req.transportCost.signum() < 0 || req.othersCost.signum() < 0 || req.grossDiscount.signum() < 0)
			throw new Exception("Transport Cost, Others Cost and Gross Discount cannot be negative.");
		if (req.grossDiscountPct && req.grossDiscount.compareTo(HUNDRED) > 0)
			throw new Exception("Gross Discount % cannot be more than 100.");

		// quantities may have been used by another PO since the dialog was opened
		Map<Integer, Line> fresh = new LinkedHashMap<Integer, Line>();
		for (Line l : loadLines(req.rfqId, req.respId))
			fresh.put(Integer.valueOf(l.rfqLineId), l);
		for (LineReq lr : req.lines) {
			Line f = fresh.get(Integer.valueOf(lr.line.rfqLineId));
			if (f == null)
				throw new Exception("Line " + lr.line.name + " is no longer a winner line.");
			if (lr.qty.signum() <= 0)
				throw new Exception("PO Qty must be more than zero (" + lr.line.name + ").");
			if (lr.qty.compareTo(f.remaining()) > 0)
				throw new Exception("PO Qty of " + lr.line.name + " is more than the remaining " + f.remaining().stripTrailingZeros().toPlainString() + ".");
			if (f.productId <= 0)
				throw new Exception("RFQ line " + lr.line.name + " has no product.");
		}

		int orderId;
		String docNo;
		BigDecimal payable = BigDecimal.ZERO;
		int docTypeId = req.docTypeId;
		Trx trx = Trx.get(Trx.createTrxName("RfqPO"), true);
		try {
			String trxName = trx.getTrxName();
			MBPartner bp = new MBPartner(Env.getCtx(), req.bpId, trxName);
			MBPartnerLocation[] locs = bp.getLocations(false);
			if (locs.length == 0)
				throw new Exception("Supplier " + bp.getName() + " has no location. Add an address to the Business Partner first.");

			MOrder order = new MOrder(Env.getCtx(), 0, trxName);
			order.setIsSOTrx(false);
			order.setC_DocTypeTarget_ID(req.docTypeId);
			order.setC_BPartner_ID(req.bpId);
			order.setC_BPartner_Location_ID(locs[0].getC_BPartner_Location_ID());
			order.setM_PriceList_ID(req.priceListId);
			order.setM_Warehouse_ID(req.warehouseId);
			order.setC_PaymentTerm_ID(req.paymentTermId);
			order.setDateOrdered(req.dateOrdered);
			order.setDatePromised(req.datePromised);
			order.setSalesRep_ID(Env.getAD_User_ID(Env.getCtx()));
			order.setDescription(req.description == null || req.description.trim().length() == 0 ? null : req.description);

			// the order stays in the warehouse's organization, otherwise WarehouseOrgConflict
			int orgId = req.orgId;
			MWarehouse wh = MWarehouse.get(Env.getCtx(), req.warehouseId, trxName);
			if (wh != null && wh.getAD_Org_ID() > 0)
				orgId = wh.getAD_Org_ID();
			if (orgId > 0)
				order.setAD_Org_ID(orgId);
			order.saveEx();

			int lineNo = 10;
			for (LineReq lr : req.lines) {
				MOrderLine ol = new MOrderLine(order);
				ol.setLine(lineNo);
				ol.setM_Product_ID(lr.line.productId);
				if (lr.line.uomId > 0)
					ol.setC_UOM_ID(lr.line.uomId);
				ol.setQty(lr.qty);
				ol.setPrice(lr.line.rate);
				if (lr.taxId > 0)
					ol.setC_Tax_ID(lr.taxId);
				ol.saveEx();

				// trace back to the CS line, and mark the requisition line as ordered
				DB.executeUpdateEx("UPDATE C_OrderLine SET XX_RfQLine_ID=?, XX_IsRebate=?, XX_IsTariff=? WHERE C_OrderLine_ID=?",
						new Object[] { Integer.valueOf(lr.line.rfqLineId), lr.rebate ? "Y" : "N", lr.tariff ? "Y" : "N",
								Integer.valueOf(ol.getC_OrderLine_ID()) }, trxName);
				if (lr.line.reqLineId > 0)
					DB.executeUpdateEx("UPDATE M_RequisitionLine SET C_OrderLine_ID=? "
							+ "WHERE M_RequisitionLine_ID=? AND C_OrderLine_ID IS NULL",
							new Object[] { Integer.valueOf(ol.getC_OrderLine_ID()), Integer.valueOf(lr.line.reqLineId) }, trxName);
				lineNo += 10;
			}

			// Payable = lines + VAT - gross discount + transport + others. The approval matrix is started with it.
			BigDecimal linesPlusVat = new MOrder(Env.getCtx(), order.getC_Order_ID(), trxName).getGrandTotal();
			BigDecimal discount = discountAmount(linesPlusVat, req.grossDiscountPct, req.grossDiscount);
			payable = linesPlusVat.subtract(discount).add(req.transportCost).add(req.othersCost);
			DB.executeUpdateEx("UPDATE C_Order SET XX_RfQ_ID=?, XX_TransportCost=?, XX_OthersCost=?, "
					+ "XX_IsGrossDiscountPct=?, XX_GrossDiscount=?, XX_PayableTotal=? WHERE C_Order_ID=?",
					new Object[] { Integer.valueOf(req.rfqId), req.transportCost, req.othersCost,
							req.grossDiscountPct ? "Y" : "N", req.grossDiscount, payable,
							Integer.valueOf(order.getC_Order_ID()) }, trxName);

			if (termColumnsReady())
				DB.executeUpdateEx("UPDATE C_Order SET XX_RFQ_Term_ID=?, XX_TermsText=? WHERE C_Order_ID=?",
						new Object[] { req.termId > 0 ? Integer.valueOf(req.termId) : null,
								req.termsText == null || req.termsText.trim().length() == 0 ? null : req.termsText,
								Integer.valueOf(order.getC_Order_ID()) }, trxName);

			if (req.attachments != null && !req.attachments.isEmpty()) {
				org.compiere.model.MAttachment att = new org.compiere.model.MAttachment(Env.getCtx(),
						MOrder.Table_ID, order.getC_Order_ID(), trxName);
				for (org.zkoss.util.media.Media m : req.attachments) {
					byte[] bytes = m.isBinary() ? m.getByteData() : m.getStringData().getBytes("UTF-8");
					att.addEntry(m.getName(), bytes);
				}
				att.saveEx();
			}

			orderId = order.getC_Order_ID();
			docNo = order.getDocumentNo();

			if (req.complete) {
				MOrder o = new MOrder(Env.getCtx(), orderId, trxName);
				o.setDocAction(DocAction.ACTION_Complete);
				if (!o.processIt(DocAction.ACTION_Complete))
					throw new Exception("Could not complete the PO: " + o.getProcessMsg());
				o.saveEx();
				// The PO is completed right here. If a trigger on C_Order made a pending row for it in
				// the PO approval list, close it so it cannot be approved (= completed) a second time.
				DB.executeUpdateEx("UPDATE " + RfQApprovalService.T_HISTORY
						+ " SET Status='" + RfQApprovalService.S_APPROVED + "', Updated=now(), UpdatedBy=? "
						+ "WHERE AD_Client_ID=? AND DocumentNo=? AND C_DocType_ID=? AND Status='" + RfQApprovalService.S_PENDING + "'",
						new Object[] { Integer.valueOf(Env.getAD_User_ID(Env.getCtx())),
								Integer.valueOf(Env.getAD_Client_ID(Env.getCtx())), docNo, Integer.valueOf(docTypeId) }, trxName);
			}
			trx.commit();
		} catch (Exception ex) {
			trx.rollback();
			throw ex;
		} finally {
			trx.close();
		}

		Result res = new Result();
		res.orderId = orderId;
		res.docNo = docNo;
		res.approvalMessage = req.complete ? "The PO is Completed." : submit(orderId, docTypeId, req.rfqDocNo, payable);
		return res;
	}

	/**
	 * One-click PO for a winner supplier: every line that still has quantity left (or just the one
	 * line asked for), header values taken from the requisition / supplier, the PO is created and
	 * Completed in one go.
	 */
	public Result createCompletedPo(Row row) throws Exception {
		return createCompletedPo(row, 0);
	}

	/** Same, but only for one CS line (rfqLineId > 0): the "Create PO" button of a single product line. */
	public Result createCompletedPo(Row row, int rfqLineId) throws Exception {
		Defaults d = loadDefaults(row.rfqId, row.bpId);
		Request req = new Request();
		req.rfqId = row.rfqId;
		req.respId = row.respId;
		req.bpId = row.bpId;
		req.rfqDocNo = row.docNo;
		req.orgId = d.orgId;

		req.warehouseId = d.warehouseId;
		if (req.warehouseId <= 0) {
			List<KeyNamePair> wh = warehouses();
			if (wh.size() == 1)
				req.warehouseId = wh.get(0).getKey();
		}
		if (req.warehouseId <= 0)
			throw new Exception("No warehouse is set on the requisition of CS " + row.docNo + ".");

		List<KeyNamePair> types = docTypes();
		if (types.isEmpty())
			throw new Exception("No Purchase Order document type found.");
		req.docTypeId = types.get(0).getKey();

		req.priceListId = d.priceListId;
		if (req.priceListId <= 0)
			throw new Exception("No purchase price list: set one on the requisition or on supplier " + row.bpName + ".");

		req.paymentTermId = d.paymentTermId;
		if (req.paymentTermId <= 0)
			req.paymentTermId = Math.max(0, DB.getSQLValue(null, "SELECT C_PaymentTerm_ID FROM C_PaymentTerm "
					+ "WHERE IsDefault='Y' AND IsActive='Y' AND AD_Client_ID IN (0,?) ORDER BY AD_Client_ID DESC",
					Env.getAD_Client_ID(Env.getCtx())));
		if (req.paymentTermId <= 0)
			throw new Exception("No PO payment term: set one on supplier " + row.bpName + ".");

		req.dateOrdered = new Timestamp(System.currentTimeMillis());
		req.datePromised = d.dateRequired != null ? d.dateRequired : req.dateOrdered;
		req.description = "Created from CS " + row.docNo;
		loadResponseCosts(d, row.respId);
		req.transportCost = d.transportCost;
		req.othersCost = d.othersCost;
		req.grossDiscountPct = d.grossDiscountPct;
		req.grossDiscount = d.grossDiscount;
		req.complete = false; // PO will be created as Drafted and sent to Approval Matrix

		for (Line l : loadLines(row.rfqId, row.respId)) {
			if (rfqLineId > 0 && l.rfqLineId != rfqLineId)
				continue;
			if (l.remaining().signum() <= 0)
				continue;
			LineReq lr = new LineReq();
			lr.line = l;
			lr.qty = l.remaining();
			lr.taxId = l.taxId;
			lr.rebate = l.rebate;
			lr.tariff = l.tariff;
			req.lines.add(lr);
		}
		if (req.lines.isEmpty())
			throw new Exception("A PO is already created for the whole quantity of this item.");
		return createPo(req);
	}

	/** Hands the Drafted PO to the approval matrix. A failure here leaves the PO Drafted. */
	private String submit(int orderId, int docTypeId, String rfqDocNo, BigDecimal payable) {
		try {
			ApprovalTransaction t = new ApprovalEngineClient().start(MOrder.Table_ID, orderId, payable, docTypeId,
					"PO created from Comparative Statement " + rfqDocNo);
			if (t == null)
				return "No approval process is configured for this PO. It stays Drafted.";
			return "Submitted for approval: " + t.getDisplayName();
		} catch (Exception e) {
			log.warning("approval start: " + e.getMessage());
			return "The PO was created but could not be submitted for approval (" + e.getMessage()
					+ "). It stays Drafted: use Submit for Approval on the PO.";
		}
	}

	// ------------------------------------------------------------ small helpers

	public static BigDecimal vat(BigDecimal base, BigDecimal ratePct) {
		return base.multiply(ratePct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	private static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}
}