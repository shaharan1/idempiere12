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
import org.compiere.model.MAttachmentEntry;
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
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;

/**
 * Requisition Form: header (Type, Location, Warehouse, Priority, dates, description,
 * attachment), line entry (Segment, Department, Parent Category, Sub-Category, Product,
 * Qty, Rate) and the list of lines added, then Save saves, Cancel closes.
 * <p>
 * Every drop-down / search field is a type-ahead combo: click it (or press Enter) and the whole
 * list drops down; type a letter and the list shows only the entries that start with it (Type,
 * Location, Priority, Segment from memory; Warehouse, Department, Categories, Product from the
 * database, max 50 rows per search).
 * <p>
 * Organization is a search field too: it lists the organizations the login role may use (all of them
 * for an administrator). The Requisition card (master data) can be hidden with the arrow button above it.
 * <p>
 * "All Requisition" lists every requisition the user created (status, who it is with). Click one
 * to load it back into this form: while no approver has approved it (and it is still Drafted) it
 * can be changed and saved again; after the first approval, or once completed, it opens read-only.
 * The same product cannot be added twice to one requisition, and the Qty of a line can be
 * changed straight in the line table.
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
	/** standard "_Document Status" list */
	private static final int REF_DOCSTATUS = 131;

	/** max rows a type-ahead search brings back */
	private static final int SEARCH_LIMIT = 100;

	/** bump when the CSS below changes so open browsers take the new look */
	private static final String CSS_VERSION = "6";

	private final int clientId = Env.getAD_Client_ID(Env.getCtx());
	private final int orgId = Env.getAD_Org_ID(Env.getCtx());
	private final int userId = Env.getAD_User_ID(Env.getCtx());

	// ---- header
	private Picker pOrg;
	private final Label lblUser = new Label();
	/** the Requisition (master data) card: can be hidden to give room to the lines */
	private Div headerCard;
	private final Div toggleBar = new Div();
	private final Button btnHideInfo = new Button("\u25B2  Hide Requisition Info");
	private final Button btnShowInfo = new Button("\u25BC  Show Requisition Info");
	private final Label lblHeadSummary = new Label();
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
	/** files that were already attached to the requisition being edited (never saved again) */
	private final List<Media> existingMedia = new ArrayList<Media>();

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
	/** M_RequisitionLine_ID of lines removed while editing (deleted at Save) */
	private final List<Integer> deletedLineIds = new ArrayList<Integer>();

	private final Button btnOk = new Button("\u2714  Save");
	private final Button btnCancel = new Button("\u2716  Cancel");
	private final Button btnAll = new Button("All Requisition");
	private final Button btnNew = new Button("+  New Requisition");

	// ---- view / edit of an existing requisition
	private final Div banner = new Div();
	private final Label lblBanner = new Label();
	/** M_Requisition_ID being edited / viewed, 0 = a new requisition */
	private int editId = 0;
	private String editDocNo = "";
	/** true when the loaded requisition may not be changed any more */
	private boolean readOnly = false;

	/** one product line of the requisition */
	private static final class Line {
		/** M_RequisitionLine_ID, 0 = not saved yet */
		int lineId;
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
	// look (Bootstrap 5 style, scoped to .xx-req / .xx-reqwin so the rest of iDempiere is untouched)
	// =====================================================================

	/**
	 * Bootstrap's own bootstrap.min.css cannot be loaded here: its global reset would
	 * restyle the whole iDempiere page. These rules use Bootstrap 5's class names,
	 * colours, radii, shadows and focus ring, but only below .xx-req (the form) and
	 * .xx-reqwin (its pop-up windows).
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
.xx-req .z-textbox[disabled], .xx-req .z-decimalbox[disabled], .xx-req .z-datebox-input[disabled], .xx-req .z-combobox-input[disabled] { background:#e9ecef !important; color:#6c757d; }
.xx-req .xx-readonly { background:#e9ecef; display:flex; align-items:center; min-height:26px; }
.xx-req .xx-readonly .z-label { font-size:.8rem; color:#495057; }
.xx-req .xx-attach-note { display:block; margin-top:.25rem; font-size:.75rem; color:#6c757d; }
.z-combobox-popup, .z-combobox-content { border:1px solid #ced4da !important; border-radius:.375rem !important; box-shadow:0 .5rem 1rem rgba(0,0,0,.15) !important; }
.z-comboitem { padding:.25rem .6rem !important; font-size:.8rem; }
.z-comboitem-over, .z-comboitem-over-seld { background:#e7f1ff !important; }
.z-comboitem-selected { background:#cfe2ff !important; }
.xx-form.xx-req button.z-button.btn, .xx-reqwin button.z-button.btn { display:inline-flex; align-items:center; justify-content:center; padding:.15rem .7rem; min-height:26px; font-size:.8rem; font-weight:600; line-height:1.5; border-radius:.375rem !important; text-shadow:none !important; transform:none; transition:background-color .15s,border-color .15s,box-shadow .15s; }
.xx-form.xx-req button.z-button.btn:hover, .xx-reqwin button.z-button.btn:hover { filter:none; transform:none; }
.xx-form.xx-req button.z-button.btn:focus, .xx-reqwin button.z-button.btn:focus { box-shadow:0 0 0 .25rem rgba(13,110,253,.35); outline:0; }
.xx-form.xx-req button.z-button.btn-block { width:100%; }
.xx-form.xx-req button.z-button.btn-sm, .xx-reqwin button.z-button.btn-sm { min-height:26px; padding:.1rem .5rem; font-size:.75rem; }
.xx-form.xx-req button.z-button.btn-primary, .xx-reqwin button.z-button.btn-primary { background:#0d6efd !important; border:1px solid #0d6efd !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn-primary:hover, .xx-reqwin button.z-button.btn-primary:hover { background:#0b5ed7 !important; border-color:#0a58ca !important; }
.xx-form.xx-req button.z-button.btn-success, .xx-reqwin button.z-button.btn-success { background:#198754 !important; border:1px solid #198754 !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn-success:hover, .xx-reqwin button.z-button.btn-success:hover { background:#157347 !important; border-color:#146c43 !important; }
.xx-form.xx-req button.z-button.btn-danger, .xx-reqwin button.z-button.btn-danger { background:#dc3545 !important; border:1px solid #dc3545 !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn-danger:hover, .xx-reqwin button.z-button.btn-danger:hover { background:#bb2d3b !important; border-color:#b02a37 !important; }
.xx-form.xx-req button.z-button.btn-outline-primary, .xx-reqwin button.z-button.btn-outline-primary { background:#fff !important; border:1px solid #0d6efd !important; color:#0d6efd !important; }
.xx-form.xx-req button.z-button.btn-outline-primary:hover, .xx-reqwin button.z-button.btn-outline-primary:hover { background:#0d6efd !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn-outline-danger, .xx-reqwin button.z-button.btn-outline-danger { background:#fff !important; border:1px solid #dc3545 !important; color:#dc3545 !important; box-shadow:none !important; }
.xx-form.xx-req button.z-button.btn-outline-danger:hover, .xx-reqwin button.z-button.btn-outline-danger:hover { background:#dc3545 !important; color:#fff !important; }
.xx-form.xx-req button.z-button.btn[disabled], .xx-reqwin button.z-button.btn[disabled] { opacity:.55; cursor:not-allowed; }
.xx-req .xx-main { background:#f1f4f9; }
.xx-req .xx-card-header + .z-listbox, .xx-req .xx-card > .z-listbox { margin:0; }
/* line tables: a visible box round every line and cell */
.xx-req .z-listbox, .xx-reqwin .z-listbox { min-height:200px; border:1px solid #adb5bd !important; border-radius:.375rem; }
.xx-req .z-listbox .z-listheader, .xx-reqwin .z-listbox .z-listheader { border-right:1px solid rgba(255,255,255,.45) !important; }
.xx-req .z-listbox .z-listcell, .xx-reqwin .z-listbox .z-listcell { border-right:1px solid #ced4da !important; border-bottom:1px solid #ced4da !important; }
.xx-req .z-listbox .z-listitem:nth-child(even) > .z-listcell, .xx-reqwin .z-listbox .z-listitem:nth-child(even) > .z-listcell { background:#f8fafc; }
.xx-req .z-listbox .z-listitem:hover > .z-listcell, .xx-reqwin .z-listbox .z-listitem:hover > .z-listcell { background:#e7f1ff; }
.xx-req .z-listbox .z-listcell .z-decimalbox { min-height:24px; text-align:right; }
.xx-req .xx-footer { position:sticky; bottom:0; z-index:5; display:flex; justify-content:space-between; align-items:center; gap:.5rem; padding:.4rem .75rem; margin:6px 10px; background:#fff; border:1px solid #dee2e6; border-radius:.5rem; box-shadow:0 .125rem .25rem rgba(0,0,0,.075); }
.xx-req .xx-foot-grp { display:flex; align-items:center; gap:.5rem; }
/* hide / show the Requisition (master data) card */
.xx-req .xx-toggle { display:flex; align-items:center; justify-content:flex-end; gap:.6rem; margin:6px 10px 0; }
.xx-req .xx-sum { font-size:.78rem; font-weight:600; color:#084298; background:#e7f1ff; border:1px solid #b6d4fe; padding:.15rem .7rem; border-radius:999px; }
.xx-req .xx-sum .z-label { font-size:.78rem; font-weight:600; color:#084298; }
/* view / edit banner */
.xx-req .xx-banner { margin:6px 10px 0; padding:.4rem .9rem; border-radius:.5rem; border:1px solid #b6d4fe; background:#cfe2ff; color:#084298; }
.xx-req .xx-banner .z-label { font-size:.82rem; font-weight:600; color:inherit; }
.xx-req .xx-banner.xx-banner-ro { border-color:#ffecb5; background:#fff3cd; color:#664d03; }
/* status badges of the All Requisition list */
.xx-reqwin .xx-badge { display:inline-block; padding:.15rem .55rem; border-radius:999px; font-size:.72rem; font-weight:600; color:#fff; }
.xx-reqwin .xx-badge-draft { background:#6c757d; }
.xx-reqwin .xx-badge-wait { background:#fd7e14; }
.xx-reqwin .xx-badge-prog { background:#0d6efd; }
.xx-reqwin .xx-badge-ok { background:#198754; }
.xx-reqwin .xx-badge-bad { background:#dc3545; }
.xx-reqwin .xx-link { color:#0d6efd; font-weight:700; text-decoration:underline; cursor:pointer; }
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

	/**
	 * Drop-down / search fields of this form: a click inside the text box opens the list (the
	 * list itself is filled by the server when the field gets the focus). Clicking the small
	 * arrow keeps its normal open/close behaviour.
	 */
	private static final String COMBO_JS = "(function(){if(window.__xxComboV==='1')return;window.__xxComboV='1';"
			+ "document.addEventListener('click',function(e){var t=e.target;"
			+ "if(!(t&&t.classList&&t.classList.contains('z-combobox-input')&&t.closest&&t.closest('.xx-req')))return;"
			+ "if(t.disabled)return;var w=null;try{w=zk.Widget.$(t);}catch(x){}"
			+ "if(w&&typeof w.open==='function'&&!(w.isOpen&&w.isOpen()))w.open();},true);})();";

	private void installCss() {
		if (Executions.getCurrent() == null)
			return;
		try {
			String css = CSS.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n");
			Clients.evalJavaScript("(function(){var V='" + CSS_VERSION + "';var s=document.getElementById('xx-req-css');"
					+ "if(!s){s=document.createElement('style');s.id='xx-req-css';document.head.appendChild(s);}"
					+ "if(s.getAttribute('data-v')!==V){s.setAttribute('data-v',V);s.textContent='" + css + "';}})();");
			Clients.evalJavaScript(DATE_JS);
			Clients.evalJavaScript(COMBO_JS);
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

		pOrg = new Picker("Organization",
				"SELECT AD_Org_ID, Value, Name FROM AD_Org WHERE IsActive='Y' AND IsSummary='N' AND AD_Org_ID>0 "
						+ "AND AD_Client_ID=" + clientId
						+ " AND (EXISTS (SELECT 1 FROM AD_Role ro WHERE ro.AD_Role_ID=" + Env.getAD_Role_ID(Env.getCtx())
						+ " AND ro.IsAccessAllOrgs='Y') OR AD_Org_ID IN (SELECT oa.AD_Org_ID FROM AD_Role_OrgAccess oa "
						+ "WHERE oa.AD_Role_ID=" + Env.getAD_Role_ID(Env.getCtx()) + " AND oa.IsActive='Y'))",
				"Name", false, null);
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

		// banner: shown while an existing requisition is open (edit / view only)
		banner.setSclass("xx-banner");
		banner.setVisible(false);
		banner.appendChild(lblBanner);
		main.appendChild(banner);

		// arrow button: hide / show the Requisition card
		toggleBar.setSclass("xx-toggle");
		lblHeadSummary.setSclass("xx-sum");
		lblHeadSummary.setVisible(false);
		btnHideInfo.setSclass("btn btn-sm btn-outline-primary");
		btnShowInfo.setSclass("btn btn-sm btn-primary");
		btnShowInfo.setVisible(false);
		toggleBar.appendChild(lblHeadSummary);
		toggleBar.appendChild(btnHideInfo);
		toggleBar.appendChild(btnShowInfo);
		main.appendChild(toggleBar);

		main.appendChild(buildHeader());
		main.appendChild(buildLineEntry());

		buildLineList();
		main.appendChild(card("Requisition Line", lineList));

		Div foot = new Div();
		foot.setSclass("xx-footer");
		btnAll.setSclass("btn btn-primary");
		btnNew.setSclass("btn btn-outline-primary");
		btnNew.setVisible(false);
		Div left = new Div();
		left.setSclass("xx-foot-grp");
		left.appendChild(btnAll);
		left.appendChild(btnNew);
		btnOk.setSclass("btn btn-success");
		btnCancel.setSclass("btn btn-danger");
		Div right = new Div();
		right.setSclass("xx-foot-grp");
		right.appendChild(btnOk);
		right.appendChild(btnCancel);
		foot.appendChild(left);
		foot.appendChild(right);
		main.appendChild(foot);

		wireEvents();
	}

	/** header card: 4 fields per row, same order as the screen */
	private Div buildHeader() {
		Div g = new Div();
		g.setSclass("xx-grid");
		g.appendChild(field("Organization", true, pOrg.box, 1));
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

		headerCard = card("Requisition", g);
		return headerCard;
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
				{ "Qty", "100px" }, { "Rate", "80px" }, { "Amount", "95px" }, { "", "50px" } };
		for (String[] c : cols) {
			Listheader h = new Listheader(c[0]);
			if (c[1] != null)
				h.setWidth(c[1]);
			else
				h.setHflex("1");
			head.appendChild(h);
		}
		lineList.appendChild(head);
		lineList.setEmptyMessage("No product added yet. Fill the fields above and click + Add Product.");
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
		cb.setAutocomplete(false); // we filter ourselves ("starts with")
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

	/** drops the list down (after the server has filled it); does nothing when it is already open */
	private static void openList(Combobox cb) {
		js("(function(){var w=zk.Widget.$('#" + cb.getUuid()
				+ "');if(w&&w.open&&!(w.isOpen&&w.isOpen()))w.open();})();");
	}

	/** drop-down with a small fixed list (value + name); click / Enter shows all, a letter shows the names starting with it */
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
			// click or Tab into the field: the whole list
			box.addEventListener(Events.ON_FOCUS, e -> {
				typed = "";
				show("");
				openList(box);
				box.select();
			});
			box.addEventListener(Events.ON_SELECT, e -> {
				Comboitem it = box.getSelectedItem();
				if (it == null)
					return;
				Object v = it.getValue();
				value = v == null ? "" : String.valueOf(v);
				name = it.getLabel();
				typed = "";
				show(""); // next click shows the whole list again
				box.setValue(name);
			});
			// leaving the field: take what was typed (first name that starts with it)
			box.addEventListener(Events.ON_CHANGE, e -> resolve(box.getValue()));
			// Enter: nothing typed (or the value is already chosen) -> drop the whole list down
			box.addEventListener(Events.ON_OK, e -> {
				String t = box.getValue() == null ? "" : box.getValue().trim();
				if (t.isEmpty() || t.equals(name)) {
					typed = "";
					show("");
					openList(box);
				} else {
					resolve(t);
				}
			});
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

		/** select the entry with this value (no match: nothing changes) */
		void selectValue(String v) {
			if (v == null)
				return;
			for (String[] a : all)
				if (a[0].equals(v)) {
					value = a[0];
					name = a[1];
					box.setValue(name);
					return;
				}
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

	/** drop-down backed by a query; click / Enter shows the first rows, a letter shows Value/Name starting with it (max SEARCH_LIMIT) */
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
			// click or Tab into the field: the list of this field
			box.addEventListener(Events.ON_FOCUS, e -> {
				typed = "";
				load("");
				openList(box);
				box.select();
			});
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
					else
						box.setValue(display());
					load(""); // next click shows the whole list again
				}
			});
			box.addEventListener(Events.ON_CHANGE, e -> resolve(box.getValue()));
			// Enter: nothing typed (or the value is already chosen) -> drop the list down
			box.addEventListener(Events.ON_OK, e -> {
				String t = box.getValue() == null ? "" : box.getValue().trim();
				if (t.isEmpty() || t.equals(display())) {
					typed = "";
					load("");
					openList(box);
				} else {
					resolve(t);
				}
			});
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
		setDefaultOrg();
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
		btnAll.addEventListener(Events.ON_CLICK, e -> openAllRequisitions());
		btnNew.addEventListener(Events.ON_CLICK, e -> newRequisition());
		btnHideInfo.addEventListener(Events.ON_CLICK, e -> setHeaderVisible(false));
		btnShowInfo.addEventListener(Events.ON_CLICK, e -> setHeaderVisible(true));
	}

	/** hide / show the Requisition card; hidden, a one line summary of it stays next to the button */
	private void setHeaderVisible(boolean show) {
		headerCard.setVisible(show);
		btnHideInfo.setVisible(show);
		btnShowInfo.setVisible(!show);
		lblHeadSummary.setVisible(!show);
		if (!show) {
			List<String> parts = new ArrayList<String>();
			if (pOrg.id > 0)
				parts.add(pOrg.name);
			if (!cbType.name.isEmpty())
				parts.add(cbType.name);
			if (!cbLocation.name.isEmpty())
				parts.add(cbLocation.name);
			if (pWarehouse.id > 0)
				parts.add(pWarehouse.name);
			if (!cbPriority.name.isEmpty())
				parts.add(cbPriority.name);
			Timestamp rd = ts(dtRequired);
			if (rd != null)
				parts.add("Required " + dmy(rd));
			lblHeadSummary.setValue(parts.isEmpty() ? "Requisition info is empty" : String.join("  |  ", parts));
		}
	}

	/** a missing master data field: open the card so the user sees it, then tell */
	private void headWarn(String msg) {
		setHeaderVisible(true);
		warn(msg);
	}

	/** the login organization is the default (an administrator logged in on * picks one) */
	private void setDefaultOrg() {
		if (orgId > 0)
			pOrg.set(orgId, str("SELECT Value FROM AD_Org WHERE AD_Org_ID=" + orgId),
					str("SELECT Name FROM AD_Org WHERE AD_Org_ID=" + orgId), false);
		else
			pOrg.clear(false);
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
		win.setSclass("xx-reqwin");
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
			for (final Media m : new ArrayList<Media>(attachments)) {
				n++;
				Listitem li = new Listitem();
				li.appendChild(new Listcell(String.valueOf(n)));
				Listcell nameCell = new Listcell();
				org.zkoss.zul.A link = new org.zkoss.zul.A(m.getName());
				link.setStyle("color:#0d6efd;text-decoration:underline;cursor:pointer;font-weight:600;");
				link.setTooltiptext("Click to open");
				link.addEventListener(Events.ON_CLICK, ev -> openMedia(m));
				nameCell.appendChild(link);
				li.appendChild(nameCell);
				li.appendChild(new Listcell(sizeOf(m)));
				Listcell rm = new Listcell();
				// files saved earlier, or any file of a read-only requisition, are not removable
				if (!readOnly && !existingMedia.contains(m)) {
					Button x = new Button("\u2716");
					x.setSclass("btn btn-sm btn-outline-danger");
					x.addEventListener(Events.ON_CLICK, ev -> {
						attachments.remove(m);
						refresh[0].run();
					});
					rm.appendChild(x);
				}
				li.appendChild(rm);
				lb.appendChild(li);
			}
			count.setValue(attachments.isEmpty() ? "No file attached" : attachments.size() + " file(s) attached");
			updateAttachLabel();
		};

		Button add = new Button("+  Add File");
		add.setSclass("btn btn-primary");
		add.setUpload("true,multiple=true");
		add.setVisible(!readOnly);
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
		if (readOnly)
			return;
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
		// one requisition: one line per product
		int at = 0;
		for (Line x : lines) {
			at++;
			if (x.productId == pProduct.id) {
				warn("This product (" + pProduct.code + " - " + pProduct.name + ") is already in this requisition "
						+ "(line " + (at * 10) + ").\n\nThe same product cannot be added twice. "
						+ "To change the quantity, edit the Qty of that line in the Requisition Line table below.");
				return;
			}
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

	private static String money(BigDecimal v) {
		return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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

			// Qty: edit it right here (more or less); Amount follows
			final Label amount = new Label(l.rate.signum() == 0 ? "" : money(l.rate.multiply(l.qty)));
			Listcell qtyCell = new Listcell();
			if (readOnly) {
				qtyCell.appendChild(new Label(l.qty.stripTrailingZeros().toPlainString()));
			} else {
				final Decimalbox qb = new Decimalbox();
				qb.setSclass("form-control");
				ZKUpdateUtil.setWidth(qb, "100%");
				qb.setValue(l.qty.stripTrailingZeros());
				org.zkoss.zk.ui.event.EventListener<org.zkoss.zk.ui.event.Event> apply = ev -> {
					BigDecimal v = qb.getValue();
					if (v == null || v.signum() <= 0) {
						warn("Qty must be greater than 0. To remove the line use the \u2716 button.");
						qb.setValue(l.qty.stripTrailingZeros());
						return;
					}
					l.qty = v;
					amount.setValue(l.rate.signum() == 0 ? "" : money(l.rate.multiply(l.qty)));
				};
				qb.addEventListener(Events.ON_CHANGE, apply);
				qb.addEventListener(Events.ON_OK, apply);
				qtyCell.appendChild(qb);
			}
			li.appendChild(qtyCell);
			li.appendChild(new Listcell(l.rate.signum() == 0 ? "" : l.rate.toPlainString()));
			Listcell amtCell = new Listcell();
			amtCell.appendChild(amount);
			li.appendChild(amtCell);

			Listcell rm = new Listcell();
			if (!readOnly) {
				Button x = new Button("\u2716");
				x.setSclass("btn btn-sm btn-outline-danger");
				x.addEventListener(Events.ON_CLICK, e -> {
					if (l.lineId > 0)
						deletedLineIds.add(Integer.valueOf(l.lineId));
					lines.remove(l);
					refreshLines();
				});
				rm.appendChild(x);
			}
			li.appendChild(rm);
			lineList.appendChild(li);
		}
	}

	// =====================================================================
	// view / edit an existing requisition
	// =====================================================================

	/**
	 * Why the requisition can not be changed any more, null when it can: it must still be Drafted
	 * and no approver may have approved it yet (UT_ApprovalHistory status A).
	 */
	private String lockReason(int requisitionId) {
		String st = str("SELECT DocStatus FROM M_Requisition WHERE M_Requisition_ID=" + requisitionId);
		if (!"DR".equals(st)) {
			String nm = str("SELECT Name FROM AD_Ref_List WHERE AD_Reference_ID=" + REF_DOCSTATUS + " AND Value='"
					+ st.replace("'", "") + "'");
			return "its status is " + (nm.isEmpty() ? st : nm);
		}
		int approved = DB.getSQLValue(null, "SELECT COUNT(*) FROM UT_ApprovalHistory h JOIN M_Requisition r "
				+ "ON r.DocumentNo=h.DocumentNo AND r.C_DocType_ID=h.C_DocType_ID AND r.AD_Client_ID=h.AD_Client_ID "
				+ "WHERE r.M_Requisition_ID=? AND h.Status='A'", requisitionId);
		if (approved > 0)
			return "it has already been approved by " + approved + " approver" + (approved > 1 ? "s" : "");
		return null;
	}

	/** switch the fields on / off for a new, an editable or a read-only requisition */
	private void applyMode() {
		boolean ro = readOnly;
		boolean existing = editId > 0;
		pOrg.box.setDisabled(ro || existing); // organization and type decide the approval path: fixed once saved
		cbType.box.setDisabled(ro || existing);
		cbLocation.box.setDisabled(ro);
		cbPriority.box.setDisabled(ro);
		pWarehouse.box.setDisabled(ro);
		dtDoc.setDisabled(ro);
		dtRequired.setDisabled(ro);
		txtDescription.setDisabled(ro);
		cbSegment.box.setDisabled(ro);
		pDept.box.setDisabled(ro);
		pParent.box.setDisabled(ro);
		pSub.box.setDisabled(ro);
		pProduct.box.setDisabled(ro);
		txtSpec.setDisabled(ro);
		dtLine.setDisabled(ro);
		decQty.setDisabled(ro);
		decRate.setDisabled(ro);
		btnAdd.setDisabled(ro);
		btnOk.setVisible(!ro);
		btnNew.setVisible(existing);
		banner.setVisible(existing);
	}

	/** back to an empty new requisition */
	private void newRequisition() {
		resetForm();
	}

	/** All Requisition -> a row was clicked */
	private void openRequisition(final int id) {
		if (editId == 0 && !lines.isEmpty()) {
			Messagebox.show("The requisition you are entering will be discarded. Continue?", "Requisition",
					Messagebox.YES | Messagebox.NO, Messagebox.QUESTION, (org.zkoss.zk.ui.event.Event ev) -> {
						if (Messagebox.ON_YES.equals(ev.getName()))
							loadRequisition(id);
					});
			return;
		}
		loadRequisition(id);
	}

	private void loadRequisition(int id) {
		MRequisition req = new MRequisition(Env.getCtx(), id, null);
		if (req.get_ID() != id) {
			warn("Requisition not found.");
			return;
		}
		resetForm();
		editId = id;
		editDocNo = req.getDocumentNo();
		String why = lockReason(id);
		readOnly = why != null;

		pOrg.set(req.getAD_Org_ID(), str("SELECT Value FROM AD_Org WHERE AD_Org_ID=" + req.getAD_Org_ID()),
				str("SELECT Name FROM AD_Org WHERE AD_Org_ID=" + req.getAD_Org_ID()), false);
		cbType.selectValue(String.valueOf(req.getC_DocType_ID()));
		cbLocation.selectValue(str("SELECT XX_Location FROM M_Requisition WHERE M_Requisition_ID=" + id));
		cbPriority.selectValue(req.getPriorityRule());
		if (req.getM_Warehouse_ID() > 0)
			pWarehouse.set(req.getM_Warehouse_ID(),
					str("SELECT Value FROM M_Warehouse WHERE M_Warehouse_ID=" + req.getM_Warehouse_ID()),
					str("SELECT Name FROM M_Warehouse WHERE M_Warehouse_ID=" + req.getM_Warehouse_ID()), false);
		dtDoc.setValue(req.getDateDoc());
		dtRequired.setValue(req.getDateRequired());
		txtDescription.setValue(req.getDescription() == null ? "" : req.getDescription());

		loadLines(id, req.getM_Warehouse_ID());
		loadExistingAttachments(id);

		refreshLines();
		applyMode();
		if (readOnly) {
			banner.setSclass("xx-banner xx-banner-ro");
			lblBanner.setValue("\uD83D\uDD12  Requisition " + editDocNo + " is view only: " + why + ".");
		} else {
			banner.setSclass("xx-banner");
			lblBanner.setValue("\u270E  Editing Requisition " + editDocNo
					+ ". No approver has approved it yet, so you can still change it. Click Save to keep the changes.");
		}
	}

	private void loadLines(int id, int warehouseId) {
		String sql = "SELECT rl.M_RequisitionLine_ID, rl.M_Product_ID, p.Value, p.Name, COALESCE(u.Name,''), rl.Qty, "
				+ "COALESCE(rl.PriceActual,0), COALESCE(rl.Description,''), COALESCE(rl.XX_Segment,''), "
				+ "COALESCE((SELECT l.Name FROM AD_Ref_List l JOIN AD_Reference rf ON rf.AD_Reference_ID=l.AD_Reference_ID "
				+ "WHERE rf.Name='" + REF_SEGMENT + "' AND l.Value=rl.XX_Segment LIMIT 1),''), "
				+ "COALESCE(rl.XX_Department_ID,0), COALESCE(a.Name,''), rl.XX_DateRequired "
				+ "FROM M_RequisitionLine rl JOIN M_Product p ON p.M_Product_ID=rl.M_Product_ID "
				+ "LEFT JOIN C_UOM u ON u.C_UOM_ID=p.C_UOM_ID LEFT JOIN C_Activity a ON a.C_Activity_ID=rl.XX_Department_ID "
				+ "WHERE rl.M_Requisition_ID=? AND rl.IsActive='Y' ORDER BY rl.Line, rl.M_RequisitionLine_ID";
		try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Line l = new Line();
					l.lineId = rs.getInt(1);
					l.productId = rs.getInt(2);
					l.code = rs.getString(3);
					l.name = rs.getString(4);
					l.uom = rs.getString(5);
					l.qty = rs.getBigDecimal(6);
					l.rate = rs.getBigDecimal(7);
					l.spec = rs.getString(8);
					l.segVal = rs.getString(9);
					l.segName = rs.getString(10);
					l.deptId = rs.getInt(11);
					l.deptName = rs.getString(12);
					l.dateReq = rs.getTimestamp(13);
					l.avail = availableQty(l.productId, warehouseId);
					lines.add(l);
				}
			}
		} catch (Exception e) {
			log.log(Level.SEVERE, sql, e);
			warn("Could not read the lines of this requisition:\n" + e.getMessage());
		}
	}

	/** files already attached to the requisition: shown in the Attachment pop-up, never saved again */
	private void loadExistingAttachments(int id) {
		try {
			MAttachment att = MAttachment.get(Env.getCtx(), MRequisition.Table_ID, id);
			if (att == null)
				return;
			for (MAttachmentEntry en : att.getEntries()) {
				byte[] data = en.getData();
				if (data == null)
					continue;
				Media m = new org.zkoss.util.media.AMedia(en.getName(), null, null, data);
				existingMedia.add(m);
				attachments.add(m);
			}
			updateAttachLabel();
		} catch (Exception e) {
			log.log(Level.WARNING, "attachments of requisition " + id + ": " + e.getMessage(), e);
		}
	}

	// =====================================================================
	// All Requisition (pop-up list of my requisitions)
	// =====================================================================

	/** dd/MM/yyyy (a new formatter each time: SimpleDateFormat is not thread safe) */
	private static String dmy(Timestamp t) {
		return t == null ? "" : new java.text.SimpleDateFormat("dd/MM/yyyy").format(t);
	}

	private void openAllRequisitions() {
		final org.adempiere.webui.component.Window win = new org.adempiere.webui.component.Window();
		win.setTitle("All Requisition");
		win.setWidth("1200px");
		win.setHeight("640px");
		win.setClosable(true);
		win.setSizable(true);
		win.setSclass("xx-reqwin");
		win.setAttribute(org.adempiere.webui.component.Window.MODE_KEY, org.adempiere.webui.component.Window.MODE_HIGHLIGHTED);
		org.mycompany.requisition.ui.XxTheme.apply(win);

		Div body = new Div();
		body.setStyle("padding:10px;");

		final Textbox q = new Textbox();
		q.setPlaceholder("Requisition no, description or product...");
		q.setWidth("340px");
		Button go = new Button("Search");
		go.setSclass("btn btn-primary");
		go.setStyle("margin-left:6px;");
		Button reload = new Button("Refresh");
		reload.setSclass("btn btn-outline-primary");
		reload.setStyle("margin-left:6px;");
		final Label hint = new Label("Click a requisition to open it.");
		hint.setStyle("margin-left:14px;color:#6c757d;font-size:12px;");
		Div bar = new Div();
		bar.setStyle("margin-bottom:8px;display:flex;align-items:center;");
		bar.appendChild(q);
		bar.appendChild(go);
		bar.appendChild(reload);
		bar.appendChild(hint);

		final Listbox lb = new Listbox();
		lb.setHeight("490px");
		Listhead lh = new Listhead();
		String[][] cols = { { "Requisition No", "120px" }, { "Date", "90px" }, { "Type", "150px" },
				{ "Priority", "80px" }, { "Location", "130px" }, { "Date Required", "100px" }, { "Lines", "55px" },
				{ "Amount", "100px" }, { "Status", null } };
		for (String[] c : cols) {
			Listheader h = new Listheader(c[0]);
			if (c[1] != null)
				h.setWidth(c[1]);
			else
				h.setHflex("1");
			lh.appendChild(h);
		}
		lb.appendChild(lh);
		lb.setEmptyMessage("No requisition found.");

		final Runnable[] load = new Runnable[1];
		load[0] = () -> {
			while (lb.getItemCount() > 0)
				lb.removeItemAt(0);
			String s = q.getValue() == null ? "" : q.getValue().trim().toUpperCase();
			List<Object> params = new ArrayList<Object>();
			params.add(Integer.valueOf(clientId));
			params.add(Integer.valueOf(userId));
			String find = "";
			if (!s.isEmpty()) {
				find = " AND (UPPER(r.DocumentNo) LIKE ? OR UPPER(COALESCE(r.Description,'')) LIKE ? OR EXISTS ("
						+ "SELECT 1 FROM M_RequisitionLine fl JOIN M_Product fp ON fp.M_Product_ID=fl.M_Product_ID "
						+ "WHERE fl.M_Requisition_ID=r.M_Requisition_ID AND (UPPER(fp.Name) LIKE ? OR UPPER(fp.Value) LIKE ?)))";
				for (int i = 0; i < 4; i++)
					params.add("%" + s + "%");
			}
			String hist = "FROM UT_ApprovalHistory h WHERE h.DocumentNo=r.DocumentNo AND h.C_DocType_ID=r.C_DocType_ID "
					+ "AND h.AD_Client_ID=r.AD_Client_ID";
			String sql = "SELECT r.M_Requisition_ID, r.DocumentNo, r.DateDoc, dt.Name, "
					+ "COALESCE((SELECT pl.Name FROM AD_Ref_List pl WHERE pl.AD_Reference_ID=" + REF_PRIORITY
					+ " AND pl.Value=r.PriorityRule LIMIT 1), r.PriorityRule), "
					+ "COALESCE((SELECT l.Name FROM AD_Ref_List l JOIN AD_Reference rf ON rf.AD_Reference_ID=l.AD_Reference_ID "
					+ "WHERE rf.Name='" + REF_LOCATION + "' AND l.Value=r.XX_Location LIMIT 1), COALESCE(r.XX_Location,'')), "
					+ "r.DateRequired, "
					+ "(SELECT COUNT(*) FROM M_RequisitionLine x WHERE x.M_Requisition_ID=r.M_Requisition_ID), "
					+ "COALESCE((SELECT SUM(x.LineNetAmt) FROM M_RequisitionLine x WHERE x.M_Requisition_ID=r.M_Requisition_ID),0), "
					+ "r.DocStatus, "
					+ "COALESCE((SELECT sl.Name FROM AD_Ref_List sl WHERE sl.AD_Reference_ID=" + REF_DOCSTATUS
					+ " AND sl.Value=r.DocStatus LIMIT 1), r.DocStatus), "
					+ "(SELECT COUNT(*) " + hist + " AND h.Status='A'), "
					+ "(SELECT COUNT(*) " + hist + " AND h.Status='R'), "
					+ "(SELECT u.Name FROM UT_ApprovalHistory h JOIN AD_User u ON u.AD_User_ID="
					+ "(CASE WHEN h.Checked ~ '^[0-9]+$' THEN h.Checked::int ELSE NULL END) "
					+ "WHERE h.DocumentNo=r.DocumentNo AND h.C_DocType_ID=r.C_DocType_ID AND h.AD_Client_ID=r.AD_Client_ID "
					+ "AND h.Status='P' ORDER BY h.Created DESC LIMIT 1) "
					+ "FROM M_Requisition r JOIN C_DocType dt ON dt.C_DocType_ID=r.C_DocType_ID "
					+ "WHERE r.AD_Client_ID=? AND r.CreatedBy=?" + find
					+ " ORDER BY r.DateDoc DESC, r.M_Requisition_ID DESC LIMIT 200";
			try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
				for (int i = 0; i < params.size(); i++) {
					Object o = params.get(i);
					if (o instanceof Integer)
						ps.setInt(i + 1, ((Integer) o).intValue());
					else
						ps.setString(i + 1, String.valueOf(o));
				}
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						final int rid = rs.getInt(1);
						Listitem li = new Listitem();
						li.setStyle("cursor:pointer;");
						Listcell noCell = new Listcell();
						Label no = new Label(rs.getString(2));
						no.setSclass("xx-link");
						noCell.appendChild(no);
						li.appendChild(noCell);
						Timestamp d = rs.getTimestamp(3);
						li.appendChild(new Listcell(dmy(d)));
						li.appendChild(new Listcell(rs.getString(4)));
						li.appendChild(new Listcell(rs.getString(5) == null ? "" : rs.getString(5)));
						li.appendChild(new Listcell(rs.getString(6) == null ? "" : rs.getString(6)));
						Timestamp dr = rs.getTimestamp(7);
						li.appendChild(new Listcell(dmy(dr)));
						li.appendChild(new Listcell(String.valueOf(rs.getInt(8))));
						li.appendChild(new Listcell(money(rs.getBigDecimal(9))));

						String docStatus = rs.getString(10);
						String statusName = rs.getString(11);
						int approved = rs.getInt(12);
						int rejected = rs.getInt(13);
						String pending = rs.getString(14);
						String text;
						String kind;
						if ("CO".equals(docStatus) || "CL".equals(docStatus)) {
							text = statusName;
							kind = "ok";
						} else if ("VO".equals(docStatus) || "RE".equals(docStatus)) {
							text = statusName;
							kind = "bad";
						} else if (approved > 0) {
							text = "Approved by " + approved + " level" + (approved > 1 ? "s" : "")
									+ (pending != null ? ", now with " + pending : ", in approval");
							kind = "prog";
						} else if (pending != null) {
							text = "Waiting for " + pending;
							kind = "wait";
						} else if (rejected > 0) {
							text = "Rejected";
							kind = "bad";
						} else {
							text = statusName;
							kind = "draft";
						}
						Listcell st = new Listcell();
						Label badge = new Label(text);
						badge.setSclass("xx-badge xx-badge-" + kind);
						st.appendChild(badge);
						li.appendChild(st);

						li.addEventListener(Events.ON_CLICK, ev -> {
							win.detach();
							openRequisition(rid);
						});
						lb.appendChild(li);
					}
				}
			} catch (Exception ex) {
				log.log(Level.SEVERE, sql, ex);
				warn("Could not read the requisitions:\n" + ex.getMessage());
			}
			hint.setValue(lb.getItemCount() + " requisition(s). Click one to open it.");
		};
		q.addEventListener(Events.ON_OK, ev -> load[0].run());
		go.addEventListener(Events.ON_CLICK, ev -> load[0].run());
		reload.addEventListener(Events.ON_CLICK, ev -> {
			q.setValue("");
			load[0].run();
		});

		Button close = new Button("Close");
		close.setSclass("btn btn-danger");
		close.addEventListener(Events.ON_CLICK, ev -> win.detach());
		Div foot = new Div();
		foot.setStyle("text-align:right;margin-top:8px;");
		foot.appendChild(close);

		body.appendChild(bar);
		body.appendChild(lb);
		body.appendChild(foot);
		win.appendChild(body);
		org.adempiere.webui.apps.AEnv.showCenterScreen(win);
		load[0].run();
	}

	// =====================================================================
	// save (new requisition or changes to an open one)
	// =====================================================================

	private void save() {
		if (readOnly) {
			warn("This requisition is view only and can not be saved.");
			return;
		}
		if (pOrg.id <= 0) {
			headWarn("Organization is required.");
			return;
		}
		if (selValue(cbType).length() == 0) {
			headWarn("Type is required.");
			return;
		}
		if (selValue(cbLocation).length() == 0) {
			headWarn("Location is required.");
			return;
		}
		if (selValue(cbPriority).length() == 0) {
			headWarn("Priority is required.");
			return;
		}
		Timestamp docDate = ts(dtDoc);
		Timestamp reqDate = ts(dtRequired);
		if (docDate == null) {
			headWarn("Document Date is required.");
			return;
		}
		if (reqDate == null) {
			headWarn("Date Required is required.");
			return;
		}
		if (lines.isEmpty()) {
			warn("Add at least one product (+ Add Product).");
			return;
		}
		for (Line l : lines) {
			if (l.qty == null || l.qty.signum() <= 0) {
				warn("Qty of " + l.code + " must be greater than 0.");
				return;
			}
		}
		if (!columnExists("M_Requisition", "XX_Location") || !columnExists("M_RequisitionLine", "XX_Segment")
				|| !columnExists("M_RequisitionLine", "XX_Department_ID")
				|| !columnExists("M_RequisitionLine", "XX_DateRequired")) {
			warn("Run db/16_requisition_entry_form.sql first (extra requisition columns are missing).");
			return;
		}

		final boolean editing = editId > 0;
		if (editing) {
			// somebody may have approved it since it was opened
			String why = lockReason(editId);
			if (why != null) {
				warn("Requisition " + editDocNo + " can not be changed any more: " + why + ".");
				loadRequisition(editId);
				return;
			}
		}

		int warehouseId = pWarehouse.id;
		if (warehouseId <= 0)
			warehouseId = DB.getSQLValue(null, "SELECT M_Warehouse_ID FROM M_Warehouse WHERE IsActive='Y' "
					+ "AND AD_Client_ID=? ORDER BY (AD_Org_ID=?) DESC, M_Warehouse_ID LIMIT 1", clientId, pOrg.id);
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
			MRequisition req = new MRequisition(Env.getCtx(), editing ? editId : 0, trxName);
			if (!editing) {
				req.setAD_Org_ID(pOrg.id);
				req.setAD_User_ID(userId);
				req.setC_DocType_ID(Integer.parseInt(selValue(cbType)));
				req.setM_PriceList_ID(priceListId);
			}
			req.setDateDoc(docDate);
			req.setDateRequired(reqDate);
			req.setPriorityRule(selValue(cbPriority));
			req.setM_Warehouse_ID(warehouseId);
			String desc = txtDescription.getValue() == null ? "" : txtDescription.getValue().trim();
			req.setDescription(desc.length() > 0 ? desc : null);
			req.saveEx();
			DB.executeUpdateEx("UPDATE M_Requisition SET XX_Location=? WHERE M_Requisition_ID=?",
					new Object[] { selValue(cbLocation), Integer.valueOf(req.get_ID()) }, trxName);

			// lines taken out while editing
			for (Integer did : deletedLineIds) {
				MRequisitionLine gone = new MRequisitionLine(Env.getCtx(), did.intValue(), trxName);
				if (gone.get_ID() > 0)
					gone.deleteEx(true);
			}

			int n = 0;
			for (Line l : lines) {
				n++;
				MRequisitionLine rl = l.lineId > 0 ? new MRequisitionLine(Env.getCtx(), l.lineId, trxName)
						: new MRequisitionLine(req);
				rl.setLine(n * 10);
				rl.setM_Product_ID(l.productId);
				rl.setQty(l.qty);
				rl.setPriceActual(l.rate);
				rl.setDescription(l.spec.length() == 0 ? null : (l.spec.length() > 255 ? l.spec.substring(0, 255) : l.spec));
				rl.saveEx();
				DB.executeUpdateEx("UPDATE M_RequisitionLine SET XX_Segment=?, XX_Department_ID=?, "
						+ "XX_DateRequired=? WHERE M_RequisitionLine_ID=?",
						new Object[] { l.segVal, Integer.valueOf(l.deptId), l.dateReq != null ? l.dateReq : reqDate,
								Integer.valueOf(rl.get_ID()) },
						trxName);
			}

			// only files added now (the ones that were already attached stay as they are)
			List<Media> fresh = new ArrayList<Media>();
			for (Media m : attachments)
				if (!existingMedia.contains(m))
					fresh.add(m);
			if (!fresh.isEmpty()) {
				MAttachment att = editing ? MAttachment.get(Env.getCtx(), MRequisition.Table_ID, req.get_ID()) : null;
				if (att == null)
					att = new MAttachment(Env.getCtx(), MRequisition.Table_ID, req.get_ID(), trxName);
				else
					att.set_TrxName(trxName);
				for (Media m : fresh) {
					byte[] bytes = m.isBinary() ? m.getByteData() : m.getStringData().getBytes("UTF-8");
					att.addEntry(m.getName(), bytes);
				}
				att.saveEx();
			}

			trx.commit(true);
			final String docNo = req.getDocumentNo();
			final int reqId = req.get_ID();
			resetForm();
			Messagebox.show("Requisition " + docNo + (editing ? " has been updated." : " has been saved.")
					+ "\n\nDo you want to view the Requisition PDF?",
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

			showViewer("Requisition " + docNo, media);
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			warn("Could not create the PDF:\n" + e.getMessage());
		}
	}

	/** an attached file: PDF, image and text open in a viewer pop-up, anything else is downloaded */
	private void openMedia(Media m) {
		String ct = m.getContentType() == null ? "" : m.getContentType().toLowerCase();
		String nm = m.getName() == null ? "" : m.getName().toLowerCase();
		boolean viewable = ct.contains("pdf") || ct.startsWith("image/") || ct.startsWith("text/")
				|| nm.endsWith(".pdf") || nm.endsWith(".png") || nm.endsWith(".jpg") || nm.endsWith(".jpeg")
				|| nm.endsWith(".gif") || nm.endsWith(".txt");
		if (viewable)
			showViewer(m.getName(), m);
		else
			org.zkoss.zul.Filedownload.save(m);
	}

	/** pop-up viewer (iframe) with a Download button */
	private void showViewer(String title, final Media media) {
		final org.adempiere.webui.component.Window win = new org.adempiere.webui.component.Window();
		win.setTitle(title);
		win.setWidth("900px");
		win.setHeight("92%");
		win.setClosable(true);
		win.setSizable(true);
		win.setSclass("xx-reqwin");
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
	}

	private void resetForm() {
		editId = 0;
		editDocNo = "";
		readOnly = false;
		deletedLineIds.clear();
		existingMedia.clear();
		lines.clear();
		refreshLines();
		attachments.clear();
		updateAttachLabel();
		setDefaultOrg();
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
		applyMode();
	}
}