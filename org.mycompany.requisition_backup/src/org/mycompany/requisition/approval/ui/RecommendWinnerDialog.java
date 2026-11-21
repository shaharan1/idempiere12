package org.mycompany.requisition.approval.ui;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.mycompany.requisition.rfqapproval.RfQApprovalService;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Comparison;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Item;
import org.mycompany.requisition.rfqapproval.ComparativeStatementReport;
import org.zkoss.util.media.AMedia;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Caption;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Groupbox;
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
 * "Recommend Winner" popup for a Comparative Statement (RFQ), opened from the
 * Purchase Order Approval form. The lowest price of each product is ticked
 * automatically; the approver can change it and save.
 */
public class RecommendWinnerDialog {

	private static final CLogger log = CLogger.getCLogger(RecommendWinnerDialog.class);
	private static final String GREEN = "background:#90EE90;font-weight:600;";
	private static final String EDIT_STYLE = "text-align:right;background:#fffde7;";
	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

	private static final String[] COLS = { "Product", "Supplier", "Manufacturer", "Brand", "Origin", "RFQ Qty",
			"Quoted Qty", "Quoted Price", "Negotiate Price", "VAT%", "Total Value", "Rank", "Winner", "Comment" };
	private static final String[] WIDTH = { "170px", "150px", "100px", "80px", "80px", "70px", "80px", "110px",
			"110px", "85px", "105px", "50px", "60px", "190px" };

	private static class QuoteRow {
		int lineId, respId, rank, lineQtyId;
		boolean saved;
		String product, supplier;
		BigDecimal qty = BigDecimal.ZERO;
		BigDecimal origPrice, origNeg;
		int origTaxId;
		BigDecimal vatAmt = BigDecimal.ZERO, lineTotal = BigDecimal.ZERO;
		Listitem item;
		Listcell totalCell, rankCell;
		Decimalbox price, neg;
		Listbox vat;
		Checkbox win;
		Textbox comment;

		/** Price that counts for ranking and totals: Negotiate Price when given, else Quoted Price. */
		BigDecimal unit() {
			BigDecimal n = nzv(neg.getValue());
			return n.signum() > 0 ? n : nzv(price.getValue());
		}

		int taxId() {
			Listitem sel = vat.getSelectedItem();
			return sel == null || sel.getValue() == null ? 0 : ((Integer) sel.getValue()).intValue();
		}

		boolean changed() {
			return nzv(price.getValue()).compareTo(origPrice) != 0 || nzv(neg.getValue()).compareTo(origNeg) != 0
					|| taxId() != origTaxId;
		}
	}

	/** C_RfQ_ID of the document number shown in the approval inbox, 0 when it is not an RFQ. */
	public static int findRfqId(String documentNo, int docTypeId) {
		if (documentNo == null)
			return 0;
		String expr = DB.getSQLValue(null, "SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE LOWER(table_name)='c_rfq' AND LOWER(column_name)='documentno'") > 0
						? "COALESCE(r.DocumentNo, r.Name)" : "r.Name";
		int id = DB.getSQLValue(null, "SELECT r.C_RfQ_ID FROM C_RfQ r JOIN M_Requisition rq "
				+ "ON rq.M_Requisition_ID=r.M_Requisition_ID WHERE " + expr + "=? AND rq.C_DocType_ID=? "
				+ "AND r.AD_Client_ID=? ORDER BY r.C_RfQ_ID DESC LIMIT 1",
				documentNo, docTypeId, Env.getAD_Client_ID(Env.getCtx()));
		return id > 0 ? id : 0;
	}

	/** True when at least one winner has been recommended for the RFQ. */
	public static boolean hasWinner(int rfqId) {
		return DB.getSQLValue(null,
				"SELECT COUNT(*) FROM C_RfQResponseLine sl JOIN C_RfQLine l ON l.C_RfQLine_ID=sl.C_RfQLine_ID "
						+ "WHERE l.C_RfQ_ID=? AND sl.IsSelectedWinner='Y' AND sl.IsActive='Y'", rfqId) > 0;
	}

