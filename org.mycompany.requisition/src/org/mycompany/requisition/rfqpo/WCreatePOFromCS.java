package org.mycompany.requisition.rfqpo;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

import org.adempiere.webui.panel.ADForm;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.mycompany.requisition.rfqpo.RfqPoService.Line;
import org.mycompany.requisition.rfqpo.RfqPoService.Row;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Vlayout;

/**
 * Create PO from CS.
 * <p>
 * Every RFQ that has a recommended winner is listed, one row per winner supplier, with its
 * details. Pick a row and press "Create PO": the Create PO popup opens, and its OK creates the
 * Drafted PO and sends it to the approval matrix (the approval history is kept by the engine and
 * is visible in the PO approval form).
 */
public class WCreatePOFromCS extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WCreatePOFromCS.class);
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
	private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy");

	private final RfqPoService service = new RfqPoService();

	private Checkbox pendingOnly;
	private Label countLabel, detailTitle;
	private Listbox rfqGrid, detailGrid;
	private Map<Integer, Object[]> taxes;

	@Override
	protected void initForm() {
		org.mycompany.requisition.ui.XxTheme.wrap(this);
		setTitle("Create PO From CS");
		setWidth("100%");
		setHeight("100%");

		Vlayout root = new Vlayout();
		root.setWidth("100%");
		root.setStyle("padding:6px;");
		appendChild(root);

		// ---- top row
		Hlayout top = new Hlayout();
		top.setSclass("z-hlayout");
		Label org = new Label("Organization: " + Env.getContext(Env.getCtx(), "#AD_Org_Name"));
		org.setStyle("font-weight:600;");
		pendingOnly = new Checkbox("Pending PO only");
		pendingOnly.setChecked(true);
		pendingOnly.addEventListener(Events.ON_CHECK, e -> reload());
		Button reload = new Button("Reload RFQ");
		reload.addEventListener(Events.ON_CLICK, e -> reload());
		countLabel = new Label();
		top.appendChild(org);
		top.appendChild(pendingOnly);
		top.appendChild(reload);
		top.appendChild(countLabel);
		root.appendChild(top);

		// ---- all RFQs, one row per winner supplier
		rfqGrid = new Listbox();
		rfqGrid.setWidth("100%");
		rfqGrid.setHeight("320px");
		Listhead head = new Listhead();
		String[] names = { "SL", "RFQ No", "RFQ Name", "Date", "PR No", "BP Code", "Business Partner", "Address",
				"Products", "Items", "Qty", "Amount", "CS Status", "PO Status" };
		String[] widths = { "45px", "100px", "150px", "90px", "110px", "80px", "160px", "170px", "230px", "55px",
				"80px", "110px", "120px", "170px" };
		for (int i = 0; i < names.length; i++) {
			Listheader h = new Listheader(names[i]);
			h.setWidth(widths[i]);
			head.appendChild(h);
		}
		rfqGrid.appendChild(head);
		rfqGrid.addEventListener(Events.ON_SELECT, e -> showDetail());
		root.appendChild(rfqGrid);

		// ---- selected row: product lines
		detailTitle = new Label("Response Line Detail");
		detailTitle.setStyle("font-weight:600;");
		root.appendChild(detailTitle);
		detailGrid = new Listbox();
		detailGrid.setWidth("100%");
		detailGrid.setHeight("170px");
		Listhead dh = new Listhead();
		String[] dn = { "Line", "Product Code", "Product Name", "UOM", "RFQ Qty", "Already PO Qty", "Rate", "VAT %",
				"Total" };
		for (String n : dn)
			dh.appendChild(new Listheader(n));
		detailGrid.appendChild(dh);
		root.appendChild(detailGrid);

		// ---- buttons
		Hlayout btns = new Hlayout();
		Button create = new Button("Create PO");
		create.addEventListener(Events.ON_CLICK, e -> createPo());
		btns.appendChild(create);
		root.appendChild(btns);

		taxes = service.taxes();
		reload();
	}

	private void reload() {
		while (rfqGrid.getItemCount() > 0)
			rfqGrid.removeItemAt(0);
		clearDetail();
		List<Row> rows = service.listRows(pendingOnly.isChecked());
		int sl = 0;
		for (Row r : rows) {
			Listitem li = new Listitem();
			li.setValue(r);
			li.appendChild(right(String.valueOf(++sl)));
			li.appendChild(new Listcell(r.docNo));
			li.appendChild(new Listcell(r.name));
			li.appendChild(new Listcell(r.date == null ? "" : DATE.format(r.date)));
			li.appendChild(new Listcell(r.prNos));
			li.appendChild(new Listcell(r.bpCode));
			li.appendChild(new Listcell(r.bpName));
			li.appendChild(new Listcell(r.address));
			li.appendChild(new Listcell(r.products));
			li.appendChild(right(String.valueOf(r.items)));
			li.appendChild(right(r.qty.stripTrailingZeros().toPlainString()));
			li.appendChild(right(MONEY.format(r.total)));
			li.appendChild(new Listcell(r.csStatus()));
			li.appendChild(new Listcell(r.poStatus()));
			rfqGrid.appendChild(li);
		}
		countLabel.setValue("Total: " + rows.size());
		if (!RfqPoService.columnsReady())
			Messagebox.show("Run db/09_create_po_from_cs.sql first. Until then PO status cannot be shown and a PO cannot be created.",
					"Create PO From CS", Messagebox.OK, Messagebox.EXCLAMATION);
	}

	private void clearDetail() {
		while (detailGrid.getItemCount() > 0)
			detailGrid.removeItemAt(0);
		detailTitle.setValue("Response Line Detail");
	}

	private Row selected(boolean warn) {
		Listitem li = rfqGrid.getSelectedItem();
		if (li == null) {
			if (warn)
				Messagebox.show("Select a row first.", "Create PO From CS", Messagebox.OK, Messagebox.EXCLAMATION);
			return null;
		}
		return li.getValue();
	}

	private void showDetail() {
		clearDetail();
		Row r = selected(false);
		if (r == null)
			return;
		detailTitle.setValue("Response Line Detail  -  " + r.docNo + "  /  " + r.bpName);
		for (Line l : service.loadLines(r.rfqId, r.respId)) {
			BigDecimal pct = BigDecimal.ZERO;
			Object[] t = taxes.get(Integer.valueOf(l.taxId));
			if (t != null)
				pct = (BigDecimal) t[1];
			BigDecimal base = l.rfqQty.multiply(l.rate);
			BigDecimal total = base.add(RfqPoService.vat(base, pct));
			Listitem li = new Listitem();
			li.appendChild(right(String.valueOf(l.lineNo)));
			li.appendChild(new Listcell(l.code));
			li.appendChild(new Listcell(l.name));
			li.appendChild(new Listcell(l.uom));
			li.appendChild(right(l.rfqQty.stripTrailingZeros().toPlainString()));
			li.appendChild(right(l.already.stripTrailingZeros().toPlainString()));
			li.appendChild(right(MONEY.format(l.rate)));
			li.appendChild(right(pct.stripTrailingZeros().toPlainString()));
			li.appendChild(right(MONEY.format(total)));
			detailGrid.appendChild(li);
		}
	}

	private void createPo() {
		Row r = selected(true);
		if (r == null)
			return;
		if (!RfqPoService.columnsReady()) {
			Messagebox.show("Run db/09_create_po_from_cs.sql first.", "Create PO From CS", Messagebox.OK, Messagebox.ERROR);
			return;
		}
		if (!r.csApproved()) {
			Messagebox.show("The Comparative Statement of " + r.docNo + " is \"" + r.csStatus()
					+ "\". A PO can be created only after it is Approved.", "Create PO From CS", Messagebox.OK,
					Messagebox.EXCLAMATION);
			return;
		}
		if (r.remaining().signum() <= 0) {
			Messagebox.show("PO is already created for the whole quantity (" + r.poNos + ").", "Create PO From CS",
					Messagebox.OK, Messagebox.INFORMATION);
			return;
		}
		CreatePODialog.open(this, r, () -> reload());
	}

	private static Listcell right(String text) {
		Listcell c = new Listcell(text);
		c.setStyle("text-align:right;");
		return c;
	}
}
