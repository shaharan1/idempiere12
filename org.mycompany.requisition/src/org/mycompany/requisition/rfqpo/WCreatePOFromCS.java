package org.mycompany.requisition.rfqpo;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.adempiere.webui.panel.ADForm;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.mycompany.requisition.rfqpo.RfqPoService.CsLine;
import org.mycompany.requisition.rfqpo.RfqPoService.Line;
import org.mycompany.requisition.rfqpo.RfqPoService.Result;
import org.mycompany.requisition.rfqpo.RfqPoService.Row;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listgroup;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Vlayout;

/**
 * Create PO from CS.
 * <p>
 * One table, the columns of the Response Line Detail grid: SL No., BP Code, Business Partner,
 * Address, PO Creation, Product Code, Product Name, Product Rank, Price, Recommended Winner,
 * Select Winner, Comments. One row per product line of every supplier of a finally approved CS.
 * The "Create PO" button of a row creates the PO of that product (the quantity still left) for
 * that supplier and Completes it. It is enabled for the recommended winner's lines only.
 */
public class WCreatePOFromCS extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WCreatePOFromCS.class);
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
	private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy");

	private final RfqPoService service = new RfqPoService();

	private Checkbox pendingOnly;
	private Label countLabel;
	private Listbox grid;

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

		// ---- the table
		grid = new Listbox();
		grid.setWidth("100%");
		grid.setHeight("560px");
		Listhead head = new Listhead();
		String[] names = { "SL No.", "BP Code", "Business Partner", "Address", "PO Creation", "Product Code",
				"Product Name", "Product Rank", "Price", "Recommended Winner", "Select Winner", "Comments" };
		String[] widths = { "60px", "90px", "200px", "230px", "120px", "110px", "230px", "100px", "90px", "110px",
				"100px", "170px" };
		for (int i = 0; i < names.length; i++) {
			Listheader h = new Listheader(names[i]);
			h.setWidth(widths[i]);
			head.appendChild(h);
		}
		grid.appendChild(head);
		root.appendChild(grid);

		reload();
	}

	private void reload() {
		while (grid.getItemCount() > 0)
			grid.removeItemAt(0);
		grid.getChildren().removeIf(c -> c instanceof Listgroup);

		// CSs to show, and the winner (CS, supplier) rows a PO can be made for
		Map<Integer, Row> csHeads = new LinkedHashMap<Integer, Row>();
		for (Row r : service.listRows(pendingOnly.isChecked()))
			csHeads.putIfAbsent(Integer.valueOf(r.rfqId), r);
		Map<String, Row> winnerRows = new HashMap<String, Row>();
		for (Row r : service.listRows(false))
			winnerRows.put(r.rfqId + ":" + r.respId, r);

		for (Row head : csHeads.values()) {
			Listgroup g = new Listgroup();
			g.setLabel("CS " + head.docNo + "   " + head.name + "   " + (head.date == null ? "" : DATE.format(head.date)));
			grid.appendChild(g);

			// quantity still left per winner line
			Map<String, Line> left = new HashMap<String, Line>();
			for (Row wr : winnerRows.values())
				if (wr.rfqId == head.rfqId)
					for (Line l : service.loadLines(wr.rfqId, wr.respId))
						left.put(wr.respId + ":" + l.rfqLineId, l);

			int sl = 0, lastResp = -1;
			for (final CsLine c : service.listCsLines(head.rfqId)) {
				boolean first = c.respId != lastResp;
				if (first) {
					sl++;
					lastResp = c.respId;
				}
				Listitem li = new Listitem();
				li.appendChild(first ? center(String.valueOf(sl)) : new Listcell());
				li.appendChild(new Listcell(first ? c.bpCode : ""));
				li.appendChild(new Listcell(first ? c.bpName : ""));
				li.appendChild(new Listcell(first ? c.address : ""));

				final Row wr = winnerRows.get(c.rfqId + ":" + c.respId);
				Line wl = left.get(c.respId + ":" + c.rfqLineId);
				boolean canCreate = c.winner && wr != null && wl != null && wl.remaining().signum() > 0
						&& wr.csApproved();
				Listcell action = new Listcell();
				final Button create = new Button("Create PO");
				create.setDisabled(!canCreate);
				create.addEventListener(Events.ON_CLICK, e -> createPo(wr, c.rfqLineId, create));
				action.appendChild(create);
				li.appendChild(action);

				li.appendChild(new Listcell(c.productCode));
				li.appendChild(new Listcell(c.productName));
				li.appendChild(center(String.valueOf(c.rank)));
				Listcell price = new Listcell(MONEY.format(c.price));
				price.setStyle("text-align:right;");
				li.appendChild(price);
				li.appendChild(check(c.winner)); // Recommended Winner
				li.appendChild(check(c.winner)); // Select Winner (one winner flag per line)
				Listcell cm = new Listcell();
				Textbox tb = new Textbox(c.comment);
				tb.setReadonly(true);
				tb.setWidth("95%");
				cm.appendChild(tb);
				li.appendChild(cm);
				grid.appendChild(li);
			}
		}
		countLabel.setValue("Total: " + csHeads.size());
		if (!RfqPoService.columnsReady())
			Messagebox.show("Run db/09_create_po_from_cs.sql first. Until then a PO cannot be created.",
					"Create PO From CS", Messagebox.OK, Messagebox.EXCLAMATION);
	}

	/** Creates the PO of this one product line (the quantity still left) and Completes it. */
	private void createPo(Row r, int rfqLineId, Button button) {
		if (!RfqPoService.columnsReady()) {
			Messagebox.show("Run db/09_create_po_from_cs.sql first.", "Create PO From CS", Messagebox.OK, Messagebox.ERROR);
			return;
		}
		button.setDisabled(true); // no double click
		try {
			Result res = service.createCompletedPo(r, rfqLineId);
			Messagebox.show("PO " + res.docNo + " created.\n" + res.approvalMessage, "Create PO From CS", Messagebox.OK,
					Messagebox.INFORMATION);
		} catch (Exception ex) {
			log.warning("createPo: " + ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "Create PO From CS",
					Messagebox.OK, Messagebox.ERROR);
		}
		reload();
	}

	private static Listcell check(boolean on) {
		Listcell c = new Listcell();
		Checkbox cb = new Checkbox();
		cb.setChecked(on);
		cb.setDisabled(true);
		c.appendChild(cb);
		c.setStyle("text-align:center;");
		return c;
	}

	private static Listcell center(String text) {
		Listcell c = new Listcell(text);
		c.setStyle("text-align:center;");
		return c;
	}
}