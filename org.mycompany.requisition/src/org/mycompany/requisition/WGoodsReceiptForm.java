package org.mycompany.requisition;

import java.util.ArrayList;
import java.util.List;

import org.adempiere.webui.component.Combobox;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.panel.ADForm;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.zkoss.util.media.Media;
import org.zkoss.zul.Button;

import bsh.org.objectweb.asm.Label;



/**
 * Goods Received Note (GRN) form.
 * <p>
 * 1. The box lists every COMPLETED purchase order that still has something to receive
 *    (tick one, several, or all of them; the search box filters by PO No. or supplier).
 * 2. The lines still pending on the ticked POs appear in the Receipt Line table
 *    (Receive Qty is pre-filled with the pending qty; change it, or remove a line).
 * 3. Save creates ONE completed material receipt (M_InOut, MovementType V+) per PO, linked to
 *    the PO lines, so stock and the PO "delivered" qty are updated by the standard document
 *    process. Then it asks whether to view the GRN PDF.
 * <p>
 * Attachments work exactly like the Requisition form: + Add File, click a name to open it,
 * the cross removes it, OK closes. Files are stored on every GRN saved in that go.
 */


public class WGoodsReceiptForm extends ADForm{

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WGoodsReceiptForm.class);

	private static final int MAX_PO = 300;
	private static final String CSS_VERSION = "g1";

	private final int clientId = Env.getAD_Client_ID(Env.getCtx());
	private final int orgId = Env.getAD_Org_ID(Env.getCtx());
	
	
	
	// header
		private final Label lblOrg = new Label();
		private Combobox cbDocType;
		private Combobox cbWarehouse;
		private Combobox cbLocator;
		private final Datebox dtMove = new Datebox();
		private final Textbox txtRef = new Textbox();
		private final Textbox txtDesc = new Textbox();
		private final Button btnAttach = new Button("Attachment");
		private final Label lblAttach = new Label();
		private final List<Media> attachments = new ArrayList()<Media>();
		
		
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
		private final Map<Integer, Set<Integer>> removedByOrder = new HashMap<Integer, Set<Integer>>();
		private Set<Integer> prevSel = new HashSet<Integer>();

		private final Button btnOk = new Button("\u2714  Save GRN");
		private final Button btnCancel = new Button("\u2716  Cancel");
		
		
		
		
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
		}
		
		
		
		
		
}
