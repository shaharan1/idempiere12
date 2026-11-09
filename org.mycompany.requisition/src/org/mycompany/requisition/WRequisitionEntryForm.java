package org.mycompany.requisition;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.model.MAttachment;
import org.compiere.model.MRequisition;
import org.compiere.model.MRequisitionLine;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.InputEvent;
import org.zkoss.zk.ui.event.OpenEvent;
import org.zkoss.zk.ui.event.UploadEvent;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Caption;
import org.zkoss.zul.Center;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.South;

/**
 * Requisition Form: header (Type, Location, Warehouse, Priority, dates, description,
 * attachment), line entry (Segment, Department, Parent Category, Sub-Category, Product,
 * Qty, Rate) and the list of lines added, then OK saves, Cancel closes.
 * <p>
 * Every drop-down / search field is a type-ahead combo: type a letter and the list shows
 * only the entries that contain it (Type, Location, Priority, Segment from memory;
 * Warehouse, Department, Categories, Product from the database, max 50 rows per search).
 * <p>
 * It saves a standard Drafted M_Requisition with M_RequisitionLine rows (through the model
 * classes), so the approval inbox (trigger on M_Requisition), "Create RFQ From PR" and the
 * rest of this plugin work on it unchanged. The extra fields live in plain columns created by
 * db/16_requisition_entry_form.sql:
 * <pre>
 *   M_Requisition.XX_Location          (Location)
 *   M_RequisitionLine.XX_Segment       (Segment)
 *   M_RequisitionLine.XX_Department_ID (Department = C_Activity)
 *   M_RequisitionLine.XX_DateRequired  (Date Required (Line))
 * </pre>
 * Type = the document type (C_DocType, base type Purchase Requisition), so the approval
 * matrix, which is per document type, applies. Priority = the standard Priority list.
 * Specification goes to the line description.
 */
