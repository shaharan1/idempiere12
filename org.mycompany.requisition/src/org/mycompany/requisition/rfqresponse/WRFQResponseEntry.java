package org.mycompany.requisition.rfqresponse;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.ConfirmPanel;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.editor.WTableDirEditor;
import org.adempiere.webui.event.ValueChangeEvent;
import org.adempiere.webui.event.ValueChangeListener;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.panel.WAttachment;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.model.MColumn;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MRfQ;
import org.compiere.model.MRfQResponse;
import org.compiere.model.MRfQTopicSubscriber;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.mycompany.requisition.rfqapproval.RfQApprovalService;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.A;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Caption;
import org.zkoss.zul.Center;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Column;
import org.zkoss.zul.Columns;
import org.zkoss.zul.Div;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Style;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.South;
import org.zkoss.zul.Vlayout;

/**
 * "RFQ Response Entry" form.
 *
 * - Upore RFQ er table (RFQ No, Name, Date, PR No, Items, Business Partner Code gulo por por,
 *   Status, CS Status). Shudhu je RFQ gulo ekhono complete hoy ni (Document Status != Completed)
 *   ashe; "Complete RFQ" korle RFQ ta ei form theke soriye jay (tick dile dekha jay).
 * - Business Partner Code e click -> oi RFQ select hoy + response popup khole.
 * - Prottek RFQ row er Business Partner line er pashe-i oi supplier er Status, Complete ar
 *   Attachment thake (alada Supplier Responses table nei).
 * - Business Partner Code e click -> WRFQResponseDialog (response entry popup).
 * - "Complete" -> IsComplete='Y'. "Attachment" -> standard attachment window.
 *
 * AD_Form.Classname = org.mycompany.requisition.rfqresponse.WRFQResponseEntry
 */
