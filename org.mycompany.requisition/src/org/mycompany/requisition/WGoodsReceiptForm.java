package org.mycompany.requisition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.compiere.model.MTable;
import org.compiere.model.MUOM;
import org.compiere.model.MUser;
import org.compiere.model.MWarehouse;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.model.Query;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.InputEvent;
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
 * 1. Pick ONE completed purchase order in the "Search PO No. or Supplier" box (dropdown: PO No | Date | Supplier | PO Total).
 *    Supplier, PO Date, PO Total and Warehouse are loaded into the header and the pending lines of the PO
 *    appear in the Receipt Line table.
 * 2. Receive Qty is pre-filled with the pending qty. Less or more (within the tolerance of the rule picked) can be received.
 *    Each line has a Partial / Complete button: Partial keeps the PO line open for the rest, Complete closes it with this receipt.
 * 3. Damaged Qty is part of the Receive Qty. Only the Good Qty (Receive - Damaged) goes to stock. The supplier rate stays the
 *    same, so the damaged qty does not reduce the payable: Net Cost / Unit = (Rate x Receive Qty) / Good Qty.
 * 4. Save creates ONE completed material receipt (M_InOut, MovementType V+) linked to the PO lines.
 *    The whole calculation is written on the receipt line (Description, and the DamagedQty / EffCost columns when they exist)
 *    and the GRN PDF reads it from there.
 * <p>
 * Header: VAT Challan No / Date and Delivery Challan No / Date are looked up by name in the Application Dictionary
 * (see resolveColumns); a value whose column cannot be found is not saved and the user is told after saving.
 * <p>
 * Attachments work exactly like the Requisition form: + Add File, click a name to open it, the cross removes it, OK closes.
 */
