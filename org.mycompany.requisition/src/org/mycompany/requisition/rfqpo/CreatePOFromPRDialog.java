package org.mycompany.requisition.rfqpo;

import static org.mycompany.requisition.rfqpo.CreatePODialog.MONEY;
import static org.mycompany.requisition.rfqpo.CreatePODialog.addItem;
import static org.mycompany.requisition.rfqpo.CreatePODialog.cell;
import static org.mycompany.requisition.rfqpo.CreatePODialog.greenLabel;
import static org.mycompany.requisition.rfqpo.CreatePODialog.idOf;
import static org.mycompany.requisition.rfqpo.CreatePODialog.label;
import static org.mycompany.requisition.rfqpo.CreatePODialog.moneyBox;
import static org.mycompany.requisition.rfqpo.CreatePODialog.qtyText;
import static org.mycompany.requisition.rfqpo.CreatePODialog.right;
import static org.mycompany.requisition.rfqpo.CreatePODialog.rowOf;
import static org.mycompany.requisition.rfqpo.CreatePODialog.selectOf;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.compiere.util.CLogger;
import org.compiere.util.KeyNamePair;
import org.mycompany.requisition.rfqpo.RfqPoService.PrDefaults;
import org.mycompany.requisition.rfqpo.RfqPoService.PrLine;
import org.mycompany.requisition.rfqpo.RfqPoService.PrLineReq;
import org.mycompany.requisition.rfqpo.RfqPoService.PrRequest;
import org.mycompany.requisition.rfqpo.RfqPoService.Result;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Column;
import org.zkoss.zul.Columns;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Vlayout;
import org.zkoss.zul.Window;

/**
 * "Create PO" popup of the Create RFQ From PR form: a PO straight from the ticked requisition lines,
 * without RFQ and without Comparative Statement.
 * <p>
 * The user picks the supplier and the PO header values, checks / changes the rate and VAT of every line,
 * and presses OK. {@link RfqPoService#createPoFromPr} creates the Drafted PO and sends it to the approval matrix,
 * exactly like a PO that comes from a CS.
 */
public class CreatePOFromPRDialog {

	private static final CLogger log = CLogger.getCLogger(CreatePOFromPRDialog.class);

	private static class LineRow {
		PrLine line;
		Decimalbox rate;
		Listbox vat;
		Listcell amountCell, vatCell, totalCell;
		BigDecimal amount = BigDecimal.ZERO, vatAmt = BigDecimal.ZERO;
	}