	public static void open(final Component owner, final int rfqId, final String docNo) {
		try {
			final RfQApprovalService service = new RfQApprovalService();
			final Comparison c = service.load(rfqId);
			if (c.suppliers.isEmpty()) {
				Messagebox.show("This RFQ has no supplier response.", "Recommend Winner", Messagebox.OK,
						Messagebox.EXCLAMATION);
				return;
			}
			Map<Integer, Item> items = new LinkedHashMap<Integer, Item>();
			for (Item it : c.items)
				items.put(Integer.valueOf(it.rfqLineId), it);

			final Window win = new Window("RFQ Response; RFQ: " + docNo, "normal", true);
			win.setWidth("98%");
			win.setHeight("90%");
			win.setPosition("center");
			win.setContentStyle("overflow:auto;");
			Vlayout box = new Vlayout();
			box.setWidth("100%");

			Listbox grid = new Listbox();
			grid.setWidth("100%");
			grid.setHeight("300px");
			Listhead gh = new Listhead();
			for (int i = 0; i < COLS.length; i++) {
				Listheader lh = new Listheader(COLS[i]);
				lh.setWidth(WIDTH[i]);
				gh.appendChild(lh);
			}
			grid.appendChild(gh);

			final List<QuoteRow> rows = new ArrayList<QuoteRow>();
			// products whose winner the user decided (saved earlier or ticked by hand): never auto-moved
			final Set<Integer> locked = new HashSet<Integer>();

			// VAT list: C_Tax_ID -> rate, and the entries shown in each row's drop-down
			final Map<Integer, BigDecimal> taxRates = new LinkedHashMap<Integer, BigDecimal>();
			final Map<Integer, String> taxNames = new HashMap<Integer, String>();
			try (PreparedStatement tp = DB.prepareStatement(
					"SELECT C_Tax_ID, Name, Rate FROM C_Tax WHERE IsActive='Y' AND AD_Client_ID IN (0,?) ORDER BY Name",
					null)) {
				tp.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
				try (ResultSet tr = tp.executeQuery()) {
					while (tr.next()) {
						Integer id = Integer.valueOf(tr.getInt(1));
						taxRates.put(id, nzv(tr.getBigDecimal(3)));
						taxNames.put(id, tr.getString(2));
					}
				}
			}

			String sql = "SELECT sl.C_RfQLine_ID, sl.C_RfQResponse_ID, bp.Name, q.XX_Manufacturer, q.XX_Brand, "
					+ "q.XX_Origin, q.XX_QuotationQty, q.Price, q.XX_NegotiatePrice, t.Rate, q.XX_LineTotal, "
					+ "sl.IsSelectedWinner, sl.XX_WinnerComment, q.C_RfQResponseLineQty_ID, q.XX_Tax_ID "
					+ "FROM C_RfQResponseLine sl "
					+ "JOIN C_RfQLine l ON l.C_RfQLine_ID=sl.C_RfQLine_ID "
					+ "JOIN C_RfQResponse rs ON rs.C_RfQResponse_ID=sl.C_RfQResponse_ID "
					+ "JOIN C_BPartner bp ON bp.C_BPartner_ID=rs.C_BPartner_ID "
					+ "JOIN C_RfQResponseLineQty q ON q.C_RfQResponseLine_ID=sl.C_RfQResponseLine_ID "
					+ "LEFT JOIN C_Tax t ON t.C_Tax_ID=q.XX_Tax_ID "
					+ "WHERE l.C_RfQ_ID=? AND sl.IsActive='Y' AND q.IsActive='Y' AND COALESCE(q.Price,0)>0 "
					+ "ORDER BY l.Line, l.C_RfQLine_ID, q.Price, bp.Name";
			try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
				ps.setInt(1, rfqId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						final QuoteRow qr = new QuoteRow();
						qr.lineId = rs.getInt(1);
						qr.respId = rs.getInt(2);
						qr.supplier = rs.getString(3);
						qr.lineQtyId = rs.getInt(14);
						qr.saved = "Y".equals(rs.getString(12));
						if (qr.saved)
							locked.add(Integer.valueOf(qr.lineId));
						Item it = items.get(Integer.valueOf(qr.lineId));
						qr.product = it == null ? "" : it.name;
						BigDecimal quotQty = rs.getBigDecimal(7);
						qr.qty = quotQty != null && quotQty.signum() > 0 ? quotQty : (it == null ? BigDecimal.ZERO : it.qty);

						int taxId = rs.getInt(15);
						BigDecimal rate = rs.getBigDecimal(10);
						if (taxId > 0 && !taxRates.containsKey(Integer.valueOf(taxId))) { // inactive / other client
							taxRates.put(Integer.valueOf(taxId), nzv(rate));
							taxNames.put(Integer.valueOf(taxId), "");
						}

						// ---- editable fields: Quoted Price, Negotiate Price, VAT%
						qr.price = moneyBox(rs.getBigDecimal(8));
						qr.neg = moneyBox(rs.getBigDecimal(9));
						qr.vat = new Listbox();
						qr.vat.setMold("select");
						qr.vat.setWidth("100%");
						qr.vat.appendChild(new Listitem("", null));
						for (Map.Entry<Integer, BigDecimal> te : taxRates.entrySet()) {
							Listitem ti = new Listitem(te.getValue().stripTrailingZeros().toPlainString(), te.getKey());
							ti.setTooltiptext(taxNames.get(te.getKey()));
							qr.vat.appendChild(ti);
							if (te.getKey().intValue() == taxId)
								qr.vat.setSelectedItem(ti);
						}
						if (qr.vat.getSelectedItem() == null)
							qr.vat.setSelectedIndex(0);
						qr.origPrice = nzv(qr.price.getValue());
						qr.origNeg = nzv(qr.neg.getValue());
						qr.origTaxId = taxId;

						Listitem li = new Listitem();
						qr.item = li;
						li.appendChild(new Listcell(qr.product));
						li.appendChild(new Listcell(rs.getString(3)));
						li.appendChild(new Listcell(nz(rs.getString(4))));
						li.appendChild(new Listcell(nz(rs.getString(5))));
						li.appendChild(new Listcell(nz(rs.getString(6))));
						li.appendChild(new Listcell(it == null ? "" : it.qty.stripTrailingZeros().toPlainString()));
						li.appendChild(new Listcell(num(rs.getBigDecimal(7))));
						li.appendChild(cellOf(qr.price));
						li.appendChild(cellOf(qr.neg));
						li.appendChild(cellOf(qr.vat));

						BigDecimal stored = rs.getBigDecimal(11);
						compute(qr, taxRates);
						if (stored != null)
							qr.lineTotal = stored; // show what is saved until the user edits something
						qr.totalCell = new Listcell(num(qr.lineTotal));
						li.appendChild(qr.totalCell);
						qr.rankCell = new Listcell();
						li.appendChild(qr.rankCell);

						qr.win = new Checkbox();
						qr.win.setChecked(qr.saved);
						li.appendChild(cellOf(qr.win));

						qr.comment = new Textbox(nz(rs.getString(13)));
						qr.comment.setWidth("95%");
						li.appendChild(cellOf(qr.comment));

						// one winner per product; a hand-made choice is never moved automatically
						qr.win.addEventListener(Events.ON_CHECK, e -> {
							locked.add(Integer.valueOf(qr.lineId));
							if (qr.win.isChecked())
								for (QuoteRow o : rows)
									if (o != qr && o.lineId == qr.lineId)
										o.win.setChecked(false);
						});

						// editing a price / VAT: new total, new ranks, rank-1 row moves its colour
						EventListener<Event> onEdit = e -> {
							compute(qr, taxRates);
							qr.totalCell.setLabel(num(qr.lineTotal));
							rerank(rows, qr.lineId);
							autoTick(rows, locked, qr.lineId);
						};
						qr.price.addEventListener(Events.ON_CHANGE, onEdit);
						qr.neg.addEventListener(Events.ON_CHANGE, onEdit);
						qr.vat.addEventListener(Events.ON_SELECT, onEdit);

						rows.add(qr);
						grid.appendChild(li);
					}
				}
			}

