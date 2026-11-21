package org.mycompany.requisition.rfqwinner;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.zkoss.zk.ui.Page;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Button;
import org.zkoss.zul.Center;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Column;
import org.zkoss.zul.Columns;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.Caption;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.Row;
import org.zkoss.zul.Rows;
import org.zkoss.zul.South;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Window;

/**
 * "RFQ Response; RFQ: &lt;no&gt;" window of the Document Approval Form.
 * <p>
 * One row per product and supplier. The approver edits <b>Negotiate Price</b>,
 * <b>Winner</b> and <b>Comment</b> (when the approval matrix allows it); the
 * rank and the total follow the price as it is typed, and the winning rows are
 * painted green. The History records panel underneath lists every earlier save.
 */
public class RecommendWinnerDialog extends Window {

	private static final long serialVersionUID = 1L;

	private static final String HEAD = "background:#e8edf7;font-weight:bold;";
	private static final String WIN_ROW = "background:#9fe3b9;";
	private static final String CELL_NUM = "text-align:right;";
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
	private static final DecimalFormat QTY = new DecimalFormat("#,##0.##");

	private final RecommendWinnerService service = new RecommendWinnerService();
	private final int rfqId;
	private final boolean editable;
	private final Runnable afterSave;

	private final List<RecommendWinnerService.Line> lines = new ArrayList<RecommendWinnerService.Line>();
	private final List<RowRef> refs = new ArrayList<RowRef>();

	private Listbox supplierFilter;
	private Rows historyRows;

	/** The widgets of one grid row, kept so they can be refreshed in place. */
	private static final class RowRef {
		RecommendWinnerService.Line line;
		Row row;
		Label rank, total;
		Checkbox winner;
	}

	public RecommendWinnerDialog(int rfqId, boolean editable, Runnable afterSave) {
		super("RFQ Response; RFQ: " + RecommendWinnerService.rfqNo(rfqId), "normal", true);
		this.rfqId = rfqId;
		this.editable = editable;
		this.afterSave = afterSave;
	}

	/** Builds the window on the given page and shows it modally. */
	public void open(Page page) {
		setWidth("98%");
		setHeight("94%");
		setPosition("center");
		setSizable(true);
		setStyle("background:#8db8e0;");

		lines.addAll(service.loadLines(rfqId));

		Borderlayout bl = new Borderlayout();
		bl.setStyle("background:#8db8e0;");
		appendChild(bl);

		North north = new North();
		north.setStyle("background:transparent;border:none;");
		north.setSplittable(false);
		bl.appendChild(north);
		north.appendChild(buildSupplierBar());
		north.setHeight("46px");

		Center center = new Center();
		center.setStyle("background:transparent;border:none;");
		center.setAutoscroll(true);
		bl.appendChild(center);
		Div body = new Div();
		body.setStyle("padding:4px 6px;");
		center.appendChild(body);
		body.appendChild(buildResponseGrid());
		body.appendChild(buildHistory());

		South south = new South();
		south.setStyle("background:transparent;border:none;");
		south.setHeight("46px");
		bl.appendChild(south);
		south.appendChild(buildButtons());

		setPage(page);
		org.mycompany.requisition.ui.XxTheme.apply(this);
		doModal();
	}

	// ------------------------------------------------------------ supplier filter

	private Div buildSupplierBar() {
		Div bar = new Div();
		bar.setStyle("padding:6px;");
		Hlayout h = new Hlayout();
		h.setStyle("background:#e8edf7;padding:4px 8px;border:1px solid #c5cfe0;");
		h.setHflex("1");
		Label l = new Label("Supplier");
		l.setStyle("font-weight:bold;width:140px;display:inline-block;");
		h.appendChild(l);
		supplierFilter = new Listbox();
		supplierFilter.setMold("select");
		supplierFilter.setHflex("1");
		supplierFilter.setStyle("background:#ffff9e;");
		supplierFilter.appendItem("", "");
		List<String> seen = new ArrayList<String>();
		for (RecommendWinnerService.Line r : lines)
			if (!seen.contains(r.supplier)) {
				seen.add(r.supplier);
				supplierFilter.appendItem(r.supplier, r.supplier);
			}
		supplierFilter.setSelectedIndex(0);
		supplierFilter.addEventListener(Events.ON_SELECT, e -> applyFilter());
		h.appendChild(supplierFilter);
		bar.appendChild(h);
		return bar;
	}

