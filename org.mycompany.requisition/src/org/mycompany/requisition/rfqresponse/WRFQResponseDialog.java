package org.mycompany.requisition.rfqresponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.ConfirmPanel;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.component.Window;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Intbox;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Style;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.South;

/**
 * Response popup (Image 3).
 *
 * Header  : C_RfQResponse
 * Rows    : C_RfQResponseLineQty (RFQ Qty = C_RfQLineQty.Qty)
 *
 * Custom column gulo db/05_add_rfqresponse_custom_columns.sql diye toiri hoy.
 * PO generic API (MTable.getPO + set_ValueOfColumn) use kora hoyeche, tai
 * X_ model class generate korte hobe na.
 */
public class WRFQResponseDialog extends Window implements EventListener<Event> {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WRFQResponseDialog.class);

	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final String FMT = "#,##0.00";
	private static final String HDR_STYLE = "font-weight:600;color:#475569;font-size:12px;text-transform:uppercase;letter-spacing:.3px;";
	private static final String TH_STYLE = "background:#1e4d7b;color:#ffffff;font-weight:600;font-size:12px;"
			+ "text-transform:uppercase;letter-spacing:.3px;border-color:#1e4d7b;";

	private static final String CSS = ""
			+ ".rq-banner{background:linear-gradient(90deg,#0f2b46 0%,#1e5a8c 100%);color:#fff;border-radius:12px;"
			+ "padding:14px 22px;box-shadow:0 4px 14px rgba(15,43,70,.25);}"
			+ ".rq-title{display:block;font-size:20px;font-weight:700;color:#fff;letter-spacing:.3px;}"
			+ ".rq-sub{display:block;font-size:13px;color:#cfe3f5;}"
			+ ".rq-card{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:8px 12px;"
			+ "box-shadow:0 1px 4px rgba(15,23,42,.08);margin-top:10px;}"
			+ ".rq-plain, .rq-plain .z-row, .rq-plain .z-row-content, .rq-plain .z-column{border:none !important;"
			+ "background:transparent !important;}"
			+ ".rq-list .z-listheader-content{color:#fff !important;}"
			+ ".rq-list .z-listcell{border-color:#eef2f7;}"
			+ ".rq-list .z-listitem:hover > .z-listcell{background:#eaf3fb !important;}"
			+ ".rq-section{font-size:14px;font-weight:700;color:#0f2b46;padding:2px 2px 6px 2px;display:block;}"
			+ ".rq-tot{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:8px 18px;min-width:190px;"
			+ "box-shadow:0 1px 4px rgba(15,23,42,.08);}"
			+ ".rq-tot-lbl{font-size:11px;color:#64748b;font-weight:600;text-transform:uppercase;letter-spacing:.4px;display:block;}"
			+ ".rq-tot-val{font-size:20px;font-weight:700;color:#0f2b46;display:block;}"
			+ ".rq-grand{background:linear-gradient(90deg,#0f766e 0%,#0ea5a4 100%);border:none;}"
			+ ".rq-grand .rq-tot-lbl{color:#d1fae5;}"
			+ ".rq-grand .rq-tot-val{color:#ffffff;font-size:24px;}"
			+ ".rq-dlg .z-textbox, .rq-dlg .z-decimalbox, .rq-dlg .z-intbox, .rq-dlg .z-datebox-input,"
			+ ".rq-dlg .z-select{border-radius:6px;}";

	private static final String T_RESPONSE = "C_RfQResponse";
	private static final String T_LINEQTY = "C_RfQResponseLineQty";

	/** Ek ta row (grid er) */
	private static class LineRow {
		int id; // C_RfQResponseLineQty_ID
		BigDecimal rfqQty;
		Textbox spec, manufacturer, brand, origin;
		Decimalbox quotQty, price, negPrice;
		Listbox vat;
		Checkbox rebate, tariff;
		Label vatAmtLbl, totalLbl;
		BigDecimal vatAmt = BigDecimal.ZERO;
		BigDecimal lineTotal = BigDecimal.ZERO;
	}

	private final int respId;
	private final Runnable onDone;
	private PO header;

	// header fields
	private Label bannerSub = new Label("");
	private Label bpLabel = new Label();
	private Label addressLabel = new Label();
	private Textbox quotationRef = new Textbox();
	private Datebox responseDate = new Datebox();
	private Textbox warranty = new Textbox();
	private Intbox deliveryDays = new Intbox();
	private Textbox deliveryPlace = new Textbox();
	private Listbox paymentTerm = new Listbox();
	private Listbox paymentRule = new Listbox();
	private Textbox transportMode = new Textbox();
	private Textbox remarks = new Textbox();
	private Decimalbox transportCost = new Decimalbox();
	private Decimalbox othersCost = new Decimalbox();
	private Checkbox grossDiscPct = new Checkbox("Gross Discount %");
	private Decimalbox grossDiscount = new Decimalbox();

	// footer labels
	private Label totalLbl = new Label("0.00");
	private Label grossDiscLbl = new Label("0.00");
	private Label transOthersLbl = new Label("0.00");
	private Label grandTotalLbl = new Label("0.00");

	private BigDecimal grandTotalValue = BigDecimal.ZERO;

	private Listbox lines = new Listbox();
	private List<LineRow> rows = new ArrayList<LineRow>();

	private Map<Integer, BigDecimal> taxRates = new HashMap<Integer, BigDecimal>();
	private List<Object[]> taxItems = new ArrayList<Object[]>(); // {Integer id, String label}

	private ConfirmPanel confirmPanel = new ConfirmPanel(true); // tick / cross icon

	// ------------------------------------------------------------ constructor

	public WRFQResponseDialog(int C_RfQResponse_ID, Runnable onDone) {
		super();
		this.respId = C_RfQResponse_ID;
		this.onDone = onDone;

		MTable tbl = MTable.get(Env.getCtx(), T_RESPONSE);
		header = tbl.getPO(respId, null);
		if (header == null || header.get_ID() <= 0)
			throw new AdempiereException("RFQ Response record not found (C_RfQResponse_ID=" + respId + ").");
		if (header.get_ColumnIndex("XX_Warranty") < 0)
			throw new AdempiereException(
					"The required custom columns (XX_*) are not registered for C_RfQResponse. "
								+ "Please add them in Application Dictionary (Table and Column), then run Cache Reset and try again.");

		setTitle("Supplier Quotation Response");
		setSclass("rq-dlg");
		setBorder("normal");
		setClosable(true);
		setSizable(true);
		ZKUpdateUtil.setWidth(this, "97%");
		ZKUpdateUtil.setHeight(this, "92%");

		initLookups();
		initLinesTable();
		layout();
		loadHeader();
		loadLines();
		recalc();
	}

	/** Modal-moto popup dekhao */
	public void show() {
		setAttribute(Window.MODE_KEY, Window.MODE_HIGHLIGHTED);
		AEnv.showWindow(this);
	}

	// ------------------------------------------------------------------- init

	private void initLookups() {
		int clientId = Env.getAD_Client_ID(Env.getCtx());

		fillCombo(paymentTerm, true,
				"SELECT C_PaymentTerm_ID, Name FROM C_PaymentTerm WHERE IsActive='Y' AND AD_Client_ID IN (0,?) ORDER BY Name",
				clientId);
		// Reference 195 = _Payment Rule
		fillCombo(paymentRule, false,
				"SELECT Value, Name FROM AD_Ref_List WHERE AD_Reference_ID=195 AND IsActive='Y' ORDER BY Name");

		// VAT rate list
		String sql = "SELECT C_Tax_ID, Name, Rate FROM C_Tax WHERE IsActive='Y' AND AD_Client_ID IN (0,?) ORDER BY Name";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, clientId);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				Integer id = Integer.valueOf(rs.getInt(1));
				BigDecimal rate = rs.getBigDecimal(3) == null ? BigDecimal.ZERO : rs.getBigDecimal(3);
				taxRates.put(id, rate);
				taxItems.add(new Object[] { id, rs.getString(2) });
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, sql, e);
			throw new AdempiereException(e);
		} finally {
			DB.close(rs, pstmt);
		}

		for (Decimalbox d : new Decimalbox[] { transportCost, othersCost, grossDiscount })
			d.setFormat(FMT);
		ZKUpdateUtil.setHflex(quotationRef, "1");
		ZKUpdateUtil.setHflex(warranty, "1");
		ZKUpdateUtil.setHflex(deliveryPlace, "1");
		ZKUpdateUtil.setHflex(transportMode, "1");
		ZKUpdateUtil.setHflex(remarks, "1");
		ZKUpdateUtil.setHflex(deliveryDays, "1");
		ZKUpdateUtil.setHflex(transportCost, "1");
		ZKUpdateUtil.setHflex(othersCost, "1");
		ZKUpdateUtil.setHflex(grossDiscount, "1");
		ZKUpdateUtil.setHflex(paymentTerm, "1");
		ZKUpdateUtil.setHflex(paymentRule, "1");
		quotationRef.setMaxlength(60);
		warranty.setMaxlength(120);
		deliveryPlace.setMaxlength(120);
		transportMode.setMaxlength(60);
		remarks.setMaxlength(255);
		quotationRef.setStyle("background-color:#ffffa0;");

		transportCost.addEventListener(Events.ON_CHANGE, this);
		othersCost.addEventListener(Events.ON_CHANGE, this);
		grossDiscount.addEventListener(Events.ON_CHANGE, this);
		grossDiscPct.addEventListener(Events.ON_CHECK, this);

		confirmPanel.addActionListener(this);
	}

	private void initLinesTable() {
		Listhead head = new Listhead();
		String[][] cols = { { "Select", "50px" }, { "Line No", "60px" }, { "Product Code", "100px" }, { "Product Name", "180px" },
				{ "UOM", "55px" }, { "Specification", "130px" }, { "Manufacturer", "110px" }, { "Brand", "100px" },
				{ "Origin", "90px" }, { "RFQ Qty", "80px" }, { "Available Qty", "95px" },
				{ "Quotation Qty", "105px" }, { "Quoted Price", "105px" }, { "Negotiate Price", "105px" },
				{ "VAT Rate", "110px" }, { "Rebate", "60px" }, { "TariffRate", "75px" }, { "VAT Amt", "95px" },
				{ "Total", "105px" } };
		lines.setSclass("rq-list");
		for (String[] c : cols) {
			Listheader h = new Listheader(c[0]);
			h.setWidth(c[1]);
			h.setStyle(TH_STYLE);
			head.appendChild(h);
		}
		lines.appendChild(head);
		ZKUpdateUtil.setHflex(lines, "1");
		lines.setHeight("330px");
	}

	// ----------------------------------------------------------------- layout

	private void layout() {
		Style css = new Style();
		css.setContent(CSS);
		appendChild(css);

		Borderlayout bl = new Borderlayout();
		ZKUpdateUtil.setWidth(bl, "100%");
		ZKUpdateUtil.setHeight(bl, "100%");
		bl.setStyle("background:#f1f5f9;");
		appendChild(bl);

		// ---- North: banner + header card ----
		North north = new North();
		ZKUpdateUtil.setVflex(north, "min");
		north.setStyle("background:transparent;border:none;");
		bl.appendChild(north);

		Div wrap = new Div();
		wrap.setStyle("padding:10px 12px 2px 12px;");
		north.appendChild(wrap);

		Div banner = new Div();
		banner.setSclass("rq-banner");
		Label title = new Label("Supplier Quotation Response");
		title.setSclass("rq-title");
		bannerSub.setSclass("rq-sub");
		banner.appendChild(title);
		banner.appendChild(bannerSub);
		wrap.appendChild(banner);

		Div card = new Div();
		card.setSclass("rq-card");
		wrap.appendChild(card);

		Grid grid = new Grid();
		grid.setSclass("rq-plain");
		Rows gr = new Rows();
		grid.appendChild(gr);
		card.appendChild(grid);

		Row r1 = new Row();
		gr.appendChild(r1);
		r1.appendChild(bold("Business Partner"));
		bpLabel.setStyle("font-weight:700;color:#0f2b46;");
		r1.appendChild(bpLabel);
		r1.appendChild(bold("Address"));
		r1.appendChild(addressLabel);
		r1.appendChild(bold("Quotation Ref."));
		r1.appendChild(quotationRef);

		Row r2 = new Row();
		gr.appendChild(r2);
		r2.appendChild(bold("Response Date"));
		r2.appendChild(responseDate);
		r2.appendChild(bold("Warranty"));
		r2.appendChild(warranty);
		r2.appendChild(bold("Delivery Days"));
		r2.appendChild(deliveryDays);

		Row r3 = new Row();
		gr.appendChild(r3);
		r3.appendChild(bold("Delivery Place"));
		r3.appendChild(deliveryPlace);
		r3.appendChild(bold("Payment Term"));
		r3.appendChild(paymentTerm);
		r3.appendChild(bold("Payment Rule"));
		r3.appendChild(paymentRule);

		Row r4 = new Row();
		gr.appendChild(r4);
		r4.appendChild(bold("Transportation Mode"));
		r4.appendChild(transportMode);
		r4.appendChild(green("Transport Cost"));
		r4.appendChild(transportCost);
		r4.appendChild(green("Others Cost"));
		r4.appendChild(othersCost);

		Row r5 = new Row();
		gr.appendChild(r5);
		r5.appendChild(bold("Remarks"));
		r5.appendChild(remarks);
		r5.appendChild(grossDiscPct);
		grossDiscPct.setStyle("color:#0f766e; font-weight:600;");
		r5.appendChild(new Label(""));
		r5.appendChild(green("Gross Discount"));
		r5.appendChild(grossDiscount);

		// ---- Center: line grid ----
		Center center = new Center();
		center.setStyle("background:transparent;border:none;");
		center.setAutoscroll(true);
		bl.appendChild(center);
		Div cwrap = new Div();
		cwrap.setStyle("padding:2px 12px 4px 12px;");
		ZKUpdateUtil.setHflex(cwrap, "1");
		ZKUpdateUtil.setVflex(cwrap, "1");
		center.appendChild(cwrap);
		Div lcard = new Div();
		lcard.setSclass("rq-card");
		lcard.setStyle("margin-top:0;overflow:auto;");
		cwrap.appendChild(lcard);
		Label cap = new Label("Items");
		cap.setSclass("rq-section");
		lcard.appendChild(cap);
		lcard.appendChild(lines);

		// ---- South: total cards + buttons ----
		South south = new South();
		ZKUpdateUtil.setVflex(south, "min");
		south.setStyle("background:transparent;border:none;");
		bl.appendChild(south);

		Div box = new Div();
		box.setStyle("padding:2px 12px 8px 12px;");
		south.appendChild(box);

		Hlayout totals = new Hlayout();
		totals.setSpacing("12px");
		totals.setStyle("padding:6px 0;");
		totals.appendChild(totBox("Total", totalLbl, false));
		totals.appendChild(totBox("Gross Discount", grossDiscLbl, false));
		totals.appendChild(totBox("Transport and Others Cost", transOthersLbl, false));
		totals.appendChild(totBox("Grand Total", grandTotalLbl, true));
		box.appendChild(totals);

		box.appendChild(confirmPanel);
	}

	private Div totBox(String title, Label value, boolean grand) {
		Div d = new Div();
		d.setSclass(grand ? "rq-tot rq-grand" : "rq-tot");
		Label l = new Label(title);
		l.setSclass("rq-tot-lbl");
		value.setSclass("rq-tot-val");
		d.appendChild(l);
		d.appendChild(value);
		return d;
	}

	private Label bold(String t) {
		Label l = new Label(t);
		l.setStyle(HDR_STYLE);
		return l;
	}

	private Label green(String t) {
		Label l = new Label(t);
		l.setStyle("color:#0f766e; font-weight:600; font-size:12px; text-transform:uppercase; letter-spacing:.3px;");
		return l;
	}

	private Label spacer() {
		return new Label("      ");
	}

	// ------------------------------------------------------------------- load

	private void loadHeader() {
		int bpId = header.get_ValueAsInt("C_BPartner_ID");
		bpLabel.setValue(nvl(DB.getSQLValueString(null, "SELECT Name FROM C_BPartner WHERE C_BPartner_ID=?", bpId)));
		addressLabel.setValue(loadAddress(header.get_ValueAsInt("C_BPartner_Location_ID")));
		bannerSub.setValue(bpLabel.getValue() + (addressLabel.getValue().isEmpty() ? "" : "  |  " + addressLabel.getValue()));

		quotationRef.setValue(header.get_ValueAsString("XX_QuotationRef"));
		Object dr = header.get_Value("DateResponse");
		responseDate.setValue(dr instanceof Date ? (Date) dr : new Date());
		warranty.setValue(header.get_ValueAsString("XX_Warranty"));
		int dd = header.get_ValueAsInt("DeliveryDays");
		deliveryDays.setValue(dd > 0 ? Integer.valueOf(dd) : null);
		deliveryPlace.setValue(header.get_ValueAsString("XX_DeliveryPlace"));
		selectByValue(paymentTerm, header.get_ValueAsInt("XX_PaymentTerm_ID") > 0
				? Integer.valueOf(header.get_ValueAsInt("XX_PaymentTerm_ID")) : null);
		selectByValue(paymentRule, emptyToNull(header.get_ValueAsString("XX_PaymentRule")));
		transportMode.setValue(header.get_ValueAsString("XX_TransportMode"));
		remarks.setValue(header.get_ValueAsString("Description"));
		transportCost.setValue(bd(header.get_Value("XX_TransportCost")));
		othersCost.setValue(bd(header.get_Value("XX_OthersCost")));
		grossDiscPct.setChecked(header.get_ValueAsBoolean("XX_IsGrossDiscountPct"));
		grossDiscount.setValue(bd(header.get_Value("XX_GrossDiscount")));
	}

	private String loadAddress(int locationId) {
		if (locationId <= 0)
			return "";
		String sql = "SELECT l.Address1, l.Address2, l.City FROM C_BPartner_Location bl "
				+ "JOIN C_Location l ON l.C_Location_ID = bl.C_Location_ID WHERE bl.C_BPartner_Location_ID=?";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, locationId);
			rs = pstmt.executeQuery();
			if (rs.next()) {
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i <= 3; i++) {
					String s = rs.getString(i);
					if (s == null || s.trim().isEmpty())
						continue;
					if (sb.length() > 0)
						sb.append(", ");
					sb.append(s.trim());
				}
				return sb.toString();
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, sql, e);
		} finally {
			DB.close(rs, pstmt);
		}
		return "";
	}

