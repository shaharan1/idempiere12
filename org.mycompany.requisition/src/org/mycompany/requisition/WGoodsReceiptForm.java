package org.mycompany.requisition;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.compiere.model.MBPartner;
import org.compiere.model.MDocType;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MLocator;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MOrg;
import org.compiere.model.MProduct;
import org.compiere.model.MUOM;
import org.compiere.model.MWarehouse;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.Query;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Events;
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
 * Goods Received Note (GRN) form.
 * <p>
 * 1. The box lists every COMPLETED purchase order that still has something to receive
 *    (tick one, several, or all of them; the search box filters by PO No. or supplier).
 * 2. The lines still pending on the ticked POs appear in the Receipt Line table
 *    (Receive Qty is pre-filled with the pending qty; change it, or remove a line).
 *    Each line also takes Pack Size, No of Pack and Challan Qty.
 * 3. Save creates ONE completed material receipt (M_InOut, MovementType V+) per PO, linked to
 *    the PO lines, so stock and the PO "delivered" qty are updated by the standard document
 *    process. Then it asks whether to view the GRN PDF.
 * <p>
 * Header: VAT Challan No / Date and Delivery Challan No / Date are saved on every GRN created in
 * that go. Their columns (on M_InOut / M_InOutLine) are looked up by name in the Application
 * Dictionary (see resolveColumns); a value whose column cannot be found is not saved and the
 * user is told after saving.
 * <p>
 * Attachments work exactly like the Requisition form: + Add File, click a name to open it,
 * the cross removes it, OK closes. Files are stored on every GRN saved in that go.
 */
