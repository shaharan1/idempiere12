package org.mycompany.requisition.approval.ui;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.mycompany.requisition.rfqapproval.RfQApprovalService;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Comparison;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Item;
import org.mycompany.requisition.rfqapproval.ComparativeStatementReport;
import org.zkoss.util.media.AMedia;
import org.zkoss.zk.ui.Component;
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
	private static final String GREEN = "background:#90EE90;";
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

	private static final String[] COLS = { "Product", "Supplier", "Manufacturer", "Brand", "Origin", "RFQ Qty",
			"Quoted Qty", "Quoted Price", "Negotiate Price", "VAT%", "Total Value", "Rank", "Winner", "Comment" };
	private static final String[] WIDTH = { "170px", "150px", "100px", "80px", "80px", "70px", "80px", "95px",
			"100px", "60px", "105px", "50px", "60px", "190px" };

	private static class QuoteRow {
		int lineId, respId, rank;
		boolean saved;
		Checkbox win;
		Textbox comment;
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

			Map<String, Integer> rankOf = new HashMap<String, Integer>();
			for (RfQApprovalService.RankedQuote q : RfQApprovalService.rankQuotes(rfqId))
				rankOf.put(q.rfqLineId + ":" + q.responseId, Integer.valueOf(q.rank));

			final Window win = new Window("RFQ Response; RFQ: " + docNo, "normal", true);
			win.setWidth("98%");
			win.setHeight("90%");
			win.setPosition("center");
			Vlayout box = new Vlayout();
			box.setWidth("100%");

			Listbox grid = new Listbox();
			grid.setWidth("100%");
			grid.setHeight("430px");
			Listhead gh = new Listhead();
			for (int i = 0; i < COLS.length; i++) {
				Listheader lh = new Listheader(COLS[i]);
				lh.setWidth(WIDTH[i]);
				gh.appendChild(lh);
			}
			grid.appendChild(gh);

			final List<QuoteRow> rows = new ArrayList<QuoteRow>();
			String sql = "SELECT sl.C_RfQLine_ID, sl.C_RfQResponse_ID, bp.Name, q.XX_Manufacturer, q.XX_Brand, "
					+ "q.XX_Origin, q.XX_QuotationQty, q.Price, q.XX_NegotiatePrice, t.Rate, q.XX_LineTotal, "
					+ "sl.IsSelectedWinner, sl.XX_WinnerComment "
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
						Integer rk = rankOf.get(qr.lineId + ":" + qr.respId);
						qr.rank = rk == null ? 0 : rk.intValue();
						qr.saved = "Y".equals(rs.getString(12));
						Item it = items.get(Integer.valueOf(qr.lineId));

						Listitem li = new Listitem();
						if (qr.rank == 1)
							li.setStyle(GREEN);
						li.appendChild(new Listcell(it == null ? "" : it.name));
						li.appendChild(new Listcell(rs.getString(3)));
						li.appendChild(new Listcell(nz(rs.getString(4))));
						li.appendChild(new Listcell(nz(rs.getString(5))));
						li.appendChild(new Listcell(nz(rs.getString(6))));
						li.appendChild(new Listcell(it == null ? "" : it.qty.stripTrailingZeros().toPlainString()));
						li.appendChild(new Listcell(num(rs.getBigDecimal(7))));
						li.appendChild(new Listcell(num(rs.getBigDecimal(8))));
						li.appendChild(new Listcell(num(rs.getBigDecimal(9))));
						BigDecimal rate = rs.getBigDecimal(10);
						li.appendChild(new Listcell(rate == null ? "" : rate.stripTrailingZeros().toPlainString()));
						li.appendChild(new Listcell(num(rs.getBigDecimal(11))));
						li.appendChild(new Listcell(qr.rank == 0 ? "" : String.valueOf(qr.rank)));

						qr.win = new Checkbox();
						qr.win.setChecked(qr.saved);
						Listcell wc = new Listcell();
						wc.appendChild(qr.win);
						li.appendChild(wc);

						qr.comment = new Textbox(nz(rs.getString(13)));
						qr.comment.setWidth("95%");
						Listcell cc = new Listcell();
						cc.appendChild(qr.comment);
						li.appendChild(cc);

						// one winner per product
						qr.win.addEventListener(Events.ON_CHECK, e -> {
							if (qr.win.isChecked())
								for (QuoteRow o : rows)
									if (o != qr && o.lineId == qr.lineId)
										o.win.setChecked(false);
						});
						rows.add(qr);
						grid.appendChild(li);
					}
				}
			}

			// Products without a saved winner: tick the lowest price (rank 1) automatically.
			Set<Integer> hasSaved = new HashSet<Integer>();
			for (QuoteRow q : rows)
				if (q.saved)
					hasSaved.add(Integer.valueOf(q.lineId));
			Set<Integer> autoDone = new HashSet<Integer>();
			for (QuoteRow q : rows) {
				Integer k = Integer.valueOf(q.lineId);
				if (!hasSaved.contains(k) && q.rank == 1 && autoDone.add(k))
					q.win.setChecked(true);
			}
			box.appendChild(grid);

			box.appendChild(new Label("Overall remarks"));
			final Textbox remarks = new Textbox();
			remarks.setMultiline(true);
			remarks.setRows(2);
			remarks.setWidth("100%");
			box.appendChild(remarks);

			Hlayout btns = new Hlayout();
			Button save = new Button("Save Winners");
			save.addEventListener(Events.ON_CLICK, e -> {
				if (saveWinners(service, rfqId, rows, remarks.getValue()))
					Messagebox.show("Winners saved. You can now approve.", "Recommend Winner", Messagebox.OK,
							Messagebox.INFORMATION);
			});
			btns.appendChild(save);

			// CS PDF / Print: saves the ticked winners first, so the printout matches the screen
			Button print = new Button("CS PDF / Print");
			print.addEventListener(Events.ON_CLICK, e -> {
				if (saveWinners(service, rfqId, rows, remarks.getValue()))
					showPdf(owner, service, rfqId, docNo);
			});
			btns.appendChild(print);
			Button close = new Button("Close");
			close.addEventListener(Events.ON_CLICK, e -> win.detach());
			btns.appendChild(close);
			box.appendChild(btns);

			win.appendChild(box);
			win.setPage(owner.getPage());
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
			service.recommendLines(rfqId, winners, justs, remarks);
			return true;
		} catch (Exception ex) {
			log.severe(ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(), "Recommend Winner",
					Messagebox.OK, Messagebox.ERROR);
			return false;
		}
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