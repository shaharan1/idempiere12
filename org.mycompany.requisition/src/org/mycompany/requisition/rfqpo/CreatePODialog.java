package org.mycompany.requisition.rfqpo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.compiere.util.CLogger;
import org.compiere.util.KeyNamePair;
import org.mycompany.requisition.rfqpo.RfqPoService.Defaults;
import org.mycompany.requisition.rfqpo.RfqPoService.Line;
import org.mycompany.requisition.rfqpo.RfqPoService.LineReq;
import org.mycompany.requisition.rfqpo.RfqPoService.Request;
import org.mycompany.requisition.rfqpo.RfqPoService.Result;
import org.mycompany.requisition.rfqpo.RfqPoService.Row;
import org.zkoss.zk.ui.Component;
import org.zkoss.util.media.AMedia;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.UploadEvent;
import org.zkoss.util.media.Media;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Column;
import org.zkoss.zul.Columns;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Iframe;
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
 * "Create PO" popup of one winner supplier of one approved CS: PO header, the lines to order
 * (PO Qty and VAT can be changed), and OK. OK creates the Drafted PO and sends it to the approval
 * matrix.
 */
public class CreatePODialog {

	private static final CLogger log = CLogger.getCLogger(CreatePODialog.class);
	static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

	private static class LineRow {
		Line line;
		Checkbox sel;
		Decimalbox qty;
		Listbox vat;
		Checkbox rebate, tariff;
		Listcell amountCell, vatCell, totalCell;
		BigDecimal amount = BigDecimal.ZERO, vatAmt = BigDecimal.ZERO;
	}