	private void applyFilter() {
		Listitem sel = supplierFilter.getSelectedItem();
		String s = sel == null || sel.getValue() == null ? "" : sel.getValue().toString();
		for (RowRef r : refs)
			r.row.setVisible(s.length() == 0 || s.equals(r.line.supplier));
	}

	// ------------------------------------------------------------ RFQ Response grid

	private Groupbox buildResponseGrid() {
		Groupbox gb = new Groupbox();
		gb.appendChild(new Caption("RFQ Response"));

		Grid grid = new Grid();
		grid.setHeight("330px");
		Columns cols = new Columns();
		grid.appendChild(cols);
		col(cols, "Product", "190px");
		col(cols, "Supplier", "150px");
		col(cols, "Manufacturer", "100px");
		col(cols, "Brand", "80px");
		col(cols, "Origin", "70px");
		col(cols, "RFQ Qty", "70px");
		col(cols, "Available Qty", "90px");
		col(cols, "Quoted Qty", "80px");
		col(cols, "Quoted Price", "100px");
		col(cols, "Negotiate Price", "110px");
		col(cols, "VAT%", "60px");
		col(cols, "Total Value", "100px");
		col(cols, "Rank", "50px");
		col(cols, "Winner", "60px");
		col(cols, "Comment", "150px");
		col(cols, "Attachment", "80px");

		Rows rows = new Rows();
		grid.appendChild(rows);
		for (RecommendWinnerService.Line line : lines) {
			final RowRef ref = new RowRef();
			ref.line = line;
			Row row = new Row();
			ref.row = row;

			row.appendChild(new Label(line.product));
			row.appendChild(new Label(line.supplier));
			row.appendChild(new Label(line.manufacturer));
			row.appendChild(new Label(line.brand));
			row.appendChild(new Label(line.origin));
			row.appendChild(num(QTY.format(line.rfqQty)));
			row.appendChild(num(QTY.format(line.availableQty)));
			row.appendChild(num(QTY.format(line.quotedQty)));
			row.appendChild(num(MONEY.format(line.quotedPrice)));

			final Decimalbox neg = new Decimalbox(line.negotiatePrice.signum() > 0 ? line.negotiatePrice : line.quotedPrice);
			neg.setFormat("#,##0.00");
			neg.setWidth("90px");
			neg.setReadonly(!editable);
			neg.addEventListener(Events.ON_CHANGE, e -> {
				BigDecimal v = neg.getValue();
				ref.line.negotiatePrice = v == null ? BigDecimal.ZERO : v;
				refresh();
			});
			row.appendChild(neg);

			row.appendChild(num(MONEY.format(line.vatPct)));
			ref.total = num(MONEY.format(line.total()));
			ref.total.setStyle(CELL_NUM + "background:#eee;display:block;padding:2px;");
			row.appendChild(ref.total);
			ref.rank = num(String.valueOf(line.rank));
			row.appendChild(ref.rank);

			ref.winner = new Checkbox();
			ref.winner.setChecked(line.winner);
			ref.winner.setDisabled(!editable);
			ref.winner.addEventListener(Events.ON_CHECK, e -> onWinner(ref));
			row.appendChild(ref.winner);

			final Textbox comment = new Textbox(line.comment);
			comment.setWidth("140px");
			comment.setMaxlength(255);
			comment.setReadonly(!editable);
			comment.addEventListener(Events.ON_CHANGE, e -> ref.line.comment = comment.getValue());
			row.appendChild(comment);

			Button view = new Button("View");
			view.addEventListener(Events.ON_CLICK, e -> showAttachments(ref.line));
			row.appendChild(view);

			paint(ref);
			rows.appendChild(row);
			refs.add(ref);
		}
		gb.appendChild(grid);
		return gb;
	}

	private void col(Columns cols, String title, String width) {
		Column c = new Column(title);
		c.setWidth(width);
		c.setStyle(HEAD);
		cols.appendChild(c);
	}

	private Label num(String text) {
		Label l = new Label(text);
		l.setStyle(CELL_NUM + "display:block;");
		return l;
	}

	/** One winner per product: ticking a row clears the others of the same product. */
	private void onWinner(RowRef ticked) {
		ticked.line.winner = ticked.winner.isChecked();
		if (ticked.line.winner)
			for (RowRef r : refs)
				if (r != ticked && r.line.rfqLineId == ticked.line.rfqLineId) {
					r.line.winner = false;
					r.winner.setChecked(false);
				}
		refresh();
	}