public class WRequisitionEntryForm extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WRequisitionEntryForm.class);

	/** AD_Reference names seeded by db/16_requisition_entry_form.sql */
	static final String REF_LOCATION = "XX Requisition Location";
	static final String REF_SEGMENT = "XX Requisition Segment";
	/** standard "_PriorityRule" list */
	private static final int REF_PRIORITY = 154;

	/** max rows a type-ahead search brings back */
	private static final int SEARCH_LIMIT = 50;

	/** bump when the CSS below changes so open browsers take the new look */
	private static final String CSS_VERSION = "4";

	private final int clientId = Env.getAD_Client_ID(Env.getCtx());
	private final int orgId = Env.getAD_Org_ID(Env.getCtx());
	private final int userId = Env.getAD_User_ID(Env.getCtx());

	// ---- header
	private final Label lblOrg = new Label();
	private final Label lblUser = new Label();
	private Choice cbType;
	private Choice cbLocation;
	private Choice cbPriority;
	private Picker pWarehouse;
	private final Datebox dtDoc = new Datebox();
	private final Datebox dtRequired = new Datebox();
	private final Textbox txtDescription = new Textbox();
	private final Button btnAttach = new Button("Attachment");
	private final Label lblAttach = new Label();
	private final List<Media> attachments = new ArrayList<Media>();

	// ---- line entry
	private Choice cbSegment;
	private Picker pDept;
	private Picker pParent;
	private Picker pSub;
	private Picker pProduct;
	private final Textbox txtSpec = new Textbox();
	private final Datebox dtLine = new Datebox();
	private final Label lblUom = new Label();
	private final Label lblAvail = new Label("0");
	private final Decimalbox decQty = new Decimalbox();
	private final Decimalbox decRate = new Decimalbox();
	private final Button btnAdd = new Button("+  Add Product");

	// ---- lines
	private final Listbox lineList = new Listbox();
	private final List<Line> lines = new ArrayList<Line>();

	private final Button btnOk = new Button("\u2714  Save");
	private final Button btnCancel = new Button("\u2716  Cancel");

	/** one product line waiting to be saved */
	private static final class Line {
		String segVal = "";
		String segName = "";
		int deptId;
		String deptName = "";
		int productId;
		String code = "";
		String name = "";
		String uom = "";
		String spec = "";
		Timestamp dateReq;
		BigDecimal avail = BigDecimal.ZERO;
		BigDecimal qty = BigDecimal.ZERO;
		BigDecimal rate = BigDecimal.ZERO;
	}

	// =====================================================================
	// init
	// =====================================================================

	@Override
	protected void initForm() {
		org.mycompany.requisition.ui.XxTheme.wrap(this);
		String sc = getSclass();
		setSclass(sc == null || sc.isEmpty() ? "xx-req" : sc + " xx-req");
		installCss();
		try {
			buildUi();
			loadData();
			if (!columnExists("M_Requisition", "XX_Location") || !columnExists("M_RequisitionLine", "XX_Segment")
					|| !columnExists("M_RequisitionLine", "XX_Department_ID")
					|| !columnExists("M_RequisitionLine", "XX_DateRequired"))
				Messagebox.show("The extra requisition columns are missing.\nRun db/16_requisition_entry_form.sql, "
						+ "then reopen this form.", "Setup needed", Messagebox.OK, Messagebox.EXCLAMATION);
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			throw new AdempiereException(e);
		}
	}

	// =====================================================================
	// look (Bootstrap 5 style, scoped to .xx-req so the rest of iDempiere is untouched)
	// =====================================================================

	/**
	 * Bootstrap's own bootstrap.min.css cannot be loaded here: its global reset would
	 * restyle the whole iDempiere page. These rules use Bootstrap 5's class names,
	 * colours, radii, shadows and focus ring, but only below .xx-req.
	 */
	private static final String CSS = """
.xx-req { background:#f1f4f9; font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif; }
.xx-req .xx-card { background:#fff; border:1px solid #dee2e6; border-radius:.5rem; box-shadow:0 .125rem .25rem rgba(0,0,0,.075); margin:6px 10px 0; overflow:visible; }
.xx-req .xx-card-header { display:flex; align-items:center; padding:.25rem .75rem; background:#f8f9fa; border-bottom:1px solid #dee2e6; border-radius:.5rem .5rem 0 0; font-weight:600; font-size:.82rem; color:#212529; }
.xx-req .xx-card-header::before { content:""; width:4px; height:1.1em; background:#0d6efd; border-radius:2px; margin-right:.6rem; }
.xx-req .xx-card-body { padding:.5rem .75rem .6rem; }
.xx-req .xx-grid { display:grid; grid-template-columns:repeat(6,minmax(0,1fr)); gap:.4rem .6rem; text-align:left; }
.xx-req .xx-grid7 { grid-template-columns:repeat(7,minmax(0,1fr)); }
.xx-req .xx-span2 { grid-column:span 2; }
.xx-req .xx-span3 { grid-column:span 3; }
.xx-req .xx-span4 { grid-column:span 4; }
@media (max-width:1500px) { .xx-req .xx-grid, .xx-req .xx-grid7 { grid-template-columns:repeat(4,minmax(0,1fr)); } }
@media (max-width:1000px) { .xx-req .xx-grid, .xx-req .xx-grid7 { grid-template-columns:repeat(2,minmax(0,1fr)); } .xx-req .xx-span2, .xx-req .xx-span3, .xx-req .xx-span4 { grid-column:span 2; } }
@media (max-width:600px)  { .xx-req .xx-grid { grid-template-columns:minmax(0,1fr); } .xx-req .xx-span2, .xx-req .xx-span3, .xx-req .xx-span4 { grid-column:span 1; } }
.xx-req .xx-field { min-width:0; }
.xx-req .form-label { display:block; margin-bottom:.1rem; text-align:left; font-size:.72rem; line-height:1.2; font-weight:600; color:#495057; }
.xx-req .form-label.xx-req-mark::after { content:" *"; color:#dc3545; }
.xx-req .form-control, .xx-req .z-textbox, .xx-req .z-decimalbox, .xx-req .z-datebox-input, .xx-req .z-combobox-input {
  display:block; width:100%; min-height:26px; padding:.15rem .5rem; font-size:.8rem; line-height:1.4; color:#212529;
  background:#fff; border:1px solid #ced4da !important; border-radius:.375rem !important; box-shadow:none; }
.xx-req .z-datebox, .xx-req .z-combobox { width:100%; display:inline-flex; align-items:stretch; }
.xx-req .z-datebox-input, .xx-req .z-combobox-input { flex:1 1 auto; min-width:0; border-top-right-radius:0 !important; border-bottom-right-radius:0 !important; }
.xx-req .z-datebox-button, .xx-req .z-combobox-button { display:flex; align-items:center; justify-content:center; width:26px; border:1px solid #ced4da !important; border-left:0 !important; border-radius:0 .375rem .375rem 0 !important; background:#e9ecef; color:#495057; cursor:pointer; }
.xx-req .z-datebox-button:hover, .xx-req .z-combobox-button:hover { background:#dde1e5; }
.xx-req .z-textbox:focus, .xx-req .z-decimalbox:focus, .xx-req .z-datebox-input:focus, .xx-req .z-combobox-input:focus {
  border-color:#86b7fe !important; box-shadow:0 0 0 .25rem rgba(13,110,253,.25) !important; outline:0; }
.xx-req .xx-readonly { background:#e9ecef; display:flex; align-items:center; min-height:26px; }
.xx-req .xx-readonly .z-label { font-size:.8rem; color:#495057; }
.xx-req .xx-attach-note { display:block; margin-top:.25rem; font-size:.75rem; color:#6c757d; }
.z-combobox-popup, .z-combobox-content { border:1px solid #ced4da !important; border-radius:.375rem !important; box-shadow:0 .5rem 1rem rgba(0,0,0,.15) !important; }
.z-comboitem { padding:.25rem .6rem !important; font-size:.8rem; }
.z-comboitem-over, .z-comboitem-over-seld { background:#e7f1ff !important; }
.z-comboitem-selected { background:#cfe2ff !important; }
.xx-form.xx-req button.z-button.btn { display:inline-flex; align-items:center; justify-content:center; padding:.15rem .7rem; min-height:26px; font-size:.8rem; font-weight:600; line-height:1.5; border-radius:.375rem !important; text-shadow:none !important; transform:none; transition:background-color .15s,border-color .15s,box-shadow .15s; }
.xx-form.xx-req button.z-button.btn:hover { filter:none; transform:none; }
.xx-form.xx-req button.z-button.btn:focus { box-shadow:0 0 0 .25rem rgba(13,110,253,.35); outline:0; }
.xx-form.xx-req button.z-button.btn-block { width:100%; }
.xx-form.xx-req button.z-button.btn-sm { min-height:26px; padding:.1rem .5rem; font-size:.75rem; }
.xx-form.xx-req button.z-button.btn-primary { background:#0d6efd !important; border:1px solid #0d6efd !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn-primary:hover { background:#0b5ed7 !important; border-color:#0a58ca !important; }
.xx-form.xx-req button.z-button.btn-success { background:#198754 !important; border:1px solid #198754 !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn-success:hover { background:#157347 !important; border-color:#146c43 !important; }
.xx-form.xx-req button.z-button.btn-danger { background:#dc3545 !important; border:1px solid #dc3545 !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn-danger:hover { background:#bb2d3b !important; border-color:#b02a37 !important; }
.xx-form.xx-req button.z-button.btn-outline-primary { background:#fff !important; border:1px solid #0d6efd !important; color:#0d6efd !important; }
.xx-form.xx-req button.z-button.btn-outline-primary:hover { background:#0d6efd !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn-outline-danger { background:#fff !important; border:1px solid #dc3545 !important; color:#dc3545 !important; box-shadow:none !important; }
.xx-form.xx-req button.z-button.btn-outline-danger:hover { background:#dc3545 !important; color:#fff !important; }
.xx-req .z-listbox { min-height:280px; }
.xx-req .xx-main { background:#f1f4f9; }
.xx-req .xx-card-header + .z-listbox, .xx-req .xx-card > .z-listbox { margin:0; }
.xx-req .xx-footer { position:sticky; bottom:0; z-index:5; display:flex; justify-content:flex-end; gap:.5rem; padding:.4rem .75rem; margin:6px 10px; background:#fff; border:1px solid #dee2e6; border-radius:.5rem; box-shadow:0 .125rem .25rem rgba(0,0,0,.075); }
""";

	/**
	 * Date fields of this form: a click in the box, or Enter while it has the focus, opens the
	 * calendar (no need to hit the small icon). One document-level listener, installed once.
	 */
	private static final String DATE_JS = "(function(){if(window.__xxDateV==='2')return;window.__xxDateV='2';"
			+ "function openIt(inp){var w=null;try{w=zk.Widget.$(inp);}catch(x){}if(!w)return;"
			+ "if(w.isOpen&&w.isOpen())return;"
			+ "if(typeof w.open==='function'){w.open();return;}"
			+ "var b=w.$n&&w.$n('btn');if(b)b.click();}"
			+ "function mine(t){return t&&t.classList&&t.classList.contains('z-datebox-input')&&t.closest&&t.closest('.xx-req');}"
			+ "document.addEventListener('click',function(e){if(mine(e.target))openIt(e.target);},true);"
			+ "document.addEventListener('keydown',function(e){if(e.key==='Enter'&&mine(e.target)){"
			+ "var w=null;try{w=zk.Widget.$(e.target);}catch(x){}"
			+ "if(w&&!(w.isOpen&&w.isOpen())){e.preventDefault();e.stopPropagation();openIt(e.target);}}},true);})();";

	private void installCss() {
		if (Executions.getCurrent() == null)
			return;
		try {
			String css = CSS.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n");
			Clients.evalJavaScript("(function(){var V='" + CSS_VERSION + "';var s=document.getElementById('xx-req-css');"
					+ "if(!s){s=document.createElement('style');s.id='xx-req-css';document.head.appendChild(s);}"
					+ "if(s.getAttribute('data-v')!==V){s.setAttribute('data-v',V);s.textContent='" + css + "';}})();");
			Clients.evalJavaScript(DATE_JS);
		} catch (Exception e) {
			log.warning("requisition css not installed: " + e);
		}
	}

	// =====================================================================
	// UI
	// =====================================================================

	private void buildUi() {
		// one scrolling column (no Borderlayout): when the browser is zoomed in or the window is
		// small nothing is squeezed out of view, the page just scrolls and Save/Cancel stay at the bottom
		Div main = new Div();
		main.setSclass("xx-main");
		main.setStyle("height:100%;overflow:auto;");
		appendChild(main);
		ZKUpdateUtil.setWidth(this, "99%");
		ZKUpdateUtil.setHeight(this, "100%");

		cbType = new Choice();
		cbLocation = new Choice();
		cbPriority = new Choice();
		cbSegment = new Choice();

		pWarehouse = new Picker("Warehouse",
				"SELECT M_Warehouse_ID, Value, Name FROM M_Warehouse WHERE IsActive='Y' AND AD_Client_ID=" + clientId,
				"Name", false, null);
		pDept = new Picker("Department",
				"SELECT C_Activity_ID, Value, Name FROM C_Activity WHERE IsActive='Y' AND IsSummary='N' "
						+ "AND AD_Client_ID IN (0," + clientId + ")",
				"Name", false, null);
		pParent = new Picker("Parent Category",
				"SELECT M_Product_Category_ID, Value, Name FROM M_Product_Category WHERE IsActive='Y' "
						+ "AND AD_Client_ID IN (0," + clientId + ")",
				"Name", false,
				p -> " AND (M_Product_Category_Parent_ID IS NULL OR M_Product_Category_Parent_ID=M_Product_Category_ID)");
		pSub = new Picker("Sub-Category",
				"SELECT M_Product_Category_ID, Value, Name FROM M_Product_Category WHERE IsActive='Y' "
						+ "AND AD_Client_ID IN (0," + clientId + ")",
				"Name", false, p -> {
					if (pParent.id > 0) {
						p.add(Integer.valueOf(pParent.id));
						return " AND M_Product_Category_Parent_ID=? AND M_Product_Category_ID<>M_Product_Category_Parent_ID";
					}
					return " AND M_Product_Category_Parent_ID IS NOT NULL "
							+ "AND M_Product_Category_Parent_ID<>M_Product_Category_ID";
				});
		pProduct = new Picker("Product",
				"SELECT M_Product_ID, Value, Name FROM M_Product WHERE IsActive='Y' AND IsSummary='N' "
						+ "AND AD_Client_ID IN (0," + clientId + ")",
				"Name", true, p -> {
					if (pSub.id > 0) {
						p.add(Integer.valueOf(pSub.id));
						return " AND M_Product_Category_ID=?";
					}
					if (pParent.id > 0) {
						p.add(Integer.valueOf(pParent.id));
						return " AND M_Product_Category_ID IN (WITH RECURSIVE t AS ("
								+ "SELECT M_Product_Category_ID FROM M_Product_Category WHERE M_Product_Category_ID=? "
								+ "UNION SELECT c.M_Product_Category_ID FROM M_Product_Category c "
								+ "JOIN t ON c.M_Product_Category_Parent_ID=t.M_Product_Category_ID) "
								+ "SELECT M_Product_Category_ID FROM t)";
					}
					return "";
				});

		main.appendChild(buildHeader());
		main.appendChild(buildLineEntry());

		buildLineList();
		main.appendChild(card("Requisition Line", lineList));

		Div foot = new Div();
		foot.setSclass("xx-footer");
		btnOk.setSclass("btn btn-success");
		btnCancel.setSclass("btn btn-danger");
		foot.appendChild(btnOk);
		foot.appendChild(btnCancel);
		main.appendChild(foot);

		wireEvents();
	}

	/** header card: 4 fields per row, same order as the screen */
	private Div buildHeader() {
		Div g = new Div();
		g.setSclass("xx-grid");
		lblOrg.setValue("");
		g.appendChild(field("Organization", false, readonly(lblOrg), 1));
		g.appendChild(field("Type", true, cbType.box, 1));
		g.appendChild(field("Location", true, cbLocation.box, 1));
		g.appendChild(field("Warehouse", false, pWarehouse.box, 1));
		g.appendChild(field("User", false, readonly(lblUser), 1));
		g.appendChild(field("Priority", true, cbPriority.box, 1));
		ZKUpdateUtil.setWidth(dtDoc, "100%");
		g.appendChild(field("Document Date", true, dtDoc, 1));
		ZKUpdateUtil.setWidth(dtRequired, "100%");
		g.appendChild(field("Date Required", true, dtRequired, 1));

		txtDescription.setMultiline(true);
		txtDescription.setRows(1);
		txtDescription.setSclass("form-control");
		txtDescription.setStyle("resize:none;");
		ZKUpdateUtil.setWidth(txtDescription, "100%");
		g.appendChild(field("Description", false, txtDescription, 3));

		btnAttach.setSclass("btn btn-outline-primary btn-block");
		lblAttach.setSclass("xx-attach-note");
		updateAttachLabel();
		Div att = new Div();
		att.appendChild(btnAttach);
		att.appendChild(lblAttach);
		g.appendChild(field("Attachment", false, att, 1));

		return card("Requisition", g);
	}

	/** line entry card (Segment ... Add Product) */
	private Div buildLineEntry() {
		Div g = new Div();
		g.setSclass("xx-grid xx-grid7");
		// row 0 (7 columns)
		g.appendChild(field("Segment", true, cbSegment.box, 1));
		g.appendChild(field("Department", true, pDept.box, 1));
		g.appendChild(field("Parent Category", true, pParent.box, 1));
		g.appendChild(field("Sub-Category", false, pSub.box, 1));
		g.appendChild(field("Product", true, pProduct.box, 1));
		g.appendChild(field("UOM", false, readonly(lblUom), 1));
		g.appendChild(field("Available Qty", false, readonly(lblAvail), 1));
		// row 1 (2 + 1 + 1 + 1 + 2 = 7)
		txtSpec.setMultiline(true);
		txtSpec.setRows(1);
		txtSpec.setSclass("form-control");
		txtSpec.setStyle("resize:none;");
		ZKUpdateUtil.setWidth(txtSpec, "100%");
		g.appendChild(field("Specification", false, txtSpec, 2));
		ZKUpdateUtil.setWidth(dtLine, "100%");
		g.appendChild(field("Date Required (Line)", false, dtLine, 1));
		ZKUpdateUtil.setWidth(decQty, "100%");
		decQty.setSclass("form-control");
		g.appendChild(field("Qty", true, decQty, 1));
		ZKUpdateUtil.setWidth(decRate, "100%");
		decRate.setSclass("form-control");
		g.appendChild(field("Rate", false, decRate, 1));
		btnAdd.setSclass("btn btn-primary btn-block");
		g.appendChild(field("\u00A0", false, btnAdd, 2));

		return card("Add Product", g);
	}

	private void buildLineList() {
		Listhead head = new Listhead();
		String[][] cols = { { "Line", "55px" }, { "Segment", "130px" }, { "Department", "140px" },
				{ "Product Code", "120px" }, { "Product Name", null }, { "UOM", "60px" },
				{ "Specification", "170px" }, { "Date Required", "100px" }, { "Available", "80px" },
				{ "Qty", "80px" }, { "Rate", "80px" }, { "Amount", "95px" }, { "", "50px" } };
		for (String[] c : cols) {
			Listheader h = new Listheader(c[0]);
			if (c[1] != null)
				h.setWidth(c[1]);
			else
				h.setHflex("1");
			head.appendChild(h);
		}
		lineList.appendChild(head);
		ZKUpdateUtil.setWidth(lineList, "100%");
		lineList.setHeight("340px");
	}

	// ---- small layout helpers

	/** card with a title bar */
	private Div card(String title, Component body) {
		Div card = new Div();
		card.setSclass("xx-card");
		Div hd = new Div();
		hd.setSclass("xx-card-header");
		hd.appendChild(new Label(title));
		Div bd = new Div();
		bd.setSclass("xx-card-body");
		bd.appendChild(body);
		card.appendChild(hd);
		card.appendChild(bd);
		return card;
	}

	/** Bootstrap style form group: label on top, input below */
	private Div field(String text, boolean required, Component c, int span) {
		Div d = new Div();
		d.setSclass("xx-field" + (span == 2 ? " xx-span2" : span == 3 ? " xx-span3" : ""));
		Label l = new Label(text);
		l.setSclass("form-label" + (required ? " xx-req-mark" : ""));
		d.appendChild(l);
		d.appendChild(c);
		return d;
	}

	/** a grey read-only box that holds a label */
	private Div readonly(Label l) {
		Div d = new Div();
		d.setSclass("form-control xx-readonly");
		d.appendChild(l);
		return d;
	}

	// =====================================================================
	// type-ahead combos
	// =====================================================================

	/** shared setup of a type-ahead Combobox */
	private static Combobox newCombo(String placeholder) {
		Combobox cb = new Combobox();
		cb.setAutodrop(true); // open the list while typing
		cb.setAutocomplete(false); // we filter ourselves ("contains", not "starts with")
		cb.setButtonVisible(true);
		cb.setPlaceholder(placeholder);
		ZKUpdateUtil.setWidth(cb, "100%");
		return cb;
	}

	private static void clearItems(Combobox cb) {
		while (cb.getItemCount() > 0)
			cb.removeItemAt(0);
	}

	/** runs a little script in the browser (open/close a list); cosmetic, never throws */
	private static void js(String script) {
		try {
			if (Executions.getCurrent() != null)
				Clients.evalJavaScript(script);
		} catch (Exception e) {
			// ignore
		}
	}

	private static void closeList(Combobox cb) {
		js("(function(){var w=zk.Widget.$('#" + cb.getUuid() + "');if(w&&w.close)w.close();})();");
	}

	/** drop-down with a small fixed list (value + name); type a letter and only names starting with it are listed */
	private final class Choice {
		final Combobox box = newCombo("Type or select...");
		private final List<String[]> all = new ArrayList<String[]>();
		String value = "";
		String name = "";
		private String typed = "";

		Choice() {
			box.addEventListener(Events.ON_CHANGING, e -> {
				typed = ((InputEvent) e).getValue();
				show(typed);
			});
			box.addEventListener(Events.ON_OPEN, e -> {
				if (((OpenEvent) e).isOpen())
					show(typed);
			});
			box.addEventListener(Events.ON_FOCUS, e -> box.select());
			box.addEventListener(Events.ON_SELECT, e -> {
				Comboitem it = box.getSelectedItem();
				if (it == null)
					return;
				Object v = it.getValue();
				value = v == null ? "" : String.valueOf(v);
				name = it.getLabel();
				typed = "";
			});
			// leaving the field or pressing Enter: take what was typed (first name that starts with it)
			box.addEventListener(Events.ON_CHANGE, e -> resolve(box.getValue()));
			box.addEventListener(Events.ON_OK, e -> resolve(box.getValue()));
		}

		private void resolve(String text) {
			String t = text == null ? "" : text.trim();
			typed = "";
			if (t.isEmpty()) {
				value = "";
				name = "";
				box.setValue("");
				return;
			}
			if (!value.isEmpty() && t.equals(name))
				return;
			String[] hit = null;
			for (String[] a : all)
				if (a[1].equalsIgnoreCase(t)) {
					hit = a;
					break;
				}
			if (hit == null) {
				String lt = t.toLowerCase();
				for (String[] a : all)
					if (a[1].toLowerCase().startsWith(lt)) {
						hit = a;
						break;
					}
			}
			if (hit != null) {
				value = hit[0];
				name = hit[1];
			}
			box.setValue(name); // no match: back to the last good value
			closeList(box);
		}

		void add(String v, String n) {
			all.add(new String[] { v == null ? "" : v, n == null ? "" : n });
		}

		int count() {
			return all.size();
		}

		void selectAt(int i) {
			String[] a = all.get(i);
			value = a[0];
			name = a[1];
			box.setValue(name);
		}

		void reset() {
			value = "";
			name = "";
			typed = "";
			box.setValue("");
		}

		private void show(String q) {
			clearItems(box);
			String s = q == null ? "" : q.trim().toLowerCase();
			int n = 0;
			for (String[] a : all) {
				if (!s.isEmpty() && !a[1].toLowerCase().startsWith(s))
					continue;
				Comboitem it = box.appendItem(a[1]);
				it.setValue(a[0]);
				if (++n >= 200)
					break;
			}
		}
	}

	private interface Filter {
		/** extra " AND ..." for the query; add its ? values to params */
		String where(List<Object> params);
	}

	/** drop-down backed by a query; type a letter and only Value/Name starting with it are listed (max SEARCH_LIMIT) */
	private final class Picker {
		final Combobox box = newCombo("Type to search...");
		private final String title;
		private final String select;
		private final String order;
		private final boolean showCode;
		private final Filter filter;
		int id;
		String code = "";
		String name = "";
		Runnable onChange;
		private String typed = "";

		Picker(String title, String select, String order, boolean showCode, Filter filter) {
			this.title = title;
			this.select = select;
			this.order = order;
			this.showCode = showCode;
			this.filter = filter;
			box.setPlaceholder("Type to search " + title.toLowerCase() + "...");
			box.addEventListener(Events.ON_CHANGING, e -> {
				typed = ((InputEvent) e).getValue();
				load(typed);
			});
			box.addEventListener(Events.ON_OPEN, e -> {
				if (((OpenEvent) e).isOpen())
					load(typed);
			});
			box.addEventListener(Events.ON_FOCUS, e -> box.select());
			box.addEventListener(Events.ON_SELECT, e -> {
				Comboitem it = box.getSelectedItem();
				if (it == null)
					return;
				Object o = it.getValue();
				if (o instanceof Object[]) {
					Object[] v = (Object[]) o;
					typed = "";
					if (((Integer) v[0]).intValue() != id)
						set(((Integer) v[0]).intValue(), (String) v[1], (String) v[2], true);
				}
			});
			box.addEventListener(Events.ON_CHANGE, e -> resolve(box.getValue()));
			box.addEventListener(Events.ON_OK, e -> resolve(box.getValue()));
		}

		/** Enter / leaving the field: first row that starts with the typed text */
		private void resolve(String text) {
			String t = text == null ? "" : text.trim();
			typed = "";
			if (t.isEmpty()) {
				if (id > 0)
					clear(true);
				box.setValue("");
				return;
			}
			if (id > 0 && t.equals(display()))
				return;
			int dash = t.indexOf(" - ");
			String q = dash > 0 ? t.substring(0, dash) : t;
			List<Object[]> rows = query(q, 1);
			if (!rows.isEmpty()) {
				Object[] v = rows.get(0);
				if (((Integer) v[0]).intValue() != id)
					set(((Integer) v[0]).intValue(), (String) v[1], (String) v[2], true);
			}
			box.setValue(display()); // no match: back to the last good value
			closeList(box);
		}

		private String display() {
			return id <= 0 ? "" : (showCode ? code + " - " + name : name);
		}

		void set(int newId, String newCode, String newName, boolean fire) {
			id = newId;
			code = newCode == null ? "" : newCode;
			name = newName == null ? "" : newName;
			typed = "";
			box.setValue(display());
			if (fire && onChange != null)
				onChange.run();
		}

		void clear(boolean fire) {
			set(0, "", "", fire);
		}

		private List<Object[]> query(String q, int limit) {
			List<Object[]> out = new ArrayList<Object[]>();
			List<Object> params = new ArrayList<Object>();
			String extra = filter == null ? "" : filter.where(params);
			String pat = (q == null ? "" : q.trim().toUpperCase()) + "%";
			params.add(pat);
			params.add(pat);
			String sql = select + extra + " AND (UPPER(Value) LIKE ? OR UPPER(Name) LIKE ?) ORDER BY " + order
					+ " LIMIT " + limit;
			try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
				for (int i = 0; i < params.size(); i++) {
					Object o = params.get(i);
					if (o instanceof Integer)
						ps.setInt(i + 1, ((Integer) o).intValue());
					else
						ps.setString(i + 1, String.valueOf(o));
				}
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next())
						out.add(new Object[] { Integer.valueOf(rs.getInt(1)), rs.getString(2), rs.getString(3) });
				}
			} catch (Exception ex) {
				log.log(Level.SEVERE, sql, ex);
			}
			return out;
		}

		private void load(String q) {
			clearItems(box);
			for (Object[] v : query(q, SEARCH_LIMIT)) {
				String c = (String) v[1];
				String n = (String) v[2];
				Comboitem it = box.appendItem(showCode ? c + " - " + n : n);
				it.setValue(v);
			}
		}
	}

	// =====================================================================
	// data
	// =====================================================================

	private void loadData() {
		lblOrg.setValue(orgId > 0 ? str("SELECT Name FROM AD_Org WHERE AD_Org_ID=" + orgId) : "*");
		lblUser.setValue(str("SELECT Name FROM AD_User WHERE AD_User_ID=" + userId));

		fill(cbType, "SELECT C_DocType_ID, Name FROM C_DocType WHERE DocBaseType='POR' AND IsActive='Y' "
				+ "AND AD_Client_ID=" + clientId + " ORDER BY Name");
		fill(cbLocation, "SELECT l.Value, l.Name FROM AD_Ref_List l JOIN AD_Reference r "
				+ "ON r.AD_Reference_ID=l.AD_Reference_ID WHERE r.Name='" + REF_LOCATION
				+ "' AND l.IsActive='Y' ORDER BY l.AD_Ref_List_ID");
		fill(cbSegment, "SELECT l.Value, l.Name FROM AD_Ref_List l JOIN AD_Reference r "
				+ "ON r.AD_Reference_ID=l.AD_Reference_ID WHERE r.Name='" + REF_SEGMENT
				+ "' AND l.IsActive='Y' ORDER BY l.Name");
		fill(cbPriority, "SELECT Value, Name FROM AD_Ref_List WHERE AD_Reference_ID=" + REF_PRIORITY
				+ " AND IsActive='Y' ORDER BY CASE Value WHEN '3' THEN 1 WHEN '5' THEN 2 WHEN '7' THEN 3 "
				+ "WHEN '9' THEN 4 WHEN '1' THEN 5 ELSE 6 END");

		Date today = new Date();
		dtDoc.setValue(today);
		// a document type that is the only one is the obvious choice
		if (cbType.count() == 1)
			cbType.selectAt(0);
	}

	private void fill(Choice c, String sql) {
		try (PreparedStatement ps = DB.prepareStatement(sql, null); ResultSet rs = ps.executeQuery()) {
			while (rs.next())
				c.add(rs.getString(1), rs.getString(2));
		} catch (Exception e) {
			log.log(Level.SEVERE, sql, e);
		}
	}

	private String str(String sql) {
		String s = DB.getSQLValueString(null, sql);
		return s == null ? "" : s;
	}

	private boolean columnExists(String table, String column) {
		return DB.getSQLValue(null, "SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)", table, column) > 0;
	}

	private BigDecimal availableQty(int productId, int warehouseId) {
		String sql = "SELECT COALESCE(SUM(s.QtyOnHand),0) FROM M_StorageOnHand s "
				+ "JOIN M_Locator l ON l.M_Locator_ID=s.M_Locator_ID WHERE s.M_Product_ID=?"
				+ (warehouseId > 0 ? " AND l.M_Warehouse_ID=?" : "");
		BigDecimal v = warehouseId > 0 ? DB.getSQLValueBD(null, sql, productId, warehouseId)
				: DB.getSQLValueBD(null, sql, productId);
		return v == null ? BigDecimal.ZERO : v;
	}

	// =====================================================================
	// events
	// =====================================================================

	private void wireEvents() {
		pParent.onChange = () -> {
			pSub.clear(false);
			pProduct.clear(false);
			productChanged();
		};
		pSub.onChange = () -> {
			pProduct.clear(false);
			productChanged();
		};
		pProduct.onChange = () -> {
			productChanged();
			fillCategoryFromProduct();
			fillPrice();
		};
		pWarehouse.onChange = this::productChanged;

		btnAdd.addEventListener(Events.ON_CLICK, e -> addLine());
		btnOk.addEventListener(Events.ON_CLICK, e -> save());
		btnCancel.addEventListener(Events.ON_CLICK, e -> close());
		btnAttach.addEventListener(Events.ON_CLICK, e -> openAttachments());
	}

	/** UOM and Available Qty follow the product (and the warehouse) */
	private void productChanged() {
		if (pProduct.id <= 0) {
			lblUom.setValue("");
			lblAvail.setValue("0");
			return;
		}
		lblUom.setValue(str("SELECT u.Name FROM M_Product p JOIN C_UOM u ON u.C_UOM_ID=p.C_UOM_ID "
				+ "WHERE p.M_Product_ID=" + pProduct.id));
		lblAvail.setValue(availableQty(pProduct.id, pWarehouse.id).stripTrailingZeros().toPlainString());
	}

	/**
	 * Rate follows the product: last purchase order price of the product (M_Product_PO.PriceLastPO),
	 * else the price of its last purchase order line, else M_Product_PO.PricePO, else the price list
	 * (default purchase price list, newest valid version). Still editable.
	 */
	private void fillPrice() {
		if (pProduct.id <= 0)
			return;
		BigDecimal p = firstPositive(
				"SELECT PriceLastPO FROM M_Product_PO WHERE M_Product_ID=? AND IsActive='Y' AND COALESCE(PriceLastPO,0)>0 "
						+ "ORDER BY IsCurrentVendor DESC, Updated DESC LIMIT 1",
				"SELECT ol.PriceActual FROM C_OrderLine ol JOIN C_Order o ON o.C_Order_ID=ol.C_Order_ID "
						+ "WHERE ol.M_Product_ID=? AND o.IsSOTrx='N' AND o.DocStatus IN ('CO','CL') AND ol.PriceActual>0 "
						+ "ORDER BY o.DateOrdered DESC, ol.C_OrderLine_ID DESC LIMIT 1",
				"SELECT PricePO FROM M_Product_PO WHERE M_Product_ID=? AND IsActive='Y' AND COALESCE(PricePO,0)>0 "
						+ "ORDER BY IsCurrentVendor DESC, Updated DESC LIMIT 1",
				"SELECT pp.PriceStd FROM M_ProductPrice pp JOIN M_PriceList_Version v "
						+ "ON v.M_PriceList_Version_ID=pp.M_PriceList_Version_ID JOIN M_PriceList pl "
						+ "ON pl.M_PriceList_ID=v.M_PriceList_ID WHERE pp.M_Product_ID=? AND pp.IsActive='Y' "
						+ "AND v.IsActive='Y' AND pl.IsActive='Y' AND pl.IsSOPriceList='N' AND v.ValidFrom<=now() "
						+ "AND pl.AD_Client_ID IN (0," + clientId + ") AND COALESCE(pp.PriceStd,0)>0 "
						+ "ORDER BY pl.IsDefault DESC, v.ValidFrom DESC LIMIT 1");
		decRate.setValue(p);
	}

	private BigDecimal firstPositive(String... sqls) {
		for (String sql : sqls) {
			try {
				BigDecimal v = DB.getSQLValueBD(null, sql, pProduct.id);
				if (v != null && v.signum() > 0)
					return v;
			} catch (Exception e) {
				log.log(Level.WARNING, sql, e);
			}
		}
		return null;
	}

	// =====================================================================
	// attachments pop-up
	// =====================================================================

	private void updateAttachLabel() {
		lblAttach.setValue(attachments.isEmpty() ? "No file attached" : attachments.size() + " file(s) attached");
	}

	private static String sizeOf(Media m) {
		long n;
		try {
			n = m.isBinary() ? m.getByteData().length : m.getStringData().length();
		} catch (Exception e) {
			return "";
		}
		if (n < 1024)
			return n + " B";
		if (n < 1024 * 1024)
			return (n + 512) / 1024 + " KB";
		return String.format("%.1f MB", n / (1024.0 * 1024.0));
	}

	/** pop-up: add files and see (or remove) the ones already attached */
	private void openAttachments() {
		final org.adempiere.webui.component.Window win = new org.adempiere.webui.component.Window();
		win.setTitle("Attachment");
		win.setWidth("560px");
		win.setClosable(true);
		win.setAttribute(org.adempiere.webui.component.Window.MODE_KEY, org.adempiere.webui.component.Window.MODE_HIGHLIGHTED);
		org.mycompany.requisition.ui.XxTheme.apply(win);

		Div body = new Div();
		body.setStyle("padding:12px;");
		final Listbox lb = new Listbox();
		lb.setHeight("240px");
		Listhead lh = new Listhead();
		Listheader h0 = new Listheader("#");
		h0.setWidth("40px");
		lh.appendChild(h0);
		Listheader h1 = new Listheader("File name");
		h1.setHflex("1");
		lh.appendChild(h1);
		Listheader h2 = new Listheader("Size");
		h2.setWidth("90px");
		lh.appendChild(h2);
		Listheader h3 = new Listheader("");
		h3.setWidth("60px");
		lh.appendChild(h3);
		lb.appendChild(lh);

		final Label count = new Label();
		final Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> {
			while (lb.getItemCount() > 0)
				lb.removeItemAt(0);
			int n = 0;
			for (final Media m : attachments) {
				n++;
				Listitem li = new Listitem();
				li.appendChild(new Listcell(String.valueOf(n)));
				li.appendChild(new Listcell(m.getName()));
				li.appendChild(new Listcell(sizeOf(m)));
				Listcell rm = new Listcell();
				Button x = new Button("✖");
				x.setSclass("btn btn-sm btn-outline-danger");
				x.addEventListener(Events.ON_CLICK, ev -> {
					attachments.remove(m);
					refresh[0].run();
				});
				rm.appendChild(x);
				li.appendChild(rm);
				lb.appendChild(li);
			}
			count.setValue(attachments.isEmpty() ? "No file attached" : attachments.size() + " file(s) attached");
			updateAttachLabel();
		};

		Button add = new Button("+  Add File");
		add.setSclass("btn btn-primary");
		add.setUpload("true,multiple=true");
		add.addEventListener(Events.ON_UPLOAD, ev -> {
			if (ev instanceof UploadEvent) {
				Media[] ms = ((UploadEvent) ev).getMedias();
				if (ms != null)
					for (Media m : ms)
						if (m != null)
							attachments.add(m);
				refresh[0].run();
			}
		});
		Button done = new Button("Close");
		done.setSclass("btn btn-danger");
		done.addEventListener(Events.ON_CLICK, ev -> win.detach());

		Div bar = new Div();
		bar.setStyle("display:flex;align-items:center;gap:10px;margin-bottom:8px;");
		bar.appendChild(add);
		bar.appendChild(count);
		Div foot = new Div();
		foot.setStyle("text-align:right;margin-top:10px;");
		foot.appendChild(done);

		body.appendChild(bar);
		body.appendChild(lb);
		body.appendChild(foot);
		win.appendChild(body);
		refresh[0].run();
		org.adempiere.webui.apps.AEnv.showCenterScreen(win);
	}

	/** a product picked first fills its categories */
	private void fillCategoryFromProduct() {
		if (pProduct.id <= 0 || pParent.id > 0)
			return;
		String sql = "SELECT c.M_Product_Category_ID, c.Value, c.Name, p.M_Product_Category_ID, p.Value, p.Name "
				+ "FROM M_Product pr JOIN M_Product_Category c ON c.M_Product_Category_ID=pr.M_Product_Category_ID "
				+ "LEFT JOIN M_Product_Category p ON p.M_Product_Category_ID=c.M_Product_Category_Parent_ID "
				+ "AND p.M_Product_Category_ID<>c.M_Product_Category_ID WHERE pr.M_Product_ID=?";
		try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
			ps.setInt(1, pProduct.id);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					if (rs.getInt(4) > 0) {
						pParent.set(rs.getInt(4), rs.getString(5), rs.getString(6), false);
						pSub.set(rs.getInt(1), rs.getString(2), rs.getString(3), false);
					} else {
						pParent.set(rs.getInt(1), rs.getString(2), rs.getString(3), false);
					}
				}
			}
		} catch (Exception e) {
			log.log(Level.WARNING, e.getMessage(), e);
		}
	}

	private void close() {
		try {
			SessionManager.getAppDesktop().closeActiveWindow();
		} catch (Exception e) {
			resetForm();
		}
	}

	// =====================================================================
	// lines
	// =====================================================================

	// (the old Listbox version used String.valueOf(item.getValue()); ZK's generic getValue()
	// made Java pick valueOf(char[]) -> ClassCastException. Choice keeps plain Strings.)
	private String selValue(Choice c) {
		return c.value == null ? "" : c.value;
	}

	private String selName(Choice c) {
		return c.name == null ? "" : c.name;
	}

	private Timestamp ts(Datebox d) {
		Date v = d.getValue();
		return v == null ? null : new Timestamp(v.getTime());
	}

	private void warn(String msg) {
		Messagebox.show(msg, "Requisition", Messagebox.OK, Messagebox.EXCLAMATION);
	}

	private void addLine() {
		if (selValue(cbSegment).length() == 0) {
			warn("Segment is required.");
			return;
		}
		if (pDept.id <= 0) {
			warn("Department is required.");
			return;
		}
		if (pParent.id <= 0) {
			warn("Parent Category is required.");
			return;
		}
		if (pProduct.id <= 0) {
			warn("Product is required.");
			return;
		}
		BigDecimal qty = decQty.getValue();
		if (qty == null || qty.signum() <= 0) {
			warn("Qty must be greater than 0.");
			return;
		}
		Line l = new Line();
		l.segVal = selValue(cbSegment);
		l.segName = selName(cbSegment);
		l.deptId = pDept.id;
		l.deptName = pDept.name;
		l.productId = pProduct.id;
		l.code = pProduct.code;
		l.name = pProduct.name;
		l.uom = lblUom.getValue();
		l.spec = txtSpec.getValue() == null ? "" : txtSpec.getValue().trim();
		l.dateReq = ts(dtLine);
		try {
			l.avail = new BigDecimal(lblAvail.getValue());
		} catch (Exception ignore) {
			l.avail = BigDecimal.ZERO;
		}
		l.qty = qty;
		BigDecimal r = decRate.getValue();
		l.rate = r == null ? BigDecimal.ZERO : r;
		lines.add(l);
		refreshLines();

		// next product: keep Segment, Department and categories, clear the rest
		pProduct.clear(false);
		txtSpec.setValue("");
		decQty.setValue((BigDecimal) null);
		decRate.setValue((BigDecimal) null);
		productChanged();
	}

	private void refreshLines() {
		while (lineList.getItemCount() > 0)
			lineList.removeItemAt(0);
		int n = 0;
		for (final Line l : lines) {
			n++;
			Listitem li = new Listitem();
			li.appendChild(new Listcell(String.valueOf(n * 10)));
			li.appendChild(new Listcell(l.segName));
			li.appendChild(new Listcell(l.deptName));
			li.appendChild(new Listcell(l.code));
			li.appendChild(new Listcell(l.name));
			li.appendChild(new Listcell(l.uom));
			li.appendChild(new Listcell(l.spec));
			li.appendChild(new Listcell(l.dateReq == null ? "" : new java.text.SimpleDateFormat("dd/MM/yyyy").format(l.dateReq)));
			li.appendChild(new Listcell(l.avail.stripTrailingZeros().toPlainString()));
			li.appendChild(new Listcell(l.qty.stripTrailingZeros().toPlainString()));
			li.appendChild(new Listcell(l.rate.signum() == 0 ? "" : l.rate.toPlainString()));
			li.appendChild(new Listcell(l.rate.signum() == 0 ? "" : l.rate.multiply(l.qty).toPlainString()));
			Listcell rm = new Listcell();
			Button x = new Button("\u2716");
			x.setSclass("btn btn-sm btn-outline-danger");
			x.addEventListener(Events.ON_CLICK, e -> {
				lines.remove(l);
				refreshLines();
			});
			rm.appendChild(x);
			li.appendChild(rm);
			lineList.appendChild(li);
		}
	}

	// =====================================================================
	// save
	// =====================================================================

	private void save() {
		if (orgId <= 0) {
			warn("Log in with a specific organization (not *) to create a requisition.");
			return;
		}
		if (selValue(cbType).length() == 0) {
			warn("Type is required.");
			return;
		}
		if (selValue(cbLocation).length() == 0) {
			warn("Location is required.");
			return;
		}
		if (selValue(cbPriority).length() == 0) {
			warn("Priority is required.");
			return;
		}
		Timestamp docDate = ts(dtDoc);
		Timestamp reqDate = ts(dtRequired);
		if (docDate == null) {
			warn("Document Date is required.");
			return;
		}
		if (reqDate == null) {
			warn("Date Required is required.");
			return;
		}
		if (lines.isEmpty()) {
			warn("Add at least one product (+ Add Product).");
			return;
		}
		if (!columnExists("M_Requisition", "XX_Location") || !columnExists("M_RequisitionLine", "XX_Segment")
				|| !columnExists("M_RequisitionLine", "XX_Department_ID")
				|| !columnExists("M_RequisitionLine", "XX_DateRequired")) {
			warn("Run db/16_requisition_entry_form.sql first (extra requisition columns are missing).");
			return;
		}

		int warehouseId = pWarehouse.id;
		if (warehouseId <= 0)
			warehouseId = DB.getSQLValue(null, "SELECT M_Warehouse_ID FROM M_Warehouse WHERE IsActive='Y' "
					+ "AND AD_Client_ID=? ORDER BY (AD_Org_ID=?) DESC, M_Warehouse_ID LIMIT 1", clientId, orgId);
		int priceListId = DB.getSQLValue(null, "SELECT M_PriceList_ID FROM M_PriceList WHERE IsActive='Y' "
				+ "AND AD_Client_ID=? AND IsSOPriceList='N' ORDER BY IsDefault DESC, M_PriceList_ID LIMIT 1",
				clientId);
		if (warehouseId <= 0 || priceListId <= 0) {
			warn("No warehouse or purchase price list found for this client.");
			return;
		}

		String trxName = Trx.createTrxName("ReqEntry");
		Trx trx = Trx.get(trxName, true);
		try {
			MRequisition req = new MRequisition(Env.getCtx(), 0, trxName);
			req.setAD_Org_ID(orgId);
			req.setAD_User_ID(userId);
			req.setC_DocType_ID(Integer.parseInt(selValue(cbType)));
			req.setDateDoc(docDate);
			req.setDateRequired(reqDate);
			req.setPriorityRule(selValue(cbPriority));
			req.setM_Warehouse_ID(warehouseId);
			req.setM_PriceList_ID(priceListId);
			String desc = txtDescription.getValue() == null ? "" : txtDescription.getValue().trim();
			if (desc.length() > 0)
				req.setDescription(desc);
			req.saveEx();
			DB.executeUpdateEx("UPDATE M_Requisition SET XX_Location=? WHERE M_Requisition_ID=?",
					new Object[] { selValue(cbLocation), Integer.valueOf(req.get_ID()) }, trxName);

			int n = 0;
			for (Line l : lines) {
				n++;
				MRequisitionLine rl = new MRequisitionLine(req);
				rl.setLine(n * 10);
				rl.setM_Product_ID(l.productId);
				rl.setQty(l.qty);
				rl.setPriceActual(l.rate);
				if (l.spec.length() > 0)
					rl.setDescription(l.spec.length() > 255 ? l.spec.substring(0, 255) : l.spec);
				rl.saveEx();
				DB.executeUpdateEx("UPDATE M_RequisitionLine SET XX_Segment=?, XX_Department_ID=?, "
						+ "XX_DateRequired=? WHERE M_RequisitionLine_ID=?",
						new Object[] { l.segVal, Integer.valueOf(l.deptId), l.dateReq != null ? l.dateReq : reqDate,
								Integer.valueOf(rl.get_ID()) },
						trxName);
			}

			if (!attachments.isEmpty()) {
				MAttachment att = new MAttachment(Env.getCtx(), MRequisition.Table_ID, req.get_ID(), trxName);
				for (Media m : attachments) {
					byte[] bytes = m.isBinary() ? m.getByteData() : m.getStringData().getBytes("UTF-8");
					att.addEntry(m.getName(), bytes);
				}
				att.saveEx();
			}

			trx.commit(true);
			final String docNo = req.getDocumentNo();
			final int reqId = req.get_ID();
			resetForm();
			Messagebox.show("Requisition " + docNo + " has been saved.\n\nDo you want to view the Requisition PDF?",
					"Requisition", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
					(org.zkoss.zk.ui.event.Event ev) -> {
						if (Messagebox.ON_YES.equals(ev.getName()))
							showPdf(reqId, docNo);
					});
		} catch (Exception ex) {
			trx.rollback();
			log.log(Level.SEVERE, ex.getMessage(), ex);
			Messagebox.show("Could not save the requisition:\n" + ex.getMessage(), "Error", Messagebox.OK,
					Messagebox.ERROR);
		} finally {
			trx.close();
		}
	}

	// =====================================================================
	// PDF
	// =====================================================================

	private static final String PDF_JRXML = "/org/mycompany/requisition/PurchaseRequisition.jrxml";

	/** a logo from the bundle written to a temp file (the report takes a file path) */
	private String logoPath(String resource) {
		try (java.io.InputStream in = WRequisitionEntryForm.class.getResourceAsStream(resource)) {
			if (in == null)
				return "";
			java.io.File f = java.io.File.createTempFile("xxlogo", ".png");
			f.deleteOnExit();
			java.nio.file.Files.copy(in, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return f.getAbsolutePath();
		} catch (Exception e) {
			return "";
		}
	}

	private byte[] buildPdf(int requisitionId) throws Exception {
		try (java.io.InputStream jr = WRequisitionEntryForm.class.getResourceAsStream(PDF_JRXML)) {
			if (jr == null)
				throw new AdempiereException(PDF_JRXML + " not found in the bundle");
			net.sf.jasperreports.engine.JasperReport report = net.sf.jasperreports.engine.JasperCompileManager
					.compileReport(jr);
			java.util.Map<String, Object> params = new java.util.HashMap<String, Object>();
			params.put("RECORD_ID", Integer.valueOf(requisitionId));
			params.put("LOGO_LEFT", logoPath("/org/mycompany/requisition/rfqapproval/logo_left.png"));
			params.put("LOGO_RIGHT", logoPath("/org/mycompany/requisition/rfqapproval/logo_right.png"));
			java.sql.Connection conn = DB.getConnectionRO();
			try {
				net.sf.jasperreports.engine.JasperPrint print = net.sf.jasperreports.engine.JasperFillManager
						.fillReport(report, params, conn);
				return net.sf.jasperreports.engine.JasperExportManager.exportReportToPdf(print);
			} finally {
				try {
					conn.close();
				} catch (Exception ignore) {
					// ignore
				}
			}
		}
	}

	/** pop-up viewer with the requisition PDF and a Download button */
	private void showPdf(int requisitionId, String docNo) {
		try {
			byte[] pdf = buildPdf(requisitionId);
			final org.zkoss.util.media.AMedia media = new org.zkoss.util.media.AMedia(
					"Requisition_" + docNo + ".pdf", "pdf", "application/pdf", pdf);

			final org.adempiere.webui.component.Window win = new org.adempiere.webui.component.Window();
			win.setTitle("Requisition " + docNo);
			win.setWidth("900px");
			win.setHeight("92%");
			win.setClosable(true);
			win.setSizable(true);
			win.setAttribute(org.adempiere.webui.component.Window.MODE_KEY, org.adempiere.webui.component.Window.MODE_HIGHLIGHTED);
			org.mycompany.requisition.ui.XxTheme.apply(win);

			Div body = new Div();
			body.setStyle("height:100%;display:flex;flex-direction:column;");
			org.zkoss.zul.Iframe frame = new org.zkoss.zul.Iframe();
			frame.setContent(media);
			frame.setStyle("flex:1 1 auto;width:100%;min-height:500px;border:1px solid #dee2e6;");
			ZKUpdateUtil.setHflex(frame, "1");
			ZKUpdateUtil.setVflex(frame, "1");

			Div bar = new Div();
			bar.setStyle("text-align:right;padding:8px 0 0;");
			Button dl = new Button("Download");
			dl.setSclass("btn btn-primary");
			dl.addEventListener(Events.ON_CLICK, ev -> org.zkoss.zul.Filedownload.save(media));
			Button cl = new Button("Close");
			cl.setSclass("btn btn-danger");
			cl.setStyle("margin-left:6px;");
			cl.addEventListener(Events.ON_CLICK, ev -> win.detach());
			bar.appendChild(dl);
			bar.appendChild(cl);

			body.appendChild(frame);
			body.appendChild(bar);
			win.appendChild(body);
			org.adempiere.webui.apps.AEnv.showCenterScreen(win);
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			warn("Could not create the PDF:\n" + e.getMessage());
		}
	}

	private void resetForm() {
		lines.clear();
		refreshLines();
		attachments.clear();
		updateAttachLabel();
		cbType.reset();
		if (cbType.count() == 1)
			cbType.selectAt(0);
		cbLocation.reset();
		cbPriority.reset();
		cbSegment.reset();
		pWarehouse.clear(false);
		pDept.clear(false);
		pParent.clear(false);
		pSub.clear(false);
		pProduct.clear(false);
		dtDoc.setValue(new Date());
		dtRequired.setValue((Date) null);
		dtLine.setValue((Date) null);
		txtDescription.setValue("");
		txtSpec.setValue("");
		decQty.setValue((BigDecimal) null);
		decRate.setValue((BigDecimal) null);
		productChanged();
	}
}