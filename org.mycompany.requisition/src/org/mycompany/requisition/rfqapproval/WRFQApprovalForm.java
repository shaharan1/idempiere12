package org.mycompany.requisition.rfqapproval;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.adempiere.webui.panel.ADForm;
import org.compiere.util.CLogger;
import org.compiere.util.KeyNamePair;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Comparison;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.HistoryLine;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Item;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.RfqRow;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Supplier;
import org.zkoss.util.media.AMedia;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Iframe;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Radio;
import org.zkoss.zul.Radiogroup;
import org.zkoss.zul.Tab;
import org.zkoss.zul.Tabbox;
import org.zkoss.zul.Tabpanel;
import org.zkoss.zul.Tabpanels;
import org.zkoss.zul.Tabs;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Vlayout;
import org.zkoss.zul.Window;

/**
 * RFQ approval screen: the RFQs whose suppliers have all completed their
 * response, with Recommend Winner, Comparative Statement (PDF), Last PO History,
 * Approval History and Approve / Reject / Forward.
 * <p>
 * Menu registration: AD_Form.Classname =
 * {@code org.mycompany.requisition.rfqapproval.WRFQApprovalForm} (54 of 60 chars).
 */
public class WRFQApprovalForm extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WRFQApprovalForm.class);
	private static final String GREEN = "background:#90EE90;";
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
	private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy");
	private static final SimpleDateFormat DATETIME = new SimpleDateFormat("dd/MM/yyyy HH:mm");

	private final RfQApprovalService service = new RfQApprovalService();

	private Checkbox pendingOnly;
	private Label countLabel;
	private Listbox rfqList;
	private Textbox message;
	private Listbox forwardTo;

	@Override
	protected void initForm() {
		setTitle("RFQ Approval");
		setWidth("100%");
		setHeight("100%");

		Vlayout root = new Vlayout();
		root.setWidth("100%");
		root.setStyle("padding:6px;");
		appendChild(root);

		// ---- search row
		Hlayout top = new Hlayout();
		top.setSclass("z-hlayout");
		pendingOnly = new Checkbox("Pending only");
		pendingOnly.setChecked(true);
		Button reload = new Button("Reload");
		reload.addEventListener(Events.ON_CLICK, e -> reload());
		countLabel = new Label();
		top.appendChild(pendingOnly);
		top.appendChild(reload);
		top.appendChild(countLabel);
		root.appendChild(top);

		// ---- RFQ list
		rfqList = new Listbox();
		rfqList.addEventListener(Events.ON_SELECT, e -> updateRecommendVisibility());
		rfqList.setWidth("100%");
		rfqList.setHeight("300px");
		Listhead head = new Listhead();
		for (String h : new String[] { "CS / RFQ No", "Name", "Date", "Suppliers", "Recommended Winner", "Status" })
			head.appendChild(new Listheader(h));
		rfqList.appendChild(head);
		root.appendChild(rfqList);

		// ---- document buttons
		Hlayout docButtons = new Hlayout();
		Button recommendBtn = button("Recommend", e -> openRecommend());
		recommendBtn.setId("recommendBtn");
		docButtons.appendChild(recommendBtn);
		docButtons.appendChild(button("Recommend Winner", e -> openRecommend()));
		docButtons.appendChild(button("Comparative Statement (PDF)", e -> showPdf()));
		docButtons.appendChild(button("Approval History", e -> showHistory()));
		root.appendChild(docButtons);

		// ---- decision panel
		root.appendChild(new Label("Message"));
		message = new Textbox();
		message.setMultiline(true);
		message.setRows(3);
		message.setWidth("100%");
		root.appendChild(message);

		Hlayout decide = new Hlayout();
		decide.appendChild(button("Approve", e -> decide(RfQApprovalService.A_APPROVE)));
		decide.appendChild(button("Reject", e -> decide(RfQApprovalService.A_REJECT)));
		forwardTo = new Listbox();
		forwardTo.setMold("select");
		forwardTo.appendItem("-- Forward to --", null);
		for (KeyNamePair u : service.userOptions())
			forwardTo.appendItem(u.getName(), String.valueOf(u.getKey()));
		forwardTo.setSelectedIndex(0);
		decide.appendChild(forwardTo);
		decide.appendChild(button("Forward", e -> decide(RfQApprovalService.A_FORWARD)));
		root.appendChild(decide);

		reload();
	}

	private Button button(String label, org.zkoss.zk.ui.event.EventListener<org.zkoss.zk.ui.event.Event> l) {
		Button b = new Button(label);
		b.addEventListener(Events.ON_CLICK, l);
		return b;
	}

	// ------------------------------------------------------------ list

	private void reload() {
		while (rfqList.getItemCount() > 0)
			rfqList.removeItemAt(0);
		List<RfqRow> rows = service.listRfqs(pendingOnly.isChecked());
		for (RfqRow r : rows) {
			Listitem li = new Listitem();
			li.setValue(r);
			li.appendChild(new Listcell(r.docNo));
			li.appendChild(new Listcell(r.name));
			li.appendChild(new Listcell(r.date == null ? "" : DATE.format(r.date)));
			li.appendChild(new Listcell(String.valueOf(r.supplierCount)));
			li.appendChild(new Listcell(r.winner == null ? "" : r.winner));
			li.appendChild(new Listcell(r.status()));
			rfqList.appendChild(li);
		}
		countLabel.setValue("Total RFQ: " + rows.size());
	}

	private void updateRecommendVisibility() {
		RfqRow r = selected();
		org.zkoss.zk.ui.Component btn = null;
		// find recommend button
		for (org.zkoss.zk.ui.Component c : getPage().getRoots()) {
		}
		// simpler: search by id via desktop
		try {
			org.zkoss.zk.ui.Component comp = getPage().getFellowIfAny("recommendBtn");
			if (comp != null)
				comp.setVisible(r != null && RfQApprovalService.isCsDocType(r.id));
		} catch (Exception ignore) {
		}
	}

	private RfqRow selected() {
		Listitem li = rfqList.getSelectedItem();
		if (li == null) {
			Messagebox.show("Select an RFQ first.", "RFQ Approval", Messagebox.OK, Messagebox.EXCLAMATION);
			return null;
		}
		return li.getValue();
	}

	// ------------------------------------------------------------ Comparative Statement PDF

	private void showPdf() {
		RfqRow r = selected();
		if (r == null)
			return;
		try {
			Comparison c = service.load(r.id);
			byte[] pdf = new ComparativeStatementReport().toPdf(c);
			AMedia media = new AMedia("CS_" + r.docNo + ".pdf", "pdf", "application/pdf", pdf);

			final Window win = new Window("Comparative Statement - " + r.docNo, "normal", true);
			win.setWidth("95%");
			win.setHeight("90%");
			win.setStyle("padding:0;");
			win.setPosition("center");
			Iframe frame = new Iframe();
			frame.setContent(media);
			frame.setWidth("100%");
			frame.setHeight("calc(100% - 40px)");
			win.appendChild(frame);
			Button close = new Button("Close");
			close.addEventListener(Events.ON_CLICK, e -> win.detach());
			win.appendChild(close);
			win.setPage(getPage());
			win.doModal();
		} catch (Exception ex) {
			fail(ex);
		}
	}

	// ------------------------------------------------------------ Recommend Winner popup

	private void openRecommend() {
		final RfqRow r = selected();
		if (r == null)
			return;
		try {
			final Comparison c = service.load(r.id);
			if (c.suppliers.isEmpty()) {
				Messagebox.show("This RFQ has no supplier response.", "Recommend Winner", Messagebox.OK,
						Messagebox.EXCLAMATION);
				return;
			}
			final Window win = new Window("Recommend Winner - " + r.docNo, "normal", true);
			win.setWidth("95%");
			win.setHeight("85%");
			win.setPosition("center");

			Tabbox tb = new Tabbox();
			tb.setHeight("calc(100% - 10px)");
			Tabs tabs = new Tabs();
			tabs.appendChild(new Tab("Recommend Winner"));
			tabs.appendChild(new Tab("Comparative Statement Details"));
			tb.appendChild(tabs);
			Tabpanels panels = new Tabpanels();

			// ---- tab 1: recommend a winner for each product
			Tabpanel p1 = new Tabpanel();
			final Map<Integer, Radiogroup> groups = new LinkedHashMap<Integer, Radiogroup>();
			final Map<Integer, Textbox> justifications = new LinkedHashMap<Integer, Textbox>();

			List<RfQApprovalService.RankedQuote> ranked = RfQApprovalService.rankQuotes(r.id);
			final Map<Integer, List<RfQApprovalService.RankedQuote>> byLine =
					new LinkedHashMap<Integer, List<RfQApprovalService.RankedQuote>>();
			for (RfQApprovalService.RankedQuote q : ranked) {
				Integer key = Integer.valueOf(q.rfqLineId);
				List<RfQApprovalService.RankedQuote> l = byLine.get(key);
				if (l == null) {
					l = new java.util.ArrayList<RfQApprovalService.RankedQuote>();
					byLine.put(key, l);
				}
				l.add(q);
			}
			final Map<Integer, Integer> existing = RfQApprovalService.lineWinners(r.id);

			Vlayout prodBox = new Vlayout();
			prodBox.setWidth("100%");
			prodBox.setHeight("380px");
			prodBox.setStyle("overflow:auto;");
			int productNo = 0;
			for (Item it : c.items) {
				productNo++;
				Vlayout card = new Vlayout();
				card.setWidth("100%");
				card.setStyle("border:1px solid #dbe3ec;border-radius:6px;padding:6px 10px;margin-bottom:8px;");
				Label title = new Label(productNo + ". " + it.name + "   (Qty "
						+ it.qty.stripTrailingZeros().toPlainString()
						+ (it.uom.length() == 0 ? "" : " " + it.uom) + ")");
				title.setStyle("font-weight:700;color:#1e4d7b;display:block;");
				card.appendChild(title);

				Radiogroup rg = new Radiogroup();
				groups.put(Integer.valueOf(it.rfqLineId), rg);
				List<RfQApprovalService.RankedQuote> quotes = byLine.get(Integer.valueOf(it.rfqLineId));
				if (quotes == null || quotes.isEmpty()) {
					card.appendChild(new Label("No vendor quoted this product."));
				} else {
					Integer prev = existing.get(Integer.valueOf(it.rfqLineId));
					for (RfQApprovalService.RankedQuote q : quotes) {
						Radio rb = new Radio(q.vendorName + "   -   " + MONEY.format(q.price)
								+ "   (rank " + q.rank + (q.rank == 1 ? ", lowest" : "") + ")");
						rb.setValue(Integer.valueOf(q.responseId));
						rb.setAttribute("rank", Integer.valueOf(q.rank));
						rb.setStyle("display:block;margin:3px 0;");
						rg.appendChild(rb);
						if (prev != null && prev.intValue() == q.responseId)
							rg.setSelectedItem(rb);
					}
				}
				card.appendChild(rg);

				Label jl = new Label(
						"Justification (required when the chosen vendor is not the lowest price)");
				jl.setStyle("font-size:11px;color:#64748b;display:block;margin-top:4px;");
				card.appendChild(jl);
				Textbox just = new Textbox();
				just.setMultiline(true);
				just.setRows(2);
				just.setWidth("100%");
				justifications.put(Integer.valueOf(it.rfqLineId), just);
				card.appendChild(just);
				prodBox.appendChild(card);
			}
			p1.appendChild(prodBox);

			p1.appendChild(new Label("Overall remarks"));
			final Textbox remarks = new Textbox();
			remarks.setMultiline(true);
			remarks.setRows(2);
			remarks.setWidth("100%");
			p1.appendChild(remarks);

			Button save = new Button("Recommend Selected");
			save.addEventListener(Events.ON_CLICK, e -> {
				Map<Integer, Integer> winners = new LinkedHashMap<Integer, Integer>();
				Map<Integer, String> justs = new LinkedHashMap<Integer, String>();
				for (Map.Entry<Integer, Radiogroup> entry : groups.entrySet()) {
					Radio sel = entry.getValue().getSelectedItem();
					if (sel == null)
						continue;
					winners.put(entry.getKey(), (Integer) sel.getValue());
					Object rank = sel.getAttribute("rank");
					if (rank instanceof Integer && ((Integer) rank).intValue() > 1) {
						Textbox jb = justifications.get(entry.getKey());
						justs.put(entry.getKey(), jb == null ? "" : jb.getValue());
					}
				}
				if (winners.isEmpty()) {
					Messagebox.show("Select a vendor for at least one product.", "Recommend Winner",
							Messagebox.OK, Messagebox.EXCLAMATION);
					return;
				}
				try {
					service.recommendLines(r.id, winners, justs, remarks.getValue());
					win.detach();
					reload();
					Messagebox.show("Recommendation saved.", "Recommend Winner", Messagebox.OK,
							Messagebox.INFORMATION);
				} catch (Exception ex) {
					fail(ex);
				}
			});
			p1.appendChild(save);
			panels.appendChild(p1);

			// ---- tab 2: comparison grid + last PO history
			Tabpanel p2 = new Tabpanel();
			p2.appendChild(comparisonGrid(c));
			panels.appendChild(p2);

			tb.appendChild(panels);
			win.appendChild(tb);
			win.setPage(getPage());
			win.doModal();
		} catch (Exception ex) {
			fail(ex);
		}
	}

	private Vlayout comparisonGrid(final Comparison c) {
		Vlayout box = new Vlayout();
		box.setWidth("100%");
		final Listbox grid = new Listbox();
		grid.setWidth("100%");
		grid.setHeight("260px");
		Listhead h = new Listhead();
		for (String t : new String[] { "Sl", "PR No", "Item", "UoM", "Qty", "Last P." })
			h.appendChild(new Listheader(t));
		for (Supplier s : c.suppliers) {
			h.appendChild(new Listheader(s.name + " - Unit"));
			h.appendChild(new Listheader("Total"));
		}
		grid.appendChild(h);
		int sl = 0;
		for (Item it : c.items) {
			Listitem li = new Listitem();
			li.setValue(it);
			li.appendChild(new Listcell(String.valueOf(++sl)));
			li.appendChild(new Listcell(it.prNo));
			li.appendChild(new Listcell(it.name));
			li.appendChild(new Listcell(it.uom));
			li.appendChild(new Listcell(it.qty.stripTrailingZeros().toPlainString()));
			li.appendChild(new Listcell(it.lastPrice == null ? "" : MONEY.format(it.lastPrice)));
			for (Supplier s : c.suppliers) {
				Integer k = Integer.valueOf(s.responseId);
				Listcell u = new Listcell(it.unit.get(k) == null ? "" : MONEY.format(it.unit.get(k)));
				if (it.lowest.contains(k))
					u.setStyle(GREEN);
				li.appendChild(u);
				li.appendChild(new Listcell(it.total.get(k) == null ? "" : MONEY.format(it.total.get(k))));
			}
			grid.appendChild(li);
		}
		String[] labels = { "Sub Total", "Discount", "Transportation", "Other Cost", "Grand Total", "VAT",
				"Cost of Ownership" };
		for (int r = 0; r < labels.length; r++) {
			Listitem li = new Listitem();
			for (int i = 0; i < 6; i++)
				li.appendChild(new Listcell(i == 2 ? labels[r] : ""));
			for (Supplier s : c.suppliers) {
				BigDecimal v;
				switch (r) {
				case 0: v = s.subTotal; break;
				case 1: v = s.discount; break;
				case 2: v = s.transport; break;
				case 3: v = s.other; break;
				case 4: v = s.grand; break;
				case 5: v = s.vat; break;
				default: v = s.ownership;
				}
				li.appendChild(new Listcell(""));
				li.appendChild(new Listcell(MONEY.format(v)));
			}
			li.setDisabled(true);
			grid.appendChild(li);
		}
		box.appendChild(grid);

		box.appendChild(new Label("Last PO History (select an item above)"));
		final Listbox po = new Listbox();
		po.setWidth("100%");
		po.setHeight("140px");
		Listhead ph = new Listhead();
		for (String t : new String[] { "PO No", "Date", "Vendor", "Qty", "Price" })
			ph.appendChild(new Listheader(t));
		po.appendChild(ph);
		box.appendChild(po);
		grid.addEventListener(Events.ON_SELECT, e -> {
			while (po.getItemCount() > 0)
				po.removeItemAt(0);
			Listitem sel = grid.getSelectedItem();
			if (sel == null || !(sel.getValue() instanceof Item))
				return;
			for (String[] row : service.lastPoHistory(((Item) sel.getValue()).rfqLineId)) {
				Listitem li = new Listitem();
				for (String cell : row)
					li.appendChild(new Listcell(cell));
				po.appendChild(li);
			}
		});
		return box;
	}

	// ------------------------------------------------------------ Approval History

	private void showHistory() {
		RfqRow r = selected();
		if (r == null)
			return;
		final Window win = new Window("Approval History - " + r.docNo, "normal", true);
		win.setWidth("800px");
		win.setHeight("420px");
		win.setPosition("center");
		Listbox lb = new Listbox();
		lb.setWidth("100%");
		lb.setHeight("320px");
		Listhead h = new Listhead();
		for (String t : new String[] { "Date", "User", "Action", "To", "Comment" })
			h.appendChild(new Listheader(t));
		lb.appendChild(h);
		for (HistoryLine l : service.history(r.id)) {
			Listitem li = new Listitem();
			li.appendChild(new Listcell(l.date == null ? "" : DATETIME.format(l.date)));
			li.appendChild(new Listcell(l.user == null ? "" : l.user));
			li.appendChild(new Listcell(l.action == null ? "" : l.action));
			li.appendChild(new Listcell(l.toUser == null ? "" : l.toUser));
			li.appendChild(new Listcell(l.comment == null ? "" : l.comment));
			lb.appendChild(li);
		}
		win.appendChild(lb);
		Button close = new Button("Close");
		close.addEventListener(Events.ON_CLICK, e -> win.detach());
		win.appendChild(close);
		win.setPage(getPage());
		win.doModal();
	}

	// ------------------------------------------------------------ Approve / Reject / Forward

	private void decide(String action) {
		RfqRow r = selected();
		if (r == null)
			return;
		try {
			if (RfQApprovalService.A_APPROVE.equals(action))
				service.approve(r.id, message.getValue());
			else if (RfQApprovalService.A_REJECT.equals(action))
				service.reject(r.id, message.getValue());
			else {
				Listitem to = forwardTo.getSelectedItem();
				int uid = to == null || to.getValue() == null ? 0 : Integer.parseInt((String) to.getValue());
				service.forward(r.id, uid, message.getValue());
			}
			message.setValue("");
			forwardTo.setSelectedIndex(0);
			reload();
			Messagebox.show("Done: " + action, "RFQ Approval", Messagebox.OK, Messagebox.INFORMATION);
		} catch (Exception ex) {
			fail(ex);
		}
	}

	private void fail(Exception ex) {
		log.log(Level.SEVERE, ex.getMessage(), ex);
		Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "RFQ Approval",
				Messagebox.OK, Messagebox.ERROR);
	}
}
