package org.mycompany.requisition.rfq;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;

import org.mycompany.requisition.rfq.WCreateRFQDialog.LineRow;

/**
 * "Create RFQ From PR" form  -  step 1: the requisition list.
 *
 * Flow:
 *  1. The form opens with ONE big table that lists every requisition that still has lines
 *     available for an RFQ. The lines (BP Code, Product, UOM, Qty ...) of each requisition
 *     sit one after another, so a requisition reads as one block.
 *  2. Select lines straight in that table by ticking the product lines (one, several, or lines of
 *     several requisitions). No popup, no "Add Line" / "Selected Lines" basket.
 *  3. The ticks survive a Search / Refresh.
 *  4. "Create RFQ" opens {@link WCreateRFQDialog} with every ticked line already listed there: RFQ Topic, date, term,
 *     attachment -> OK creates ONE RFQ.
 *  5. The list refreshes (the used lines disappear).
 *
 * Eligibility rule (same as before / RequisitionRFQService):
 *  - M_RequisitionLine.XX_RfQ_ID is null and C_OrderLine_ID is null, line is active.
 *
 * The RFQ is created in WCreateRFQDialog (save logic unchanged).
 */
public class WCreateRFQFromPR extends ADForm implements EventListener<Event> {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WCreateRFQFromPR.class);

	/** Newest N requisitions are shown (use Search to narrow down). */
	private static final int MAX_REQUISITIONS = 300;

	private static final String GROUP_BORDER = "border-top:2px solid #94a3b8;";
	private static final String GROUP_ALT_BG = "background:#eef4ff;";
	private static final String GROUP_BG = "background:#ffffff;";

	/** Requisition level data for the first row of a block */
	private static class ReqHead {
		int reqId;
		String docNo;
		Timestamp date;
		String requester;
		String status;
	}

	private Borderlayout mainLayout = new Borderlayout();

	private Textbox searchBox = new Textbox();
	private Button searchBtn = new Button("Search");
	private Button refreshBtn = new Button("Refresh");
	private Checkbox selectAll = new Checkbox("Select All");
	private Label selectedLbl = new Label("Selected lines: 0");
	private Label countLbl = new Label("");
	private Button createBtn = new Button("Create RFQ");
	/** the ticked lines: requisition line id -> line (can hold lines of several requisitions) */
	private Map<Integer, LineRow> cart = new LinkedHashMap<Integer, LineRow>();

	private Listbox reqList;

	/** requisition id -> its eligible lines (in display order) */
	private Map<Integer, List<LineRow>> reqLines = new LinkedHashMap<Integer, List<LineRow>>();
	/** requisition LINE id -> its tick box (one per product line) */
	private Map<Integer, Checkbox> reqChecks = new LinkedHashMap<Integer, Checkbox>();

	private boolean reqLineHasRfqCol;
	private boolean reqLineHasBPartner;

	// ------------------------------------------------------------------ init

	@Override
	protected void initForm() {
		org.mycompany.requisition.ui.XxTheme.wrap(this);
		try {
			reqLineHasRfqCol = columnExists("M_RequisitionLine", "XX_RfQ_ID");
			reqLineHasBPartner = columnExists("M_RequisitionLine", "C_BPartner_ID");
			initComponents();
			layout();
			loadList();
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			throw new AdempiereException(e);
		}
	}

	private void initComponents() {
		searchBox.setPlaceholder("Requisition no / product / BP / requester");
		searchBox.setWidth("320px");
		searchBox.addEventListener(Events.ON_OK, this);   // Enter key = Search

		searchBtn.addEventListener(Events.ON_CLICK, this);
		refreshBtn.addEventListener(Events.ON_CLICK, this);
		createBtn.addEventListener(Events.ON_CLICK, this);
		selectAll.addEventListener(Events.ON_CHECK, this);

		reqList = new Listbox();
		Listhead head = new Listhead();
		String[][] cols = {
				{ "", "40px" },
				{ "Requisition", "120px" },
				{ "Date", "95px" },
				{ "Requester", "120px" },
				{ "Status", "100px" },
				{ "BP Code", "100px" },
				{ "BP Name", "180px" },
				{ "Product Code", "110px" },
				{ "Product Name", "220px" },
				{ "UOM", "60px" },
				{ "Qty", "80px" },
				{ "Description", null } };
		for (String[] c : cols) {
			Listheader h = new Listheader(c[0]);
			if (c[1] != null)
				h.setWidth(c[1]);
			else
				h.setHflex("1");
			head.appendChild(h);
		}
		reqList.appendChild(head);
		ZKUpdateUtil.setHflex(reqList, "1");
		ZKUpdateUtil.setVflex(reqList, "1");
	}

	// ---------------------------------------------------------------- layout

	private void layout() {
		appendChild(mainLayout);
		ZKUpdateUtil.setWidth(this, "99%");
		ZKUpdateUtil.setHeight(this, "100%");
		ZKUpdateUtil.setWidth(mainLayout, "100%");
		ZKUpdateUtil.setHeight(mainLayout, "100%");

		// ---- North: search + actions ----
		North north = new North();
		ZKUpdateUtil.setVflex(north, "min");
		mainLayout.appendChild(north);

		Hlayout bar = new Hlayout();
		bar.setSpacing("10px");
		bar.setStyle("padding:8px;");
		bar.appendChild(new Label("Search"));
		bar.appendChild(searchBox);
		bar.appendChild(searchBtn);
		bar.appendChild(refreshBtn);
		bar.appendChild(selectAll);
		bar.appendChild(selectedLbl);
		bar.appendChild(countLbl);
		bar.appendChild(createBtn);
		north.appendChild(bar);

		// ---- Center: the big requisition table ----
		Center center = new Center();
		mainLayout.appendChild(center);
		center.appendChild(reqList);
	}

	// ------------------------------------------------------------------ data

	/** Reloads the table from the database using the current search text. */
	private void loadList() {
		for (Listitem old : new ArrayList<Listitem>(reqList.getItems()))
			old.detach();
		reqLines.clear();
		reqChecks.clear();
		selectAll.setChecked(false);

		Map<Integer, ReqHead> heads = new LinkedHashMap<Integer, ReqHead>();
		List<LineRow> rows = query(searchBox.getValue(), null, MAX_REQUISITIONS, heads);

		for (LineRow r : rows) {
			List<LineRow> g = reqLines.get(Integer.valueOf(r.reqId));
			if (g == null) {
				g = new ArrayList<LineRow>();
				reqLines.put(Integer.valueOf(r.reqId), g);
			}
			g.add(r);
		}

		SimpleDateFormat df = new SimpleDateFormat("dd-MMM-yyyy");
		int block = 0;
		for (Map.Entry<Integer, List<LineRow>> en : reqLines.entrySet()) {
			ReqHead h = heads.get(en.getKey());
			String bg = (block % 2 == 0) ? GROUP_BG : GROUP_ALT_BG;
			boolean first = true;
			for (LineRow r : en.getValue()) {
				String st = bg + (first ? GROUP_BORDER : "");

				Listitem item = new Listitem();
				item.setValue(r);

				// col 0: tick box (first row of the block only)
				Listcell c0 = cell(st);
				// col 1: requisition code = link (first row of the block only)
				Listcell c1 = cell(st);
				// col 2-4: requisition data (first row of the block only)
				Listcell c2 = cell(st);
				Listcell c3 = cell(st);
				Listcell c4 = cell(st);
				// every product line has its own tick box
				Checkbox chk = new Checkbox();
				chk.addEventListener(Events.ON_CHECK, this);
				chk.setAttribute("line", r);
				reqChecks.put(Integer.valueOf(r.reqLineId), chk);
				chk.setChecked(cart.containsKey(Integer.valueOf(r.reqLineId)));
				c0.appendChild(chk);
				if (first) {
					Label docNo = new Label(nvl(h.docNo));
					docNo.setStyle("color:#1e3a8a; font-weight:700;");
					c1.appendChild(docNo);

					c2.setLabel(h.date == null ? "" : df.format(h.date));
					c3.setLabel(nvl(h.requester));
					c4.setLabel(nvl(h.status));
				}
				item.appendChild(c0);
				item.appendChild(c1);
				item.appendChild(c2);
				item.appendChild(c3);
				item.appendChild(c4);

				// col 5-11: this line
				item.appendChild(cell(st, r.bpCode));
				item.appendChild(cell(st, r.bpName));
				item.appendChild(cell(st, r.productValue));
				item.appendChild(cell(st, nvl(r.productName)));
				item.appendChild(cell(st, r.uomName));
				item.appendChild(cell(st, r.qty == null ? "" : r.qty.stripTrailingZeros().toPlainString()));
				item.appendChild(cell(st, r.desc));

				reqList.appendChild(item);
				first = false;
			}
			block++;
		}

		countLbl.setValue(reqLines.isEmpty()
				? "No requisition with available lines found."
				: reqLines.size() + " requisition(s)"
						+ (reqLines.size() >= MAX_REQUISITIONS ? " (newest " + MAX_REQUISITIONS + " - use Search to narrow)" : ""));
		updateSelectedCount();
	}

	/**
	 * Eligible requisition lines (rule: not used in any RFQ/PO).
	 *
	 * @param filter   search text (null/empty = none)
	 * @param reqIds   only these requisitions (null = all)
	 * @param maxReq   stop after this many requisitions (0 = no limit)
	 * @param heads    receives the requisition level data (may be null)
	 */
	private List<LineRow> query(String filter, Set<Integer> reqIds, int maxReq, Map<Integer, ReqHead> heads) {
		List<LineRow> result = new ArrayList<LineRow>();

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT r.M_Requisition_ID, r.DocumentNo, r.DateDoc, u.Name, dl.Name, ");
		sql.append("rl.M_RequisitionLine_ID, rl.Line, rl.M_Product_ID, p.Value, p.Name, ");
		sql.append("rl.C_UOM_ID, um.Name, rl.Qty, rl.Description, rl.M_AttributeSetInstance_ID, ");
		if (reqLineHasBPartner)
			sql.append("rl.C_BPartner_ID, bp.Value, bp.Name ");
		else
			sql.append("0, NULL, NULL ");
		sql.append("FROM M_RequisitionLine rl ");
		sql.append("JOIN M_Requisition r ON r.M_Requisition_ID = rl.M_Requisition_ID ");
		sql.append("JOIN M_Product p ON p.M_Product_ID = rl.M_Product_ID ");
		sql.append("LEFT JOIN C_UOM um ON um.C_UOM_ID = rl.C_UOM_ID ");
		sql.append("LEFT JOIN AD_User u ON u.AD_User_ID = r.AD_User_ID ");
		sql.append("LEFT JOIN AD_Ref_List dl ON dl.AD_Reference_ID = 131 AND dl.Value = r.DocStatus ");
		if (reqLineHasBPartner)
			sql.append("LEFT JOIN C_BPartner bp ON bp.C_BPartner_ID = rl.C_BPartner_ID ");
		sql.append("WHERE r.AD_Client_ID=? AND r.IsActive='Y' AND rl.IsActive='Y' AND rl.C_OrderLine_ID IS NULL ");
		if (reqLineHasRfqCol)
			sql.append("AND (rl.XX_RfQ_ID IS NULL OR rl.XX_RfQ_ID=0) ");

		List<Object> params = new ArrayList<Object>();
		params.add(Integer.valueOf(Env.getAD_Client_ID(Env.getCtx())));

		if (reqIds != null && !reqIds.isEmpty()) {
			sql.append("AND r.M_Requisition_ID IN (");
			boolean firstId = true;
			for (Integer id : reqIds) {
				sql.append(firstId ? "?" : ",?");
				params.add(id);
				firstId = false;
			}
			sql.append(") ");
		}

		if (filter != null && !filter.trim().isEmpty()) {
			String like = "%" + filter.trim().toUpperCase() + "%";
			sql.append("AND (UPPER(r.DocumentNo) LIKE ? OR UPPER(p.Name) LIKE ? OR UPPER(p.Value) LIKE ? ");
			sql.append("OR UPPER(COALESCE(u.Name,'')) LIKE ? ");
			int n = 4;
			if (reqLineHasBPartner) {
				sql.append("OR UPPER(COALESCE(bp.Value,'')) LIKE ? OR UPPER(COALESCE(bp.Name,'')) LIKE ? ");
				n = 6;
			}
			sql.append(") ");
			for (int i = 0; i < n; i++)
				params.add(like);
		}
		sql.append("ORDER BY r.DateDoc DESC, r.M_Requisition_ID DESC, rl.Line, rl.M_RequisitionLine_ID");

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), null);
			DB.setParameters(pstmt, params);
			rs = pstmt.executeQuery();
			Set<Integer> seen = new LinkedHashSet<Integer>();
			while (rs.next()) {
				Integer reqId = Integer.valueOf(rs.getInt(1));
				if (!seen.contains(reqId)) {
					if (maxReq > 0 && seen.size() >= maxReq)
						break;   // keep only the newest requisitions
					seen.add(reqId);
					if (heads != null) {
						ReqHead h = new ReqHead();
						h.reqId = reqId.intValue();
						h.docNo = rs.getString(2);
						h.date = rs.getTimestamp(3);
						h.requester = rs.getString(4);
						h.status = rs.getString(5);
						heads.put(reqId, h);
					}
				}
				LineRow r = new LineRow();
				r.reqId = reqId.intValue();
				r.reqNo = rs.getString(2);
				r.reqLineId = rs.getInt(6);
				r.lineNo = rs.getInt(7);
				r.productId = rs.getInt(8);
				r.productValue = rs.getString(9);
				r.productName = rs.getString(10);
				r.uomId = rs.getInt(11);
				r.uomName = rs.getString(12);
				r.qty = rs.getBigDecimal(13) != null ? rs.getBigDecimal(13) : BigDecimal.ONE;
				r.desc = rs.getString(14);
				r.asiId = rs.getInt(15);
				r.bpId = rs.getInt(16);
				r.bpCode = rs.getString(17);
				r.bpName = rs.getString(18);
				result.add(r);
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, sql.toString(), e);
			throw new AdempiereException(e);
		} finally {
			DB.close(rs, pstmt);
		}
		return result;
	}

	// ----------------------------------------------------------------- events

	@Override
	public void onEvent(Event e) {
		try {
			Component c = e.getTarget();
			if (c == searchBtn || c == searchBox) {
				loadList();
			} else if (c == refreshBtn) {
				searchBox.setValue("");
				loadList();
			} else if (c == selectAll) {
				for (Checkbox chk : reqChecks.values()) {
					chk.setChecked(selectAll.isChecked());
					syncSelection(chk);
				}
				updateSelectedCount();
			} else if (c == createBtn) {
				openCreateDialog();
			} else if (c instanceof Checkbox) {
				syncSelection((Checkbox) c);   // a line tick box
				updateSelectedCount();
			}
		} catch (Exception ex) {
			log.log(Level.SEVERE, ex.getMessage(), ex);
			Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
		}
	}

	/** Keeps the selection map in step with one tick box. */
	private void syncSelection(Checkbox chk) {
		Object o = chk.getAttribute("line");
		if (!(o instanceof LineRow))
			return;
		LineRow r = (LineRow) o;
		if (chk.isChecked())
			cart.put(Integer.valueOf(r.reqLineId), r);
		else
			cart.remove(Integer.valueOf(r.reqLineId));
	}

	private void updateSelectedCount() {
		selectedLbl.setValue("Selected lines: " + cart.size());
	}

	/** Opens the separate "Create RFQ" window with every ticked line. */
	private void openCreateDialog() {
		if (cart.isEmpty()) {
			warn("No line selected. Tick one or more lines in the table, then click Create RFQ.");
			return;
		}
		WCreateRFQDialog dlg = new WCreateRFQDialog(m_WindowNo, new ArrayList<LineRow>(cart.values()), new Runnable() {
			@Override
			public void run() {
				cart.clear();
				loadList();   // used lines disappear from the list
			}
		});
		appendChild(dlg);
		org.mycompany.requisition.ui.XxTheme.apply(dlg);
		dlg.doModal();
	}

	// ---------------------------------------------------------------- helpers

	private static Listcell cell(String style) {
		Listcell c = new Listcell();
		c.setStyle(style);
		return c;
	}

	private static Listcell cell(String style, String label) {
		Listcell c = new Listcell(nvl(label));
		c.setStyle(style);
		return c;
	}

	private static boolean columnExists(String table, String column) {
		return DB.getSQLValue(null,
				"SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)",
				table, column) > 0;
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	private void warn(String msg) {
		Messagebox.show(msg, "Warning", Messagebox.OK, Messagebox.EXCLAMATION);
	}
}