public class WRFQResponseEntry extends ADForm implements ValueChangeListener, EventListener<Event> {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WRFQResponseEntry.class);

	private static final String HDR_STYLE = "font-weight:600;color:#475569;font-size:12px;text-transform:uppercase;letter-spacing:.4px;";
	private static final String TH_STYLE = "background:#1e4d7b;color:#ffffff;font-weight:600;font-size:12px;"
			+ "text-transform:uppercase;letter-spacing:.4px;border-color:#1e4d7b;";
	private static final String LINK_STYLE = "color:#1e5aa8; font-weight:600; text-decoration:underline; cursor:pointer;";
	private static final String BTN_PRIMARY = "background:#16a34a;background-image:none;color:#ffffff;border:none;"
			+ "border-radius:6px;font-weight:600;padding:5px 16px;cursor:pointer;";
	private static final String BTN_DISABLED = "background:#e2e8f0;background-image:none;color:#64748b;border:none;"
			+ "border-radius:6px;font-weight:600;padding:5px 16px;";
	private static final String BTN_OUTLINE = "background:#ffffff;background-image:none;color:#1e4d7b;"
			+ "border:1px solid #1e4d7b;border-radius:6px;font-weight:600;padding:4px 16px;cursor:pointer;";

	/** Same height for every supplier line, so BP / Status / Complete / Attachment line up */
	private static final String LINE_STYLE = "display:flex;align-items:center;height:34px;";
	private static final String BTN_SMALL = "padding:2px 12px;font-size:12px;min-width:84px;";
	private static final String BTN_ATTACH = "background:#0ea5e9;background-image:none;color:#ffffff;border:none;"
			+ "border-radius:6px;font-weight:600;cursor:pointer;";

	private static final String CSS = ""
			+ ".rq-banner{background:linear-gradient(90deg,#0f2b46 0%,#1e5a8c 100%);color:#fff;border-radius:12px;"
			+ "padding:16px 22px;box-shadow:0 4px 14px rgba(15,43,70,.25);}"
			+ ".rq-title{display:block;font-size:22px;font-weight:700;color:#fff;letter-spacing:.3px;}"
			+ ".rq-sub{display:block;font-size:13px;color:#cfe3f5;}"
			+ ".rq-card{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:10px 14px;"
			+ "box-shadow:0 1px 4px rgba(15,23,42,.08);margin-top:12px;}"
			+ ".rq-stat{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:10px 18px;min-width:170px;"
			+ "box-shadow:0 1px 4px rgba(15,23,42,.08);}"
			+ ".rq-stat-val{font-size:24px;font-weight:700;display:block;line-height:1.1;}"
			+ ".rq-stat-lbl{font-size:12px;color:#64748b;font-weight:600;text-transform:uppercase;letter-spacing:.4px;}"
			+ ".rq-plain, .rq-plain .z-row, .rq-plain .z-row-content, .rq-plain .z-column{border:none !important;"
			+ "background:transparent !important;}"
			+ ".rq-list .z-listheader-content{color:#fff !important;}"
			+ ".rq-list .z-listcell{border-color:#eef2f7;}"
			+ ".rq-list .z-listitem:hover > .z-listcell{background:#eaf3fb !important;}"
			+ ".rq-section{font-size:15px;font-weight:700;color:#0f2b46;padding:4px 2px 8px 2px;display:block;}"
			+ ".rq-empty{color:#94a3b8;font-size:14px;padding:46px 0;text-align:center;display:block;}";

	private Borderlayout mainLayout = new Borderlayout();

	private WTableDirEditor orgEditor;
	private Listbox rfqList;
	private int selectedRfqId;
	private String selectedRfqLabel = "";
	private Label rfqNameLabel = new Label("");
	private Button reloadBtn = new Button("Reload RFQ");
	private Button completeRfqBtn = new Button("Complete RFQ");
	private ConfirmPanel confirmPanel = new ConfirmPanel(true);

	private boolean rfqHasDocNo;

	private Label statTotal = new Label("0");
	private Label statDone = new Label("0");
	private Label statPending = new Label("0");
	private Checkbox showDoneChk = new Checkbox("Show completed RFQs");

	// ------------------------------------------------------------------ init

	@Override
	protected void initForm() {
		org.mycompany.requisition.ui.XxTheme.wrap(this);
		try {
			rfqHasDocNo = columnExists("C_RfQ", "DocumentNo");
			initComponents();
			layout();
			loadRfqList();
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			throw new AdempiereException(e);
		}
	}

	private void initComponents() {
		// Organization dropdown
		int orgCol = requireColumn("C_RfQ", "AD_Org_ID");
		MLookup orgLookup = MLookupFactory.get(Env.getCtx(), m_WindowNo, 0, orgCol, DisplayType.TableDir);
		orgEditor = new WTableDirEditor("AD_Org_ID", true, false, true, orgLookup);
		orgEditor.setValue(Integer.valueOf(Env.getAD_Org_ID(Env.getCtx())));
		orgEditor.addValueChangeListener(this);

		// RFQ table (master list)
		rfqList = new Listbox();
		rfqList.setSclass("rq-list");
		rfqList.setHeight("480px");
		rfqList.setEmptyMessage("No RFQ found.");
		Listhead rh = new Listhead();
		rh.appendChild(header("SL", "50px"));
		rh.appendChild(header("RFQ No", "110px"));
		rh.appendChild(header("RFQ Name", "210px"));
		rh.appendChild(header("Date", "100px"));
		rh.appendChild(header("PR No", "130px"));
		rh.appendChild(header("Items", "60px"));
		rh.appendChild(header("Business Partner Code", null));
		rh.appendChild(header("Responses", "90px"));
		rh.appendChild(header("Status", "120px"));
		rh.appendChild(header("Complete Response Line", "170px"));
		rh.appendChild(header("Attachment", "130px"));
		rh.appendChild(header("CS Status", "130px"));
		rfqList.appendChild(rh);
		ZKUpdateUtil.setHflex(rfqList, "1");
		rfqList.addEventListener(Events.ON_SELECT, this);

		reloadBtn.setStyle("background:#0ea5a4;background-image:none;color:#ffffff;border:none;border-radius:6px;"
				+ "font-weight:600;padding:6px 18px;cursor:pointer;");
		reloadBtn.addEventListener(Events.ON_CLICK, this);
		completeRfqBtn.setStyle("background:#1e5aa8;background-image:none;color:#ffffff;border:none;border-radius:6px;"
				+ "font-weight:600;padding:5px 16px;cursor:pointer;");
		completeRfqBtn.addEventListener(Events.ON_CLICK, e -> completeRfq());
		showDoneChk.setChecked(false); // default: shudhu je RFQ gulo complete hoy ni
		showDoneChk.addEventListener(Events.ON_CHECK, this);
		showDoneChk.setStyle("font-weight:600;color:#475569;padding-left:10px;");
		confirmPanel.addActionListener(this); // OK / Cancel (tick / cross icon)

	}

	private Listheader header(String label, String width) {
		Listheader h = new Listheader(label);
		h.setStyle(TH_STYLE);
		if (width != null)
			h.setWidth(width);
		return h;
	}

	// ---------------------------------------------------------------- layout

	private void layout() {
		appendChild(mainLayout);
		ZKUpdateUtil.setWidth(this, "99%");
		ZKUpdateUtil.setHeight(this, "100%");
		ZKUpdateUtil.setWidth(mainLayout, "100%");
		ZKUpdateUtil.setHeight(mainLayout, "100%");
		mainLayout.setStyle("background:#f1f5f9;");

		Style css = new Style();
		css.setContent(CSS);
		appendChild(css);

		// ---- North: banner + filter card + summary boxes ----
		North north = new North();
		ZKUpdateUtil.setVflex(north, "min");
		north.setStyle("background:transparent;border:none;");
		mainLayout.appendChild(north);

		Div wrap = new Div();
		wrap.setStyle("padding:12px 14px 4px 14px;");
		north.appendChild(wrap);

		Div banner = new Div();
		banner.setSclass("rq-banner");
		Label title = new Label("RFQ Response Entry");
		title.setSclass("rq-title");
		Label sub = new Label("Collect, review and complete supplier quotations for a request for quotation");
		sub.setSclass("rq-sub");
		banner.appendChild(title);
		banner.appendChild(sub);
		wrap.appendChild(banner);

		Div card = new Div();
		card.setSclass("rq-card");
		wrap.appendChild(card);

		Grid grid = new Grid();
		grid.setSclass("rq-plain");
		Columns cols = new Columns();
		grid.appendChild(cols);
		addColumn(cols, "120px", null);
		addColumn(cols, null, "1");
		addColumn(cols, "120px", null);
		addColumn(cols, null, "2");
		addColumn(cols, "170px", null);
		Rows rows = new Rows();
		grid.appendChild(rows);
		card.appendChild(grid);

		Row r1 = new Row();
		rows.appendChild(r1);
		r1.appendChild(boldLabel("Organization"));
		r1.appendChild(orgEditor.getComponent());
		r1.appendChild(boldLabel("Selected RFQ"));
		rfqNameLabel.setStyle("font-weight:600;color:#0f2b46;font-size:14px;");
		r1.appendChild(rfqNameLabel);
		r1.appendChild(reloadBtn);

		Hlayout stats = new Hlayout();
		stats.setSpacing("14px");
		stats.setStyle("padding:12px 0 2px 0;");
		stats.appendChild(statBox("Total Suppliers", statTotal, "#1e5aa8"));
		stats.appendChild(statBox("Completed", statDone, "#16a34a"));
		stats.appendChild(statBox("Pending", statPending, "#d97706"));
		stats.appendChild(showDoneChk);
		stats.appendChild(completeRfqBtn);
		wrap.appendChild(stats);

		// ---- Center: supplier list ----
		Center center = new Center();
		center.setStyle("background:transparent;border:none;");
		center.setAutoscroll(true);
		mainLayout.appendChild(center);
		Div cwrap = new Div();
		cwrap.setStyle("padding:6px 14px 8px 14px;");
		ZKUpdateUtil.setHflex(cwrap, "1");
		center.appendChild(cwrap);

		Div rfqCard = new Div();
		rfqCard.setSclass("rq-card");
		rfqCard.setStyle("margin-top:0;");
		cwrap.appendChild(rfqCard);
		Label rcap = new Label("RFQ List");
		rcap.setSclass("rq-section");
		rfqCard.appendChild(rcap);
		rfqCard.appendChild(rfqList);

		// ---- South: OK / Cancel ----
		South south = new South();
		ZKUpdateUtil.setVflex(south, "min");
		south.setStyle("background:transparent;border:none;");
		mainLayout.appendChild(south);
		Div sbox = new Div();
		sbox.setStyle("padding:4px 14px 10px 14px;");
		sbox.appendChild(confirmPanel);
		south.appendChild(sbox);
	}

	private Div statBox(String title, Label value, String color) {
		Div d = new Div();
		d.setSclass("rq-stat");
		d.setStyle("border-left:5px solid " + color + ";");
		value.setSclass("rq-stat-val");
		value.setStyle("color:" + color + ";");
		Label l = new Label(title);
		l.setSclass("rq-stat-lbl");
		d.appendChild(value);
		d.appendChild(l);
		return d;
	}

	private void setStats(int total, int done) {
		statTotal.setValue(String.valueOf(total));
		statDone.setValue(String.valueOf(done));
		statPending.setValue(String.valueOf(total - done));
	}

	private void addColumn(Columns cols, String width, String hflex) {
		Column c = new Column();
		if (width != null)
			c.setWidth(width);
		if (hflex != null)
			ZKUpdateUtil.setHflex(c, hflex);
		cols.appendChild(c);
	}

	private Label boldLabel(String text) {
		Label l = new Label(text);
		l.setStyle(HDR_STYLE);
		return l;
	}

	// ------------------------------------------------------------ RFQ list

	private static class Resp {
		int id;
		String code, name, address;
		boolean complete;
	}

	/**
	 * RFQ table. "Show completed RFQs" tick na thakle shudhu oi RFQ gulo ashe jegulor kono
	 * response ekhono Complete hoy ni (ba jar subscriber er response ekhono toiri hoy ni).
	 */
	private void loadRfqList() {
		int keep = selectedRfqId;
		clear(rfqList);
		setStats(0, 0);
		selectedRfqId = 0;
		selectedRfqLabel = "";
		rfqNameLabel.setValue("");

		int orgId = toInt(orgEditor.getValue());

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT r.C_RfQ_ID, ").append(RfQApprovalService.docNoExpr()).append(", r.Name, r.Created, ");
		sql.append("(SELECT string_agg(DISTINCT x.DocumentNo, ', ') FROM C_RfQLine xl ");
		sql.append("   JOIN M_RequisitionLine xrl ON xrl.M_RequisitionLine_ID=xl.XX_RequisitionLine_ID ");
		sql.append("   JOIN M_Requisition x ON x.M_Requisition_ID=xrl.M_Requisition_ID WHERE xl.C_RfQ_ID=r.C_RfQ_ID), ");
		sql.append("(SELECT COUNT(*) FROM C_RfQLine ql WHERE ql.C_RfQ_ID=r.C_RfQ_ID AND ql.IsActive='Y'), ");
		sql.append(RfQApprovalService.lastActionSql()).append(" ");
		sql.append("FROM C_RfQ r WHERE r.IsActive='Y' AND r.AD_Client_ID=? ");
		if (orgId > 0)
			sql.append("AND r.AD_Org_ID=? ");
		// Shudhu Document Status = Drafted RFQ ashe. In Progress (CS approval cholche) ba Completed hole ashe na.
		// "Show completed RFQs" tick dile shob dekhay.
		if (!showDoneChk.isChecked())
			sql.append("AND ").append(RfQApprovalService.draftSql()).append(" ");
		sql.append("AND (EXISTS (SELECT 1 FROM C_RfQResponse x WHERE x.C_RfQ_ID=r.C_RfQ_ID AND x.IsActive='Y') ");
		sql.append("OR ").append(missingSubscriberSql("r.C_RfQ_ID")).append(") ");
		sql.append("ORDER BY r.C_RfQ_ID DESC");

		java.util.List<Object[]> rfqs = new java.util.ArrayList<Object[]>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), null);
			int idx = 1;
			pstmt.setInt(idx++, Env.getAD_Client_ID(Env.getCtx()));
			if (orgId > 0)
				pstmt.setInt(idx++, orgId);
			rs = pstmt.executeQuery();
			while (rs.next())
				rfqs.add(new Object[] { Integer.valueOf(rs.getInt(1)), nvl(rs.getString(2)), nvl(rs.getString(3)),
						rs.getTimestamp(4), nvl(rs.getString(5)), Integer.valueOf(rs.getInt(6)), rs.getString(7) });
		} catch (SQLException e) {
			log.log(Level.SEVERE, sql.toString(), e);
			throw new AdempiereException(e);
		} finally {
			DB.close(rs, pstmt);
		}
		if (rfqs.isEmpty())
			return;

		// subscriber er response ekhono na thakle toiri kore nao (BP code gulo dekhanor jonno)
		StringBuilder ids = new StringBuilder();
		for (Object[] r : rfqs) {
			int id = ((Integer) r[0]).intValue();
			if (ids.length() > 0)
				ids.append(',');
			ids.append(id);
			try {
				if (DB.getSQLValue(null, "SELECT CASE WHEN " + missingSubscriberSql("?") + " THEN 1 ELSE 0 END", id) > 0)
					ensureResponses(id);
			} catch (Exception ex) {
				log.log(Level.WARNING, "ensureResponses RFQ " + id + ": " + ex.getMessage());
			}
		}

		// shob RFQ er response ek query te
		java.util.Map<Integer, java.util.List<Resp>> byRfq = new java.util.HashMap<Integer, java.util.List<Resp>>();
		String rsql = "SELECT r.C_RfQ_ID, r.C_RfQResponse_ID, bp.Value, bp.Name, r.IsComplete, "
				+ "loc.Address1, loc.Address2, loc.City "
				+ "FROM C_RfQResponse r JOIN C_BPartner bp ON bp.C_BPartner_ID=r.C_BPartner_ID "
				+ "LEFT JOIN C_BPartner_Location bl ON bl.C_BPartner_Location_ID=r.C_BPartner_Location_ID "
				+ "LEFT JOIN C_Location loc ON loc.C_Location_ID=bl.C_Location_ID "
				+ "WHERE r.IsActive='Y' AND r.C_RfQ_ID IN (" + ids + ") ORDER BY r.C_RfQ_ID, r.C_RfQResponse_ID";
		try {
			pstmt = DB.prepareStatement(rsql, null);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				Resp rp = new Resp();
				rp.id = rs.getInt(2);
				rp.code = nvl(rs.getString(3));
				rp.name = nvl(rs.getString(4));
				rp.complete = "Y".equals(rs.getString(5));
				rp.address = joinAddress(rs.getString(6), rs.getString(7), rs.getString(8));
				Integer k = Integer.valueOf(rs.getInt(1));
				if (!byRfq.containsKey(k))
					byRfq.put(k, new java.util.ArrayList<Resp>());
				byRfq.get(k).add(rp);
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, rsql, e);
			throw new AdempiereException(e);
		} finally {
			DB.close(rs, pstmt);
		}

		java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("dd/MM/yyyy");
		int sl = 0;
		Listitem keepItem = null;
		for (Object[] r : rfqs) {
			sl++;
			int id = ((Integer) r[0]).intValue();
			String no = (String) r[1];
			java.util.List<Resp> resps = byRfq.get(Integer.valueOf(id));
			if (resps == null)
				resps = new java.util.ArrayList<Resp>();
			int done = 0;
			for (Resp rp : resps)
				if (rp.complete)
					done++;
			Listitem item = newRfqRow(id, sl, no, (String) r[2], r[3] == null ? "" : df.format((java.util.Date) r[3]),
					(String) r[4], ((Integer) r[5]).intValue(), resps, done, (String) r[6]);
			rfqList.appendChild(item);
			if (id == keep)
				keepItem = item;
		}
		if (keepItem != null)
			selectRfq(keepItem);
	}

	/** True SQL expression: oi RFQ er Topic e emon active subscriber ache jar response ekhono nei. */
	private static String missingSubscriberSql(String rfqIdExpr) {
		return "EXISTS (SELECT 1 FROM C_RfQ_TopicSubscriber s JOIN C_RfQ q ON q.C_RfQ_Topic_ID=s.C_RfQ_Topic_ID "
				+ "WHERE q.C_RfQ_ID=" + rfqIdExpr + " AND s.IsActive='Y' AND NOT EXISTS (SELECT 1 FROM C_RfQResponse y "
				+ "WHERE y.C_RfQ_ID=q.C_RfQ_ID AND y.C_BPartner_ID=s.C_BPartner_ID AND y.IsActive='Y'))";
	}

	private Listitem newRfqRow(final int rfqId, int sl, String no, String name, String date, String prNos, int items,
			java.util.List<Resp> resps, int done, String csState) {
		final Listitem item = new Listitem();
		item.setValue(Integer.valueOf(rfqId));
		item.setAttribute("label", no + (name.isEmpty() ? "" : " - " + name));
		item.setAttribute("total", Integer.valueOf(resps.size()));
		item.setAttribute("done", Integer.valueOf(done));
		if (sl % 2 == 0)
			item.setStyle("background:#f8fafc;");

		item.appendChild(new Listcell(String.valueOf(sl)));
		Listcell cNo = new Listcell(no);
		cNo.setStyle("font-weight:700;color:#0f2b46;");
		item.appendChild(cNo);
		item.appendChild(new Listcell(name));
		item.appendChild(new Listcell(date));
		item.appendChild(new Listcell(prNos));
		item.appendChild(new Listcell(String.valueOf(items)));

		// Prottek supplier er line: BP Code + name | Status | Complete | Attachment.
		// Char column-i same line height (LINE_STYLE) tai sob line ek shoriye thake.
		Div bpBox = new Div();
		Div stBox = new Div();
		Div cmBox = new Div();
		Div atBox = new Div();
		for (final Resp rp : resps) {
			// BP code (link -> select RFQ + response popup) + name
			Div l1 = new Div();
			l1.setStyle(LINE_STYLE);
			A link = new A(rp.code);
			link.setStyle(LINK_STYLE + "color:" + (rp.complete ? "#166534;" : "#b45309;"));
			link.setTooltiptext(rp.name + (rp.complete ? " (Completed)" : " (Pending)"));
			link.addEventListener(Events.ON_CLICK, e -> {
				selectRfq(item);
				openResponse(rp.id);
			});
			Label nm = new Label(rp.name);
			nm.setStyle("color:#64748b;font-size:12px;margin-left:8px;");
			if (!rp.address.isEmpty())
				nm.setTooltiptext(rp.address);
			l1.appendChild(link);
			l1.appendChild(nm);
			bpBox.appendChild(l1);

			// Status badge
			Div l2 = new Div();
			l2.setStyle(LINE_STYLE);
			Label sb = new Label(rp.complete ? "Completed" : "Pending");
			sb.setStyle("display:inline-block;padding:3px 14px;border-radius:999px;font-size:12px;font-weight:600;"
					+ (rp.complete ? "background:#dcfce7;color:#166534;" : "background:#fef3c7;color:#92400e;"));
			l2.appendChild(sb);
			stBox.appendChild(l2);

			// Complete Response Line
			Div l3 = new Div();
			l3.setStyle(LINE_STYLE);
			Button cb = new Button(rp.complete ? "Completed" : "Complete");
			cb.setStyle((rp.complete ? BTN_DISABLED : BTN_PRIMARY) + BTN_SMALL);
			cb.setDisabled(rp.complete);
			cb.addEventListener(Events.ON_CLICK, e -> {
				selectRfq(item);
				completeResponse(rp.id);
			});
			l3.appendChild(cb);
			cmBox.appendChild(l3);

			// Attachment
			Div l4 = new Div();
			l4.setStyle(LINE_STYLE);
			Button ab = new Button("Attachment");
			ab.setStyle(BTN_ATTACH + BTN_SMALL);
			ab.addEventListener(Events.ON_CLICK, e -> openAttachment(rp.id));
			l4.appendChild(ab);
			atBox.appendChild(l4);
		}
		Listcell cBp = new Listcell();
		cBp.appendChild(bpBox);
		item.appendChild(cBp);

		item.appendChild(new Listcell(done + " / " + resps.size()));

		Listcell cSt = new Listcell();
		cSt.appendChild(stBox);
		item.appendChild(cSt);
		Listcell cCm = new Listcell();
		cCm.appendChild(cmBox);
		item.appendChild(cCm);
		Listcell cAt = new Listcell();
		cAt.appendChild(atBox);
		item.appendChild(cAt);

		String cs = RfQApprovalService.S_APPROVED.equals(csState) ? "Approved"
				: RfQApprovalService.S_REJECTED.equals(csState) ? "Rejected"
				: RfQApprovalService.S_PENDING.equals(csState) ? "Pending Approval" : "Not Submitted";
		item.appendChild(new Listcell(cs));
		return item;
	}

	/** RFQ row select kore: Selected RFQ naam + supplier count update. */
	private void selectRfq(Listitem item) {
		rfqList.setSelectedItem(item);
		selectedRfqId = toInt(item.getValue());
		Object lbl = item.getAttribute("label");
		selectedRfqLabel = lbl == null ? "" : lbl.toString();
		rfqNameLabel.setValue(selectedRfqLabel);
		setStats(toInt(item.getAttribute("total")), toInt(item.getAttribute("done")));
	}

	/** Selected RFQ id (table er selected row) */
	private int currentRfqId() {
		return selectedRfqId;
	}

	// ------------------------------------------------------------ grid data

	/**
	 * Oi RFQ er Topic er prottek active subscriber er jonno C_RfQResponse (+ Line + LineQty)
	 * jodi na thake tahole toiri kore (standard MRfQResponse constructor diye).
	 * Already response thaka BP skip hoy, tai duplicate hoy na.
	 */
	private void ensureResponses(int rfqId) {
		String trxName = Trx.createTrxName("RFQRespCreate");
		Trx trx = Trx.get(trxName, true);
		try {
			MRfQ rfq = new MRfQ(Env.getCtx(), rfqId, trxName);
			if (rfq.getC_RfQ_Topic_ID() <= 0) {
				trx.rollback();
				return;
			}
			int[] subIds = org.compiere.model.PO.getAllIDs("C_RfQ_TopicSubscriber",
					"C_RfQ_Topic_ID=" + rfq.getC_RfQ_Topic_ID() + " AND IsActive='Y'", trxName);
			int created = 0;
			if (subIds != null) {
				for (int subId : subIds) {
					MRfQTopicSubscriber sub = new MRfQTopicSubscriber(Env.getCtx(), subId, trxName);
					int exists = DB.getSQLValue(trxName,
							"SELECT COUNT(*) FROM C_RfQResponse WHERE C_RfQ_ID=? AND C_BPartner_ID=? AND IsActive='Y'",
							rfqId, sub.getC_BPartner_ID());
					if (exists > 0)
						continue;
					MRfQResponse resp = new MRfQResponse(rfq, sub);
					resp.saveEx();
					created++;
				}
			}
			if (created > 0)
				trx.commit();
			else
				trx.rollback();
		} catch (Exception ex) {
			trx.rollback();
			throw new AdempiereException("Could not create responses for the RFQ subscribers: " + ex.getMessage(), ex);
		} finally {
			trx.close();
		}
	}

	// --------------------------------------------------------------- actions

	private void openResponse(int respId) {
		try {
			WRFQResponseDialog dlg = new WRFQResponseDialog(respId, () -> reloadKeepingSelection());
			dlg.show();
		} catch (Exception ex) {
			error(ex);
		}
	}

	/** Complete Response Line: IsComplete='Y' */
	private void completeResponse(int respId) {
		try {
			java.math.BigDecimal priced = DB.getSQLValueBD(null,
					"SELECT COALESCE(SUM(rlq.Price),0) FROM C_RfQResponseLineQty rlq "
							+ "JOIN C_RfQResponseLine rl ON rl.C_RfQResponseLine_ID = rlq.C_RfQResponseLine_ID "
							+ "WHERE rl.C_RfQResponse_ID=?",
					respId);
			if (priced == null || priced.signum() <= 0)
				throw new AdempiereException("No price has been entered for this response. Please open the response by clicking the Business Partner Code and enter the prices first.");

			PO po = MTable.get(Env.getCtx(), "C_RfQResponse").getPO(respId, null);
			po.set_ValueOfColumn("IsComplete", Boolean.TRUE);
			po.saveEx();
			reloadKeepingSelection();
		} catch (Exception ex) {
			error(ex);
		}
	}

	/**
	 * Complete RFQ: only when every invited vendor has answered, every response
	 * is complete, and every product has a valid price in every response. On
	 * success the RFQ is pushed into the approval chain (UT_ApprovalHistory +
	 * AD_Note audit trail).
	 */
	private void completeRfq() {
		try {
			int rfqId = currentRfqId();
			if (rfqId <= 0)
				throw new AdempiereException("Select an RFQ first.");

			int total = DB.getSQLValue(null,
					"SELECT COUNT(*) FROM C_RfQResponse WHERE C_RfQ_ID=? AND IsActive='Y'", rfqId);
			if (total == 0)
				throw new AdempiereException("This RFQ has no supplier response yet.");

			int missing = DB.getSQLValue(null,
					"SELECT COUNT(*) FROM C_RfQ_TopicSubscriber s "
					+ "WHERE s.C_RfQ_Topic_ID=(SELECT C_RfQ_Topic_ID FROM C_RfQ WHERE C_RfQ_ID=?) "
					+ "AND s.IsActive='Y' AND NOT EXISTS (SELECT 1 FROM C_RfQResponse x "
					+ "  WHERE x.C_RfQ_ID=? AND x.C_BPartner_ID=s.C_BPartner_ID AND x.IsActive='Y')",
					rfqId, rfqId);
			if (missing > 0)
				throw new AdempiereException(missing + " invited vendor(s) have not submitted a response. "
						+ "Every invited vendor must respond before the RFQ can be completed.");

			int incomplete = DB.getSQLValue(null,
					"SELECT COUNT(*) FROM C_RfQResponse WHERE C_RfQ_ID=? AND IsActive='Y' AND IsComplete='N'",
					rfqId);
			if (incomplete > 0)
				throw new AdempiereException(incomplete + " response(s) are still pending. "
						+ "Mark every response as Complete before completing the RFQ.");

			int missingPrices = DB.getSQLValue(null,
					"SELECT COUNT(*) FROM C_RfQLine l "
					+ "JOIN C_RfQResponse x ON x.C_RfQ_ID=l.C_RfQ_ID AND x.IsActive='Y' "
					+ "WHERE l.C_RfQ_ID=? AND l.IsActive='Y' AND NOT EXISTS ("
					+ "  SELECT 1 FROM C_RfQResponseLine sl "
					+ "  JOIN C_RfQResponseLineQty q ON q.C_RfQResponseLine_ID=sl.C_RfQResponseLine_ID "
					+ "  WHERE sl.C_RfQResponse_ID=x.C_RfQResponse_ID AND sl.C_RfQLine_ID=l.C_RfQLine_ID "
					+ "  AND sl.IsActive='Y' AND q.IsActive='Y' "
					+ "  AND COALESCE(NULLIF(q.XX_NegotiatePrice,0), q.Price) > 0)", rfqId);
			if (missingPrices > 0)
				throw new AdempiereException(missingPrices + " product/vendor price(s) are missing or invalid. "
						+ "Every product must have a valid quoted price from every vendor.");

			// Record the submission and, when a process is configured for C_RfQ,
			// start the approval chain. The history row is the guaranteed audit
			// trail; the engine call degrades to a no-op when nothing is configured.
			new RfQApprovalService().submitForApproval(rfqId,
					"RFQ completed: all invited vendors responded with valid prices.");
			try {
				int tableId = MTable.getTable_ID("C_RfQ");
				int docTypeId = DB.getSQLValue(null,
						"SELECT rq.C_DocType_ID FROM C_RfQ r "
						+ "JOIN M_Requisition rq ON rq.M_Requisition_ID=r.M_Requisition_ID "
						+ "WHERE r.C_RfQ_ID=?", rfqId);
				if (tableId > 0)
					new org.mycompany.requisition.approval.service.ApprovalEngineClient()
							.start(tableId, rfqId, null, docTypeId, "RFQ completed");
			} catch (Exception ignore) {
				// No approval process configured for C_RfQ: the history row is enough.
				log.log(Level.INFO, "RFQ " + rfqId + " not started in the approval engine: " + ignore.getMessage());
			}

			reloadKeepingSelection();
			Messagebox.show("RFQ completed and submitted for approval.", "Complete RFQ", Messagebox.OK,
					Messagebox.INFORMATION);
		} catch (Exception ex) {
			error(ex);
		}
	}

	/** Standard iDempiere Attachment window (C_RfQResponse record er jonno) */
	private void openAttachment(int respId) {
		try {
			int tableId = MTable.getTable_ID("C_RfQResponse");
			int attId = DB.getSQLValue(null,
					"SELECT AD_Attachment_ID FROM AD_Attachment WHERE AD_Table_ID=? AND Record_ID=?", tableId,
					respId);
			if (attId < 0)
				attId = 0;
			WAttachment win = new WAttachment(m_WindowNo, attId, tableId, respId, null, null);
			AEnv.showWindow(win);
		} catch (Exception ex) {
			error(ex);
		}
	}

	// ----------------------------------------------------------------- events

	@Override
	public void valueChange(ValueChangeEvent evt) {
		if ("AD_Org_ID".equals(evt.getPropertyName())) {
			selectedRfqId = 0; // org bodlale selection reset
			loadRfqList();
		}
	}

	@Override
	public void onEvent(Event e) {
		try {
			Object c = e.getTarget();
			if (c == rfqList) {
				Listitem li = rfqList.getSelectedItem();
				if (li != null && toInt(li.getValue()) != selectedRfqId)
					selectRfq(li);
			} else if (c == reloadBtn || c == showDoneChk) {
				reloadKeepingSelection();
			} else if (c instanceof org.zkoss.zk.ui.Component) {
				String id = ((org.zkoss.zk.ui.Component) c).getId();
				if (ConfirmPanel.A_OK.equals(id))
					reloadKeepingSelection(); // OK: stay on this form, just refresh the data
				else if (ConfirmPanel.A_CANCEL.equals(id))
					SessionManager.getAppDesktop().closeActiveWindow();
			}
		} catch (Exception ex) {
			error(ex);
		}
	}

	/** Reload RFQ table + supplier list, keeping the currently selected RFQ if it is still in the list. */
	private void reloadKeepingSelection() {
		loadRfqList();
	}

	// ---------------------------------------------------------------- helpers

	private void error(Exception ex) {
		log.log(Level.SEVERE, ex.getMessage(), ex);
		Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
	}

	private static void clear(Listbox lb) {
		while (lb.getItemCount() > 0)
			lb.removeItemAt(0);
	}

	private static boolean columnExists(String table, String column) {
		return DB.getSQLValue(null,
				"SELECT COUNT(*) FROM information_schema.columns "
						+ "WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)",
				table, column) > 0;
	}

	private static int requireColumn(String table, String column) {
		int id = MColumn.getColumn_ID(table, column);
		if (id <= 0)
			throw new AdempiereException("Column not found in Application Dictionary: " + table + "." + column);
		return id;
	}

	private static String joinAddress(String... parts) {
		StringBuilder sb = new StringBuilder();
		for (String p : parts) {
			if (p == null || p.trim().isEmpty())
				continue;
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(p.trim());
		}
		return sb.toString();
	}

	private static int toInt(Object o) {
		if (o instanceof Number)
			return ((Number) o).intValue();
		return 0;
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}
}