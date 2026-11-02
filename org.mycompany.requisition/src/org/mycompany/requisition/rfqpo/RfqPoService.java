package org.mycompany.requisition.rfqpo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.compiere.model.MBPartner;
import org.compiere.model.MBPartnerLocation;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MWarehouse;
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

		public BigDecimal remaining() {
			return rfqQty.subtract(already).max(BigDecimal.ZERO);
		}
	}

	/** Header defaults taken from the requisition and the supplier. */
	public static class Defaults {
		public int orgId, warehouseId, priceListId, paymentTermId;
		public String orgName = "";
		public Timestamp dateRequired;
	}

	public static class LineReq {
		public Line line;
		public BigDecimal qty;
		public int taxId;
	}

	public static class Request {
		public int rfqId, respId, bpId, orgId, warehouseId, docTypeId, priceListId, paymentTermId;
		public String rfqDocNo, description;
		public Timestamp dateOrdered, datePromised;
		public List<LineReq> lines = new ArrayList<LineReq>();
	}

	public static class Result {
		public int orderId;
		public String docNo;
		/** Approval outcome, in words, for the message box. */
		public String approvalMessage;
	}

	// ------------------------------------------------------------ checks

	/** False until db/09_create_po_from_cs.sql has been run. */
	public static boolean columnsReady() {
		return hasColumn("c_order", "xx_rfq_id") && hasColumn("c_orderline", "xx_rfqline_id");
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

	/** Every RFQ that has a recommended winner, one row per winner supplier. */
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
				+ "COALESCE(q.XX_Tax_ID,0) "
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
				list.add(x);
			}
		} catch (Exception e) {
			log.severe("loadLines: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return list;
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
		if (!RfQApprovalService.S_APPROVED.equals(csState(req.rfqId)))
			throw new Exception("The Comparative Statement is not Approved yet.");
		if (req.lines.isEmpty())
			throw new Exception("Select at least one line.");

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
			order.setDescription(req.description);

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
				DB.executeUpdateEx("UPDATE C_OrderLine SET XX_RfQLine_ID=? WHERE C_OrderLine_ID=?",
						new Object[] { Integer.valueOf(lr.line.rfqLineId), Integer.valueOf(ol.getC_OrderLine_ID()) }, trxName);
				if (lr.line.reqLineId > 0)
					DB.executeUpdateEx("UPDATE M_RequisitionLine SET C_OrderLine_ID=? "
							+ "WHERE M_RequisitionLine_ID=? AND C_OrderLine_ID IS NULL",
							new Object[] { Integer.valueOf(ol.getC_OrderLine_ID()), Integer.valueOf(lr.line.reqLineId) }, trxName);
				lineNo += 10;
			}
			DB.executeUpdateEx("UPDATE C_Order SET XX_RfQ_ID=? WHERE C_Order_ID=?",
					new Object[] { Integer.valueOf(req.rfqId), Integer.valueOf(order.getC_Order_ID()) }, trxName);

			orderId = order.getC_Order_ID();
			docNo = order.getDocumentNo();
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
		res.approvalMessage = submit(orderId, docTypeId, req.rfqDocNo);
		return res;
	}

	/** Hands the Drafted PO to the approval matrix. A failure here leaves the PO Drafted. */
	private String submit(int orderId, int docTypeId, String rfqDocNo) {
		try {
			BigDecimal grand = new MOrder(Env.getCtx(), orderId, null).getGrandTotal();
			ApprovalTransaction t = new ApprovalEngineClient().start(MOrder.Table_ID, orderId, grand, docTypeId,
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