//	private void loadLines() {
//		StringBuilder sql = new StringBuilder();
//		sql.append("SELECT rlq.C_RfQResponseLineQty_ID, rl.Line, p.Value, COALESCE(p.Name, rl.Description), u.UOMSymbol, ");
//		sql.append("lq.Qty, ");
//		sql.append("(SELECT COALESCE(SUM(s.QtyOnHand),0) FROM M_StorageOnHand s WHERE s.M_Product_ID = rl.M_Product_ID), ");
//		sql.append("rlq.Price, rlq.XX_Specification, rlq.XX_Manufacturer, rlq.XX_Brand, rlq.XX_Origin, ");
//		sql.append("rlq.XX_QuotationQty, rlq.XX_NegotiatePrice, rlq.XX_Tax_ID, rlq.XX_IsRebate, rlq.XX_IsTariff ");
//		sql.append("FROM C_RfQResponseLineQty rlq ");
//		sql.append("JOIN C_RfQResponseLine rrl ON rrl.C_RfQResponseLine_ID = rlq.C_RfQResponseLine_ID ");
//		sql.append("JOIN C_RfQLine rl ON rl.C_RfQLine_ID = rrl.C_RfQLine_ID ");
//		sql.append("JOIN C_RfQLineQty lq ON lq.C_RfQLineQty_ID = rlq.C_RfQLineQty_ID ");
//		sql.append("LEFT JOIN M_Product p ON p.M_Product_ID = rl.M_Product_ID ");
//		sql.append("LEFT JOIN C_UOM u ON u.C_UOM_ID = lq.C_UOM_ID ");
//		sql.append("WHERE rrl.C_RfQResponse_ID=? AND rlq.IsActive='Y' AND rrl.IsActive='Y' ");
//		sql.append("ORDER BY rl.Line, lq.Qty");
//
//		PreparedStatement pstmt = null;
//		ResultSet rs = null;
//		try {
//			pstmt = DB.prepareStatement(sql.toString(), null);
//			pstmt.setInt(1, respId);
//			rs = pstmt.executeQuery();
//			while (rs.next()) {
//				LineRow r = new LineRow();
//				r.id = rs.getInt(1);
//				r.rfqQty = nz(rs.getBigDecimal(6));
//				BigDecimal avail = nz(rs.getBigDecimal(7));
//
//				r.spec = text(rs.getString(9), 255, "120px");
//				r.manufacturer = text(rs.getString(10), 60, "100px");
//				r.brand = text(rs.getString(11), 60, "90px");
//				r.origin = text(rs.getString(12), 60, "80px");
//
//				BigDecimal qq = rs.getBigDecimal(13);
//				r.quotQty = number(qq != null ? qq : r.rfqQty);
//				r.price = number(rs.getBigDecimal(8));
//				r.negPrice = number(rs.getBigDecimal(14));
//
//				r.vat = newTaxBox(rs.getInt(15));
//				r.rebate = new Checkbox();
//				r.rebate.setChecked("Y".equals(rs.getString(16)));
//				r.tariff = new Checkbox();
//				r.tariff.setChecked("Y".equals(rs.getString(17)));
//
//				r.vatAmtLbl = new Label("0.00");
//				r.totalLbl = new Label("0.00");
//
//				Listitem item = new Listitem();
//				Checkbox sel = new Checkbox();
//				sel.setChecked(true);
//				sel.setDisabled(true);
//				item.appendChild(cell(sel));
//				item.appendChild(new Listcell(String.valueOf(rs.getInt(2))));
//				item.appendChild(new Listcell(nvl(rs.getString(3))));
//				item.appendChild(new Listcell(nvl(rs.getString(4))));
//				item.appendChild(new Listcell(nvl(rs.getString(5))));
//				item.appendChild(cell(r.spec));
//				item.appendChild(cell(r.manufacturer));
//				item.appendChild(cell(r.brand));
//				item.appendChild(cell(r.origin));
//				item.appendChild(right(fmt(r.rfqQty)));
//				item.appendChild(right(fmt(avail)));
//				item.appendChild(cell(r.quotQty));
//				item.appendChild(cell(r.price));
//				item.appendChild(cell(r.negPrice));
//				item.appendChild(cell(r.vat));
//				item.appendChild(cell(r.rebate));
//				item.appendChild(cell(r.tariff));
//				item.appendChild(cell(r.vatAmtLbl));
//				item.appendChild(cell(r.totalLbl));
//				lines.appendChild(item);
//				rows.add(r);
//			}
//		} catch (SQLException e) {
//			log.log(Level.SEVERE, sql.toString(), e);
//			throw new AdempiereException(e);
//		} finally {
//			DB.close(rs, pstmt);
//		}
//	}

	
	
	
	
	private void loadLines() {
	    StringBuilder sql = new StringBuilder();
	    
	    // SELECT করা কলামগুলোতে Aggregation Function (MIN, MAX, SUM) ব্যবহার করা হলো
	    sql.append("SELECT MIN(rlq.C_RfQResponseLineQty_ID), MIN(rl.Line), p.Value, MAX(COALESCE(p.Name, rl.Description)), MAX(u.UOMSymbol), ");
	    sql.append("SUM(lq.Qty), "); // ৬ নাম্বার কলাম: কোয়ান্টিটি যোগ করা হলো (SUM)
	    
	    // সাব-কুয়েরি: যেহেতু গ্রুপ করা হচ্ছে, তাই এই সাব-কুয়েরি প্রোডাক্টের ওপর ভিত্তি করে স্টক দেখাবে
	    sql.append("(SELECT COALESCE(SUM(s.QtyOnHand),0) FROM M_StorageOnHand s WHERE s.M_Product_ID = rl.M_Product_ID), ");
	    
	    // অন্যান্য ফিল্ডগুলোতে MAX() ব্যবহার করা হলো
	    sql.append("MAX(rlq.Price), MAX(rlq.XX_Specification), MAX(rlq.XX_Manufacturer), MAX(rlq.XX_Brand), MAX(rlq.XX_Origin), ");
	    sql.append("SUM(rlq.XX_QuotationQty), MAX(rlq.XX_NegotiatePrice), MAX(rlq.XX_Tax_ID), MAX(rlq.XX_IsRebate), MAX(rlq.XX_IsTariff) ");
	    
	    sql.append("FROM C_RfQResponseLineQty rlq ");
	    sql.append("JOIN C_RfQResponseLine rrl ON rrl.C_RfQResponseLine_ID = rlq.C_RfQResponseLine_ID ");
	    sql.append("JOIN C_RfQLine rl ON rl.C_RfQLine_ID = rrl.C_RfQLine_ID ");
	    sql.append("JOIN C_RfQLineQty lq ON lq.C_RfQLineQty_ID = rlq.C_RfQLineQty_ID ");
	    sql.append("LEFT JOIN M_Product p ON p.M_Product_ID = rl.M_Product_ID ");
	    sql.append("LEFT JOIN C_UOM u ON u.C_UOM_ID = lq.C_UOM_ID ");
	    sql.append("WHERE rrl.C_RfQResponse_ID=? AND rlq.IsActive='Y' AND rrl.IsActive='Y' ");
	    
	    // এখানে প্রোডাক্ট আইডি, ভ্যালু এবং UOM দিয়ে গ্রুপ করা হলো
	    sql.append("GROUP BY rl.M_Product_ID, p.Value, u.UOMSymbol ");
	    
	    // ORDER BY-তেও Aggregation ফাংশন ব্যবহার করতে হবে
	    sql.append("ORDER BY MIN(rl.Line)");

	    PreparedStatement pstmt = null;
	    ResultSet rs = null;
	    try {
	        pstmt = DB.prepareStatement(sql.toString(), null);
	        pstmt.setInt(1, respId);
	        rs = pstmt.executeQuery();
	        while (rs.next()) {
	            LineRow r = new LineRow();
	            // ১ নাম্বার ইনডেক্স: এখন এটি ওই প্রোডাক্টের প্রথম রেকর্ডের আইডি ধরবে
	            r.id = rs.getInt(1); 
	            r.rfqQty = nz(rs.getBigDecimal(6)); // এটি এখন SUM করা কোয়ান্টিটি হবে
	            BigDecimal avail = nz(rs.getBigDecimal(7));

	            r.spec = text(rs.getString(9), 255, "120px");
	            r.manufacturer = text(rs.getString(10), 60, "100px");
	            r.brand = text(rs.getString(11), 60, "90px");
	            r.origin = text(rs.getString(12), 60, "80px");

	            BigDecimal qq = rs.getBigDecimal(13); // এটিও এখন SUM করা ভ্যালু
	            r.quotQty = number(qq != null ? qq : r.rfqQty);
	            r.price = number(rs.getBigDecimal(8));
	            r.negPrice = number(rs.getBigDecimal(14));

	            r.vat = newTaxBox(rs.getInt(15));
	            r.rebate = new Checkbox();
	            r.rebate.setChecked("Y".equals(rs.getString(16)));
	            r.tariff = new Checkbox();
	            r.tariff.setChecked("Y".equals(rs.getString(17)));

	            r.vatAmtLbl = new Label("0.00");
	            r.totalLbl = new Label("0.00");

	            Listitem item = new Listitem();
	            Checkbox sel = new Checkbox();
	            sel.setChecked(true);
	            sel.setDisabled(true);
	            item.appendChild(cell(sel));
	            item.appendChild(new Listcell(String.valueOf(rs.getInt(2))));
	            item.appendChild(new Listcell(nvl(rs.getString(3))));
	            item.appendChild(new Listcell(nvl(rs.getString(4))));
	            item.appendChild(new Listcell(nvl(rs.getString(5))));
	            item.appendChild(cell(r.spec));
	            item.appendChild(cell(r.manufacturer));
	            item.appendChild(cell(r.brand));
	            item.appendChild(cell(r.origin));
	            item.appendChild(right(fmt(r.rfqQty)));
	            item.appendChild(right(fmt(avail)));
	            item.appendChild(cell(r.quotQty));
	            item.appendChild(cell(r.price));
	            item.appendChild(cell(r.negPrice));
	            item.appendChild(cell(r.vat));
	            item.appendChild(cell(r.rebate));
	            item.appendChild(cell(r.tariff));
	            item.appendChild(cell(r.vatAmtLbl));
	            item.appendChild(cell(r.totalLbl));
	            lines.appendChild(item);
	            rows.add(r);
	        }
	    } catch (SQLException e) {
	        log.log(Level.SEVERE, sql.toString(), e);
	        throw new AdempiereException(e);
	    } finally {
	        DB.close(rs, pstmt);
	    }
	}
	
	
	// ------------------------------------------------------- row components

	private Textbox text(String value, int max, String width) {
		Textbox t = new Textbox();
		t.setValue(nvl(value));
		t.setMaxlength(max);
		t.setWidth(width);
		return t;
	}

	private Decimalbox number(BigDecimal value) {
		Decimalbox d = new Decimalbox(value == null ? BigDecimal.ZERO : value);
		d.setFormat(FMT);
		d.setWidth("90px");
		d.addEventListener(Events.ON_CHANGE, this); // qty/price change e total recalc
		return d;
	}

	private Listbox newTaxBox(int selectedId) {
		Listbox lb = new Listbox();
		lb.setMold("select");
		lb.setWidth("100px");
		lb.appendChild(new Listitem("", null));
		for (Object[] t : taxItems)
			lb.appendChild(new Listitem((String) t[1], t[0]));
		selectByValue(lb, selectedId > 0 ? Integer.valueOf(selectedId) : null);
		lb.addEventListener(Events.ON_SELECT, this);
		return lb;
	}

	private Listcell cell(org.zkoss.zk.ui.Component c) {
		Listcell lc = new Listcell();
		lc.appendChild(c);
		return lc;
	}

	private Listcell right(String s) {
		Listcell lc = new Listcell(s);
		lc.setStyle("text-align:right;");
		return lc;
	}

	// -------------------------------------------------------------- calculate

	private BigDecimal taxRate(Listbox lb) {
		Listitem it = lb.getSelectedItem();
		if (it == null || it.getValue() == null)
			return BigDecimal.ZERO;
		BigDecimal r = taxRates.get((Integer) it.getValue());
		return r == null ? BigDecimal.ZERO : r;
	}

	/** Negotiate price dewa thakle seta, na hole Quoted Price */
	private BigDecimal effectivePrice(LineRow r) {
		BigDecimal neg = nz(r.negPrice.getValue());
		return neg.signum() > 0 ? neg : nz(r.price.getValue());
	}

	private void recalc() {
		BigDecimal sum = BigDecimal.ZERO;
		for (LineRow r : rows) {
			BigDecimal base = nz(r.quotQty.getValue()).multiply(effectivePrice(r));
			r.vatAmt = base.multiply(taxRate(r.vat)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
			r.lineTotal = base.add(r.vatAmt).setScale(2, RoundingMode.HALF_UP);
			r.vatAmtLbl.setValue(fmt(r.vatAmt));
			r.totalLbl.setValue(fmt(r.lineTotal));
			sum = sum.add(r.lineTotal);
		}
		BigDecimal gd = nz(grossDiscount.getValue());
		if (grossDiscPct.isChecked())
			gd = sum.multiply(gd).divide(HUNDRED, 2, RoundingMode.HALF_UP);
		BigDecimal others = nz(transportCost.getValue()).add(nz(othersCost.getValue()));

		totalLbl.setValue(fmt(sum));
		grossDiscLbl.setValue(fmt(gd));
		transOthersLbl.setValue(fmt(others));
		grandTotalValue = sum.subtract(gd).add(others);
		grandTotalLbl.setValue(fmt(grandTotalValue));
	}

	private BigDecimal grandTotal() {
		return grandTotalValue;
	}

	// ----------------------------------------------------------------- events

	@Override
	public void onEvent(Event e) {
		try {
			Object c = e.getTarget();
			String id = (c instanceof org.zkoss.zk.ui.Component) ? ((org.zkoss.zk.ui.Component) c).getId() : null;
			if (ConfirmPanel.A_OK.equals(id)) {
				save();
				detach();
				if (onDone != null)
					onDone.run();
			} else if (ConfirmPanel.A_CANCEL.equals(id)) {
				detach();
			} else {
				recalc(); // Decimalbox / Checkbox / VAT select change
			}
		} catch (Exception ex) {
			log.log(Level.SEVERE, ex.getMessage(), ex);
			Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
		}
	}

	// ------------------------------------------------------------------- save

	private void save() throws Exception {
		Date d = responseDate.getValue();
		if (d == null)
			throw new AdempiereException("Response Date is required.");
		for (LineRow r : rows) {
			if (nz(r.quotQty.getValue()).signum() < 0 || nz(r.price.getValue()).signum() < 0
					|| nz(r.negPrice.getValue()).signum() < 0)
				throw new AdempiereException("Quantity and prices must not be negative.");
		}
		recalc();

		String trxName = Trx.createTrxName("RFQResponse");
		Trx trx = Trx.get(trxName, true);
		try {
			// ---------- Header ----------
			PO h = MTable.get(Env.getCtx(), T_RESPONSE).getPO(respId, trxName);
			h.set_ValueOfColumn("DateResponse", new Timestamp(d.getTime()));
			h.set_ValueOfColumn("DeliveryDays", deliveryDays.getValue() == null ? 0 : deliveryDays.getValue());
			h.set_ValueOfColumn("Description", emptyToNull(remarks.getValue()));
			h.set_ValueOfColumn("XX_QuotationRef", emptyToNull(quotationRef.getValue()));
			h.set_ValueOfColumn("XX_Warranty", emptyToNull(warranty.getValue()));
			h.set_ValueOfColumn("XX_DeliveryPlace", emptyToNull(deliveryPlace.getValue()));
			h.set_ValueOfColumn("XX_PaymentTerm_ID", selectedInt(paymentTerm));
			h.set_ValueOfColumn("XX_PaymentRule", selectedString(paymentRule));
			h.set_ValueOfColumn("XX_TransportMode", emptyToNull(transportMode.getValue()));
			h.set_ValueOfColumn("XX_TransportCost", nz(transportCost.getValue()));
			h.set_ValueOfColumn("XX_OthersCost", nz(othersCost.getValue()));
			h.set_ValueOfColumn("XX_IsGrossDiscountPct", Boolean.valueOf(grossDiscPct.isChecked()));
			h.set_ValueOfColumn("XX_GrossDiscount", nz(grossDiscount.getValue()));
			h.set_ValueOfColumn("XX_GrandTotal", grandTotal());
			if (h.get_ColumnIndex("Price") >= 0)
				h.set_ValueOfColumn("Price", grandTotal());
			h.saveEx();

			// ---------- Rows ----------
			MTable lineTbl = MTable.get(Env.getCtx(), T_LINEQTY);
			for (LineRow r : rows) {
				PO l = lineTbl.getPO(r.id, trxName);
				l.set_ValueOfColumn("Price", nz(r.price.getValue())); // standard Price = Quoted Price
				l.set_ValueOfColumn("XX_Specification", emptyToNull(r.spec.getValue()));
				l.set_ValueOfColumn("XX_Manufacturer", emptyToNull(r.manufacturer.getValue()));
				l.set_ValueOfColumn("XX_Brand", emptyToNull(r.brand.getValue()));
				l.set_ValueOfColumn("XX_Origin", emptyToNull(r.origin.getValue()));
				l.set_ValueOfColumn("XX_QuotationQty", nz(r.quotQty.getValue()));
				l.set_ValueOfColumn("XX_NegotiatePrice", nz(r.negPrice.getValue()));
				l.set_ValueOfColumn("XX_Tax_ID", selectedInt(r.vat));
				l.set_ValueOfColumn("XX_IsRebate", Boolean.valueOf(r.rebate.isChecked()));
				l.set_ValueOfColumn("XX_IsTariff", Boolean.valueOf(r.tariff.isChecked()));
				l.set_ValueOfColumn("XX_VATAmt", r.vatAmt);
				l.set_ValueOfColumn("XX_LineTotal", r.lineTotal);
				l.saveEx();
			}
			trx.commit();
		} catch (Exception ex) {
			trx.rollback();
			if (ex instanceof AdempiereException)
				throw ex;
			throw new AdempiereException(ex);
		} finally {
			trx.close();
		}
	}

	// ---------------------------------------------------------------- helpers

	/** @param intValue true hole value Integer, na hole String */
	private void fillCombo(Listbox lb, boolean intValue, String sql, Object... params) {
		lb.setMold("select");
		lb.appendChild(new Listitem("", null));
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			DB.setParameters(pstmt, params);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				Object v = intValue ? (Object) Integer.valueOf(rs.getInt(1)) : rs.getString(1);
				lb.appendChild(new Listitem(rs.getString(2), v));
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, sql, e); // lookup na thakleo form chalu thakbe
		} finally {
			DB.close(rs, pstmt);
		}
		lb.setSelectedIndex(0);
	}

	private static void selectByValue(Listbox lb, Object value) {
		if (value == null) {
			lb.setSelectedIndex(0);
			return;
		}
		for (Listitem it : lb.getItems()) {
			Object itemValue = it.getValue();
			if (itemValue != null && String.valueOf(itemValue).equals(String.valueOf(value))) {
				lb.setSelectedItem(it);
				return;
			}
		}
		lb.setSelectedIndex(0);
	}

	private static Integer selectedInt(Listbox lb) {
		Listitem it = lb.getSelectedItem();
		return (it == null || it.getValue() == null) ? null : (Integer) it.getValue();
	}

	private static String selectedString(Listbox lb) {
		Listitem it = lb.getSelectedItem();
		return (it == null || it.getValue() == null) ? null : (String) it.getValue();
	}

	private static BigDecimal bd(Object o) {
		return o instanceof BigDecimal ? (BigDecimal) o : null;
	}

	private static BigDecimal nz(BigDecimal b) {
		return b == null ? BigDecimal.ZERO : b;
	}

	private static String fmt(BigDecimal b) {
		return new java.text.DecimalFormat("#,##0.00").format(nz(b));
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	private static String emptyToNull(String s) {
		return (s == null || s.trim().isEmpty()) ? null : s.trim();
	}
}