	public static void open(final Component owner, final Row row, final Runnable onDone) {
		final RfqPoService service = new RfqPoService();
		try {
			final Defaults d = service.loadDefaults(row.rfqId, row.bpId);
			final Map<Integer, Object[]> taxes = service.taxes();
			service.loadResponseCosts(d, row.respId);
			final List<Line> lines = service.loadLines(row.rfqId, row.respId);
			if (lines.isEmpty()) {
				Messagebox.show("No winner line found for this supplier.", "Create PO", Messagebox.OK, Messagebox.EXCLAMATION);
				return;
			}

			final Window win = new Window("Create PO  -  " + row.docNo + "  /  " + row.bpName, "normal", true);
			win.setWidth("97%");
			win.setHeight("92%");
			win.setPosition("center");
			win.setContentStyle("overflow:hidden;padding:0;");
			// top = header + costs, middle = the lines grid (vflex: takes ALL the remaining height), bottom = Total + buttons
			Vlayout root = new Vlayout();
			root.setVflex("1");
			root.setHflex("1");
			root.setSpacing("0");
			win.appendChild(root);
			Vlayout box = new Vlayout();
			box.setWidth("100%");
			box.setStyle("padding:6px;");
			root.appendChild(box);
			Vlayout foot = new Vlayout();
			foot.setWidth("100%");
			foot.setStyle("padding:6px;");

			// ------------------------------------------------ header
			final Listbox warehouse = selectOf(service.warehouses(), d.warehouseId, true);
			final Listbox docType = selectOf(service.docTypes(), 0, false);
			final Listbox priceList = selectOf(service.priceLists(), d.priceListId, true);
			final Listbox payTerm = selectOf(service.paymentTerms(), d.paymentTermId, true);
			final Datebox dateOrdered = new Datebox(new java.util.Date());
			dateOrdered.setFormat("dd/MM/yyyy");
			final Datebox datePromised = new Datebox(d.dateRequired == null ? null : new java.util.Date(d.dateRequired.getTime()));
			datePromised.setFormat("dd/MM/yyyy");
			final Textbox description = new Textbox();
			description.setWidth("95%");
			final Listbox termType = selectOf(service.termTypes(), 0, true);
			final Textbox termsText = new Textbox();
			termsText.setMultiline(true);
			termsText.setRows(4);
			termsText.setWidth("98%");
			termType.addEventListener(Events.ON_SELECT, e -> termsText.setValue(service.termText(idOf(termType))));
			final List<Media> attachments = new ArrayList<Media>();
			final Button attach = new Button("Attachment");
			final Label attachLabel = new Label("No file attached");
			attachLabel.setStyle("color:#6c757d;font-size:12px;margin-left:8px;");
			attach.addEventListener(Events.ON_CLICK, e -> openAttachments(owner, attachments, attachLabel));
			final Textbox payNote = new Textbox();
			payNote.setMultiline(true);
			payNote.setRows(2);
			payNote.setWidth("95%");
			final Decimalbox transport = moneyBox(d.transportCost);
			final Decimalbox others = moneyBox(d.othersCost);
			final Decimalbox grossDisc = moneyBox(d.grossDiscount);
			final Checkbox grossPct = new Checkbox();
			grossPct.setChecked(d.grossDiscountPct);
			Label orgLabel = new Label(d.orgName);
			orgLabel.setStyle("font-weight:600;");

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
			hr.appendChild(rowOf(label("Organization"), orgLabel, label("Warehouse*"), warehouse, label("PO Type*"), docType));
			hr.appendChild(rowOf(label("Date Ordered*"), dateOrdered, label("Date Promised*"), datePromised, label("Price List*"), priceList));
			hr.appendChild(rowOf(label("PO Payment Term*"), payTerm, label("Payment Term"), payNote, label("Supplier"), new Label(row.bpName)));
			box.appendChild(head);

			// Description + Transport Cost / Others Cost / Gross Discount % / Gross Discount (from the CS response, editable)
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

			// Terms and Conditions Type -> Terms and Conditions (text of the type, editable) + Attachment
			Grid terms = new Grid();
			Columns termCols = new Columns();
			for (int i = 0; i < 4; i++) {
				Column c = new Column();
				c.setWidth(i == 0 ? "130px" : i == 1 ? "260px" : i == 2 ? "130px" : null);
				termCols.appendChild(c);
			}
			terms.appendChild(termCols);
			org.zkoss.zul.Rows tr = new org.zkoss.zul.Rows();
			terms.appendChild(tr);
			Hlayout attBox = new Hlayout();
			attBox.appendChild(attach);
			attBox.appendChild(attachLabel);
			tr.appendChild(rowOf(label("Terms and Conditions Type"), termType, label("Terms and Conditions"), termsText));
			tr.appendChild(rowOf(label("Attachment"), attBox, new Label(""), new Label("")));
			box.appendChild(terms);

			// ------------------------------------------------ lines
			final Listbox grid = new Listbox();
			grid.setHflex("1");
			grid.setVflex("1");
			final Checkbox all = new Checkbox("ALL");
			Listhead lh = new Listhead();
			Listheader h0 = new Listheader();
			h0.setWidth("60px");
			h0.appendChild(all);
			lh.appendChild(h0);
			String[] names = { "Line No", "Product Code", "Product Name", "UOM", "RFQ/Response Qty", "Already PO Qty",
					"PO Qty", "Rate", "Amount", "VAT Rate", "Rebate", "TariffRate", "VAT Amt", "Total" };
			String[] widths = { "60px", "110px", "220px", "60px", "110px", "100px", "110px", "100px", "110px", "150px", "60px", "75px", "100px", "120px" };
			for (int i = 0; i < names.length; i++) {
				Listheader h = new Listheader(names[i]);
				h.setWidth(widths[i]);
				lh.appendChild(h);
			}
			grid.appendChild(lh);
			root.appendChild(grid);
			root.appendChild(foot);

			final Label totalLabel = new Label();
			totalLabel.setStyle("font-weight:700;font-size:14px;color:#166534;");
			foot.appendChild(totalLabel);

			final List<LineRow> rows = new ArrayList<LineRow>();
			final Runnable recalc = () -> {
				BigDecimal sa = BigDecimal.ZERO, sv = BigDecimal.ZERO;
				for (LineRow r : rows) {
					BigDecimal q = r.qty.getValue() == null ? BigDecimal.ZERO : r.qty.getValue();
					r.amount = q.multiply(r.line.rate).setScale(2, java.math.RoundingMode.HALF_UP);
					Listitem ti = r.vat.getSelectedItem();
					Object[] t = ti == null || ti.getValue() == null ? null : taxes.get(ti.getValue());
					r.vatAmt = t == null ? BigDecimal.ZERO : RfqPoService.vat(r.amount, (BigDecimal) t[1]);
					r.amountCell.setLabel(MONEY.format(r.amount));
					r.vatCell.setLabel(MONEY.format(r.vatAmt));
					r.totalCell.setLabel(MONEY.format(r.amount.add(r.vatAmt)));
					if (r.sel.isChecked()) {
						sa = sa.add(r.amount);
						sv = sv.add(r.vatAmt);
					}
				}
				BigDecimal tc = transport.getValue() == null ? BigDecimal.ZERO : transport.getValue();
				BigDecimal oc = others.getValue() == null ? BigDecimal.ZERO : others.getValue();
				BigDecimal disc = RfqPoService.discountAmount(sa.add(sv), grossPct.isChecked(), grossDisc.getValue());
				totalLabel.setValue("Total:   Amount " + MONEY.format(sa) + "   -   Gross Discount " + MONEY.format(disc)
						+ "   +   Transport " + MONEY.format(tc) + "   +   Others " + MONEY.format(oc)
						+ "   +   VAT " + MONEY.format(sv)
						+ "   =   " + MONEY.format(sa.subtract(disc).add(tc).add(oc).add(sv)));
			};

			for (Line l : lines) {
				final LineRow r = new LineRow();
				r.line = l;
				r.sel = new Checkbox();
				r.sel.setChecked(l.remaining().signum() > 0);
				r.sel.setDisabled(l.remaining().signum() <= 0);
				r.qty = new Decimalbox(l.remaining());
				r.qty.setFormat("#,##0.##");
				r.qty.setConstraint("no negative");
				r.qty.setWidth("90%");
				r.qty.setStyle("text-align:right;background:#fffde7;");
				r.vat = new Listbox();
				r.vat.setMold("select");
				r.vat.setWidth("100%");
				addItem(r.vat, "(automatic)", null);
				for (Map.Entry<Integer, Object[]> te : taxes.entrySet()) {
					Listitem ti = addItem(r.vat, te.getValue()[0] + " (" + ((BigDecimal) te.getValue()[1]).stripTrailingZeros().toPlainString() + "%)", te.getKey());
					if (te.getKey().intValue() == l.taxId)
						r.vat.setSelectedItem(ti);
				}
				if (r.vat.getSelectedItem() == null)
					r.vat.setSelectedIndex(0);
				r.rebate = new Checkbox();
				r.rebate.setChecked(l.rebate);
				r.tariff = new Checkbox();
				r.tariff.setChecked(l.tariff);

				Listitem li = new Listitem();
				li.appendChild(cell(r.sel));
				li.appendChild(right(String.valueOf(l.lineNo)));
				li.appendChild(new Listcell(l.code));
				li.appendChild(new Listcell(l.name));
				li.appendChild(new Listcell(l.uom));
				li.appendChild(right(qtyText(l.rfqQty)));
				li.appendChild(right(qtyText(l.already)));
				li.appendChild(cell(r.qty));
				li.appendChild(right(MONEY.format(l.rate)));
				r.amountCell = right("");
				li.appendChild(r.amountCell);
				li.appendChild(cell(r.vat));
				li.appendChild(cell(r.rebate));
				li.appendChild(cell(r.tariff));
				r.vatCell = right("");
				li.appendChild(r.vatCell);
				r.totalCell = right("");
				li.appendChild(r.totalCell);
				grid.appendChild(li);
				rows.add(r);

				r.qty.addEventListener(Events.ON_CHANGE, e -> recalc.run());
				r.vat.addEventListener(Events.ON_SELECT, e -> recalc.run());
				r.sel.addEventListener(Events.ON_CHECK, e -> recalc.run());
			}
			for (Decimalbox db : new Decimalbox[] { transport, others, grossDisc })
				db.addEventListener(Events.ON_CHANGE, e -> recalc.run());
			grossPct.addEventListener(Events.ON_CHECK, e -> recalc.run());
			all.setChecked(true);
			all.addEventListener(Events.ON_CHECK, e -> {
				for (LineRow r : rows)
					if (!r.sel.isDisabled())
						r.sel.setChecked(all.isChecked());
				recalc.run();
			});
			recalc.run();

			// ------------------------------------------------ buttons
			Hlayout btns = new Hlayout();
			Button ok = new Button("OK");
			Button cancel = new Button("Cancel");
			btns.appendChild(ok);
			btns.appendChild(cancel);
			foot.appendChild(btns);
			cancel.addEventListener(Events.ON_CLICK, e -> win.detach());

			ok.addEventListener(Events.ON_CLICK, e -> {
				try {
					Request req = new Request();
					req.rfqId = row.rfqId;
					req.respId = row.respId;
					req.bpId = row.bpId;
					req.rfqDocNo = row.docNo;
					req.orgId = d.orgId;
					req.warehouseId = idOf(warehouse);
					req.docTypeId = idOf(docType);
					req.priceListId = idOf(priceList);
					req.paymentTermId = idOf(payTerm);
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
					req.termId = idOf(termType);
					req.termsText = termsText.getValue();
					req.attachments.addAll(attachments);
					String note = payNote.getValue() == null ? "" : payNote.getValue().trim();
					String desc = description.getValue() == null ? "" : description.getValue().trim();
					req.description = desc + (note.length() > 0 ? (desc.length() > 0 ? "\n" : "") + "Payment Term: " + note : "");

					for (LineRow r : rows) {
						if (!r.sel.isChecked())
							continue;
						LineReq lr = new LineReq();
						lr.line = r.line;
						lr.qty = r.qty.getValue() == null ? BigDecimal.ZERO : r.qty.getValue();
						Listitem ti = r.vat.getSelectedItem();
						lr.taxId = ti == null || ti.getValue() == null ? 0 : ((Integer) ti.getValue()).intValue();
						lr.rebate = r.rebate.isChecked();
						lr.tariff = r.tariff.isChecked();
						req.lines.add(lr);
					}
					Result res = service.createPo(req);
					win.detach();
					final int newOrderId = res.orderId;
					final String newDocNo = res.docNo;
					Messagebox.show("PO " + res.docNo + " created.\n\n" + res.approvalMessage
							+ "\n\nDo you want to view the PDF? (OK = show PDF)", "Create PO",
							Messagebox.OK | Messagebox.CANCEL, Messagebox.QUESTION, new EventListener<Event>() {
								@Override
								public void onEvent(Event event) throws Exception {
									if (Messagebox.ON_OK.equals(event.getName()))
										showPdf(owner, newOrderId, newDocNo);
									if (onDone != null)
										onDone.run();
								}
							});
				} catch (Exception ex) {
					log.warning("createPo: " + ex.getMessage());
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

	/** Attachment pop-up (same as the Purchase Requisition): add / remove files before the PO is created. */
	static void openAttachments(Component owner, final List<Media> files, final Label summary) {
		final Window w = new Window("Attachment", "normal", true);
		w.setWidth("560px");
		w.setPosition("center");
		Vlayout body = new Vlayout();
		body.setStyle("padding:10px;");
		final Listbox lb = new Listbox();
		lb.setWidth("100%");
		lb.setHeight("240px");
		Listhead lh = new Listhead();
		String[] hn = { "#", "File name", "Size", "" };
		String[] hw = { "40px", null, "90px", "60px" };
		for (int i = 0; i < hn.length; i++) {
			Listheader h = new Listheader(hn[i]);
			if (hw[i] != null)
				h.setWidth(hw[i]);
			lh.appendChild(h);
		}
		lb.appendChild(lh);
		final Label count = new Label();
		final Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> {
			while (lb.getItemCount() > 0)
				lb.removeItemAt(0);
			int n = 0;
			for (final Media m : new ArrayList<Media>(files)) {
				n++;
				Listitem li = new Listitem();
				li.appendChild(new Listcell(String.valueOf(n)));
				li.appendChild(new Listcell(m.getName()));
				long len;
				try {
					len = m.isBinary() ? m.getByteData().length : m.getStringData().length();
				} catch (Exception ex) {
					len = 0;
				}
				li.appendChild(right(len < 1024 ? len + " B" : len < 1048576 ? (len + 512) / 1024 + " KB"
						: String.format("%.1f MB", len / 1048576.0)));
				Listcell rm = new Listcell();
				Button x = new Button("\u2716");
				x.addEventListener(Events.ON_CLICK, ev -> {
					files.remove(m);
					refresh[0].run();
				});
				rm.appendChild(x);
				li.appendChild(rm);
				lb.appendChild(li);
			}
			String t = files.isEmpty() ? "No file attached" : files.size() + " file(s) attached";
			count.setValue(t);
			summary.setValue(t);
		};
		Button add = new Button("+  Add File");
		add.setUpload("true,multiple=true");
		add.addEventListener(Events.ON_UPLOAD, ev -> {
			if (ev instanceof UploadEvent) {
				Media[] ms = ((UploadEvent) ev).getMedias();
				if (ms != null)
					for (Media m : ms)
						if (m != null)
							files.add(m);
				refresh[0].run();
			}
		});
		Button done = new Button("OK");
		done.addEventListener(Events.ON_CLICK, ev -> w.detach());
		Hlayout bar = new Hlayout();
		bar.appendChild(add);
		bar.appendChild(count);
		body.appendChild(bar);
		body.appendChild(lb);
		body.appendChild(done);
		w.appendChild(body);
		refresh[0].run();
		w.setPage(owner.getPage());
		org.mycompany.requisition.ui.XxTheme.apply(w);
		w.doModal();
	}

	/** PO PDF inside the app (browser viewer gives print / download). */
	static void showPdf(Component owner, int orderId, String docNo) {
		try {
			byte[] pdf = RfqPoService.orderPdf(orderId);
			AMedia media = new AMedia("PO_" + docNo + ".pdf", "pdf", "application/pdf", pdf);
			final Window w = new Window("Purchase Order - " + docNo, "normal", true);
			w.setWidth("90%");
			w.setHeight("90%");
			w.setPosition("center");
			Iframe frame = new Iframe();
			frame.setContent(media);
			frame.setWidth("100%");
			frame.setHeight("calc(100% - 40px)");
			w.appendChild(frame);
			Button close = new Button("Close");
			close.addEventListener(Events.ON_CLICK, e -> w.detach());
			w.appendChild(close);
			w.setPage(owner.getPage());
			org.mycompany.requisition.ui.XxTheme.apply(w);
			w.doModal();
		} catch (Exception ex) {
			log.warning("showPdf: " + ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "Purchase Order",
					Messagebox.OK, Messagebox.ERROR);
		}
	}

	// ------------------------------------------------------------ small UI helpers

	static Listbox selectOf(List<KeyNamePair> items, int selectedId, boolean blankFirst) {
		Listbox lb = new Listbox();
		lb.setMold("select");
		lb.setWidth("95%");
		if (blankFirst)
			addItem(lb, "", null);
		Listitem sel = null;
		for (KeyNamePair p : items) {
			Listitem li = addItem(lb, p.getName(), Integer.valueOf(p.getKey()));
			if (p.getKey() == selectedId)
				sel = li;
		}
		if (sel != null)
			lb.setSelectedItem(sel);
		else if (lb.getItemCount() > 0)
			lb.setSelectedIndex(0);
		return lb;
	}

	/** Listbox.appendItem only takes a String value; the ids here are Integers. */
	static Listitem addItem(Listbox lb, String label, Object value) {
		Listitem li = new Listitem(label, value);
		lb.appendChild(li);
		return li;
	}

	static int idOf(Listbox lb) {
		Listitem li = lb.getSelectedItem();
		return li == null || li.getValue() == null ? 0 : ((Integer) li.getValue()).intValue();
	}

	static Label label(String text) {
		Label l = new Label(text);
		l.setStyle("font-weight:600;");
		return l;
	}

	static Label greenLabel(String text) {
		Label l = new Label(text);
		l.setStyle("font-weight:600;color:#15803d;");
		return l;
	}

	static Decimalbox moneyBox(BigDecimal v) {
		Decimalbox b = new Decimalbox(v == null ? BigDecimal.ZERO : v);
		b.setFormat("#,##0.00");
		b.setConstraint("no negative");
		b.setWidth("95%");
		b.setStyle("text-align:right;");
		return b;
	}

	static org.zkoss.zul.Row rowOf(Component... cs) {
		org.zkoss.zul.Row r = new org.zkoss.zul.Row();
		for (Component c : cs)
			r.appendChild(c);
		return r;
	}

	static Listcell cell(Component c) {
		Listcell lc = new Listcell();
		lc.appendChild(c);
		return lc;
	}

	static Listcell right(String text) {
		Listcell c = new Listcell(text);
		c.setStyle("text-align:right;");
		return c;
	}

	static String qtyText(BigDecimal v) {
		return v.stripTrailingZeros().toPlainString();
	}
}