			// initial rank (same rule as the service) and colour; untouched products get rank 1 ticked
			Set<Integer> lineIds = new HashSet<Integer>();
			for (QuoteRow q : rows)
				lineIds.add(Integer.valueOf(q.lineId));
			for (Integer lid : lineIds) {
				rerank(rows, lid.intValue());
				autoTick(rows, locked, lid.intValue());
			}
			box.appendChild(grid);

			// History records: every save of this RFQ, all users, newest first
			final Listbox historyBox = new Listbox();
			box.appendChild(buildHistory(historyBox));
			fillHistory(historyBox, rfqId);
			final Runnable refreshHistory = () -> fillHistory(historyBox, rfqId);

			box.appendChild(new Label("Overall remarks"));
			final Textbox remarks = new Textbox();
			remarks.setMultiline(true);
			remarks.setRows(2);
			remarks.setWidth("100%");
			box.appendChild(remarks);

			Hlayout btns = new Hlayout();
			Button save = new Button("Save Winners");
			save.addEventListener(Events.ON_CLICK, e -> {
				if (saveWinners(service, rfqId, rows, remarks.getValue())) {
					refreshHistory.run();
					Messagebox.show("Winners saved. You can now approve.", "Recommend Winner", Messagebox.OK,
							Messagebox.INFORMATION);
				}
			});
			btns.appendChild(save);