public class WGoodsReceiptForm extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WGoodsReceiptForm.class);

	private static final int MAX_PO = 300;
	private static final String CSS_VERSION = "g2";

	private final int clientId = Env.getAD_Client_ID(Env.getCtx());
	private final int orgId = Env.getAD_Org_ID(Env.getCtx());

	// header
	private final Label lblOrg = new Label();
	private Combobox cbDocType;
	private Combobox cbWarehouse;
	private Combobox cbLocator;
	private final Datebox dtMove = new Datebox();
	private final Textbox txtRef = new Textbox();
	private final Textbox txtVatNo = new Textbox();
	private final Datebox dtVat = new Datebox();
	private final Textbox txtDelNo = new Textbox();
	private final Datebox dtDel = new Datebox();
	private final Textbox txtDesc = new Textbox();
	private final Button btnAttach = new Button("Attachment");
	private final Label lblAttach = new Label();
	private final List<Media> attachments = new ArrayList<Media>();

	// PO box
	private final Textbox txtSearch = new Textbox();
	private final Button btnSearch = new Button("Search");
	private final Button btnRefresh = new Button("Refresh");
	private final Listbox poList = new Listbox();
	private final Label lblPoCount = new Label();
	private final List<Po> pos = new ArrayList<Po>();

	// lines
	private final Listbox lineList = new Listbox();
	private final Label lblTotals = new Label();
	private final List<GLine> lines = new ArrayList<GLine>();
	private final Map<Integer, BigDecimal> keepQty = new HashMap<Integer, BigDecimal>();
	private final Map<Integer, String> keepPack = new HashMap<Integer, String>();
	private final Map<Integer, BigDecimal> keepNoPack = new HashMap<Integer, BigDecimal>();
	private final Map<Integer, BigDecimal> keepChQty = new HashMap<Integer, BigDecimal>();
	private final Map<Integer, Set<Integer>> removedByOrder = new HashMap<Integer, Set<Integer>>();
	private Set<Integer> prevSel = new HashSet<Integer>();

	private final Button btnOk = new Button("\u2714  Save GRN");
	private final Button btnReload = new Button("\u21BB  Reload");
	private final Button btnCancel = new Button("\u2716  Cancel");

	// database columns of the challan / pack fields (found by name, null when the column does not exist)
	private String colVatNo;
	private String colVatDate;
	private String colDelNo;
	private String colDelDate;
	private String colPack;
	private String colNoPack;
	private String colChQty;

	private static final class Po {
		int id;
		int warehouseId;
		String docNo = "";
		Timestamp date;
		String supplier = "";
		String warehouse = "";
		int pending;
		BigDecimal total = BigDecimal.ZERO;
	}

	private static final class GLine {
		int olId;
		int orderId;
		String poNo = "";
		String code = "";
		String name = "";
		String uom = "";
		BigDecimal ordered = BigDecimal.ZERO;
		BigDecimal received = BigDecimal.ZERO;
		BigDecimal pending = BigDecimal.ZERO;
		BigDecimal qty = BigDecimal.ZERO;
		String packSize = "";
		BigDecimal noPack;
		BigDecimal challanQty;
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
			resolveColumns();
			buildUi();
			loadHeaderData();
			loadPOs();
			if (orgId <= 0)
				Messagebox.show("Log in with a specific organization (not *) to create a GRN.", "Goods Receipt",
						Messagebox.OK, Messagebox.EXCLAMATION);
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			throw new AdempiereException(e);
		}
	}

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
.xx-req .xx-span6 { grid-column:1 / -1; }
@media (max-width:1500px) { .xx-req .xx-grid, .xx-req .xx-grid7 { grid-template-columns:repeat(4,minmax(0,1fr)); } }
@media (max-width:1000px) { .xx-req .xx-grid, .xx-req .xx-grid7 { grid-template-columns:repeat(2,minmax(0,1fr)); } .xx-req .xx-span2, .xx-req .xx-span3, .xx-req .xx-span4 { grid-column:span 2; } }
@media (max-width:600px)  { .xx-req .xx-grid { grid-template-columns:minmax(0,1fr); } .xx-req .xx-span2, .xx-req .xx-span3, .xx-req .xx-span4 { grid-column:span 1; } }
.xx-req .xx-field { min-width:0; }
.xx-req .form-label { display:block; margin-bottom:.1rem; text-align:left; font-size:.72rem; line-height:1.2; font-weight:600; color:#495057; }
.xx-req .form-label.xx-req-mark { color:#dc3545; }
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
.xx-req .z-listbox .z-listcell .z-textbox { min-height:24px; }
.xx-req .xx-footer { position:sticky; bottom:0; z-index:5; display:flex; justify-content:space-between; align-items:center; gap:.5rem; padding:.4rem .75rem; margin:6px 10px; background:#fff; border:1px solid #dee2e6; border-radius:.5rem; box-shadow:0 .125rem .25rem rgba(0,0,0,.075); }
.xx-req .xx-foot-grp { display:flex; align-items:center; gap:.5rem; }
/* Add Product card: 24 column grid (4 x 6 on the first row, 6+6+6+3+3 on the second) */
.xx-req .xx-grid.xx-grid24 { grid-template-columns:repeat(24,minmax(0,1fr)); }
.xx-req .xx-c3 { grid-column:span 3; }
.xx-req .xx-c6 { grid-column:span 6; }
@media (max-width:1000px) { .xx-req .xx-grid.xx-grid24 { grid-template-columns:repeat(12,minmax(0,1fr)); } .xx-req .xx-c3 { grid-column:span 3; } .xx-req .xx-c6 { grid-column:span 6; } }
@media (max-width:600px)  { .xx-req .xx-grid.xx-grid24 { grid-template-columns:minmax(0,1fr); } .xx-req .xx-c3, .xx-req .xx-c6 { grid-column:span 1; } }
/* Description: same size as the other fields; drag the corner to make it bigger (and smaller again) */
.xx-req textarea.xx-desc { height:26px; min-height:26px; max-height:320px; resize:vertical; overflow:auto; line-height:1.4; }
/* Product Specification: same size as its neighbours, room for 2 lines; while typing it opens up to a bigger box over the fields below */
.xx-req .xx-specwrap { position:relative; height:26px; }
.xx-req .xx-specwrap textarea.xx-spec { position:absolute; left:0; top:0; width:100%; height:26px; min-height:26px; max-height:none; box-sizing:border-box; resize:none; overflow:hidden; font-size:.7rem; line-height:1.05; padding:1px .5rem; }
.xx-req .xx-specwrap textarea.xx-spec:focus { height:56px; z-index:30; overflow:auto; font-size:.8rem; line-height:1.35; padding:.25rem .5rem; box-shadow:0 .5rem 1rem rgba(0,0,0,.18), 0 0 0 .25rem rgba(13,110,253,.25) !important; }
/* totals row at the end of the line table */
.xx-req .z-listbox .z-listfooter { background:#e7f1ff !important; border-top:2px solid #0d6efd !important; border-right:1px solid #b6d4fe !important; font-weight:700; }
.xx-req .z-listbox .z-listfooter .z-label { font-size:.82rem; font-weight:700; color:#0a2e6b; }
/* the banner and the Hide / Show Requisition Header button stay in view while the page scrolls */
.xx-req .xx-sticky { position:sticky; top:0; z-index:25; background:#f1f4f9; padding-bottom:4px; box-shadow:0 2px 4px rgba(16,24,40,.06); }
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
/* status button of the All Requisition list + the approval history pop-up */
.xx-reqwin button.z-button.xx-st { border-radius:999px !important; font-weight:600 !important; color:#fff !important; padding:.1rem .85rem !important; min-height:24px !important; cursor:pointer; }
.xx-reqwin button.z-button.xx-st-draft { background:#6c757d !important; border:1px solid #6c757d !important; }
.xx-reqwin button.z-button.xx-st-wait { background:#fd7e14 !important; border:1px solid #fd7e14 !important; }
.xx-reqwin button.z-button.xx-st-prog { background:#0d6efd !important; border:1px solid #0d6efd !important; }
.xx-reqwin button.z-button.xx-st-ok { background:#198754 !important; border:1px solid #198754 !important; }
.xx-reqwin button.z-button.xx-st-bad { background:#dc3545 !important; border:1px solid #dc3545 !important; }
.xx-reqwin button.z-button.xx-st:hover { filter:brightness(.9); }
.xx-reqwin .xx-hist-head { display:flex; flex-wrap:wrap; align-items:center; gap:.4rem 1.2rem; padding:.55rem .85rem; margin-bottom:8px; background:#f1f4f9; border:1px solid #dee2e6; border-radius:.5rem; }
.xx-reqwin .xx-hist-title { font-size:1rem; font-weight:700; color:#0a2e6b; }
.xx-reqwin .xx-hist-meta { font-size:.8rem; color:#495057; }
.xx-reqwin .xx-hist-note { margin:6px 2px; font-size:.82rem; color:#664d03; }
.xx-reqwin .xx-link { color:#0d6efd; font-weight:700; text-decoration:underline; cursor:pointer; }
""";

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

	private static final String COMBO_JS = "(function(){if(window.__xxComboV==='2')return;window.__xxComboV='2';"
			+ "function mine(t){return t&&t.classList&&t.classList.contains('z-combobox-input')&&t.closest&&t.closest('.xx-req');}"
			+ "function wid(t){try{return zk.Widget.$(t);}catch(x){return null;}}"
			+ "document.addEventListener('click',function(e){var t=e.target;if(!mine(t)||t.disabled)return;"
			+ "if(t.value&&t.value.length)return;"
			+ "var w=wid(t);if(w&&typeof w.open==='function'&&!(w.isOpen&&w.isOpen()))w.open();},true);"
			+ "document.addEventListener('keydown',function(e){if(e.key!=='ArrowDown'&&e.key!=='ArrowUp')return;"
			+ "var t=e.target;if(!mine(t)||t.disabled)return;"
			+ "var w=wid(t);if(w&&typeof w.open==='function'&&!(w.isOpen&&w.isOpen()))w.open();},true);})();";

	private static final String ENTER_JS = "(function(){if(window.__xxEnterV==='1')return;window.__xxEnterV='1';"
			+ "document.addEventListener('keydown',function(e){"
			+ "if(e.key!=='Enter'||e.ctrlKey||e.altKey||e.shiftKey)return;var t=e.target;"
			+ "if(!(t&&t.tagName==='BUTTON'&&t.closest&&t.closest('.xx-req')))return;"
			+ "if(t.disabled)return;e.preventDefault();e.stopPropagation();t.click();},true);})();";

	private void installCss() {
		if (Executions.getCurrent() == null)
			return;
		try {
			String css = CSS.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n");
			Clients.evalJavaScript("(function(){var V='" + CSS_VERSION + "';var s=document.getElementById('xx-grn-css');"
					+ "if(!s){s=document.createElement('style');s.id='xx-grn-css';document.head.appendChild(s);}"
					+ "if(s.getAttribute('data-v')!==V){s.setAttribute('data-v',V);s.textContent='" + css + "';}})();");
			Clients.evalJavaScript(DATE_JS);
			Clients.evalJavaScript(COMBO_JS);
			Clients.evalJavaScript(ENTER_JS);
		} catch (Exception e) {
			log.warning("grn css not installed: " + e);
		}
	}

	// =====================================================================
	// challan / pack columns (found by name in the Application Dictionary)
	// =====================================================================

	/**
	 * First active column of the table whose name matches one of the LIKE patterns (tried in order),
	 * shortest name first. null when there is none.
	 */
	private static String findColumn(String table, String exclude, String... patterns) {
		for (String p : patterns) {
			StringBuilder sql = new StringBuilder(
					"SELECT c.ColumnName FROM AD_Column c JOIN AD_Table t ON t.AD_Table_ID=c.AD_Table_ID "
							+ "WHERE t.TableName=? AND c.IsActive='Y' AND LOWER(c.ColumnName) LIKE ?");
			List<Object> params = new ArrayList<Object>();
			params.add(table);
			params.add(p);
			if (exclude != null) {
				sql.append(" AND LOWER(c.ColumnName) NOT LIKE ?");
				params.add(exclude);
			}
			sql.append(" ORDER BY LENGTH(c.ColumnName), c.ColumnName");
			String r = DB.getSQLValueString((String) null, sql.toString(), params.toArray());
			if (r != null && r.length() > 0)
				return r;
		}
		return null;
	}

	private void resolveColumns() {
		colVatNo = findColumn(MInOut.Table_Name, null, "%vat%challan%no%", "%vat%challan%num%");
		colVatDate = findColumn(MInOut.Table_Name, null, "%vat%challan%date%");
		colDelNo = findColumn(MInOut.Table_Name, null, "%deliver%challan%no%", "%deliver%challan%num%");
		colDelDate = findColumn(MInOut.Table_Name, null, "%deliver%challan%date%");
		colPack = findColumn(MInOutLine.Table_Name, null, "%pack%size%");
		colNoPack = findColumn(MInOutLine.Table_Name, "%size%", "%no%pack%");
		colChQty = findColumn(MInOutLine.Table_Name, null, "%challan%qty%", "%challan%quantity%");
		log.info("GRN columns: VATNo=" + colVatNo + " VATDate=" + colVatDate + " DelNo=" + colDelNo + " DelDate="
				+ colDelDate + " PackSize=" + colPack + " NoOfPack=" + colNoPack + " ChallanQty=" + colChQty);
	}

	/** sets a column by name, converting the value to the column's type; false when the column does not exist */
	private static boolean putCol(PO po, String col, Object val) {
		if (col == null)
			return false;
		int idx = po.get_ColumnIndex(col);
		if (idx < 0)
			return false;
		if (val == null)
			return true;
		Class<?> cls = POInfo.getPOInfo(Env.getCtx(), po.get_Table_ID()).getColumnClass(idx);
		Object v;
		try {
			if (cls == String.class)
				v = val.toString();
			else if (cls == BigDecimal.class)
				v = val instanceof BigDecimal ? val : new BigDecimal(val.toString().trim().replace(",", ""));
			else if (cls == Integer.class)
				v = Integer.valueOf(new BigDecimal(val.toString().trim().replace(",", "")).intValue());
			else if (cls == Timestamp.class)
				v = val instanceof Timestamp ? val : new Timestamp(((Date) val).getTime());
			else
				v = val;
		} catch (NumberFormatException e) {
			throw new AdempiereException("'" + val + "' is not a valid number for " + col);
		}
		po.set_ValueOfColumn(col, v);
		return true;
	}

	private static String txt(Textbox tb) {
		String v = tb.getValue();
		return v == null ? "" : v.trim();
	}

	private static Timestamp ts(Datebox d) {
		return d.getValue() == null ? null : new Timestamp(d.getValue().getTime());
	}

	// =====================================================================
	// UI
	// =====================================================================

	private void buildUi() {
		Div main = new Div();
		main.setSclass("xx-main");
		main.setStyle("height:100%;overflow:auto;");
		appendChild(main);
		ZKUpdateUtil.setWidth(this, "99%");
		ZKUpdateUtil.setHeight(this, "100%");

		cbDocType = newCombo("GRN Document");
		cbWarehouse = newCombo("Warehouse");
		cbLocator = newCombo("Locator");

		main.appendChild(card("Goods Received Note", buildHeader()));
		main.appendChild(card("Completed Purchase Orders  (tick the PO(s) to receive)", buildPoBox()));
		main.appendChild(card("Receipt Line", buildLineBox()));

		Div foot = new Div();
		foot.setSclass("xx-footer");
		Div left = new Div();
		left.setSclass("xx-foot-grp");
		left.appendChild(lblTotals);
		btnOk.setSclass("btn btn-success");
		btnReload.setSclass("btn btn-outline-primary");
		btnReload.setTooltiptext("Clear the form and load the header data and the purchase orders again");
		btnCancel.setSclass("btn btn-danger");
		Div right = new Div();
		right.setSclass("xx-foot-grp");
		right.appendChild(btnOk);
		right.appendChild(btnReload);
		right.appendChild(btnCancel);
		foot.appendChild(left);
		foot.appendChild(right);
		main.appendChild(foot);

		wireEvents();
	}

	private Div buildHeader() {
		Div g = new Div();
		g.setSclass("xx-grid");
		dtMove.setFormat("dd-MM-yyyy");
		dtMove.setValue(new Date());
		dtVat.setFormat("dd-MM-yyyy");
		dtDel.setFormat("dd-MM-yyyy");
		txtRef.setMaxlength(20);
		txtVatNo.setMaxlength(40);
		txtDelNo.setMaxlength(40);
		txtDesc.setMaxlength(255);
		// Description: one line at first, drag the bottom-right corner to make it bigger or smaller again
		txtDesc.setMultiline(true);
		txtDesc.setRows(1);
		txtDesc.setSclass("xx-desc");
		ZKUpdateUtil.setWidth(txtRef, "100%");
		ZKUpdateUtil.setWidth(txtVatNo, "100%");
		ZKUpdateUtil.setWidth(txtDelNo, "100%");
		ZKUpdateUtil.setWidth(txtDesc, "100%");
		ZKUpdateUtil.setWidth(dtMove, "100%");
		ZKUpdateUtil.setWidth(dtVat, "100%");
		ZKUpdateUtil.setWidth(dtDel, "100%");
		btnAttach.setSclass("btn btn-outline-primary");

		// row 1
		g.appendChild(field("Organization", true, readonly(lblOrg), 1));
		g.appendChild(field("GRN Document", true, cbDocType, 1));
		g.appendChild(field("Warehouse", true, cbWarehouse, 1));
		g.appendChild(field("Locator", true, cbLocator, 1));
		g.appendChild(field("GRN Date", true, dtMove, 1));
		g.appendChild(field("Challan / Supplier Ref. No.", false, txtRef, 1));
		// row 2
		g.appendChild(field("VAT Challan No", false, txtVatNo, 1));
		g.appendChild(field("VAT Challan Date", false, dtVat, 1));
		g.appendChild(field("Delivery Challan No", false, txtDelNo, 1));
		g.appendChild(field("Delivery Challan Date", false, dtDel, 1));
		Div at = new Div();
		at.appendChild(btnAttach);
		lblAttach.setSclass("xx-attach-note");
		at.appendChild(lblAttach);
		g.appendChild(field("Attachment", false, at, 1));
		// row 3
		g.appendChild(field("Description (Note)", false, txtDesc, 1));
		updateAttachLabel();
		return g;
	}

	private Div buildPoBox() {
		Div box = new Div();
		Div bar = new Div();
		bar.setStyle("display:flex;align-items:center;gap:8px;margin-bottom:6px;flex-wrap:wrap;");
		txtSearch.setPlaceholder("Search PO No. or Supplier");
		txtSearch.setWidth("260px");
		btnSearch.setSclass("btn btn-primary");
		btnRefresh.setSclass("btn btn-outline-primary");
		bar.appendChild(txtSearch);
		bar.appendChild(btnSearch);
		bar.appendChild(btnRefresh);
		bar.appendChild(lblPoCount);
		box.appendChild(bar);

		poList.setCheckmark(true);
		poList.setMultiple(true);
		poList.setHeight("250px");
		Listhead lh = new Listhead();
		lh.setSizable(true);
		lh.appendChild(header("PO No.", "110px"));
		lh.appendChild(header("PO Date", "100px"));
		lh.appendChild(header("Supplier", null));
		lh.appendChild(header("Warehouse", "170px"));
		lh.appendChild(header("Pending Lines", "110px"));
		lh.appendChild(header("PO Total", "130px"));
		poList.appendChild(lh);
		box.appendChild(poList);
		return box;
	}

	private Div buildLineBox() {
		Div box = new Div();
		lineList.setHeight("300px");
		Listhead lh = new Listhead();
		lh.setSizable(true);
		lh.appendChild(header("#", "40px"));
		lh.appendChild(header("PO No.", "100px"));
		lh.appendChild(header("Item Code", "120px"));
		lh.appendChild(header("Item Name", null));
		lh.appendChild(header("UOM", "60px"));
		lh.appendChild(header("Ordered", "90px"));
		lh.appendChild(header("Received", "90px"));
		lh.appendChild(header("Pending", "90px"));
		lh.appendChild(header("Receive Qty", "110px"));
		lh.appendChild(header("Pack Size", "100px"));
		lh.appendChild(header("No of Pack", "100px"));
		lh.appendChild(header("Challan Qty", "110px"));
		lh.appendChild(header("", "60px"));
		lineList.appendChild(lh);
		box.appendChild(lineList);
		return box;
	}

	private static Listheader header(String text, String width) {
		Listheader h = new Listheader(text);
		if (width != null)
			h.setWidth(width);
		else
			h.setHflex("1");
		return h;
	}

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

	private Div field(String text, boolean required, Component c, int span) {
		Div d = new Div();
		d.setSclass("xx-field" + (span == 2 ? " xx-span2" : span == 3 ? " xx-span3" : span == 6 ? " xx-span6" : ""));
		Label l = new Label(text);
		l.setSclass("form-label" + (required ? " xx-req-mark" : ""));
		d.appendChild(l);
		d.appendChild(c);
		return d;
	}

	private Div readonly(Label l) {
		Div d = new Div();
		d.setSclass("form-control xx-readonly");
		d.appendChild(l);
		return d;
	}

	private static Combobox newCombo(String placeholder) {
		Combobox cb = new Combobox();
		cb.setAutodrop(true);
		cb.setAutocomplete(true);
		cb.setButtonVisible(true);
		cb.setPlaceholder(placeholder);
		ZKUpdateUtil.setWidth(cb, "100%");
		return cb;
	}

	private static void warn(String msg) {
		Messagebox.show(msg, "Goods Receipt", Messagebox.OK, Messagebox.EXCLAMATION);
	}

	// =====================================================================
	// header data
	// =====================================================================

	/** (re)fills a combo from {id, name} pairs */
	private void fillCombo(Combobox cb, List<Object[]> rows) {
		while (cb.getItemCount() > 0)
			cb.removeItemAt(0);
		cb.setValue("");
		for (Object[] r : rows) {
			Comboitem ci = new Comboitem((String) r[1]);
			ci.setAttribute("id", r[0]);
			cb.appendChild(ci);
		}
	}

	private int idOf(Combobox cb) {
		Comboitem ci = cb.getSelectedItem();
		if (ci == null)
			return 0;
		Object v = ci.getAttribute("id");
		return v instanceof Integer ? ((Integer) v).intValue() : 0;
	}

	/** typed text that is not picked yet: the first entry that starts with it is taken (Enter / leaving the box) */
	private void resolve(Combobox cb) {
		if (cb.getSelectedItem() != null)
			return;
		String t = cb.getValue() == null ? "" : cb.getValue().trim().toLowerCase();
		if (t.length() == 0)
			return;
		for (Object o : cb.getItems()) {
			Comboitem ci = (Comboitem) o;
			if (ci.getLabel() != null && ci.getLabel().toLowerCase().startsWith(t)) {
				cb.setSelectedItem(ci);
				return;
			}
		}
	}

	private void selectFirst(Combobox cb) {
		if (cb.getItemCount() > 0)
			cb.setSelectedIndex(0);
	}

	private void loadHeaderData() {
		MOrg org = orgId > 0 ? MOrg.get(Env.getCtx(), orgId) : null;
		lblOrg.setValue(org != null ? org.getName() : "(select an organization at login)");

		List<Object[]> types = new ArrayList<Object[]>();
		List<MDocType> dts = new Query(Env.getCtx(), MDocType.Table_Name,
				"DocBaseType=? AND IsSOTrx='N' AND AD_Client_ID=?", null).setParameters(MDocType.DOCBASETYPE_MaterialReceipt, clientId)
				.setOnlyActiveRecords(true).setOrderBy("IsDefault DESC, Name").list();
		for (MDocType d : dts)
			types.add(new Object[] { Integer.valueOf(d.getC_DocType_ID()), d.getName() });
		fillCombo(cbDocType, types);
		selectFirst(cbDocType);

		List<Object[]> whs = new ArrayList<Object[]>();
		Query wq = orgId > 0
				? new Query(Env.getCtx(), MWarehouse.Table_Name, "AD_Client_ID=? AND (AD_Org_ID=? OR AD_Org_ID=0)", null)
						.setParameters(clientId, orgId)
				: new Query(Env.getCtx(), MWarehouse.Table_Name, "AD_Client_ID=?", null).setParameters(clientId);
		for (MWarehouse w : wq.setOnlyActiveRecords(true).setOrderBy("Name").<MWarehouse>list())
			whs.add(new Object[] { Integer.valueOf(w.getM_Warehouse_ID()), w.getName() });
		fillCombo(cbWarehouse, whs);
		warehouseChanged();
	}

	private void warehouseChanged() {
		int wh = idOf(cbWarehouse);
		List<Object[]> locs = new ArrayList<Object[]>();
		if (wh > 0) {
			List<MLocator> ls = new Query(Env.getCtx(), MLocator.Table_Name, "M_Warehouse_ID=?", null)
					.setParameters(wh).setOnlyActiveRecords(true).setOrderBy("IsDefault DESC, Value").list();
			for (MLocator l : ls)
				locs.add(new Object[] { Integer.valueOf(l.getM_Locator_ID()), l.getValue() });
		}
		fillCombo(cbLocator, locs);
		selectFirst(cbLocator);
	}

	private void selectWarehouse(int id) {
		for (Object o : cbWarehouse.getItems()) {
			Comboitem ci = (Comboitem) o;
			Object v = ci.getAttribute("id");
			if (v instanceof Integer && ((Integer) v).intValue() == id) {
				cbWarehouse.setSelectedItem(ci);
				warehouseChanged();
				return;
			}
		}
	}

	// =====================================================================
	// events
	// =====================================================================

	private void wireEvents() {
		cbWarehouse.addEventListener(Events.ON_CHANGE, ev -> {
			resolve(cbWarehouse);
			warehouseChanged();
		});
		cbWarehouse.addEventListener(Events.ON_SELECT, ev -> warehouseChanged());
		cbWarehouse.addEventListener(Events.ON_OK, ev -> {
			resolve(cbWarehouse);
			warehouseChanged();
		});
		for (final Combobox c : new Combobox[] { cbDocType, cbLocator }) {
			c.addEventListener(Events.ON_CHANGE, ev -> resolve(c));
			c.addEventListener(Events.ON_OK, ev -> resolve(c));
		}
		btnSearch.addEventListener(Events.ON_CLICK, ev -> loadPOs());
		btnRefresh.addEventListener(Events.ON_CLICK, ev -> {
			txtSearch.setValue("");
			loadPOs();
		});
		txtSearch.addEventListener(Events.ON_OK, ev -> loadPOs());
		poList.addEventListener(Events.ON_SELECT, ev -> reloadLines());
		btnAttach.addEventListener(Events.ON_CLICK, ev -> openAttachments());
		btnOk.addEventListener(Events.ON_CLICK, ev -> save());
		btnReload.addEventListener(Events.ON_CLICK, ev -> {
			if (lines.isEmpty()) {
				doReload();
				return;
			}
			Messagebox.show("Reload the form?\n\nThe ticked PO(s) and everything you entered will be cleared.",
					"Goods Receipt", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
					(org.zkoss.zk.ui.event.Event e2) -> {
						if (Messagebox.ON_YES.equals(e2.getName()))
							doReload();
					});
		});
		btnCancel.addEventListener(Events.ON_CLICK, ev -> close());
	}

	/** clears the form and loads the header data and the purchase orders again */
	private void doReload() {
		resetForm();
		txtSearch.setValue("");
		poList.clearSelection();
		loadHeaderData();
		loadPOs();
	}

	private void close() {
		try {
			SessionManager.getAppDesktop().closeActiveWindow();
		} catch (Exception e) {
			resetForm();
		}
	}

	// =====================================================================
	// purchase orders
	// =====================================================================

	private void loadPOs() {
		// keep what is ticked while the list is searched again
		Set<Integer> ticked = selectedOrderIds();
		while (poList.getItemCount() > 0)
			poList.removeItemAt(0);
		pos.clear();

		String where = "IsSOTrx='N' AND DocStatus=? AND AD_Client_ID=?" + (orgId > 0 ? " AND AD_Org_ID=?" : "");
		List<Object> p = new ArrayList<Object>();
		p.add(DocAction.STATUS_Completed);
		p.add(Integer.valueOf(clientId));
		if (orgId > 0)
			p.add(Integer.valueOf(orgId));
		List<MOrder> orders = new Query(Env.getCtx(), MOrder.Table_Name, where, null).setParameters(p)
				.setOrderBy("DateOrdered DESC, DocumentNo DESC").list();

		String q = txtSearch.getValue() == null ? "" : txtSearch.getValue().trim().toUpperCase();
		Map<Integer, String> whNames = new HashMap<Integer, String>();
		for (MOrder o : orders) {
			if (pos.size() >= MAX_PO)
				break;
			MBPartner bp = MBPartner.get(Env.getCtx(), o.getC_BPartner_ID());
			String supplier = bp == null ? "" : bp.getName();
			String docNo = o.getDocumentNo() == null ? "" : o.getDocumentNo();
			if (q.length() > 0 && !docNo.toUpperCase().contains(q) && !supplier.toUpperCase().contains(q))
				continue;
			int pending = 0;
			for (MOrderLine ol : o.getLines())
				if (ol.getM_Product_ID() > 0 && ol.getQtyOrdered().subtract(ol.getQtyDelivered()).signum() > 0)
					pending++;
			if (pending == 0)
				continue;
			Po po = new Po();
			po.id = o.getC_Order_ID();
			po.warehouseId = o.getM_Warehouse_ID();
			po.docNo = docNo;
			po.date = o.getDateOrdered();
			po.supplier = supplier;
			if (po.warehouseId > 0) {
				String wn = whNames.get(Integer.valueOf(po.warehouseId));
				if (wn == null) {
					wn = MWarehouse.get(Env.getCtx(), po.warehouseId).getName();
					whNames.put(Integer.valueOf(po.warehouseId), wn);
				}
				po.warehouse = wn;
			}
			po.total = o.getGrandTotal();
			po.pending = pending;
			pos.add(po);
		}

		for (Po po : pos) {
			Listitem li = new Listitem();
			li.appendChild(new Listcell(po.docNo));
			li.appendChild(new Listcell(dmy(po.date)));
			li.appendChild(new Listcell(po.supplier));
			li.appendChild(new Listcell(po.warehouse == null ? "" : po.warehouse));
			Listcell c = new Listcell(String.valueOf(po.pending));
			c.setStyle("text-align:right;");
			li.appendChild(c);
			Listcell t = new Listcell(money(po.total));
			t.setStyle("text-align:right;");
			li.appendChild(t);
			poList.appendChild(li);
			if (ticked.contains(Integer.valueOf(po.id)))
				poList.addItemToSelection(li);
		}
		lblPoCount.setValue(pos.size() + " completed PO(s) waiting for receipt");
		reloadLines();
	}

	private Set<Integer> selectedOrderIds() {
		Set<Integer> ids = new java.util.LinkedHashSet<Integer>();
		for (Listitem li : poList.getSelectedItems()) {
			int ix = li.getIndex();
			if (ix >= 0 && ix < pos.size())
				ids.add(Integer.valueOf(pos.get(ix).id));
		}
		return ids;
	}

	private static String dmy(Timestamp t) {
		return t == null ? "" : new java.text.SimpleDateFormat("dd-MM-yyyy").format(t);
	}

	private static String money(BigDecimal v) {
		return v == null ? "" : String.format("%,.2f", v);
	}

	private static String num(BigDecimal v) {
		if (v == null)
			return "0";
		BigDecimal s = v.stripTrailingZeros();
		if (s.scale() < 0)
			s = s.setScale(0);
		return s.toPlainString();
	}

	// =====================================================================
	// lines
	// =====================================================================

	private void reloadLines() {
		for (GLine g : lines) {
			Integer k = Integer.valueOf(g.olId);
			keepQty.put(k, g.qty);
			keepPack.put(k, g.packSize);
			keepNoPack.put(k, g.noPack);
			keepChQty.put(k, g.challanQty);
		}
		Set<Integer> sel = selectedOrderIds();
		for (Integer id : sel)
			if (!prevSel.contains(id))
				removedByOrder.remove(id); // ticked again: all its lines come back
		prevSel = new HashSet<Integer>(sel);
		lines.clear();

		if (!sel.isEmpty()) {
			// ticked POs in the order they are listed
			for (Po po : pos) {
				if (!sel.contains(Integer.valueOf(po.id)))
					continue;
				MOrder o = new MOrder(Env.getCtx(), po.id, null);
				Set<Integer> rem = removedByOrder.get(Integer.valueOf(po.id));
				for (MOrderLine ol : o.getLines()) {
					if (ol.getM_Product_ID() <= 0)
						continue;
					BigDecimal pend = ol.getQtyOrdered().subtract(ol.getQtyDelivered());
					if (pend.signum() <= 0)
						continue;
					if (rem != null && rem.contains(Integer.valueOf(ol.getC_OrderLine_ID())))
						continue;
					GLine g = new GLine();
					g.olId = ol.getC_OrderLine_ID();
					g.orderId = po.id;
					g.poNo = po.docNo;
					MProduct prod = MProduct.get(Env.getCtx(), ol.getM_Product_ID());
					g.code = prod.getValue();
					g.name = prod.getName();
					MUOM uom = ol.getC_UOM_ID() > 0 ? MUOM.get(Env.getCtx(), ol.getC_UOM_ID()) : null;
					g.uom = uom == null ? "" : (uom.getUOMSymbol() != null ? uom.getUOMSymbol() : uom.getName());
					g.ordered = ol.getQtyOrdered();
					g.received = ol.getQtyDelivered();
					g.pending = pend;
					Integer key = Integer.valueOf(g.olId);
					BigDecimal k = keepQty.get(key);
					g.qty = k != null ? k : pend;
					String kp = keepPack.get(key);
					g.packSize = kp == null ? "" : kp;
					g.noPack = keepNoPack.get(key);
					g.challanQty = keepChQty.get(key);
					lines.add(g);
				}
			}
			// the warehouse of the first ticked PO, when none is chosen yet
			if (idOf(cbWarehouse) <= 0)
				for (Po po : pos)
					if (sel.contains(Integer.valueOf(po.id)) && po.warehouseId > 0) {
						selectWarehouse(po.warehouseId);
						break;
					}
		}
		refreshLines();
	}

	private void refreshLines() {
		while (lineList.getItemCount() > 0)
			lineList.removeItemAt(0);
		int n = 0;
		for (final GLine g : lines) {
			n++;
			Listitem li = new Listitem();
			li.appendChild(new Listcell(String.valueOf(n)));
			li.appendChild(new Listcell(g.poNo));
			li.appendChild(new Listcell(g.code));
			li.appendChild(new Listcell(g.name));
			li.appendChild(new Listcell(g.uom));
			li.appendChild(right(num(g.ordered)));
			li.appendChild(right(num(g.received)));
			li.appendChild(right(num(g.pending)));

			// Receive Qty
			Listcell qc = new Listcell();
			final Decimalbox db = new Decimalbox();
			db.setFormat("#,##0.####");
			db.setValue(g.qty);
			ZKUpdateUtil.setWidth(db, "100%");
			db.addEventListener(Events.ON_CHANGE, ev -> {
				BigDecimal v = db.getValue();
				g.qty = v == null ? BigDecimal.ZERO : v;
				updateTotals();
			});
			qc.appendChild(db);
			li.appendChild(qc);

			// Pack Size
			Listcell pc = new Listcell();
			final Textbox tbPack = new Textbox();
			tbPack.setMaxlength(60);
			tbPack.setValue(g.packSize == null ? "" : g.packSize);
			ZKUpdateUtil.setWidth(tbPack, "100%");
			tbPack.addEventListener(Events.ON_CHANGE, ev -> g.packSize = txt(tbPack));
			pc.appendChild(tbPack);
			li.appendChild(pc);

			// No of Pack
			Listcell nc = new Listcell();
			final Decimalbox dbNo = new Decimalbox();
			dbNo.setFormat("#,##0.####");
			dbNo.setValue(g.noPack);
			ZKUpdateUtil.setWidth(dbNo, "100%");
			dbNo.addEventListener(Events.ON_CHANGE, ev -> g.noPack = dbNo.getValue());
			nc.appendChild(dbNo);
			li.appendChild(nc);

			// Challan Qty
			Listcell cc = new Listcell();
			final Decimalbox dbCh = new Decimalbox();
			dbCh.setFormat("#,##0.####");
			dbCh.setValue(g.challanQty);
			ZKUpdateUtil.setWidth(dbCh, "100%");
			dbCh.addEventListener(Events.ON_CHANGE, ev -> g.challanQty = dbCh.getValue());
			cc.appendChild(dbCh);
			li.appendChild(cc);

			Listcell rm = new Listcell();
			Button x = new Button("\u2716");
			x.setSclass("btn btn-sm btn-outline-danger");
			x.setTooltiptext("Remove this line");
			x.addEventListener(Events.ON_CLICK, ev -> {
				Set<Integer> rem = removedByOrder.get(Integer.valueOf(g.orderId));
				if (rem == null) {
					rem = new HashSet<Integer>();
					removedByOrder.put(Integer.valueOf(g.orderId), rem);
				}
				rem.add(Integer.valueOf(g.olId));
				lines.remove(g);
				refreshLines();
			});
			rm.appendChild(x);
			li.appendChild(rm);
			lineList.appendChild(li);
		}
		updateTotals();
	}

	private static Listcell right(String s) {
		Listcell c = new Listcell(s);
		c.setStyle("text-align:right;");
		return c;
	}

	private void updateTotals() {
		BigDecimal q = BigDecimal.ZERO;
		for (GLine g : lines)
			q = q.add(g.qty);
		lblTotals.setValue(lines.size() + " line(s)   |   Total Receive Qty: " + num(q));
	}

	// =====================================================================
	// attachments pop-up (same as the Requisition form)
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
		lh.appendChild(header("#", "40px"));
		lh.appendChild(header("File name", null));
		lh.appendChild(header("Size", "90px"));
		lh.appendChild(header("", "60px"));
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
				Button x = new Button("\u2716");
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
		Button done = new Button("\u2714  OK");
		done.setSclass("btn btn-success");
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

	// =====================================================================
	// save
	// =====================================================================

	private void save() {
		if (orgId <= 0) {
			warn("Log in with a specific organization (not *) to create a GRN.");
			return;
		}
		resolve(cbDocType);
		resolve(cbWarehouse);
		resolve(cbLocator);
		final int docTypeId = idOf(cbDocType);
		final int whId = idOf(cbWarehouse);
		final int locId = idOf(cbLocator);
		if (docTypeId <= 0) {
			warn("GRN Document is required.");
			return;
		}
		if (whId <= 0) {
			warn("Warehouse is required.");
			return;
		}
		if (locId <= 0) {
			warn("Locator is required (the warehouse has no locator, or none is chosen).");
			return;
		}
		if (dtMove.getValue() == null) {
			warn("GRN Date is required.");
			return;
		}
		final Timestamp moveDate = new Timestamp(dtMove.getValue().getTime());

		Map<Integer, List<GLine>> byOrder = new LinkedHashMap<Integer, List<GLine>>();
		for (GLine g : lines) {
			if (g.qty == null || g.qty.signum() <= 0)
				continue;
			if (g.qty.compareTo(g.pending) > 0) {
				warn("PO " + g.poNo + " / " + g.code + ": Receive Qty " + num(g.qty) + " is more than the pending Qty "
						+ num(g.pending) + ".");
				return;
			}
			List<GLine> l = byOrder.get(Integer.valueOf(g.orderId));
			if (l == null) {
				l = new ArrayList<GLine>();
				byOrder.put(Integer.valueOf(g.orderId), l);
			}
			l.add(g);
		}
		if (byOrder.isEmpty()) {
			warn("Tick at least one PO and enter a Receive Qty above 0.");
			return;
		}

		final String vatNo = txt(txtVatNo);
		final String delNo = txt(txtDelNo);
		final Timestamp vatDate = ts(dtVat);
		final Timestamp delDate = ts(dtDel);

		// values that cannot be saved because the database has no such column
		final List<String> notSaved = new ArrayList<String>();
		if (vatNo.length() > 0 && colVatNo == null)
			notSaved.add("VAT Challan No");
		if (vatDate != null && colVatDate == null)
			notSaved.add("VAT Challan Date");
		if (delNo.length() > 0 && colDelNo == null)
			notSaved.add("Delivery Challan No");
		if (delDate != null && colDelDate == null)
			notSaved.add("Delivery Challan Date");
		boolean anyPack = false, anyNoPack = false, anyChQty = false;
		for (List<GLine> l : byOrder.values())
			for (GLine g : l) {
				if (g.packSize != null && g.packSize.trim().length() > 0)
					anyPack = true;
				if (g.noPack != null)
					anyNoPack = true;
				if (g.challanQty != null)
					anyChQty = true;
			}
		if (anyPack && colPack == null)
			notSaved.add("Pack Size");
		if (anyNoPack && colNoPack == null)
			notSaved.add("No of Pack");
		if (anyChQty && colChQty == null)
			notSaved.add("Challan Qty");

		final String trxName = Trx.createTrxName("XXGRN");
		Trx trx = Trx.get(trxName, true);
		try {
			final List<Integer> ids = new ArrayList<Integer>();
			final List<String> nos = new ArrayList<String>();
			String ref = txtRef.getValue() == null ? "" : txtRef.getValue().trim();
			String desc = txtDesc.getValue() == null ? "" : txtDesc.getValue().trim();

			for (Map.Entry<Integer, List<GLine>> e : byOrder.entrySet()) {
				MOrder po = new MOrder(Env.getCtx(), e.getKey().intValue(), trxName);
				MInOut io = new MInOut(po, docTypeId, moveDate);
				io.setM_Warehouse_ID(whId);
				io.setMovementType(MInOut.MOVEMENTTYPE_VendorReceipts);
				io.setIsSOTrx(false);
				if (ref.length() > 0)
					io.setPOReference(ref);
				if (desc.length() > 0)
					io.setDescription(desc);
				if (vatNo.length() > 0)
					putCol(io, colVatNo, vatNo);
				if (vatDate != null)
					putCol(io, colVatDate, vatDate);
				if (delNo.length() > 0)
					putCol(io, colDelNo, delNo);
				if (delDate != null)
					putCol(io, colDelDate, delDate);
				io.saveEx();

				for (GLine g : e.getValue()) {
					MOrderLine ol = new MOrderLine(Env.getCtx(), g.olId, trxName);
					MInOutLine iol = new MInOutLine(io);
					iol.setOrderLine(ol, locId, g.qty);
					iol.setQty(g.qty);
					if (g.packSize != null && g.packSize.trim().length() > 0)
						putCol(iol, colPack, g.packSize.trim());
					if (g.noPack != null)
						putCol(iol, colNoPack, g.noPack);
					if (g.challanQty != null)
						putCol(iol, colChQty, g.challanQty);
					iol.saveEx();
				}

				io.setDocAction(DocAction.ACTION_Complete);
				if (!io.processIt(DocAction.ACTION_Complete))
					throw new AdempiereException("PO " + po.getDocumentNo() + ": " + io.getProcessMsg());
				io.saveEx();

				if (!attachments.isEmpty()) {
					MAttachment att = new MAttachment(Env.getCtx(), MInOut.Table_ID, io.get_ID(), trxName);
					for (Media m : attachments) {
						byte[] bytes = m.isBinary() ? m.getByteData() : m.getStringData().getBytes("UTF-8");
						att.addEntry(m.getName(), bytes);
					}
					att.saveEx();
				}
				ids.add(Integer.valueOf(io.get_ID()));
				nos.add(io.getDocumentNo());
			}

			trx.commit(true);
			resetForm();
			loadPOs();
			String note = notSaved.isEmpty() ? ""
					: "\n\nNOT saved (no matching column found in the database): " + String.join(", ", notSaved) + ".";
			Messagebox.show("GRN " + String.join(", ", nos) + " saved and completed." + note
					+ "\n\nDo you want to view the GRN PDF?",
					"Goods Receipt", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
					(org.zkoss.zk.ui.event.Event ev) -> {
						if (Messagebox.ON_YES.equals(ev.getName()))
							showPdf(ids, String.join("_", nos));
					});
		} catch (Exception ex) {
			trx.rollback();
			log.log(Level.SEVERE, ex.getMessage(), ex);
			Messagebox.show("Could not save the GRN:\n" + ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
		} finally {
			trx.close();
		}
	}

	private void resetForm() {
		lines.clear();
		keepQty.clear();
		keepPack.clear();
		keepNoPack.clear();
		keepChQty.clear();
		removedByOrder.clear();
		prevSel = new HashSet<Integer>();
		attachments.clear();
		updateAttachLabel();
		txtRef.setValue("");
		txtVatNo.setValue("");
		dtVat.setValue(null);
		txtDelNo.setValue("");
		dtDel.setValue(null);
		txtDesc.setValue("");
		dtMove.setValue(new Date());
		refreshLines();
	}

	// =====================================================================
	// PDF
	// =====================================================================

	private static final String PDF_JRXML = "/org/mycompany/requisition/GoodsReceipt.jrxml";

	private String logoPath(String resource) {
		try (java.io.InputStream in = WGoodsReceiptForm.class.getResourceAsStream(resource)) {
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

	/** one PDF, one page per GRN */
	private byte[] buildPdf(List<Integer> ids) throws Exception {
		StringBuilder in = new StringBuilder();
		for (Integer id : ids) {
			if (in.length() > 0)
				in.append(',');
			in.append(id.intValue()); // whole numbers only
		}
		try (java.io.InputStream jr = WGoodsReceiptForm.class.getResourceAsStream(PDF_JRXML)) {
			if (jr == null)
				throw new AdempiereException(PDF_JRXML + " not found in the bundle");
			net.sf.jasperreports.engine.JasperReport report = net.sf.jasperreports.engine.JasperCompileManager
					.compileReport(jr);
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("IDS", in.toString());
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

	private void showPdf(List<Integer> ids, String docNos) {
		try {
			byte[] pdf = buildPdf(ids);
			org.zkoss.util.media.AMedia media = new org.zkoss.util.media.AMedia("GRN_" + docNos + ".pdf", "pdf",
					"application/pdf", pdf);
			showViewer("GRN " + docNos, media);
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			warn("Could not create the PDF:\n" + e.getMessage());
		}
	}
}