	/** Re-ranks after a price change and redraws rank, total and row colour. */
	private void refresh() {
		RecommendWinnerService.rank(lines);
		for (RowRef r : refs) {
			r.rank.setValue(String.valueOf(r.line.rank));
			r.total.setValue(MONEY.format(r.line.total()));
			paint(r);
		}
	}

	private void paint(RowRef r) {
		r.row.setStyle(r.line.winner ? WIN_ROW : "");
	}

	private void showAttachments(RecommendWinnerService.Line line) {
		List<String> names = service.attachmentNames(line.responseId);
		StringBuilder sb = new StringBuilder();
		for (String n : names)
			sb.append(n).append('\n');
		if (sb.length() == 0)
			sb.append("No attachment for this supplier response.");
		Messagebox.show(sb.toString(), "Attachment", Messagebox.OK, Messagebox.INFORMATION);
	}

	// ------------------------------------------------------------ history

	private Groupbox buildHistory() {
		Groupbox gb = new Groupbox();
		gb.setStyle("margin-top:8px;");
		gb.appendChild(new Caption("History records"));
		Grid grid = new Grid();
		grid.setHeight("150px");
		Columns cols = new Columns();
		grid.appendChild(cols);
		col(cols, "Create Date and Time", "170px");
		col(cols, "User", "150px");
		col(cols, "Supplier", "160px");
		col(cols, "Product", "190px");
		col(cols, "Quoted Qty", "90px");
		col(cols, "Quoted Price", "100px");
		col(cols, "Negotiate Price", "110px");
		col(cols, "Rank", "50px");
		col(cols, "Winner", "60px");
		col(cols, "Comment", "200px");
		historyRows = new Rows();
		grid.appendChild(historyRows);
		fillHistory();
		gb.appendChild(grid);
		return gb;
	}

	private void fillHistory() {
		while (historyRows.getFirstChild() != null)
			historyRows.removeChild(historyRows.getFirstChild());
		SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy HH:mm:ss a");
		String lastStamp = null;
		String lastUser = null;
		for (RecommendWinnerService.History h : service.history(rfqId)) {
			String stamp = h.created == null ? "" : fmt.format(h.created);
			boolean same = stamp.equals(lastStamp) && h.user.equals(lastUser);
			Row r = new Row();
			r.appendChild(new Label(same ? "" : stamp));
			r.appendChild(new Label(same ? "" : h.user));
			r.appendChild(new Label(h.supplier));
			r.appendChild(new Label(h.product));
			r.appendChild(num(h.quotedQty == null ? "" : QTY.format(h.quotedQty)));
			r.appendChild(num(h.quotedPrice == null ? "" : MONEY.format(h.quotedPrice)));
			r.appendChild(num(h.negotiatePrice == null ? "" : MONEY.format(h.negotiatePrice)));
			r.appendChild(num(String.valueOf(h.rank)));
			Checkbox w = new Checkbox();
			w.setChecked(h.winner);
			w.setDisabled(true);
			r.appendChild(w);
			r.appendChild(new Label(h.comment));
			historyRows.appendChild(r);
			lastStamp = stamp;
			lastUser = h.user;
		}
	}

	// ------------------------------------------------------------ buttons

	private Div buildButtons() {
		Div bar = new Div();
		bar.setStyle("text-align:right;padding:6px 14px;");
		if (editable) {
			Button ok = new Button("\u2714");
			ok.setStyle("color:#0a8a2a;font-weight:bold;width:60px;");
			ok.addEventListener(Events.ON_CLICK, e -> save());
			bar.appendChild(ok);
		}
		Button cancel = new Button("\u2716");
		cancel.setStyle("color:#c00;font-weight:bold;width:60px;margin-left:6px;");
		cancel.addEventListener(Events.ON_CLICK, e -> detach());
		bar.appendChild(cancel);
		return bar;
	}

	private void save() {
		try {
			service.save(rfqId, lines);
			Messagebox.show("Recommendation saved.", "Recommend Winner", Messagebox.OK, Messagebox.INFORMATION);
			fillHistory();
			if (afterSave != null)
				afterSave.run();
			detach();
		} catch (Exception ex) {
			Messagebox.show(ex.getMessage(), "Recommend Winner", Messagebox.OK, Messagebox.EXCLAMATION);
		}
	}
}
