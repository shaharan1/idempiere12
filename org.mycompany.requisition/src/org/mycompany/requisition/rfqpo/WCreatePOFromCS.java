package org.mycompany.requisition.rfqpo;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.adempiere.webui.panel.ADForm;
import org.compiere.db.CConnection;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.mycompany.requisition.rfqpo.RfqPoService.CsLine;
import org.mycompany.requisition.rfqpo.RfqPoService.Line;
import org.mycompany.requisition.rfqpo.RfqPoService.Result;
import org.mycompany.requisition.rfqpo.RfqPoService.Row;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Div;
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
import org.zkoss.zul.Window;

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

		// Root fills entire form vertically
		Vlayout root = new Vlayout();
		root.setWidth("100%");
		root.setVflex("1");
		root.setStyle("padding:0;margin:0;display:flex;flex-direction:column;height:100%;box-sizing:border-box;");
		appendChild(root);

		// ---- top bar
		Div topBar = new Div();
		topBar.setStyle(
			"display:flex;align-items:center;gap:14px;padding:8px 14px;" +
			"background:linear-gradient(90deg,#1e3a8a 0%,#2563eb 100%);" +
			"border-radius:6px 6px 0 0;flex-shrink:0;");
		root.appendChild(topBar);

		// Organization label
		Label org = new Label("Organization: " + Env.getContext(Env.getCtx(), "#AD_Org_Name"));
		org.setStyle("font-weight:700;color:#ffffff;font-size:13px;");

		// Separator
		Label sep = new Label("|");
		sep.setStyle("color:#93c5fd;font-weight:300;");

		// Pending only checkbox
		pendingOnly = new Checkbox("Pending PO only");
		pendingOnly.setChecked(true);
		pendingOnly.setStyle("color:#ffffff;font-size:13px;font-weight:500;");
		pendingOnly.addEventListener(Events.ON_CHECK, e -> reload());

		// Reload button
		Button reload = new Button("⟳ Reload RFQ");
		reload.setStyle(
			"background:#0d9488;color:#fff;border:none;border-radius:4px;" +
			"padding:4px 14px;font-size:12px;font-weight:600;cursor:pointer;");
		reload.addEventListener(Events.ON_CLICK, e -> reload());

		// Count label
		countLabel = new Label();
		countLabel.setStyle("color:#bfdbfe;font-size:12px;margin-left:auto;");

		topBar.appendChild(org);
		topBar.appendChild(sep);
		topBar.appendChild(pendingOnly);
		topBar.appendChild(reload);
		topBar.appendChild(countLabel);

		// ---- Table wrapper fills remaining vertical space
		Div tableWrap = new Div();
		tableWrap.setWidth("100%");
		tableWrap.setVflex("1");
		tableWrap.setStyle("flex:1 1 auto;overflow:hidden;");
		root.appendChild(tableWrap);

		// ---- Grid / Listbox
		grid = new Listbox();
		grid.setWidth("100%");
		grid.setVflex("1");
		grid.setStyle("border:none;border-radius:0 0 6px 6px;");

		Listhead head = new Listhead();
		head.setSizable(true);
		// Column names and hflex weights (proportional widths)
		String[] names  = { "SL No.", "BP Code", "Business Partner", "Address",
				"PO Creation", "Product Code", "Product Name", "Product Rank",
				"Price", "Recommended Winner", "Select Winner", "Comments" };
		String[] hflexs = { "1", "2", "4", "5",
				"3", "2", "4", "2",
				"2", "3", "2", "4" };
		for (int i = 0; i < names.length; i++) {
			Listheader h = new Listheader(names[i]);
			h.setHflex(hflexs[i]);
			h.setStyle("text-align:center;white-space:normal;");
			head.appendChild(h);
		}
		grid.appendChild(head);
		tableWrap.appendChild(grid);

		reload();
	}

	private void reload() {
		while (grid.getItemCount() > 0)
			grid.removeItemAt(0);
		grid.getChildren().removeIf(c -> c instanceof Listgroup);

		Map<Integer, Row> csHeads = new LinkedHashMap<Integer, Row>();
		for (Row r : service.listRows(pendingOnly.isChecked()))
			csHeads.putIfAbsent(Integer.valueOf(r.rfqId), r);
		Map<String, Row> winnerRows = new HashMap<String, Row>();
		for (Row r : service.listRows(false))
			winnerRows.put(r.rfqId + ":" + r.respId, r);

		for (Row head : csHeads.values()) {
			Listgroup g = new Listgroup();
			g.setLabel("CS " + head.docNo + "   " + head.name + "   "
					+ (head.date == null ? "" : DATE.format(head.date)));
			g.setStyle("background:#dbeafe;font-weight:700;color:#1e3a8a;font-size:13px;");
			grid.appendChild(g);

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
				// Zebra striping
				li.setStyle((sl % 2 == 0)
						? "background:#f8faff;"
						: "background:#ffffff;");

				li.appendChild(first ? center(String.valueOf(sl)) : new Listcell());
				li.appendChild(new Listcell(first ? c.bpCode : ""));
				li.appendChild(new Listcell(first ? c.bpName : ""));
				li.appendChild(new Listcell(first ? c.address : ""));

				final Row wr = winnerRows.get(c.rfqId + ":" + c.respId);
				Line wl = left.get(c.respId + ":" + c.rfqLineId);
				boolean canCreate = c.winner && wr != null && wl != null
						&& wl.remaining().signum() > 0 && wr.csApproved();
				Listcell action = new Listcell();
				action.setStyle("text-align:center;");
				final Button create = new Button("Create PO");
				create.setStyle(canCreate
						? "background:#16a34a;color:#fff;border:none;border-radius:4px;padding:3px 10px;font-size:11px;font-weight:600;cursor:pointer;"
						: "background:#d1d5db;color:#6b7280;border:none;border-radius:4px;padding:3px 10px;font-size:11px;cursor:default;");
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

				// Recommended Winner — read-only
				li.appendChild(check(c.winner));

				// ---- Select Winner — interactive checkbox ----
				final int rfqLineId_f = c.rfqLineId;
				final int respId_f    = c.respId;
				final int rfqId_f     = c.rfqId;

				Listcell selCell = new Listcell();
				selCell.setStyle("text-align:center;");
				Checkbox selCb = new Checkbox();
				selCb.setChecked(c.winner);
				selCb.setStyle("cursor:pointer;");
				selCb.addEventListener(Events.ON_CHECK, evt -> {
					boolean newVal = selCb.isChecked();
					if (newVal) {
						// Must enter a comment before confirming
						showCommentDialog(rfqLineId_f, respId_f, rfqId_f, selCb);
					} else {
						// Unchecking: clear winner & comment, no dialog needed
						saveWinner(rfqLineId_f, respId_f, false, "");
						reload();
					}
				});
				selCell.appendChild(selCb);
				li.appendChild(selCell);

				// ---- Comments — editable for winner lines ----
				Listcell cm = new Listcell();
				Textbox tb = new Textbox(c.comment == null ? "" : c.comment);
				tb.setReadonly(!c.winner);
				tb.setWidth("97%");
				tb.setStyle(c.winner
						? "border:1px solid #60a5fa;border-radius:3px;font-size:12px;"
						: "border:1px solid #e5e7eb;border-radius:3px;font-size:12px;background:#f9fafb;");
				if (c.winner) {
					tb.addEventListener(Events.ON_CHANGE, e ->
						saveWinnerComment(rfqLineId_f, respId_f, tb.getValue()));
				}
				cm.appendChild(tb);
				li.appendChild(cm);

				grid.appendChild(li);
			}
		}
		countLabel.setValue("Total CS: " + csHeads.size());
		if (!RfqPoService.columnsReady())
			Messagebox.show(
				"Run db/09_create_po_from_cs.sql first. Until then a PO cannot be created.",
				"Create PO From CS", Messagebox.OK, Messagebox.EXCLAMATION);
	}

	/** Shows a modal dialog requiring the user to enter a comment when selecting a winner. */
	private void showCommentDialog(int rfqLineId, int respId, int rfqId, Checkbox selCb) {
		Window dlg = new Window();
		dlg.setTitle("Comment Required");
		dlg.setBorder("normal");
		dlg.setMode("modal");
		dlg.setWidth("420px");
		dlg.setStyle("padding:0;");

		Vlayout body = new Vlayout();
		body.setStyle("padding:16px;gap:10px;");
		dlg.appendChild(body);

		Label info = new Label("Please enter a comment to confirm winner selection:");
		info.setStyle("font-size:13px;font-weight:600;color:#1e3a8a;display:block;margin-bottom:8px;");
		body.appendChild(info);

		Textbox commentBox = new Textbox();
		commentBox.setRows(3);
		commentBox.setWidth("100%");
		commentBox.setStyle("font-size:13px;border:1px solid #93c5fd;border-radius:4px;");
		commentBox.setPlaceholder("Enter winner comment…");
		body.appendChild(commentBox);

		// Error label (hidden initially)
		Label errLabel = new Label("⚠ Comment is required to select winner.");
		errLabel.setStyle("color:#dc2626;font-size:12px;display:none;");
		body.appendChild(errLabel);

		Hlayout buttons = new Hlayout();
		buttons.setStyle("justify-content:flex-end;gap:8px;margin-top:8px;");

		Button ok = new Button("✔ Confirm");
		ok.setStyle("background:#2563eb;color:#fff;border:none;border-radius:4px;padding:5px 18px;font-weight:600;cursor:pointer;");

		Button cancel = new Button("✘ Cancel");
		cancel.setStyle("background:#6b7280;color:#fff;border:none;border-radius:4px;padding:5px 14px;cursor:pointer;");

		buttons.appendChild(ok);
		buttons.appendChild(cancel);
		body.appendChild(buttons);

		ok.addEventListener(Events.ON_CLICK, e -> {
			String comment = commentBox.getValue().trim();
			if (comment.isEmpty()) {
				errLabel.setStyle("color:#dc2626;font-size:12px;display:block;");
				return;
			}
			saveWinner(rfqLineId, respId, true, comment);
			dlg.detach();
			reload();
		});

		cancel.addEventListener(Events.ON_CLICK, e -> {
			// Revert checkbox visual state
			selCb.setChecked(false);
			dlg.detach();
		});

		dlg.setPage(grid.getPage());
		dlg.doModal();
	}

	/** Saves IsSelectedWinner + XX_WinnerComment for the given response line. */
	private void saveWinner(int rfqLineId, int respId, boolean winner, String comment) {
		try {
			DB.executeUpdateEx(
				"UPDATE C_RfQResponseLine " +
				"SET IsSelectedWinner=?, XX_WinnerComment=?, Updated=now(), UpdatedBy=? " +
				"WHERE C_RfQLine_ID=? AND C_RfQResponse_ID=?",
				new Object[]{
					winner ? "Y" : "N",
					comment,
					Integer.valueOf(Env.getAD_User_ID(Env.getCtx())),
					Integer.valueOf(rfqLineId),
					Integer.valueOf(respId)
				}, null);
		} catch (Exception e) {
			log.warning("saveWinner: " + e.getMessage());
			Messagebox.show("Error saving winner: " + e.getMessage(),
				"Error", Messagebox.OK, Messagebox.ERROR);
		}
	}

	/** Saves only the comment field for an already-selected winner line. */
	private void saveWinnerComment(int rfqLineId, int respId, String comment) {
		try {
			DB.executeUpdateEx(
				"UPDATE C_RfQResponseLine " +
				"SET XX_WinnerComment=?, Updated=now(), UpdatedBy=? " +
				"WHERE C_RfQLine_ID=? AND C_RfQResponse_ID=?",
				new Object[]{
					comment,
					Integer.valueOf(Env.getAD_User_ID(Env.getCtx())),
					Integer.valueOf(rfqLineId),
					Integer.valueOf(respId)
				}, null);
		} catch (Exception e) {
			log.warning("saveWinnerComment: " + e.getMessage());
			Messagebox.show("Error saving comment: " + e.getMessage(),
				"Error", Messagebox.OK, Messagebox.ERROR);
		}
	}

	/** Creates the PO of this one product line (the quantity still left) and Completes it. */
	private void createPo(Row r, int rfqLineId, Button button) {
		if (!RfqPoService.columnsReady()) {
			Messagebox.show("Run db/09_create_po_from_cs.sql first.",
				"Create PO From CS", Messagebox.OK, Messagebox.ERROR);
			return;
		}
		button.setDisabled(true);
		try {
			Result res = service.createCompletedPo(r, rfqLineId);
			Messagebox.show("PO " + res.docNo + " created.\n" + res.approvalMessage,
				"Create PO From CS", Messagebox.OK, Messagebox.INFORMATION);
		} catch (Exception ex) {
			log.warning("createPo: " + ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(),
				"Create PO From CS", Messagebox.OK, Messagebox.ERROR);
		}
		reload();
	}

	/** Read-only checkbox cell (for Recommended Winner). */
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
