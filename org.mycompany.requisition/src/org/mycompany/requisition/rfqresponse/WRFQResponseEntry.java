package org.mycompany.requisition.rfqresponse;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Combobox;
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
import org.adempiere.webui.theme.ThemeManager;
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
import org.zkoss.zul.Comboitem;
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
 * - RFQ field = search + dropdown (type korle filter hoy). Shudhu oi RFQ gulo ashe
 *   jegulor kono response ekhono Complete hoy ni.
 * - RFQ select korle shei RFQ er Business Partner response list ashe.
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
	private Combobox rfqCombo = new Combobox();
	private Label rfqNameLabel = new Label("");
	private Button reloadBtn = new Button("Reload RFQ");
	private Button completeRfqBtn = new Button("Complete RFQ");
	private ConfirmPanel confirmPanel = new ConfirmPanel(true);

	private Listbox respList;

	private boolean rfqHasDocNo;

	private Label statTotal = new Label("0");
	private Label statDone = new Label("0");
	private Label statPending = new Label("0");
	private Checkbox showDoneChk = new Checkbox("Show completed RFQs");
	private Label emptyLbl = new Label("Select an RFQ above to see its supplier responses.");

	// ------------------------------------------------------------------ init

	@Override
	protected void initForm() {
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

		// RFQ: search + dropdown
		rfqCombo.setAutocomplete(true);
		rfqCombo.setAutodrop(true);
		rfqCombo.setButtonVisible(true);
		ZKUpdateUtil.setHflex(rfqCombo, "1");
		rfqCombo.addEventListener(Events.ON_SELECT, this);
		rfqCombo.addEventListener(Events.ON_CHANGE, this);

		reloadBtn.setStyle("background:#0ea5a4;background-image:none;color:#ffffff;border:none;border-radius:6px;"
				+ "font-weight:600;padding:6px 18px;cursor:pointer;");
		try {
			reloadBtn.setImage(ThemeManager.getThemeResource("images/Refresh16.png"));
		} catch (Exception ignore) {
			// icon na thakleo button kaj korbe
		}
		reloadBtn.addEventListener(Events.ON_CLICK, this);
		completeRfqBtn.setStyle("background:#1e5aa8;background-image:none;color:#ffffff;border:none;border-radius:6px;"
				+ "font-weight:600;padding:5px 16px;cursor:pointer;");
		completeRfqBtn.addEventListener(Events.ON_CLICK, e -> completeRfq());
		showDoneChk.addEventListener(Events.ON_CHECK, this);
		showDoneChk.setStyle("font-weight:600;color:#475569;padding-left:10px;");
		confirmPanel.addActionListener(this); // OK / Cancel (tick / cross icon)

		respList = new Listbox();
		respList.setSclass("rq-list");
		Listhead head = new Listhead();
		head.appendChild(header("Select", "60px"));
		head.appendChild(header("SL No.", "70px"));
		head.appendChild(header("Business Partner Code", "170px"));
		head.appendChild(header("Business Partner", "260px"));
		head.appendChild(header("Address", null));
		head.appendChild(header("Status", "130px"));
		head.appendChild(header("Complete Response Line", "170px"));
		head.appendChild(header("Attachment", "170px"));
		respList.appendChild(head);
		ZKUpdateUtil.setHflex(respList, "1");
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
		addColumn(cols, "60px", null);
		addColumn(cols, null, "1");
		addColumn(cols, "90px", null);
		addColumn(cols, null, "2");
		addColumn(cols, "170px", null);
		Rows rows = new Rows();
		grid.appendChild(rows);
		card.appendChild(grid);

		Row r1 = new Row();
		rows.appendChild(r1);
		r1.appendChild(boldLabel("Organization"));
		r1.appendChild(orgEditor.getComponent());
		r1.appendChild(boldLabel("RFQ"));
		r1.appendChild(rfqCombo);
		r1.appendChild(boldLabel("RFQ Name"));
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

		Div listCard = new Div();
		listCard.setSclass("rq-card");
		listCard.setStyle("margin-top:0;");
		cwrap.appendChild(listCard);

		Label cap = new Label("Supplier Responses");
		cap.setSclass("rq-section");
		listCard.appendChild(cap);
		listCard.appendChild(respList);
		emptyLbl.setSclass("rq-empty");
		listCard.appendChild(emptyLbl);

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
		emptyLbl.setVisible(total == 0);
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

	/**
	 * Dropdown e shudhu oi RFQ gulo jegulor kono active response ekhono Complete hoy ni.
	 * (Shob RFQ dekhte chaile EXISTS er line ta muche din.)
	 */
	private void loadRfqList() {
		while (rfqCombo.getItemCount() > 0)
			rfqCombo.removeItemAt(0);
		rfqCombo.setValue("");
		rfqNameLabel.setValue("");
		clear(respList);
		setStats(0, 0);

		int orgId = toInt(orgEditor.getValue());
		String labelExpr = rfqHasDocNo ? "COALESCE(r.DocumentNo, r.Name)" : "r.Name";

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT r.C_RfQ_ID, ").append(labelExpr).append(" FROM C_RfQ r ");
		sql.append("WHERE r.IsActive='Y' AND r.AD_Client_ID=? ");
		if (orgId > 0)
			sql.append("AND r.AD_Org_ID=? ");
		// Incomplete response ache, ba Topic e emon active subscriber ache jar response ekhono toiri hoy ni
		sql.append("AND (EXISTS (SELECT 1 FROM C_RfQResponse x WHERE x.C_RfQ_ID=r.C_RfQ_ID ");
		sql.append("AND x.IsActive='Y' AND x.IsComplete='N') ");
		sql.append("OR EXISTS (SELECT 1 FROM C_RfQ_TopicSubscriber s WHERE s.C_RfQ_Topic_ID=r.C_RfQ_Topic_ID ");
		sql.append("AND s.IsActive='Y' AND NOT EXISTS (SELECT 1 FROM C_RfQResponse y ");
		sql.append("WHERE y.C_RfQ_ID=r.C_RfQ_ID AND y.C_BPartner_ID=s.C_BPartner_ID AND y.IsActive='Y')) ");
		if (showDoneChk.isChecked())
			sql.append("OR EXISTS (SELECT 1 FROM C_RfQResponse z WHERE z.C_RfQ_ID=r.C_RfQ_ID AND z.IsActive='Y') ");
		sql.append(") ");
		sql.append("ORDER BY r.C_RfQ_ID DESC");

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), null);
			int idx = 1;
			pstmt.setInt(idx++, Env.getAD_Client_ID(Env.getCtx()));
			if (orgId > 0)
				pstmt.setInt(idx++, orgId);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				Comboitem ci = rfqCombo.appendItem(nvl(rs.getString(2)));
				ci.setValue(Integer.valueOf(rs.getInt(1)));
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, sql.toString(), e);
			throw new AdempiereException(e);
		} finally {
			DB.close(rs, pstmt);
		}
	}

	/** Selected RFQ id (dropdown theke select, ba full label type kora) */
	private int currentRfqId() {
		Comboitem ci = rfqCombo.getSelectedItem();
		if (ci == null) {
			String t = rfqCombo.getValue();
			if (t != null && !t.trim().isEmpty()) {
				for (Comboitem it : rfqCombo.getItems()) {
					if (t.trim().equalsIgnoreCase(it.getLabel())) {
						rfqCombo.setSelectedItem(it);
						ci = it;
						break;
					}
				}
			}
		}
		return ci == null ? 0 : toInt(ci.getValue());
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

	private void loadResponses() {
		clear(respList);
		setStats(0, 0);
		int rfqId = currentRfqId();
		if (rfqId <= 0) {
			rfqNameLabel.setValue("");
			return;
		}
		ensureResponses(rfqId);
		String rfqName = DB.getSQLValueString(null, "SELECT Name FROM C_RfQ WHERE C_RfQ_ID=?", rfqId);
		rfqNameLabel.setValue(nvl(rfqName));

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT r.C_RfQResponse_ID, bp.Value, bp.Name, loc.Address1, loc.Address2, loc.City, r.IsComplete ");
		sql.append("FROM C_RfQResponse r ");
		sql.append("JOIN C_BPartner bp ON bp.C_BPartner_ID = r.C_BPartner_ID ");
		sql.append("LEFT JOIN C_BPartner_Location bl ON bl.C_BPartner_Location_ID = r.C_BPartner_Location_ID ");
		sql.append("LEFT JOIN C_Location loc ON loc.C_Location_ID = bl.C_Location_ID ");
		sql.append("WHERE r.C_RfQ_ID=? AND r.IsActive='Y' ");
		sql.append("ORDER BY r.C_RfQResponse_ID");

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), null);
			pstmt.setInt(1, rfqId);
			rs = pstmt.executeQuery();
			int sl = 0;
			int done = 0;
			while (rs.next()) {
				sl++;
				int respId = rs.getInt(1);
				String code = rs.getString(2);
				String name = rs.getString(3);
				String address = joinAddress(rs.getString(4), rs.getString(5), rs.getString(6));
				boolean complete = "Y".equals(rs.getString(7));
				if (complete)
					done++;
				respList.appendChild(newRow(respId, sl, code, name, address, complete));
			}
			setStats(sl, done);
		} catch (SQLException e) {
			log.log(Level.SEVERE, sql.toString(), e);
			throw new AdempiereException(e);
		} finally {
			DB.close(rs, pstmt);
		}
	}

	private Listitem newRow(final int respId, int sl, String code, String name, String address,
			boolean complete) {
		Listitem item = new Listitem();
		if (sl % 2 == 0)
			item.setStyle("background:#f8fafc;");

		// Select (read-only tick: complete hole tick)
		Checkbox chk = new Checkbox();
		chk.setChecked(complete);
		chk.setDisabled(true);
		Listcell c0 = new Listcell();
		c0.appendChild(chk);
		item.appendChild(c0);

		item.appendChild(new Listcell(String.valueOf(sl)));

		// Business Partner Code (link -> response popup)
		A link = new A(nvl(code));
		link.setStyle(LINK_STYLE);
		link.addEventListener(Events.ON_CLICK, e -> openResponse(respId));
		Listcell c2 = new Listcell();
		c2.appendChild(link);
		item.appendChild(c2);

		Listcell cName = new Listcell(nvl(name));
		cName.setStyle("font-weight:600;color:#0f2b46;");
		item.appendChild(cName);
		item.appendChild(new Listcell(nvl(address)));

		// Status badge
		Label badge = new Label(complete ? "Completed" : "Pending");
		badge.setStyle("display:inline-block;padding:3px 14px;border-radius:999px;font-size:12px;font-weight:600;"
				+ (complete ? "background:#dcfce7;color:#166534;" : "background:#fef3c7;color:#92400e;"));
		Listcell cStatus = new Listcell();
		cStatus.appendChild(badge);
		item.appendChild(cStatus);

		// Complete button
		Button completeBtn = new Button(complete ? "Completed" : "Complete");
		completeBtn.setStyle(complete ? BTN_DISABLED : BTN_PRIMARY);
		completeBtn.setDisabled(complete);
		completeBtn.addEventListener(Events.ON_CLICK, e -> completeResponse(respId));
		Listcell c5 = new Listcell();
		c5.appendChild(completeBtn);
		item.appendChild(c5);

		// Attachment button
		Button attachBtn = new Button("Attachment");
		attachBtn.setStyle(BTN_OUTLINE);
		attachBtn.addEventListener(Events.ON_CLICK, e -> openAttachment(respId));
		Listcell c6 = new Listcell();
		c6.appendChild(attachBtn);
		item.appendChild(c6);

		return item;
	}

	// --------------------------------------------------------------- actions

	private void openResponse(int respId) {
		try {
			WRFQResponseDialog dlg = new WRFQResponseDialog(respId, () -> loadResponses());
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
			loadResponses();
		} catch (Exception ex) {
			error(ex);
		}
	}

	/**
	 * Complete RFQ: only when every invited vendor has answered, every response
	 * is complete, and every product has a valid price in every response. On
	 * success the RFQ is pushed into the approval chain and an audit row is
	 * written to XX_APPROVAL_HISTORY.
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

			loadResponses();
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
		if ("AD_Org_ID".equals(evt.getPropertyName()))
			loadRfqList(); // org bodlale RFQ list notun kore
	}

	@Override
	public void onEvent(Event e) {
		try {
			Object c = e.getTarget();
			if (c == rfqCombo) {
				loadResponses();
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

	/** Reload RFQ list + grid, keeping the currently selected RFQ if it is still in the list. */
	private void reloadKeepingSelection() {
		int keep = currentRfqId();
		loadRfqList();
		if (keep > 0) {
			for (Comboitem it : rfqCombo.getItems()) {
				if (toInt(it.getValue()) == keep) {
					rfqCombo.setSelectedItem(it);
					break;
				}
			}
			loadResponses();
		}
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