			// CS PDF / Print: saves the ticked winners first, so the printout matches the screen
			Button print = new Button("CS PDF / Print");
			print.addEventListener(Events.ON_CLICK, e -> {
				if (saveWinners(service, rfqId, rows, remarks.getValue())) {
					refreshHistory.run();
					showPdf(owner, service, rfqId, docNo);
				}
			});
			btns.appendChild(print);
			Button close = new Button("Close");
			close.addEventListener(Events.ON_CLICK, e -> win.detach());
			btns.appendChild(close);
			box.appendChild(btns);

			win.appendChild(box);
			win.setPage(owner.getPage());
			org.mycompany.requisition.ui.XxTheme.apply(win);
			win.doModal();
		} catch (Exception ex) {
			log.severe(ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "Recommend Winner",
					Messagebox.OK, Messagebox.ERROR);
		}
	}

	/** Saves the ticked winners. False (after showing the reason) when it cannot be saved. */
	private static boolean saveWinners(RfQApprovalService service, int rfqId, List<QuoteRow> rows,
			String remarks) {
		Map<Integer, Integer> winners = new LinkedHashMap<Integer, Integer>();
		Map<Integer, String> justs = new LinkedHashMap<Integer, String>();
		for (QuoteRow q : rows) {
			if (!q.win.isChecked())
				continue;
			winners.put(Integer.valueOf(q.lineId), Integer.valueOf(q.respId));
			if (q.rank > 1)
				justs.put(Integer.valueOf(q.lineId), q.comment.getValue());
		}
		if (winners.isEmpty()) {
			Messagebox.show("Tick a winner for at least one product.", "Recommend Winner", Messagebox.OK,
					Messagebox.EXCLAMATION);
			return false;
		}
		try {
			// edited prices / VAT must be in the database before the service ranks the quotes
			saveEdits(rows);
			service.recommendLines(rfqId, winners, justs, remarks);
			logHistory(rfqId, rows);
			return true;
		} catch (Exception ex) {
			log.severe(ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "Recommend Winner",
					Messagebox.OK, Messagebox.ERROR);
			return false;
		}
	}

	// ------------------------------------------------------------------ editing helpers

	/** Total = Qty x unit price + VAT. Unit price is Negotiate Price when given, else Quoted Price. */
	private static void compute(QuoteRow q, Map<Integer, BigDecimal> taxRates) {
		BigDecimal base = q.qty.multiply(q.unit());
		BigDecimal rate = taxRates.get(Integer.valueOf(q.taxId()));
		q.vatAmt = base.multiply(rate == null ? BigDecimal.ZERO : rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
		q.lineTotal = base.add(q.vatAmt).setScale(2, RoundingMode.HALF_UP);
	}

	/** Ranks one product's quotes by unit price, lowest first; equal prices share a rank. */
	private static void rerank(List<QuoteRow> rows, int lineId) {
		List<QuoteRow> mine = new ArrayList<QuoteRow>();
		for (QuoteRow q : rows)
			if (q.lineId == lineId)
				mine.add(q);
		Collections.sort(mine, (a, b) -> a.unit().compareTo(b.unit()));
		int r = 0, pos = 0;
		BigDecimal prev = null;
		for (QuoteRow q : mine) {
			if (q.unit().signum() <= 0) {
				q.rank = 0;
			} else {
				pos++;
				if (prev == null || q.unit().compareTo(prev) != 0) {
					r = pos;
					prev = q.unit();
				}
				q.rank = r;
			}
			q.rankCell.setLabel(q.rank == 0 ? "" : String.valueOf(q.rank));
			paint(q);
		}
	}

	/** Rank 1 row gets its own colour. Set on every cell, because the theme colours cells, not rows. */
	private static void paint(QuoteRow q) {
		String style = q.rank == 1 ? GREEN : null;
		q.item.setStyle(style);
		for (Component c : q.item.getChildren())
			if (c instanceof Listcell)
				((Listcell) c).setStyle(style);
	}

	/** Product without a decided winner: the tick follows rank 1. */
	private static void autoTick(List<QuoteRow> rows, Set<Integer> locked, int lineId) {
		if (locked.contains(Integer.valueOf(lineId)))
			return;
		boolean done = false;
		for (QuoteRow q : rows) {
			if (q.lineId != lineId)
				continue;
			boolean tick = !done && q.rank == 1;
			if (tick)
				done = true;
			q.win.setChecked(tick);
		}
	}

	/** Writes changed Quoted Price / Negotiate Price / VAT of the edited rows in one transaction. */
	private static void saveEdits(List<QuoteRow> rows) throws Exception {
		List<QuoteRow> dirty = new ArrayList<QuoteRow>();
		for (QuoteRow q : rows) {
			if (nzv(q.price.getValue()).signum() < 0 || nzv(q.neg.getValue()).signum() < 0)
				throw new Exception("Prices must not be negative (" + q.product + " / " + q.supplier + ").");
			if (q.unit().signum() <= 0)
				throw new Exception("Enter a Quoted or Negotiate Price for " + q.product + " / " + q.supplier + ".");
			if (q.changed())
				dirty.add(q);
		}
		if (dirty.isEmpty())
			return;
		String trxName = Trx.createTrxName("RfQEdit");
		Trx trx = Trx.get(trxName, true);
		try {
			MTable tbl = MTable.get(Env.getCtx(), "C_RfQResponseLineQty");
			for (QuoteRow q : dirty) {
				PO po = tbl.getPO(q.lineQtyId, trxName);
				po.set_ValueOfColumn("Price", nzv(q.price.getValue()));
				po.set_ValueOfColumn("XX_NegotiatePrice", nzv(q.neg.getValue()));
				po.set_ValueOfColumn("XX_Tax_ID", q.taxId() > 0 ? Integer.valueOf(q.taxId()) : null);
				po.set_ValueOfColumn("XX_VATAmt", q.vatAmt);
				po.set_ValueOfColumn("XX_LineTotal", q.lineTotal);
				po.saveEx();
			}
			trx.commit();
		} catch (Exception ex) {
			trx.rollback();
			throw ex;
		} finally {
			trx.close();
		}
		for (QuoteRow q : dirty) { // saved: these are the new reference values
			q.origPrice = nzv(q.price.getValue());
			q.origNeg = nzv(q.neg.getValue());
			q.origTaxId = q.taxId();
		}
	}

	private static Decimalbox moneyBox(BigDecimal v) {
		Decimalbox d = new Decimalbox(v);
		d.setFormat("#,##0.00");
		d.setConstraint("no negative");
		d.setWidth("90%");
		d.setStyle(EDIT_STYLE); // light yellow = editable
		return d;
	}

	private static Listcell cellOf(Component c) {
		Listcell lc = new Listcell();
		lc.appendChild(c);
		return lc;
	}

	private static BigDecimal nzv(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	// ------------------------------------------------------------------ history records

	private static Groupbox buildHistory(Listbox lb) {
		Groupbox gb = new Groupbox();
		gb.setStyle("margin-top:8px;");
		gb.appendChild(new Caption("History records"));
		lb.setWidth("100%");
		lb.setHeight("120px");
		String[] names = { "Create Date and Time", "User", "Winners", "Comment" };
		String[] widths = { "170px", "150px", null, "260px" };
		Listhead head = new Listhead();
		for (int i = 0; i < names.length; i++) {
			Listheader h = new Listheader(names[i]);
			if (widths[i] != null)
				h.setWidth(widths[i]);
			else
				h.setHflex("1");
			head.appendChild(h);
		}
		lb.appendChild(head);
		gb.appendChild(lb);
		return gb;
	}

	/**
	 * History of the RFQ, newest first, ONE line per save. Reads the existing log
	 * (XX_RfQ_WinnerLog, written one row per quote at every save) and folds the rows of a
	 * save (same time stamp, same user) into a single line: who, when, the winner of each
	 * product and the comment. Nothing new is written for this.
	 */
	private static void fillHistory(Listbox lb, int rfqId) {
		while (lb.getItemCount() > 0)
			lb.removeItemAt(0);
		SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy hh:mm:ss a", Locale.ENGLISH);
		try (PreparedStatement ps = DB.prepareStatement("SELECT h.Created, h.CreatedBy, u.Name, h.ProductName, "
				+ "h.BPartnerName, h.QuotedPrice, h.NegotiatePrice, h.IsWinner, h.Comment "
				+ "FROM XX_RfQ_WinnerLog h LEFT JOIN AD_User u ON u.AD_User_ID=h.CreatedBy "
				+ "WHERE h.C_RfQ_ID=? ORDER BY h.Created DESC, h.XX_RfQ_WinnerLog_ID", null)) {
			ps.setInt(1, rfqId);
			try (ResultSet rs = ps.executeQuery()) {
				java.sql.Timestamp stamp = null;
				int user = -1;
				String userName = "";
				List<String> winners = new ArrayList<String>();
				List<String> comments = new ArrayList<String>();
				boolean open = false;
				while (rs.next()) {
					java.sql.Timestamp t = rs.getTimestamp(1);
					int u = rs.getInt(2);
					boolean sameSave = open && t != null && t.equals(stamp) && u == user;
					if (!sameSave) {
						if (open)
							addHistoryLine(lb, fmt, stamp, userName, winners, comments);
						stamp = t;
						user = u;
						userName = nz(rs.getString(3));
						winners = new ArrayList<String>();
						comments = new ArrayList<String>();
						open = true;
					}
					if ("Y".equals(rs.getString(8))) {
						BigDecimal neg = rs.getBigDecimal(7);
						BigDecimal price = neg != null && neg.signum() > 0 ? neg : rs.getBigDecimal(6);
						winners.add(nz(rs.getString(4)) + ": " + nz(rs.getString(5))
								+ (price == null ? "" : " @ " + num(price)));
					}
					String cm = nz(rs.getString(9)).trim();
					if (cm.length() > 0 && !comments.contains(cm))
						comments.add(cm);
				}
				if (open)
					addHistoryLine(lb, fmt, stamp, userName, winners, comments);
			}
		} catch (Exception ex) { // table XX_RfQ_WinnerLog missing: run db/07_recommend_winner.sql
			log.warning("history: " + ex.getMessage());
		}
	}

	private static void addHistoryLine(Listbox lb, SimpleDateFormat fmt, java.sql.Timestamp stamp, String user,
			List<String> winners, List<String> comments) {
		String w = winners.isEmpty() ? "No winner ticked" : String.join("; ", winners);
		String c = String.join(" | ", comments);
		Listitem li = new Listitem();
		li.appendChild(new Listcell(stamp == null ? "" : fmt.format(stamp)));
		li.appendChild(new Listcell(user));
		Listcell wc = new Listcell(w);
		wc.setTooltiptext(w);
		li.appendChild(wc);
		Listcell cc = new Listcell(c);
		cc.setTooltiptext(c);
		li.appendChild(cc);
		lb.appendChild(li);
	}

	/** One history record per row for this save, all with the same time stamp (one transaction). */
	private static void logHistory(int rfqId, List<QuoteRow> rows) {
		int userId = Env.getAD_User_ID(Env.getCtx());
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		String trxName = Trx.createTrxName("RfQLog");
		Trx trx = Trx.get(trxName, true);
		try {
			for (QuoteRow q : rows) {
				String cm = q.comment.getValue();
				if (cm != null && cm.length() > 255)
					cm = cm.substring(0, 255);
				DB.executeUpdateEx("INSERT INTO XX_RfQ_WinnerLog (AD_Client_ID, C_RfQ_ID, C_RfQLine_ID, "
						+ "C_RfQResponse_ID, CreatedBy, BPartnerName, ProductName, QuotedQty, QuotedPrice, "
						+ "NegotiatePrice, Rank, IsWinner, Comment) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
						new Object[] { Integer.valueOf(clientId), Integer.valueOf(rfqId), Integer.valueOf(q.lineId),
								Integer.valueOf(q.respId), Integer.valueOf(userId), q.supplier, q.product, q.qty,
								nzv(q.price.getValue()), nzv(q.neg.getValue()), Integer.valueOf(q.rank),
								q.win.isChecked() ? "Y" : "N", cm },
						trxName);
			}
			trx.commit();
		} catch (Exception ex) { // the recommendation itself is already saved; only the log failed
			trx.rollback();
			log.warning("history log: " + ex.getMessage());
		} finally {
			trx.close();
		}
	}

	private static Listcell right(String text) {
		Listcell c = new Listcell(text);
		c.setStyle("text-align:right;");
		return c;
	}

	/** Comparative Statement PDF, built from the data as it is saved now. Print from the viewer toolbar. */
	private static void showPdf(Component owner, RfQApprovalService service, int rfqId, String docNo) {
		try {
			Comparison c = service.load(rfqId);
			byte[] pdf = new ComparativeStatementReport().toPdf(c);
			AMedia media = new AMedia("CS_" + docNo + ".pdf", "pdf", "application/pdf", pdf);
			final Window w = new Window("Comparative Statement - " + docNo, "normal", true);
			w.setWidth("95%");
			w.setHeight("92%");
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
			log.severe(ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "Comparative Statement",
					Messagebox.OK, Messagebox.ERROR);
		}
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static String num(BigDecimal v) {
		return v == null ? "" : MONEY.format(v);
	}
}