	/**
	 * @param owner        the form (needed for the page)
	 * @param reqLineIds   ticked requisition line ids
	 * @param onDone       runs after the PO is created (the form reloads its list)
	 */
	public static void open(final Component owner, final List<Integer> reqLineIds, final Runnable onDone) {
		final RfqPoService service = new RfqPoService();
		try {
			// fresh data from the database: price, qty, status, and whether the line is still free
			final List<PrLine> lines = service.loadPrLines(reqLineIds);
			String problem = RfqPoService.checkPrLines(lines, reqLineIds.size());
			if (problem != null) {
				Messagebox.show(problem, "Create PO", Messagebox.OK, Messagebox.EXCLAMATION);
				return;
			}
			final PrDefaults d = service.loadPrDefaults(lines);
			final Map<Integer, Object[]> taxes = service.taxes();

			StringBuilder prNos = new StringBuilder();
			for (PrLine l : lines)
				if (prNos.indexOf(l.reqNo) < 0)
					prNos.append(prNos.length() > 0 ? ", " : "").append(l.reqNo);

			final Window win = new Window("Create PO from Requisition  -  " + prNos, "normal", true);
			win.setWidth("95%");
			win.setHeight("90%");
			win.setPosition("center");
			win.setContentStyle("overflow:auto;");
			Vlayout box = new Vlayout();
			box.setWidth("100%");
			box.setStyle("padding:6px;");
			win.appendChild(box);

			// ------------------------------------------------ header
			final Combobox supplier = new Combobox();
			supplier.setAutocomplete(true);
			supplier.setAutodrop(true);
			supplier.setWidth("95%");
			supplier.setPlaceholder("Type to search supplier");
			for (KeyNamePair p : service.vendors()) {
				Comboitem ci = new Comboitem(p.getName());
				ci.setValue(Integer.valueOf(p.getKey()));
				supplier.appendChild(ci);
				if (p.getKey() == d.bpId)
					supplier.setSelectedItem(ci);
			}

			final Listbox warehouse = selectOf(service.warehouses(), d.warehouseId, true);
			final Listbox docType = selectOf(service.docTypes(), 0, false);
			final Listbox priceList = selectOf(service.priceLists(), d.priceListId, true);
			final Listbox payTerm = selectOf(service.paymentTerms(), 0, true);
			final Datebox dateOrdered = new Datebox(new java.util.Date());
			dateOrdered.setFormat("dd/MM/yyyy");
			final Datebox datePromised = new Datebox(d.dateRequired == null ? null : new java.util.Date(d.dateRequired.getTime()));
			datePromised.setFormat("dd/MM/yyyy");
			final Textbox description = new Textbox("Created directly from Requisition " + prNos);
			description.setWidth("95%");
			final Decimalbox transport = moneyBox(BigDecimal.ZERO);
			final Decimalbox others = moneyBox(BigDecimal.ZERO);
			final Decimalbox grossDisc = moneyBox(BigDecimal.ZERO);
			final Checkbox grossPct = new Checkbox();
			Label orgLabel = new Label(d.orgName);
			orgLabel.setStyle("font-weight:600;");

			// a supplier brings its own payment term (and price list when the requisition has none)
			supplier.addEventListener(Events.ON_SELECT, e -> {
				Comboitem ci = supplier.getSelectedItem();
				int bpId = ci == null || ci.getValue() == null ? 0 : ((Integer) ci.getValue()).intValue();
				int[] vd = service.vendorDefaults(bpId);
				selectById(payTerm, vd[0]);
				if (idOf(priceList) <= 0)
					selectById(priceList, vd[1]);
			});
			if (d.bpId > 0) {
				int[] vd = service.vendorDefaults(d.bpId);
				selectById(payTerm, vd[0]);
				if (idOf(priceList) <= 0)
					selectById(priceList, vd[1]);
			}

			Grid head = new Grid();
			Columns hc = new Columns();
			for (int i = 0; i < 6; i++) {
				Column c = new Column();
				c.setWidth(i % 2 == 0 ? "130px" : null);
				hc.appendChild(c);
			}
			head.appendChild(hc);
			org.zkoss.zul.Rows hr = new org.zkoss.zul.Rows();
			head.appendChild(hr);
			hr.appendChild(rowOf(label("Organization"), orgLabel, label("Supplier*"), supplier, label("PO Type*"), docType));
			hr.appendChild(rowOf(label("Warehouse*"), warehouse, label("Date Ordered*"), dateOrdered, label("Date Promised*"), datePromised));
			hr.appendChild(rowOf(label("Price List*"), priceList, label("PO Payment Term*"), payTerm, new Label(""), new Label("")));
			box.appendChild(head);

			Grid costs = new Grid();
			Columns cc = new Columns();
			for (int i = 0; i < 10; i++) {
				Column c = new Column();
				c.setWidth(i == 0 ? "130px" : null);
				cc.appendChild(c);
			}
			costs.appendChild(cc);
			org.zkoss.zul.Rows cr = new org.zkoss.zul.Rows();
			costs.appendChild(cr);
			cr.appendChild(rowOf(label("Description"), description, greenLabel("Transport Cost"), transport,
					greenLabel("Others Cost"), others, greenLabel("Gross Discount %"), grossPct,
					greenLabel("Gross Discount"), grossDisc));
			box.appendChild(costs);

			// ------------------------------------------------ lines
			final Listbox grid = new Listbox();
			grid.setWidth("100%");
			grid.setHeight("280px");
			Listhead lh = new Listhead();
			String[] names = { "Requisition", "Line", "Product Code", "Product Name", "UOM", "Qty", "Rate", "Amount",
					"VAT Rate", "VAT Amt", "Total" };
			String[] widths = { "100px", "55px", "110px", "230px", "60px", "90px", "110px", "110px", "160px", "100px", "120px" };
			for (int i = 0; i < names.length; i++) {
				Listheader h = new Listheader(names[i]);
				h.setWidth(widths[i]);
				lh.appendChild(h);
			}
			grid.appendChild(lh);
			box.appendChild(grid);

			final Label totalLabel = new Label();
			totalLabel.setStyle("font-weight:700;font-size:14px;");
			box.appendChild(totalLabel);
			final Label payableLabel = new Label();
			payableLabel.setStyle("font-weight:700;font-size:14px;color:#166534;");
			box.appendChild(payableLabel);

			final List<LineRow> rows = new ArrayList<LineRow>();
			final Runnable recalc = () -> {
				BigDecimal sa = BigDecimal.ZERO, sv = BigDecimal.ZERO;
				for (LineRow r : rows) {
					BigDecimal rate = r.rate.getValue() == null ? BigDecimal.ZERO : r.rate.getValue();
					r.amount = r.line.qty.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
					Listitem ti = r.vat.getSelectedItem();
					Object[] t = ti == null || ti.getValue() == null ? null : taxes.get(ti.getValue());
					r.vatAmt = t == null ? BigDecimal.ZERO : RfqPoService.vat(r.amount, (BigDecimal) t[1]);
					r.amountCell.setLabel(MONEY.format(r.amount));
					r.vatCell.setLabel(MONEY.format(r.vatAmt));
					r.totalCell.setLabel(MONEY.format(r.amount.add(r.vatAmt)));
					sa = sa.add(r.amount);
					sv = sv.add(r.vatAmt);
				}
				totalLabel.setValue("Total:   Amount " + MONEY.format(sa) + "   +   VAT " + MONEY.format(sv)
						+ "   =   " + MONEY.format(sa.add(sv)));
				BigDecimal tc = transport.getValue() == null ? BigDecimal.ZERO : transport.getValue();
				BigDecimal oc = others.getValue() == null ? BigDecimal.ZERO : others.getValue();
				BigDecimal disc = RfqPoService.discountAmount(sa.add(sv), grossPct.isChecked(), grossDisc.getValue());
				payableLabel.setValue("Payable (sent for approval):   " + MONEY.format(sa.add(sv)) + "   -   Gross Discount "
						+ MONEY.format(disc) + "   +   Transport " + MONEY.format(tc) + "   +   Others " + MONEY.format(oc)
						+ "   =   " + MONEY.format(sa.add(sv).subtract(disc).add(tc).add(oc)));
			};

			for (PrLine l : lines) {
				final LineRow r = new LineRow();
				r.line = l;
				r.rate = new Decimalbox(l.rate);
				r.rate.setFormat("#,##0.00");
				r.rate.setConstraint("no negative");
				r.rate.setWidth("90%");
				r.rate.setStyle("text-align:right;background:#fffde7;");
				r.vat = new Listbox();
				r.vat.setMold("select");
				r.vat.setWidth("100%");
				addItem(r.vat, "(automatic)", null);
				r.vat.setSelectedIndex(0);
				for (Map.Entry<Integer, Object[]> te : taxes.entrySet())
					addItem(r.vat, te.getValue()[0] + " (" + ((BigDecimal) te.getValue()[1]).stripTrailingZeros().toPlainString() + "%)", te.getKey());

				Listitem li = new Listitem();
				li.appendChild(new Listcell(l.reqNo));
				li.appendChild(right(String.valueOf(l.lineNo)));
				li.appendChild(new Listcell(l.code));
				li.appendChild(new Listcell(l.name));
				li.appendChild(new Listcell(l.uom));
				li.appendChild(right(qtyText(l.qty)));
				li.appendChild(cell(r.rate));
				r.amountCell = right("");
				li.appendChild(r.amountCell);
				li.appendChild(cell(r.vat));
				r.vatCell = right("");
				li.appendChild(r.vatCell);
				r.totalCell = right("");
				li.appendChild(r.totalCell);
				grid.appendChild(li);
				rows.add(r);

				r.rate.addEventListener(Events.ON_CHANGE, e -> recalc.run());
				r.vat.addEventListener(Events.ON_SELECT, e -> recalc.run());
			}
			for (Decimalbox db : new Decimalbox[] { transport, others, grossDisc })
				db.addEventListener(Events.ON_CHANGE, e -> recalc.run());
			grossPct.addEventListener(Events.ON_CHECK, e -> recalc.run());
			recalc.run();

			// ------------------------------------------------ buttons
			Hlayout btns = new Hlayout();
			Button ok = new Button("OK");
			Button cancel = new Button("Cancel");
			btns.appendChild(ok);
			btns.appendChild(cancel);
			box.appendChild(btns);
			cancel.addEventListener(Events.ON_CLICK, e -> win.detach());

			ok.addEventListener(Events.ON_CLICK, e -> {
				try {
					PrRequest req = new PrRequest();
					Comboitem sup = supplier.getSelectedItem();
					req.bpId = sup == null || sup.getValue() == null ? 0 : ((Integer) sup.getValue()).intValue();
					req.orgId = d.orgId;
					req.warehouseId = idOf(warehouse);
					req.docTypeId = idOf(docType);
					req.priceListId = idOf(priceList);
					req.paymentTermId = idOf(payTerm);
					if (req.bpId <= 0) throw new Exception("Supplier is required. Pick one from the list.");
					if (req.warehouseId <= 0) throw new Exception("Warehouse is required.");
					if (req.docTypeId <= 0) throw new Exception("PO Type is required.");
					if (req.priceListId <= 0) throw new Exception("Price List is required.");
					if (req.paymentTermId <= 0) throw new Exception("PO Payment Term is required.");
					if (dateOrdered.getValue() == null) throw new Exception("Date Ordered is required.");
					if (datePromised.getValue() == null) throw new Exception("Date Promised is required.");
					req.dateOrdered = new Timestamp(dateOrdered.getValue().getTime());
					req.datePromised = new Timestamp(datePromised.getValue().getTime());
					req.transportCost = transport.getValue() == null ? BigDecimal.ZERO : transport.getValue();
					req.othersCost = others.getValue() == null ? BigDecimal.ZERO : others.getValue();
					req.grossDiscountPct = grossPct.isChecked();
					req.grossDiscount = grossDisc.getValue() == null ? BigDecimal.ZERO : grossDisc.getValue();
					req.description = description.getValue() == null ? "" : description.getValue();

					for (LineRow r : rows) {
						PrLineReq lr = new PrLineReq();
						lr.line = r.line;
						lr.rate = r.rate.getValue() == null ? BigDecimal.ZERO : r.rate.getValue();
						Listitem ti = r.vat.getSelectedItem();
						lr.taxId = ti == null || ti.getValue() == null ? 0 : ((Integer) ti.getValue()).intValue();
						req.lines.add(lr);
					}
					Result res = service.createPoFromPr(req);
					win.detach();
					final int newOrderId = res.orderId;
					final String newDocNo = res.docNo;
					Messagebox.show("PO " + res.docNo + " created.\n\n" + res.approvalMessage
							+ "\n\nDo you want to view the PDF? (OK = show PDF)", "Create PO",
							Messagebox.OK | Messagebox.CANCEL, Messagebox.QUESTION, new EventListener<Event>() {
								@Override
								public void onEvent(Event event) throws Exception {
									if (Messagebox.ON_OK.equals(event.getName()))
										CreatePODialog.showPdf(owner, newOrderId, newDocNo);
									if (onDone != null)
										onDone.run();
								}
							});
				} catch (Exception ex) {
					log.warning("createPoFromPr: " + ex.getMessage());
					Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "Create PO", Messagebox.OK,
							Messagebox.ERROR);
				}
			});

			win.setPage(owner.getPage());
			org.mycompany.requisition.ui.XxTheme.apply(win);
			win.doModal();
		} catch (Exception ex) {
			log.severe(ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "Create PO", Messagebox.OK, Messagebox.ERROR);
		}
	}

	/** Selects the list item whose value is this id (no change when id is 0 or not found). */
	private static void selectById(Listbox lb, int id) {
		if (id <= 0)
			return;
		for (Listitem li : lb.getItems())
			if (li.getValue() != null && ((Integer) li.getValue()).intValue() == id) {
				lb.setSelectedItem(li);
				return;
			}
	}
}