public class WGoodsReceiptForm extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WGoodsReceiptForm.class);

	private static final int MAX_PO = 300;
	private static final String CSS_VERSION = "g6";
	/** tolerance rates (custom table); its columns are found by name, see resolveColumns */
	private static final String TOL_TABLE = "T_ToleranceRateMR";
	private static final BigDecimal HUNDRED = new BigDecimal(100);

	private final int clientId = Env.getAD_Client_ID(Env.getCtx());
	private final int orgId = Env.getAD_Org_ID(Env.getCtx());

	// header
	private final Label lblOrg = new Label();
	private final Label lblSupplier = new Label();
	private final Label lblPoDate = new Label();
	private final Label lblPoTotal = new Label();
	private Combobox cbPo;
	private Combobox cbDocType;
	private Combobox cbWarehouse;
	private Combobox cbLocator;
	private Combobox cbTolerance;
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

	// PO search
	private final Button btnSearch = new Button("Search");
	private final Button btnRefresh = new Button("Refresh");
	private final Label lblPoCount = new Label();
	private final List<Po> pos = new ArrayList<Po>();
	private int selectedPoId = 0;

	// lines
	private final Listbox lineList = new Listbox();
	private final Label lblTotals = new Label();
	private final List<GLine> lines = new ArrayList<GLine>();
	private final Set<Integer> removedLines = new HashSet<Integer>();

	private final Button btnOk = new Button("\u2714  Save GRN");
	private final Button btnReload = new Button("\u21BB  Reload");
	private final Button btnCancel = new Button("\u2716  Cancel");

	// what the GRN PDF shows about the tolerance rule that was picked when the GRN was saved
	private String pdfTolerance = "";
	private String pdfSegment = "";

	// database columns of the challan / pack fields (found by name, null when the column does not exist)
	private String colVatNo;
	private String colVatDate;
	private String colDelNo;
	private String colDelDate;
	private String colPack;
	private String colNoPack;
	private String colChQty;
	private String colDamQty;
	private String colEffCost;
	// columns of the tolerance table
	private String colTolRate;
	private String colTolFrom;
	private String colTolDocType;
	private String colTolSegment;

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
		BigDecimal price = BigDecimal.ZERO;
		BigDecimal qty = BigDecimal.ZERO;
		boolean complete;
		String packSize = "";
		BigDecimal noPack;
		BigDecimal challanQty;
		BigDecimal damaged;
		String damageReason = "";
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
.xx-req .xx-span2 { grid-column:span 2; }
.xx-req .xx-span3 { grid-column:span 3; }
.xx-req .xx-span4 { grid-column:span 4; }
.xx-req .xx-span6 { grid-column:1 / -1; }
@media (max-width:1500px) { .xx-req .xx-grid { grid-template-columns:repeat(4,minmax(0,1fr)); } }
@media (max-width:1000px) { .xx-req .xx-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } .xx-req .xx-span2, .xx-req .xx-span3, .xx-req .xx-span4 { grid-column:span 2; } }
@media (max-width:600px)  { .xx-req .xx-grid { grid-template-columns:minmax(0,1fr); } .xx-req .xx-span2, .xx-req .xx-span3, .xx-req .xx-span4 { grid-column:span 1; } }
.xx-req .xx-grid.xx-grid8 { grid-template-columns:repeat(8,minmax(0,1fr)); }
.xx-req .xx-span5 { grid-column:span 5; }
@media (max-width:900px) { .xx-req .xx-grid.xx-grid8 { grid-template-columns:repeat(4,minmax(0,1fr)); } .xx-req .xx-span5 { grid-column:1 / -1; } }
@media (max-width:600px)  { .xx-req .xx-grid.xx-grid8 { grid-template-columns:minmax(0,1fr); } .xx-req .xx-span5 { grid-column:span 1; } }
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
/* top bar: stays in view (Reload button) while the page scrolls */
.xx-req .xx-topbar { position:sticky; top:0; z-index:25; display:flex; justify-content:space-between; align-items:center; gap:.5rem; padding:.4rem .85rem; background:#f1f4f9; border-bottom:1px solid #dee2e6; box-shadow:0 2px 4px rgba(16,24,40,.06); }
.xx-req .xx-top-title, .xx-req .xx-top-title.z-label { font-size:.95rem; font-weight:700; color:#0a2e6b; }
/* line tables: a visible box round every line and cell */
.xx-req .z-listbox, .xx-reqwin .z-listbox { min-height:200px; border:1px solid #adb5bd !important; border-radius:.375rem; }
/* light table header (same colours as the PDF): no dark gradient */
.xx-req .z-listbox .z-listhead, .xx-reqwin .z-listbox .z-listhead { background:#E6EDF5 !important; background-image:none !important; }
.xx-req .z-listbox .z-listheader, .xx-reqwin .z-listbox .z-listheader { background:#E6EDF5 !important; background-image:none !important; border-right:1px solid #C5D2E2 !important; border-bottom:1px solid #C5D2E2 !important; }
.xx-req .z-listbox .z-listheader:hover, .xx-reqwin .z-listbox .z-listheader:hover { background:#DCE6F2 !important; }
.xx-req .z-listbox .z-listheader-content, .xx-reqwin .z-listbox .z-listheader-content { color:#27415F !important; font-weight:700; font-size:.78rem; white-space:normal !important; line-height:1.15; }
.xx-req .z-listbox .z-listcell, .xx-reqwin .z-listbox .z-listcell { border-right:1px solid #ced4da !important; border-bottom:1px solid #ced4da !important; }
.xx-req .z-listbox .z-listitem:nth-child(even) > .z-listcell, .xx-reqwin .z-listbox .z-listitem:nth-child(even) > .z-listcell { background:#f8fafc; }
.xx-req .z-listbox .z-listitem:hover > .z-listcell, .xx-reqwin .z-listbox .z-listitem:hover > .z-listcell { background:#e7f1ff; }
.xx-req .z-listbox .z-listcell .z-decimalbox { min-height:24px; text-align:right; }
.xx-req .z-listbox .z-listcell .z-textbox { min-height:24px; }
/* the Receipt Line grid grows with its lines (no fixed height), so the scroll bar sits right under the last line */
.xx-req .z-listbox.xx-lines { min-height:0; }
.xx-req .xx-footer { position:sticky; bottom:0; z-index:5; display:flex; justify-content:space-between; align-items:center; gap:.5rem; padding:.4rem .75rem; margin:6px 10px; background:#fff; border:1px solid #dee2e6; border-radius:.5rem; box-shadow:0 .125rem .25rem rgba(0,0,0,.075); }
.xx-req .xx-foot-grp { display:flex; align-items:center; gap:.5rem; }
/* Description: same size as the other fields; drag the corner to make it bigger (and smaller again) */
.xx-req textarea.xx-desc { height:26px; min-height:26px; max-height:320px; resize:vertical; overflow:auto; line-height:1.4; }
""";
	
	

//	private static final String CSS = """
//.xx-req { background:#f1f4f9; font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif; }
//.xx-req .xx-card { background:#fff; border:1px solid #dee2e6; border-radius:.5rem; box-shadow:0 .125rem .25rem rgba(0,0,0,.075); margin:6px 10px 0; overflow:visible; }
//.xx-req .xx-card-header { display:flex; align-items:center; padding:.25rem .75rem; background:#f8f9fa; border-bottom:1px solid #dee2e6; border-radius:.5rem .5rem 0 0; font-weight:600; font-size:.82rem; color:#212529; }
//.xx-req .xx-card-header::before { content:""; width:4px; height:1.1em; background:#0d6efd; border-radius:2px; margin-right:.6rem; }
//.xx-req .xx-card-body { padding:.5rem .75rem .6rem; }
//.xx-req .xx-grid { display:grid; grid-template-columns:repeat(6,minmax(0,1fr)); gap:.4rem .6rem; text-align:left; }
//.xx-req .xx-span2 { grid-column:span 2; }
//.xx-req .xx-span3 { grid-column:span 3; }
//.xx-req .xx-span4 { grid-column:span 4; }
//.xx-req .xx-span6 { grid-column:1 / -1; }
//@media (max-width:1500px) { .xx-req .xx-grid { grid-template-columns:repeat(4,minmax(0,1fr)); } }
//@media (max-width:1000px) { .xx-req .xx-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } .xx-req .xx-span2, .xx-req .xx-span3, .xx-req .xx-span4 { grid-column:span 2; } }
//@media (max-width:600px)  { .xx-req .xx-grid { grid-template-columns:minmax(0,1fr); } .xx-req .xx-span2, .xx-req .xx-span3, .xx-req .xx-span4 { grid-column:span 1; } }
//.xx-req .xx-grid.xx-grid8 { grid-template-columns:repeat(8,minmax(0,1fr)); }
//.xx-req .xx-span5 { grid-column:span 5; }
//@media (max-width:900px) { .xx-req .xx-grid.xx-grid8 { grid-template-columns:repeat(4,minmax(0,1fr)); } .xx-req .xx-span5 { grid-column:1 / -1; } }
//@media (max-width:600px)  { .xx-req .xx-grid.xx-grid8 { grid-template-columns:minmax(0,1fr); } .xx-req .xx-span5 { grid-column:span 1; } }
//.xx-req .xx-field { min-width:0; }
//.xx-req .form-label { display:block; margin-bottom:.1rem; text-align:left; font-size:.72rem; line-height:1.2; font-weight:600; color:#495057; }
//.xx-req .form-label.xx-req-mark { color:#dc3545; }
//.xx-req .form-control, .xx-req .z-textbox, .xx-req .z-decimalbox, .xx-req .z-datebox-input, .xx-req .z-combobox-input {
//  display:block; width:100%; min-height:26px; padding:.15rem .5rem; font-size:.8rem; line-height:1.4; color:#212529;
//  background:#fff; border:1px solid #ced4da !important; border-radius:.375rem !important; box-shadow:none; }
//.xx-req .z-datebox, .xx-req .z-combobox { width:100%; display:inline-flex; align-items:stretch; }
//.xx-req .z-datebox-input, .xx-req .z-combobox-input { flex:1 1 auto; min-width:0; border-top-right-radius:0 !important; border-bottom-right-radius:0 !important; }
//.xx-req .z-datebox-button, .xx-req .z-combobox-button { display:flex; align-items:center; justify-content:center; width:26px; border:1px solid #ced4da !important; border-left:0 !important; border-radius:0 .375rem .375rem 0 !important; background:#e9ecef; color:#495057; cursor:pointer; }
//.xx-req .z-datebox-button:hover, .xx-req .z-combobox-button:hover { background:#dde1e5; }
//.xx-req .z-textbox:focus, .xx-req .z-decimalbox:focus, .xx-req .z-datebox-input:focus, .xx-req .z-combobox-input:focus {
//  border-color:#86b7fe !important; box-shadow:0 0 0 .25rem rgba(13,110,253,.25) !important; outline:0; }
//.xx-req .z-textbox[disabled], .xx-req .z-decimalbox[disabled], .xx-req .z-datebox-input[disabled], .xx-req .z-combobox-input[disabled] { background:#e9ecef !important; color:#6c757d; }
//.xx-req .xx-readonly { background:#e9ecef; display:flex; align-items:center; min-height:26px; }
//.xx-req .xx-readonly .z-label { font-size:.8rem; color:#495057; }
//.xx-req .xx-attach-note { display:block; margin-top:.25rem; font-size:.75rem; color:#6c757d; }
//.z-combobox-popup, .z-combobox-content { border:1px solid #ced4da !important; border-radius:.375rem !important; box-shadow:0 .5rem 1rem rgba(0,0,0,.15) !important; }
//.z-comboitem { padding:.25rem .6rem !important; font-size:.8rem; }
//.z-comboitem-over, .z-comboitem-over-seld { background:#e7f1ff !important; }
//.z-comboitem-selected { background:#cfe2ff !important; }
//.xx-form.xx-req button.z-button.btn, .xx-reqwin button.z-button.btn { display:inline-flex; align-items:center; justify-content:center; padding:.15rem .7rem; min-height:26px; font-size:.8rem; font-weight:600; line-height:1.5; border-radius:.375rem !important; text-shadow:none !important; transform:none; transition:background-color .15s,border-color .15s,box-shadow .15s; }
//.xx-form.xx-req button.z-button.btn:hover, .xx-reqwin button.z-button.btn:hover { filter:none; transform:none; }
//.xx-form.xx-req button.z-button.btn:focus, .xx-reqwin button.z-button.btn:focus { box-shadow:0 0 0 .25rem rgba(13,110,253,.35); outline:0; }
//.xx-form.xx-req button.z-button.btn-block { width:100%; }
//.xx-form.xx-req button.z-button.btn-sm, .xx-reqwin button.z-button.btn-sm { min-height:26px; padding:.1rem .5rem; font-size:.75rem; }
//.xx-form.xx-req button.z-button.btn-primary, .xx-reqwin button.z-button.btn-primary { background:#0d6efd !important; border:1px solid #0d6efd !important; color:#fff !important; }
//.xx-form.xx-req button.z-button.btn-primary:hover, .xx-reqwin button.z-button.btn-primary:hover { background:#0b5ed7 !important; border-color:#0a58ca !important; }
//.xx-form.xx-req button.z-button.btn-success, .xx-reqwin button.z-button.btn-success { background:#198754 !important; border:1px solid #198754 !important; color:#fff !important; }
//.xx-form.xx-req button.z-button.btn-success:hover, .xx-reqwin button.z-button.btn-success:hover { background:#157347 !important; border-color:#146c43 !important; }
//.xx-form.xx-req button.z-button.btn-danger, .xx-reqwin button.z-button.btn-danger { background:#dc3545 !important; border:1px solid #dc3545 !important; color:#fff !important; }
//.xx-form.xx-req button.z-button.btn-danger:hover, .xx-reqwin button.z-button.btn-danger:hover { background:#bb2d3b !important; border-color:#b02a37 !important; }
//.xx-form.xx-req button.z-button.btn-outline-primary, .xx-reqwin button.z-button.btn-outline-primary { background:#fff !important; border:1px solid #0d6efd !important; color:#0d6efd !important; }
//.xx-form.xx-req button.z-button.btn-outline-primary:hover, .xx-reqwin button.z-button.btn-outline-primary:hover { background:#0d6efd !important; color:#fff !important; }
//.xx-form.xx-req button.z-button.btn-outline-danger, .xx-reqwin button.z-button.btn-outline-danger { background:#fff !important; border:1px solid #dc3545 !important; color:#dc3545 !important; box-shadow:none !important; }
//.xx-form.xx-req button.z-button.btn-outline-danger:hover, .xx-reqwin button.z-button.btn-outline-danger:hover { background:#dc3545 !important; color:#fff !important; }
//.xx-form.xx-req button.z-button.btn[disabled], .xx-reqwin button.z-button.btn[disabled] { opacity:.55; cursor:not-allowed; }
//.xx-req .xx-main { background:#f1f4f9; }
///* top bar: stays in view (Reload button) while the page scrolls */
//.xx-req .xx-topbar { position:sticky; top:0; z-index:25; display:flex; justify-content:space-between; align-items:center; gap:.5rem; padding:.4rem .85rem; background:#f1f4f9; border-bottom:1px solid #dee2e6; box-shadow:0 2px 4px rgba(16,24,40,.06); }
//.xx-req .xx-top-title, .xx-req .xx-top-title.z-label { font-size:.95rem; font-weight:700; color:#0a2e6b; }
///* line tables: a visible box round every line and cell */
//.xx-req .z-listbox, .xx-reqwin .z-listbox { min-height:200px; border:1px solid #adb5bd !important; border-radius:.375rem; }
//.xx-req .z-listbox .z-listheader, .xx-reqwin .z-listbox .z-listheader { border-right:1px solid rgba(255,255,255,.45) !important; }
//.xx-req .z-listbox .z-listcell, .xx-reqwin .z-listbox .z-listcell { border-right:1px solid #ced4da !important; border-bottom:1px solid #ced4da !important; }
//.xx-req .z-listbox .z-listitem:nth-child(even) > .z-listcell, .xx-reqwin .z-listbox .z-listitem:nth-child(even) > .z-listcell { background:#f8fafc; }
//.xx-req .z-listbox .z-listitem:hover > .z-listcell, .xx-reqwin .z-listbox .z-listitem:hover > .z-listcell { background:#e7f1ff; }
//.xx-req .z-listbox .z-listcell .z-decimalbox { min-height:24px; text-align:right; }
//.xx-req .z-listbox .z-listcell .z-textbox { min-height:24px; }
///* the Receipt Line grid grows with its lines (no fixed height), so the scroll bar sits right under the last line */
//.xx-req .z-listbox.xx-lines { min-height:0; }
//.xx-req .xx-footer { position:sticky; bottom:0; z-index:5; display:flex; justify-content:space-between; align-items:center; gap:.5rem; padding:.4rem .75rem; margin:6px 10px; background:#fff; border:1px solid #dee2e6; border-radius:.5rem; box-shadow:0 .125rem .25rem rgba(0,0,0,.075); }
//.xx-req .xx-foot-grp { display:flex; align-items:center; gap:.5rem; }
///* Description: same size as the other fields; drag the corner to make it bigger (and smaller again) */
//.xx-req textarea.xx-desc { height:26px; min-height:26px; max-height:320px; resize:vertical; overflow:auto; line-height:1.4; }
//""";

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

	private static final String COMBO_JS = """
(function(){
if(window.__xxComboOff){try{window.__xxComboOff();}catch(x){}}
var IDX=-1;
function mine(t){return t&&t.classList&&t.classList.contains('z-combobox-input')&&t.closest&&t.closest('.xx-req');}
function wid(t){try{return zk.Widget.$(t);}catch(x){return null;}}
function pop(w){return w&&w.$n?w.$n('pp'):null;}
function shown(el){return !!(el&&el.getClientRects&&el.getClientRects().length>0);}
function isOpen(w){var p=pop(w);return !!(p&&p.style.display!=='none'&&shown(p));}
function items(w){var p=pop(w),r=[];if(!p)return r;var a=p.querySelectorAll('.z-comboitem');
  for(var i=0;i<a.length;i++)if(shown(a[i]))r.push(a[i]);return r;}
function mark(w,i){var a=items(w);
  for(var k=0;k<a.length;k++)a[k].classList.remove('z-comboitem-over','z-comboitem-over-seld');
  if(i>=0&&i<a.length){a[i].classList.add('z-comboitem-over');
    if(a[i].scrollIntoView)a[i].scrollIntoView({block:'nearest'});}
  IDX=i;}
// the server may (re)fill the list right after it opens: wait until it stops changing, then highlight
function settle(w,last){var tries=0,prev=null,same=0;
  (function tick(){var a=items(w),f=a.length?a[0]:null;
    if(f&&f===prev)same++;else{same=0;prev=f;}
    if(same>=2||++tries>15){if(a.length)mark(w,last?a.length-1:0);}
    else setTimeout(tick,60);})();}
function pick(w,el){
  el.click();
  setTimeout(function(){
    if(!isOpen(w))return;
    var iw=null;try{iw=zk.Widget.$(el);}catch(x){}
    if(iw&&typeof w._select==='function')w._select(iw,{sendOnSelect:true});
    if(typeof w.close==='function')w.close({focus:true});
  },250);}
function onKey(e){
  var t=e.target;if(!mine(t)||t.disabled)return;
  var k=e.key;if(k!=='ArrowDown'&&k!=='ArrowUp'&&k!=='Enter'&&k!=='Escape')return;
  var w=wid(t);if(!w)return;
  var open=isOpen(w);
  if(k==='ArrowDown'||k==='ArrowUp'){
    e.preventDefault();e.stopPropagation();
    if(!open){IDX=-1;if(typeof w.open==='function')w.open();settle(w,k==='ArrowUp');return;}
    var n=items(w).length;if(!n)return;
    var i=IDX;
    if(k==='ArrowDown')i=i<0?0:Math.min(i+1,n-1);
    else i=i<0?n-1:Math.max(i-1,0);
    mark(w,i);
  }else if(k==='Enter'){
    if(open&&IDX>=0){var a=items(w);
      if(a[IDX]){e.preventDefault();e.stopPropagation();pick(w,a[IDX]);IDX=-1;}}
  }else if(k==='Escape'){
    if(open){e.preventDefault();e.stopPropagation();
      if(typeof w.close==='function')w.close({focus:true});IDX=-1;}
  }
}
function onClick(e){var t=e.target;if(!mine(t)||t.disabled)return;
  if(t.value&&t.value.length)return;
  var w=wid(t);if(w&&typeof w.open==='function'&&!isOpen(w))w.open();}
function onInput(e){if(mine(e.target))IDX=-1;}
function onFocus(e){if(mine(e.target))IDX=-1;}
document.addEventListener('keydown',onKey,true);
document.addEventListener('click',onClick,true);
document.addEventListener('input',onInput,true);
document.addEventListener('focusin',onFocus,true);
window.__xxComboOff=function(){
  document.removeEventListener('keydown',onKey,true);
  document.removeEventListener('click',onClick,true);
  document.removeEventListener('input',onInput,true);
  document.removeEventListener('focusin',onFocus,true);};
})();
""";

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

	/** LIKE-style match without SQL: '%' separates the parts that must appear in this order (lower case) */
	private static boolean matches(String lc, String pattern) {
		int from = 0;
		for (String part : pattern.split("%")) {
			if (part.isEmpty())
				continue;
			int ix = lc.indexOf(part, from);
			if (ix < 0)
				return false;
			from = ix + part.length();
		}
		return true;
	}

	/**
	 * Column of the table whose name matches one of the patterns (tried in order), shortest name first.
	 * Read from the model (POInfo), no SQL. null when there is none.
	 */
	private static String findColumn(String table, String exclude, String... patterns) {
		int tableId = MTable.getTable_ID(table);
		if (tableId <= 0)
			return null;
		POInfo info = POInfo.getPOInfo(Env.getCtx(), tableId);
		if (info == null)
			return null;
		for (String p : patterns) {
			String best = null;
			for (int i = 0; i < info.getColumnCount(); i++) {
				String cn = info.getColumnName(i);
				String lc = cn.toLowerCase();
				if (exclude != null && matches(lc, exclude))
					continue;
				if (matches(lc, p) && (best == null || cn.length() < best.length()))
					best = cn;
			}
			if (best != null)
				return best;
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
		colDamQty = findColumn(MInOutLine.Table_Name, "%reason%", "%damag%qty%", "%damag%");
		colEffCost = findColumn(MInOutLine.Table_Name, null, "%eff%cost%", "%net%cost%");
		colTolRate = findColumn(TOL_TABLE, "%_id%", "%rate%", "%toler%", "%percent%");
		colTolFrom = findColumn(TOL_TABLE, null, "%valid%from%", "%date%from%", "%from%");
		colTolDocType = findColumn(TOL_TABLE, null, "%doctype%");
		colTolSegment = findColumn(TOL_TABLE, null, "%segment%");
		log.info("GRN tolerance columns: Rate=" + colTolRate + " ValidFrom=" + colTolFrom + " DocType=" + colTolDocType
				+ " Segment=" + colTolSegment);
		log.info("GRN columns: VATNo=" + colVatNo + " VATDate=" + colVatDate + " DelNo=" + colDelNo + " DelDate="
				+ colDelDate + " PackSize=" + colPack + " NoOfPack=" + colNoPack + " ChallanQty=" + colChQty
				+ " DamagedQty=" + colDamQty + " EffCost=" + colEffCost);
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
	// tolerance (rules of T_ToleranceRateMR, read through the model, no SQL)
	// =====================================================================

	private static Timestamp toTs(Object o) {
		if (o instanceof Timestamp)
			return (Timestamp) o;
		if (o instanceof Date)
			return new Timestamp(((Date) o).getTime());
		return null;
	}

	private static BigDecimal toBd(Object o) {
		if (o instanceof BigDecimal)
			return (BigDecimal) o;
		if (o instanceof Number)
			return new BigDecimal(o.toString());
		return null;
	}

	private static int toInt(Object o) {
		return o instanceof Number ? ((Number) o).intValue() : 0;
	}

	private static String cell(PO po, String col) {
		if (col == null || po.get_ColumnIndex(col) < 0)
			return "";
		Object v = po.get_Value(col);
		return v == null ? "" : v.toString();
	}

	/**
	 * Fills the Tolerance Rule box with the rules that are valid on the GRN date for this organization.
	 * (The best fitting one is worked out as before, but "No tolerance" stays selected: the user picks the rule.)
	 */
	private void loadTolerance() {
		while (cbTolerance.getItemCount() > 0)
			cbTolerance.removeItemAt(0);
		cbTolerance.setValue("");
		Comboitem none = new Comboitem("No tolerance (0%)");
		none.setAttribute("rate", BigDecimal.ZERO);
		none.setAttribute("segment", "");
		cbTolerance.appendChild(none);
		if (colTolRate != null) {
			Timestamp onDate = dtMove.getValue() == null ? new Timestamp(System.currentTimeMillis())
					: new Timestamp(dtMove.getValue().getTime());
			try {
				List<PO> rows = new Query(Env.getCtx(), TOL_TABLE, null, null).setClient_ID()
						.setOnlyActiveRecords(true).list();
				for (PO row : rows) {
					int rowOrg = row.getAD_Org_ID();
					if (rowOrg != 0 && orgId > 0 && rowOrg != orgId)
						continue;
					Timestamp from = colTolFrom == null ? null : toTs(row.get_Value(colTolFrom));
					if (from != null && from.after(onDate))
						continue;
					BigDecimal rate = toBd(row.get_Value(colTolRate));
					boolean bad = rate == null || rate.signum() < 0 || rate.compareTo(HUNDRED) > 0;
					int rowDt = colTolDocType == null ? 0 : toInt(row.get_Value(colTolDocType));
					String dtName = "All document types";
					if (rowDt > 0) {
						MDocType dt = MDocType.get(Env.getCtx(), rowDt);
						dtName = dt == null ? "Doc type " + rowDt : dt.getName();
					}
					String seg = cell(row, colTolSegment);
					String val = cell(row, "Value");
					String label = (val.isEmpty() ? "Rule" : val) + "  |  " + (rate == null ? "?" : num(rate)) + "%  |  "
							+ dtName + (seg.isEmpty() ? "" : "  |  " + seg)
							+ (from == null ? "" : "  |  from " + dmy(from)) + (bad ? "  |  rate ignored" : "");
					Comboitem ci = new Comboitem(label);
					ci.setAttribute("rate", bad ? BigDecimal.ZERO : rate);
					ci.setAttribute("segment", seg);
					cbTolerance.appendChild(ci);
				}
			} catch (Exception e) {
				log.log(Level.WARNING, "tolerance rules not read: " + e.getMessage(), e);
			}
		}
		cbTolerance.setSelectedItem(none);
	}

	/** the tolerance rule list depends on the GRN Document and the GRN Date; the line limits depend on the rule */
	private void toleranceChanged() {
		loadTolerance();
		refreshLines();
	}

	/** tolerance rate (percent) of the rule picked, 0 when none */
	private BigDecimal toleranceRate() {
		Comboitem ci = cbTolerance.getSelectedItem();
		Object r = ci == null ? null : ci.getAttribute("rate");
		return r instanceof BigDecimal ? (BigDecimal) r : BigDecimal.ZERO;
	}

	/** most that may be received on a line: pending + tolerance % */
	private BigDecimal maxReceive(BigDecimal pending) {
		BigDecimal f = BigDecimal.ONE.add(toleranceRate().divide(HUNDRED, 8, RoundingMode.HALF_UP));
		return pending.multiply(f).setScale(4, RoundingMode.DOWN);
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

		cbPo = newCombo("Search PO No. or Supplier");
		cbPo.setAutocomplete(false);
		cbPo.setAutodrop(false);
		cbDocType = newCombo("GRN Document");
		cbWarehouse = newCombo("Warehouse");
		cbLocator = newCombo("Locator");
		cbTolerance = newCombo("Tolerance Rule");

		// fixed top bar: stays in view while the page scrolls
		Div top = new Div();
		top.setSclass("xx-topbar");
		Label title = new Label("Goods Received Note");
		title.setSclass("xx-top-title");
		btnReload.setSclass("btn btn-outline-primary");
		btnReload.setTooltiptext("Clear the form and load the header data and the purchase orders again");
		top.appendChild(title);
		top.appendChild(btnReload);
		main.appendChild(top);

		main.appendChild(card("Goods Received Note", buildHeader()));
		main.appendChild(card("Receipt Line", buildLineBox()));

		Div foot = new Div();
		foot.setSclass("xx-footer");
		Div left = new Div();
		left.setSclass("xx-foot-grp");
		left.appendChild(lblTotals);
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

	private Div buildHeader() {
		Div g = new Div();
		g.setSclass("xx-grid xx-grid8");
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
		btnAttach.setSclass("btn btn-outline-primary btn-block");

		// row 1: Organization, PO search (dropdown + Search + Refresh + count), Attachment
		Div bar = new Div();
		bar.setStyle("display:flex;align-items:center;gap:8px;flex-wrap:wrap;");
		ZKUpdateUtil.setWidth(cbPo, "300px");
		btnSearch.setSclass("btn btn-primary");
		btnRefresh.setSclass("btn btn-outline-primary");
		bar.appendChild(cbPo);
		bar.appendChild(btnSearch);
		bar.appendChild(btnRefresh);
		bar.appendChild(lblPoCount);
		Div at = new Div();
		at.appendChild(btnAttach);
		lblAttach.setSclass("xx-attach-note");
		at.appendChild(lblAttach);
		g.appendChild(field("Organization", true, readonly(lblOrg), 2));
		g.appendChild(field("Search PO No. or Supplier", true, bar, 5));
		g.appendChild(field("Attachment", false, at, 1));
		// row 2
		g.appendChild(field("Supplier", false, readonly(lblSupplier), 3));
		g.appendChild(field("PO Total", false, readonly(lblPoTotal), 1));
		g.appendChild(field("PO Date", false, readonly(lblPoDate), 1));
		g.appendChild(field("GRN Date", true, dtMove, 1));
		g.appendChild(field("GRN Document", true, cbDocType, 2));
		// row 3
		g.appendChild(field("Warehouse", true, cbWarehouse, 2));
		g.appendChild(field("Locator", true, cbLocator, 2));
		g.appendChild(field("Challan / Supplier Ref. No.", false, txtRef, 2));
		g.appendChild(field("VAT Challan No", false, txtVatNo, 2));
		// row 4
		g.appendChild(field("VAT Challan Date", false, dtVat, 1));
		g.appendChild(field("Delivery Challan No", false, txtDelNo, 1));
		g.appendChild(field("Delivery Challan Date", false, dtDel, 1));
		g.appendChild(field("Tolerance Rule", false, cbTolerance, 2));
		g.appendChild(field("Description (Note)", false, txtDesc, 3));
		updateAttachLabel();
		return g;
	}

	private Div buildLineBox() {
		Div box = new Div();
		// no fixed height: the grid grows with its lines, so the scroll bar sits right under the last line
		lineList.setSclass("xx-lines");
		lineList.setEmptyMessage("Select a purchase order to see its lines.");
		Listhead lh = new Listhead();
		lh.setSizable(true);
		lh.appendChild(header("SL No", "20px"));
		lh.appendChild(header("PO No.", "80px"));
		lh.appendChild(header("Item Code", "100px"));
		lh.appendChild(header("Item Name","150px"));
		lh.appendChild(header("UOM", "40px"));
		lh.appendChild(header("Ordered", "60px"));
		lh.appendChild(header("Received", "60px"));
		lh.appendChild(header("Pending", "60px"));
		lh.appendChild(header("Receive Qty", "80px"));
		lh.appendChild(header("Balance", "50px"));
		lh.appendChild(header("Status", "100px"));
		lh.appendChild(header("Damaged Qty", "80px"));
		lh.appendChild(header("Damage Reason", "150px"));
		lh.appendChild(header("Good Qty", "60px"));
		lh.appendChild(header("Rate", "60px"));
		lh.appendChild(header("Receive Value", "100px"));
		lh.appendChild(header("Net Cost / Unit", "100px"));
		lh.appendChild(header("PO Line", "100px"));
		lh.appendChild(header("Pack Size", "60px"));
		lh.appendChild(header("No of Pack", "60px"));
		lh.appendChild(header("Challan Qty", "80px"));
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
		d.setSclass("xx-field" + (span == 2 ? " xx-span2" : span == 3 ? " xx-span3" : span == 4 ? " xx-span4" : span == 5 ? " xx-span5" : span == 6 ? " xx-span6" : ""));
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
		loadTolerance();
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
		for (final Combobox c : new Combobox[] { cbDocType, cbLocator, cbTolerance }) {
			c.addEventListener(Events.ON_CHANGE, ev -> resolve(c));
			c.addEventListener(Events.ON_OK, ev -> resolve(c));
		}
		// the rule that fits depends on the GRN Document and the GRN Date
		cbDocType.addEventListener(Events.ON_CHANGE, ev -> toleranceChanged());
		cbDocType.addEventListener(Events.ON_SELECT, ev -> toleranceChanged());
		dtMove.addEventListener(Events.ON_CHANGE, ev -> toleranceChanged());
		// a different rule: the allowed quantity of every line changes
		cbTolerance.addEventListener(Events.ON_CHANGE, ev -> refreshLines());
		cbTolerance.addEventListener(Events.ON_SELECT, ev -> refreshLines());

		// PO search box: typing filters the dropdown, picking loads the PO
		cbPo.addEventListener(Events.ON_CHANGING, ev -> {
			fillPoCombo(((InputEvent) ev).getValue());
			cbPo.setOpen(true);
		});
		cbPo.addEventListener(Events.ON_SELECT, ev -> pickPo());
		cbPo.addEventListener(Events.ON_CHANGE, ev -> {
			String v = cbPo.getValue() == null ? "" : cbPo.getValue().trim();
			if (v.isEmpty() && selectedPoId > 0)
				clearPo();
		});
		cbPo.addEventListener(Events.ON_OK, ev -> {
			if (cbPo.getSelectedItem() != null)
				pickPo();
			else {
				fillPoCombo(cbPo.getValue());
				cbPo.setOpen(true);
			}
		});
		btnSearch.addEventListener(Events.ON_CLICK, ev -> {
			fillPoCombo(cbPo.getSelectedItem() != null ? "" : cbPo.getValue());
			cbPo.setOpen(true);
		});
		btnRefresh.addEventListener(Events.ON_CLICK, ev -> loadPOs());

		btnAttach.addEventListener(Events.ON_CLICK, ev -> openAttachments());
		btnOk.addEventListener(Events.ON_CLICK, ev -> save());
		btnReload.addEventListener(Events.ON_CLICK, ev -> {
			if (lines.isEmpty()) {
				doReload();
				return;
			}
			Messagebox.show("Reload the form?\n\nThe selected PO and everything you entered will be cleared.",
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
		pos.clear();
		String where = "IsSOTrx='N' AND DocStatus=? AND AD_Client_ID=?" + (orgId > 0 ? " AND AD_Org_ID=?" : "");
		List<Object> p = new ArrayList<Object>();
		p.add(DocAction.STATUS_Completed);
		p.add(Integer.valueOf(clientId));
		if (orgId > 0)
			p.add(Integer.valueOf(orgId));
		List<MOrder> orders = new Query(Env.getCtx(), MOrder.Table_Name, where, null).setParameters(p)
				.setOrderBy("DateOrdered DESC, DocumentNo DESC").list();

		Map<Integer, String> whNames = new HashMap<Integer, String>();
		for (MOrder o : orders) {
			if (pos.size() >= MAX_PO)
				break;
			int pending = 0;
			for (MOrderLine ol : o.getLines())
				if (ol.getM_Product_ID() > 0 && ol.getQtyOrdered().subtract(ol.getQtyDelivered()).signum() > 0)
					pending++;
			if (pending == 0)
				continue;
			MBPartner bp = MBPartner.get(Env.getCtx(), o.getC_BPartner_ID());
			Po po = new Po();
			po.id = o.getC_Order_ID();
			po.warehouseId = o.getM_Warehouse_ID();
			po.docNo = o.getDocumentNo() == null ? "" : o.getDocumentNo();
			po.date = o.getDateOrdered();
			po.supplier = bp == null ? "" : bp.getName();
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
		lblPoCount.setValue(pos.size() + " completed PO(s) waiting for receipt");
		// the PO that was picked may be fully received by now
		if (selectedPoId > 0 && poById(selectedPoId) == null) {
			selectedPoId = 0;
			cbPo.setValue("");
		}
		fillPoCombo("");
		if (selectedPoId > 0)
			selectComboPo(selectedPoId);
		updatePoInfo();
		reloadLines();
	}

	private static String poLabel(Po po) {
		return po.docNo + "  |  " + dmy(po.date) + "  |  " + po.supplier + "  |  " + money(po.total);
	}

	/** dropdown: PO No | Date | Supplier | PO Total, filtered by what is typed */
	private void fillPoCombo(String filter) {
		while (cbPo.getItemCount() > 0)
			cbPo.removeItemAt(0);
		String q = filter == null ? "" : filter.trim().toUpperCase();
		for (Po po : pos) {
			String label = poLabel(po);
			if (q.length() > 0 && !label.toUpperCase().contains(q))
				continue;
			Comboitem ci = new Comboitem(label);
			ci.setAttribute("id", Integer.valueOf(po.id));
			cbPo.appendChild(ci);
		}
	}

	private void selectComboPo(int id) {
		for (Object o : cbPo.getItems()) {
			Comboitem ci = (Comboitem) o;
			Object v = ci.getAttribute("id");
			if (v instanceof Integer && ((Integer) v).intValue() == id) {
				cbPo.setSelectedItem(ci);
				return;
			}
		}
	}

	private void pickPo() {
		Comboitem ci = cbPo.getSelectedItem();
		if (ci == null)
			return;
		Object v = ci.getAttribute("id");
		if (!(v instanceof Integer))
			return;
		int id = ((Integer) v).intValue();
		if (id != selectedPoId)
			removedLines.clear();
		selectedPoId = id;
		updatePoInfo();
		reloadLines();
	}

	/** the search box was emptied: no PO any more */
	private void clearPo() {
		selectedPoId = 0;
		removedLines.clear();
		updatePoInfo();
		reloadLines();
	}

	private Po poById(int id) {
		for (Po p : pos)
			if (p.id == id)
				return p;
		return null;
	}

	/** Supplier / PO Date / PO Total / Warehouse of the header follow the PO that was picked */
	private void updatePoInfo() {
		Po po = selectedPoId > 0 ? poById(selectedPoId) : null;
		lblSupplier.setValue(po == null ? "" : po.supplier);
		lblPoDate.setValue(po == null ? "" : dmy(po.date));
		lblPoTotal.setValue(po == null ? "" : money(po.total));
		if (po != null && po.warehouseId > 0)
			selectWarehouse(po.warehouseId);
	}

	private static String dmy(Timestamp t) {
		return t == null ? "" : new java.text.SimpleDateFormat("dd-MM-yyyy").format(t);
	}

	private static String money(BigDecimal v) {
		return v == null ? "" : String.format("%,.2f", v);
	}

	private static String cost(BigDecimal v) {
		return String.format("%,.4f", v);
	}

	private static String num(BigDecimal v) {
		if (v == null)
			return "0";
		BigDecimal s = v.stripTrailingZeros();
		if (s.scale() < 0)
			s = s.setScale(0);
		return s.toPlainString();
	}

	/** plain number with a fixed number of decimals (no thousand separators): used in the receipt line description */
	private static String plain(BigDecimal v, int scale) {
		return nz(v).setScale(scale, RoundingMode.HALF_UP).toPlainString();
	}

	private static BigDecimal nz(BigDecimal b) {
		return b == null ? BigDecimal.ZERO : b;
	}

	// =====================================================================
	// lines
	// =====================================================================

	private void reloadLines() {
		lines.clear();
		Po po = selectedPoId > 0 ? poById(selectedPoId) : null;
		if (po != null) {
			MOrder o = new MOrder(Env.getCtx(), po.id, null);
			for (MOrderLine ol : o.getLines()) {
				if (ol.getM_Product_ID() <= 0)
					continue;
				BigDecimal pend = ol.getQtyOrdered().subtract(ol.getQtyDelivered());
				if (pend.signum() <= 0)
					continue;
				if (removedLines.contains(Integer.valueOf(ol.getC_OrderLine_ID())))
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
				g.price = nz(ol.getPriceActual());
				g.qty = pend;
				lines.add(g);
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

			final Label lblBal = new Label();
			final Label lblSt = new Label();
			final Label lblGood = new Label();
			final Label lblVal = new Label();
			final Label lblEff = new Label();
			final Button btnMode = new Button();

			// Balance, Status, Good Qty, Value, Net Cost and the Partial / Complete button follow the quantities
			final Runnable upd = () -> {
				BigDecimal q = nz(g.qty);
				BigDecimal dm = nz(g.damaged);
				boolean full = q.compareTo(g.pending) >= 0;
				boolean closes = q.signum() > 0 && (full || g.complete);
				BigDecimal bal = closes ? BigDecimal.ZERO : g.pending.subtract(q).max(BigDecimal.ZERO);
				lblBal.setValue(num(bal));

				String st;
				String color;
				if (q.signum() <= 0) {
					st = "-";
					color = "#6c757d";
				} else if (q.compareTo(g.pending) < 0) {
					if (g.complete) {
						st = "Short close (-" + num(g.pending.subtract(q)) + ")";
						color = "#198754";
					} else {
						st = "Partial";
						color = "#fd7e14";
					}
				} else if (q.compareTo(g.pending) == 0) {
					st = "Full";
					color = "#198754";
				} else if (q.compareTo(maxReceive(g.pending)) <= 0) {
					st = "Over +" + num(q.subtract(g.pending)) + " (tolerance)";
					color = "#0d6efd";
				} else {
					st = "Over limit (max " + num(maxReceive(g.pending)) + ")";
					color = "#dc3545";
				}
				lblSt.setValue(st);
				lblSt.setStyle("font-weight:600;color:" + color + ";");

				// the supplier rate stays; the damaged qty does not reduce the payable, it raises the cost of the good qty
				BigDecimal good = q.subtract(dm);
				BigDecimal value = g.price.multiply(q).setScale(2, RoundingMode.HALF_UP);
				lblGood.setValue(num(good.signum() < 0 ? BigDecimal.ZERO : good));
				lblVal.setValue(money(value));
				lblEff.setValue(good.signum() > 0 ? cost(value.divide(good, 4, RoundingMode.HALF_UP)) : "-");

				boolean complete = full || g.complete;
				btnMode.setLabel(complete ? "Complete" : "Partial");
				btnMode.setSclass(complete ? "btn btn-sm btn-success" : "btn btn-sm btn-outline-primary");
				btnMode.setDisabled(full);
				btnMode.setTooltiptext(full
						? "The whole pending qty is received: this receipt closes the PO line."
						: "Partial = the PO line stays open for the rest.  "
								+ "Complete = this receipt closes the PO line (no more partial).");
			};

			// Receive Qty
			Listcell qc = new Listcell();
			final Decimalbox db = new Decimalbox();
			db.setFormat("#,##0.####");
			db.setValue(g.qty);
			ZKUpdateUtil.setWidth(db, "100%");
			db.addEventListener(Events.ON_CHANGE, ev -> {
				g.qty = nz(db.getValue());
				upd.run();
				updateTotals();
			});
			qc.appendChild(db);
			li.appendChild(qc);

			Listcell bc = new Listcell();
			bc.setStyle("text-align:right;");
			bc.appendChild(lblBal);
			li.appendChild(bc);
			Listcell sc = new Listcell();
			sc.appendChild(lblSt);
			li.appendChild(sc);

			// Damaged Qty + reason
			Listcell dc = new Listcell();
			final Decimalbox dbDmg = new Decimalbox();
			dbDmg.setFormat("#,##0.####");
			dbDmg.setValue(g.damaged);
			ZKUpdateUtil.setWidth(dbDmg, "100%");
			dbDmg.addEventListener(Events.ON_CHANGE, ev -> {
				g.damaged = dbDmg.getValue();
				upd.run();
				updateTotals();
			});
			dc.appendChild(dbDmg);
			li.appendChild(dc);
			Listcell rc = new Listcell();
			final Textbox tbRes = new Textbox();
			tbRes.setMaxlength(100);
			tbRes.setValue(g.damageReason == null ? "" : g.damageReason);
			ZKUpdateUtil.setWidth(tbRes, "100%");
			tbRes.addEventListener(Events.ON_CHANGE, ev -> g.damageReason = txt(tbRes));
			rc.appendChild(tbRes);
			li.appendChild(rc);

			// Good Qty, Rate, Receive Value, Net Cost / Unit
			Listcell gc = new Listcell();
			gc.setStyle("text-align:right;font-weight:600;");
			gc.appendChild(lblGood);
			li.appendChild(gc);
			li.appendChild(right(money(g.price)));
			Listcell vc = new Listcell();
			vc.setStyle("text-align:right;");
			vc.appendChild(lblVal);
			li.appendChild(vc);
			Listcell ec = new Listcell();
			ec.setStyle("text-align:right;font-weight:600;");
			ec.appendChild(lblEff);
			li.appendChild(ec);

			// Partial / Complete
			Listcell mc = new Listcell();
			btnMode.addEventListener(Events.ON_CLICK, ev -> {
				g.complete = !g.complete;
				upd.run();
			});
			mc.appendChild(btnMode);
			li.appendChild(mc);

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
				removedLines.add(Integer.valueOf(g.olId));
				lines.remove(g);
				refreshLines();
			});
			rm.appendChild(x);
			li.appendChild(rm);

			upd.run();
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
		BigDecimal d = BigDecimal.ZERO;
		BigDecimal v = BigDecimal.ZERO;
		for (GLine g : lines) {
			q = q.add(nz(g.qty));
			d = d.add(nz(g.damaged));
			v = v.add(g.price.multiply(nz(g.qty)));
		}
		lblTotals.setValue(lines.size() + " line(s)   |   Receive Qty: " + num(q) + "   |   Damaged: " + num(d)
				+ "   |   Good Qty: " + num(q.subtract(d)) + "   |   Receive Value: " + money(v));
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
		resolve(cbTolerance);
		final int docTypeId = idOf(cbDocType);
		final int whId = idOf(cbWarehouse);
		final int locId = idOf(cbLocator);
		if (selectedPoId <= 0) {
			warn("Select a purchase order first.");
			return;
		}
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

		final List<GLine> toReceive = new ArrayList<GLine>();
		for (GLine g : lines) {
			if (g.qty == null || g.qty.signum() <= 0)
				continue;
			if (g.qty.compareTo(g.pending) > 0 && g.qty.compareTo(maxReceive(g.pending)) > 0) {
				warn("PO " + g.poNo + " / " + g.code + ": Receive Qty " + num(g.qty) + " is more than the pending Qty "
						+ num(g.pending)
						+ (toleranceRate().signum() > 0
								? " plus the tolerance (" + num(toleranceRate()) + "%, at most "
										+ num(maxReceive(g.pending)) + ")."
								: ". No tolerance rule is selected."));
				return;
			}
			BigDecimal dmg = nz(g.damaged);
			if (dmg.signum() < 0) {
				warn("PO " + g.poNo + " / " + g.code + ": Damaged Qty cannot be negative.");
				return;
			}
			if (dmg.signum() > 0) {
				if (dmg.compareTo(g.qty) >= 0) {
					warn("PO " + g.poNo + " / " + g.code + ": Damaged Qty " + num(dmg)
							+ " must be less than the Receive Qty " + num(g.qty) + " (there must be some good Qty).");
					return;
				}
				if (g.damageReason == null || g.damageReason.trim().isEmpty()) {
					warn("PO " + g.poNo + " / " + g.code + ": Damage Reason is required when there is a Damaged Qty.");
					return;
				}
			}
			toReceive.add(g);
		}
		if (toReceive.isEmpty()) {
			warn("Enter a Receive Qty above 0 on at least one line.");
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
		for (GLine g : toReceive) {
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

		// what the PDF shows about the tolerance rule
		Comboitem tci = cbTolerance.getSelectedItem();
		Object seg = tci == null ? null : tci.getAttribute("segment");
		pdfSegment = seg == null ? "" : seg.toString();
		pdfTolerance = toleranceRate().signum() > 0 ? num(toleranceRate()) + "%" : "No tolerance";

		final String trxName = Trx.createTrxName("XXGRN");
		Trx trx = Trx.get(trxName, true);
		try {
			final List<Integer> ids = new ArrayList<Integer>();
			final List<String> nos = new ArrayList<String>();
			String ref = txtRef.getValue() == null ? "" : txtRef.getValue().trim();
			String desc = txtDesc.getValue() == null ? "" : txtDesc.getValue().trim();

			MOrder po = new MOrder(Env.getCtx(), selectedPoId, trxName);
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

			for (GLine g : toReceive) {
				MOrderLine ol = new MOrderLine(Env.getCtx(), g.olId, trxName);
				addReceiptLine(io, ol, locId, g);
			}

			io.setDocAction(DocAction.ACTION_Complete);
			if (!io.processIt(DocAction.ACTION_Complete))
				throw new AdempiereException("PO " + po.getDocumentNo() + ": " + io.getProcessMsg());
			io.saveEx();

			// PO lines: the damaged qty counts as supplied (the payable does not drop);
			// Complete / over receipt closes the line
			boolean anyClose = false;
			for (GLine g : toReceive)
				anyClose |= settlePoLine(g, trxName);
			if (anyClose) {
				// when every line of the PO is now fully supplied (or short closed), the PO is closed with the
				// standard Close action, which also gives back the "On Order" reservation
				MOrder po2 = new MOrder(Env.getCtx(), selectedPoId, trxName);
				boolean allDone = true;
				for (MOrderLine l2 : po2.getLines(true, null))
					if (l2.getM_Product_ID() > 0 && l2.getQtyOrdered().compareTo(l2.getQtyDelivered()) > 0)
						allDone = false;
				if (allDone) {
					po2.setDocAction(DocAction.ACTION_Close);
					if (!po2.processIt(DocAction.ACTION_Close))
						throw new AdempiereException("PO " + po2.getDocumentNo() + ": " + po2.getProcessMsg());
					po2.saveEx();
				}
			}

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

	/**
	 * One receipt line: only the GOOD qty goes to stock; the whole calculation is written on the line.
	 * Description format (read back by the PDF):
	 * "Ord 100 | Recv 95 | Dmg 5 | Good 90 | Rate 10.0000 | Value 950.00 | Eff 10.5556 | St Partial | Reason text"
	 */
	private MInOutLine addReceiptLine(MInOut io, MOrderLine ol, int locatorId, GLine g) {
		BigDecimal dmg = nz(g.damaged);
		BigDecimal good = g.qty.subtract(dmg);
		BigDecimal value = g.price.multiply(g.qty).setScale(2, RoundingMode.HALF_UP);
		BigDecimal eff = value.divide(good, 4, RoundingMode.HALF_UP);
		String status = g.qty.compareTo(g.pending) > 0 ? "Over (tolerance)"
				: g.qty.compareTo(g.pending) == 0 ? "Full" : g.complete ? "Complete (short)" : "Partial";

		MInOutLine iol = new MInOutLine(io);
		iol.setOrderLine(ol, locatorId, good);
		iol.setQty(good);
		String d = "Ord " + num(g.ordered) + " | Recv " + num(g.qty) + " | Dmg " + num(dmg) + " | Good " + num(good)
				+ " | Rate " + plain(g.price, 4) + " | Value " + plain(value, 2) + " | Eff " + plain(eff, 4)
				+ " | St " + status;
		if (dmg.signum() > 0)
			d += " | Reason " + g.damageReason.trim().replace('|', '/');
		iol.setDescription(d.length() > 255 ? d.substring(0, 255) : d);
		if (g.packSize != null && g.packSize.trim().length() > 0)
			putCol(iol, colPack, g.packSize.trim());
		if (g.noPack != null)
			putCol(iol, colNoPack, g.noPack);
		if (g.challanQty != null)
			putCol(iol, colChQty, g.challanQty);
		putCol(iol, colDamQty, dmg);
		putCol(iol, colEffCost, eff);
		iol.saveEx();
		return iol;
	}

	/**
	 * After the receipt is completed: count the damaged qty as supplied, and close the PO line when it is
	 * Complete (short close). true = the ordered qty of a line was reduced.
	 */
	private boolean settlePoLine(GLine g, String trxName) {
		MOrderLine ol = new MOrderLine(Env.getCtx(), g.olId, trxName);
		BigDecimal dmg = nz(g.damaged);
		boolean changed = false;
		if (dmg.signum() > 0) {
			ol.setQtyDelivered(ol.getQtyDelivered().add(dmg));
			changed = true;
		}
		boolean ordChanged = false;
		if (g.complete && g.qty.compareTo(g.pending) < 0) {
			BigDecimal old = ol.getQtyOrdered();
			BigDecimal dlv = ol.getQtyDelivered();
			if (old.compareTo(dlv) > 0) {
				ol.setQtyLostSales(old.subtract(dlv));
				ol.setQtyOrdered(dlv);
				ol.addDescription("GRN close (" + num(old) + ")");
				changed = true;
				ordChanged = true;
			}
		}
		if (changed)
			ol.saveEx();
		return ordChanged;
	}

	private void resetForm() {
		lines.clear();
		removedLines.clear();
		selectedPoId = 0;
		cbPo.setValue("");
		updatePoInfo();
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
	// PDF (rows are built from the saved receipts, no SQL)
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

	private static BigDecimal grab(String d, String key) {
		if (d == null)
			return null;
		Matcher m = Pattern.compile("(?:^|\\| )" + key + " (-?[0-9]+(?:\\.[0-9]+)?)").matcher(d);
		return m.find() ? new BigDecimal(m.group(1)) : null;
	}

	private static String grabText(String d, String key) {
		if (d == null)
			return "";
		Matcher m = Pattern.compile("(?:^|\\| )" + key + " ([^|]*)").matcher(d);
		return m.find() ? m.group(1).trim() : "";
	}

	/** one row per receipt line of the GRN (the report groups by grn_id: one page or more per GRN) */
	private List<Map<String, Object>> pdfRows(int ioId) {
		List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		MInOut io = new MInOut(Env.getCtx(), ioId, null);
		MBPartner bp = io.getC_BPartner_ID() > 0 ? MBPartner.get(Env.getCtx(), io.getC_BPartner_ID()) : null;
		MOrder po = io.getC_Order_ID() > 0 ? new MOrder(Env.getCtx(), io.getC_Order_ID(), null) : null;
		MDocType dt = io.getC_DocType_ID() > 0 ? MDocType.get(Env.getCtx(), io.getC_DocType_ID()) : null;
		MOrg org = MOrg.get(Env.getCtx(), io.getAD_Org_ID());
		MWarehouse wh = io.getM_Warehouse_ID() > 0 ? MWarehouse.get(Env.getCtx(), io.getM_Warehouse_ID()) : null;
		MUser usr = MUser.get(Env.getCtx(), io.getCreatedBy());

		Map<String, Object> head = new HashMap<String, Object>();
		head.put("grn_id", Integer.valueOf(ioId));
		head.put("grn_no", io.getDocumentNo());
		head.put("grn_date", io.getMovementDate());
		head.put("org", org == null ? "" : org.getName());
		head.put("doc_type", dt == null ? "" : dt.getName());
		head.put("supplier", bp == null ? "" : bp.getName());
		head.put("po_no", po == null ? "" : po.getDocumentNo());
		head.put("po_date", po == null ? null : po.getDateOrdered());
		head.put("warehouse", wh == null ? "" : wh.getName());
		head.put("ref_no", io.getPOReference());
		head.put("vat_no", cell(io, colVatNo));
		head.put("vat_date", colVatDate == null || io.get_ColumnIndex(colVatDate) < 0 ? null
				: toTs(io.get_Value(colVatDate)));
		head.put("del_no", cell(io, colDelNo));
		head.put("del_date", colDelDate == null || io.get_ColumnIndex(colDelDate) < 0 ? null
				: toTs(io.get_Value(colDelDate)));
		head.put("segment", pdfSegment);
		head.put("tolerance", pdfTolerance);
		head.put("note", io.getDescription());
		head.put("prepared_by", usr == null ? "" : usr.getName());
		head.put("prepared_date", io.getCreated());

		for (MInOutLine l : io.getLines(false)) {
			if (l.getM_Product_ID() <= 0)
				continue;
			Map<String, Object> r = new HashMap<String, Object>(head);
			MProduct prod = MProduct.get(Env.getCtx(), l.getM_Product_ID());
			MOrderLine ol = l.getC_OrderLine_ID() > 0 ? new MOrderLine(Env.getCtx(), l.getC_OrderLine_ID(), null) : null;
			String d = l.getDescription();

			BigDecimal good = nz(l.getMovementQty());
			BigDecimal dmg = null;
			if (colDamQty != null && l.get_ColumnIndex(colDamQty) >= 0)
				dmg = toBd(l.get_Value(colDamQty));
			if (dmg == null)
				dmg = grab(d, "Dmg");
			dmg = nz(dmg);
			BigDecimal recv = grab(d, "Recv");
			if (recv == null)
				recv = good.add(dmg);
			BigDecimal ord = grab(d, "Ord");
			if (ord == null)
				ord = ol != null ? ol.getQtyOrdered() : recv;
			BigDecimal rate = grab(d, "Rate");
			if (rate == null)
				rate = ol != null ? nz(ol.getPriceActual()) : BigDecimal.ZERO;
			BigDecimal value = grab(d, "Value");
			if (value == null)
				value = rate.multiply(recv).setScale(2, RoundingMode.HALF_UP);
			BigDecimal eff = null;
			if (colEffCost != null && l.get_ColumnIndex(colEffCost) >= 0)
				eff = toBd(l.get_Value(colEffCost));
			if (eff == null)
				eff = grab(d, "Eff");
			if (eff == null && good.signum() > 0)
				eff = value.divide(good, 4, RoundingMode.HALF_UP);

			MUOM uom = l.getC_UOM_ID() > 0 ? MUOM.get(Env.getCtx(), l.getC_UOM_ID()) : null;
			MLocator loc = l.getM_Locator_ID() > 0 ? MLocator.get(Env.getCtx(), l.getM_Locator_ID()) : null;
			boolean coded = d != null && d.startsWith("Ord ");

			r.put("item_code", prod.getValue());
			r.put("item_name", prod.getName());
			r.put("uom", uom == null ? "" : (uom.getUOMSymbol() != null ? uom.getUOMSymbol() : uom.getName()));
			r.put("ordered", ord);
			r.put("challan_qty", colChQty == null || l.get_ColumnIndex(colChQty) < 0 ? null : toBd(l.get_Value(colChQty)));
			r.put("received", recv);
			r.put("damaged", dmg);
			r.put("accepted", good);
			r.put("rate", rate);
			r.put("value", value);
			r.put("eff_cost", eff);
			r.put("status", grabText(d, "St"));
			r.put("pack_size", cell(l, colPack));
			r.put("no_of_pack", colNoPack == null || l.get_ColumnIndex(colNoPack) < 0 ? null : toBd(l.get_Value(colNoPack)));
			r.put("locator", loc == null ? "" : loc.getValue());
			r.put("remarks", coded ? grabText(d, "Reason") : (d == null ? "" : d));
			rows.add(r);
		}
		return rows;
	}

	/** one PDF, one page (or more) per GRN */
	private byte[] buildPdf(List<Integer> ids) throws Exception {
		List<Map<String, ?>> rows = new ArrayList<Map<String, ?>>();
		for (Integer id : ids)
			rows.addAll(pdfRows(id.intValue()));
		try (java.io.InputStream jr = WGoodsReceiptForm.class.getResourceAsStream(PDF_JRXML)) {
			if (jr == null)
				throw new AdempiereException(PDF_JRXML + " not found in the bundle");
			net.sf.jasperreports.engine.JasperReport report = net.sf.jasperreports.engine.JasperCompileManager
					.compileReport(jr);
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("LOGO_LEFT", logoPath("/org/mycompany/requisition/rfqapproval/logo_left.png"));
			params.put("LOGO_RIGHT", logoPath("/org/mycompany/requisition/rfqapproval/logo_right.png"));
			net.sf.jasperreports.engine.JasperPrint print = net.sf.jasperreports.engine.JasperFillManager
					.fillReport(report, params, new net.sf.jasperreports.engine.data.JRMapCollectionDataSource(rows));
			return net.sf.jasperreports.engine.JasperExportManager.exportReportToPdf(print);
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