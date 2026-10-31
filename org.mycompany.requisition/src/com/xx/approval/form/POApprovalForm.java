package com.xx.approval.form;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.window.Dialog;
import org.compiere.model.MOrder;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.adempiere.webui.apps.AEnv;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Datebox;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.Hbox;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.North;
import org.zkoss.zul.South;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Vbox;

/** Document Approval Form (Purchase Orders). AD_Form.Classname = com.xx.approval.form.POApprovalForm */
public class POApprovalForm extends ADForm implements EventListener<Event> {

	private static final long serialVersionUID = 1L;

	private final Label totalLabel = new Label("0");
	/**
	 * The three dropdowns are iDempiere listboxes rather than raw ZK ones.
	 * <p>
	 * A raw {@code org.zkoss.zul.Listbox} offers only
	 * {@code appendItem(String, String)} and carries no value, so an integer id
	 * cannot be attached and {@code appendItem("x", 0)} does not compile.
	 * {@code org.adempiere.webui.component.Listbox} adds
	 * {@code appendItem(String, Object)} and extends the ZK class, so everything
	 * else, including {@code getSelectedItem()}, reads exactly as before.
	 */
	private final org.adempiere.webui.component.Listbox orgBox = new org.adempiere.webui.component.Listbox();
	private final org.adempiere.webui.component.Listbox docTypeBox = new org.adempiere.webui.component.Listbox();

	/**
	 * Reads the queue from XX_Test instead of from C_Order.
	 * <p>
	 * XX_Test rows belong to the supervisor mapping, so they are listed for the
	 * user named in Supervisor_ID. They are not purchase orders: the number in
	 * the id column is an XX_Test_ID, and it sits in the same numeric range as
	 * the order ids. Approving one would therefore complete a different order, so
	 * when this is true the action buttons refuse the row and say why. Set it
	 * back to false to work on real purchase orders again.
	 */
	private static final boolean USE_XX_TEST = true;
	private final Datebox fromBox = new Datebox();
	private final Datebox toBox = new Datebox();
	private final Textbox bpBox = new Textbox();
	private final Textbox docNoBox = new Textbox();
	private final Decimalbox amountBox = new Decimalbox();
	private final Button reloadBtn = new Button("Reload");

	private final Listbox grid = new Listbox();
	private final List<POApprovalService.Row> rows = new ArrayList<>();

	private final Label selDocNo = new Label("-");
	private final Label selInfo = new Label("-");
	private final Button detailBtn = new Button("Detail");
	private final Button printBtn = new Button("Print");
	private final Button attachBtn = new Button("Attachment");
	private final Button historyBtn = new Button("Approval History");
	private final Listbox actionBox = new Listbox();
	private final Textbox messageBox = new Textbox();
	private final org.adempiere.webui.component.Listbox forwardBox = new org.adempiere.webui.component.Listbox();
	private final Button submitBtn = new Button("Submit");

	@Override
	protected void initForm() {
		Borderlayout layout = new Borderlayout();
		layout.setStyle("width:100%;height:100%;position:relative;");
		appendChild(layout);

		North north = new North();
		north.setStyle("border:none;");
		layout.appendChild(north);
		north.appendChild(buildSearch());

		Center center = new Center();
		layout.appendChild(center);
		center.appendChild(buildGrid());

		South south = new South();
		south.setHeight("190px");
		layout.appendChild(south);
		south.appendChild(buildApproval());

		loadFilters();
		reload();
	}

	private Groupbox buildSearch() {
		Groupbox gb = new Groupbox();
		gb.appendChild(new org.zkoss.zul.Caption("Search Option"));
		Grid g = new Grid();
		org.zkoss.zul.Rows r = new org.zkoss.zul.Rows();
		g.appendChild(r);
		org.zkoss.zul.Row r1 = new org.zkoss.zul.Row();
		r1.appendChild(new Label("Total Document"));
		totalLabel.setStyle("color:blue;font-weight:bold;");
		r1.appendChild(totalLabel);
		r1.appendChild(new Label("Organization"));
		orgBox.setMold("select");
		r1.appendChild(orgBox);
		r1.appendChild(new Label("Date From"));
		r1.appendChild(fromBox);
		r1.appendChild(new Label("Date To"));
		r1.appendChild(toBox);
		r1.appendChild(new Label("Document Type"));
		docTypeBox.setMold("select");
		r1.appendChild(docTypeBox);
		r.appendChild(r1);
		org.zkoss.zul.Row r2 = new org.zkoss.zul.Row();
		r2.appendChild(new Label("BP"));
		r2.appendChild(bpBox);
		r2.appendChild(new Label("Document No"));
		r2.appendChild(docNoBox);
		r2.appendChild(new Label("Amount (min)"));
		r2.appendChild(amountBox);
		r2.appendChild(new Label(""));
		reloadBtn.addEventListener(Events.ON_CLICK, this);
		r2.appendChild(reloadBtn);
		r.appendChild(r2);
		gb.appendChild(g);

		// The date boxes are deliberately left empty. This is a queue of work that
		// is waiting, not a report of what happened this month: an order entered
		// last month and still unapproved is exactly what the approver must see, and
		// prefilling the current month hid it. Narrowing is still possible, it is
		// just not the default.
		return gb;
	}

	private Groupbox buildGrid() {
		Groupbox gb = new Groupbox();
		gb.setVflex("1");
		gb.appendChild(new org.zkoss.zul.Caption("Documents waiting for approval"));
		grid.setVflex("1");
		grid.setCheckmark(false);
		Listhead h = new Listhead();
		for (String s : new String[] { "Serial No", "Organization", "Receive Date", "Document Type",
				"Document No", "Business Partner", "Amount", "Prepared By" }) {
			h.appendChild(new Listheader(s));
		}
		grid.appendChild(h);
		grid.addEventListener(Events.ON_SELECT, this);
		gb.appendChild(grid);
		return gb;
	}

	private Groupbox buildApproval() {
		Groupbox gb = new Groupbox();
		gb.appendChild(new org.zkoss.zul.Caption("Approval Document"));
		Vbox v = new Vbox();
		Hbox h1 = new Hbox();
		h1.appendChild(new Label("Document No:"));
		h1.appendChild(selDocNo);
		h1.appendChild(new Label("Document Info:"));
		h1.appendChild(selInfo);
		detailBtn.addEventListener(Events.ON_CLICK, this);
		printBtn.addEventListener(Events.ON_CLICK, this);
		attachBtn.addEventListener(Events.ON_CLICK, this);
		historyBtn.addEventListener(Events.ON_CLICK, this);
		h1.appendChild(detailBtn);
		h1.appendChild(printBtn);
		h1.appendChild(attachBtn);
		h1.appendChild(historyBtn);
		v.appendChild(h1);

		Hbox h2 = new Hbox();
		h2.appendChild(new Label("Approve"));
		actionBox.setMold("select");
		actionBox.appendItem("", "");
		actionBox.appendItem("Approve", "APPROVE");
		actionBox.appendItem("Reject", "REJECT");
		actionBox.appendItem("Forward", "FORWARD");
		h2.appendChild(actionBox);
		h2.appendChild(new Label("Message"));
		messageBox.setRows(2);
		messageBox.setWidth("400px");
		h2.appendChild(messageBox);
		h2.appendChild(new Label("Forward To"));
		forwardBox.setMold("select");
		h2.appendChild(forwardBox);
		submitBtn.addEventListener(Events.ON_CLICK, this);
		h2.appendChild(submitBtn);
		v.appendChild(h2);
		gb.appendChild(v);
		return gb;
	}

	private void loadFilters() {
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		orgBox.appendItem("", 0);
		for (KeyNamePair k : DB.getKeyNamePairs(
				"SELECT AD_Org_ID, Name FROM AD_Org WHERE AD_Client_ID=" + clientId
				+ " AND IsActive='Y' AND IsSummary='N' ORDER BY Name", false)) {
			orgBox.appendItem(k.getName(), k.getKey());
		}
		docTypeBox.appendItem("", 0);
		String docTypeSql = USE_XX_TEST
				? "SELECT DISTINCT d.C_DocType_ID, d.Name FROM C_DocType d "
						+ "JOIN XX_Test t ON t.C_DocType_ID = d.C_DocType_ID "
						+ "WHERE t.AD_Client_ID=" + clientId + " AND t.IsActive='Y' ORDER BY d.Name"
				: "SELECT DISTINCT d.C_DocType_ID, d.Name FROM C_DocType d "
						+ "JOIN XX_ReqDocType_Supervisor m ON m.C_DocType_ID=d.C_DocType_ID "
						+ "WHERE m.AD_Client_ID=" + clientId + " AND m.IsActive='Y' ORDER BY d.Name";
		for (KeyNamePair k : DB.getKeyNamePairs(docTypeSql, false)) {
			docTypeBox.appendItem(k.getName(), k.getKey());
		}
		forwardBox.appendItem("", 0);
		for (KeyNamePair k : DB.getKeyNamePairs(
				"SELECT AD_User_ID, Name FROM AD_User WHERE AD_Client_ID=" + clientId
				+ " AND IsActive='Y' ORDER BY Name", false)) {
			forwardBox.appendItem(k.getName(), k.getKey());
		}
		orgBox.setSelectedIndex(0);
		docTypeBox.setSelectedIndex(0);
		forwardBox.setSelectedIndex(0);
		actionBox.setSelectedIndex(0);
	}

	private int intValue(org.adempiere.webui.component.Listbox lb) {
		Listitem i = lb.getSelectedItem();
		return i == null || i.getValue() == null ? 0 : ((Number) i.getValue()).intValue();
	}

	private void reload() {
		// The date boxes are not sent to the query. A pending approval is not a
		// report of what happened this month: an order entered last month and
		// still unapproved is precisely what the approver has to see, and any
		// date window silently hides it. The boxes stay on screen because an
		// approver does sometimes want to narrow by hand, but they no longer
		// decide what the queue contains.
		Timestamp from = null;
		Timestamp to = null;
		BigDecimal amt = amountBox.getValue();
		int clientId = Env.getAD_Client_ID(Env.getCtx());
		int userId = Env.getAD_User_ID(Env.getCtx());
		rows.clear();
		if (USE_XX_TEST) {
			rows.addAll(POApprovalService.loadXxTest(clientId, userId, intValue(orgBox),
					intValue(docTypeBox), from, to, docNoBox.getValue(), bpBox.getValue()));
		} else {
			rows.addAll(POApprovalService.load(clientId, userId, intValue(orgBox), intValue(docTypeBox),
					from, to, docNoBox.getValue(), bpBox.getValue(), amt));
		}
		grid.getItems().clear();
		int serial = 1;
		for (POApprovalService.Row r : rows) {
			Listitem item = new Listitem();
			item.appendChild(new Listcell(String.valueOf(serial++)));
			item.appendChild(new Listcell(r.org));
			item.appendChild(new Listcell(r.date == null ? "" : r.date.toString().substring(0, 10)));
			item.appendChild(new Listcell(r.docType));
			item.appendChild(new Listcell(r.documentNo));
			item.appendChild(new Listcell(r.bp));
			item.appendChild(new Listcell(r.amount == null ? "" : r.amount.toPlainString()));
			item.appendChild(new Listcell(r.preparedBy));
			grid.appendChild(item);
		}
		totalLabel.setValue(String.valueOf(rows.size()));
		selDocNo.setValue("-");
		selInfo.setValue("-");
	}

	private POApprovalService.Row selected() {
		int idx = grid.getSelectedIndex();
		return idx < 0 || idx >= rows.size() ? null : rows.get(idx);
	}

	@Override
	public void onEvent(Event e) throws Exception {
		Object t = e.getTarget();
		if (t == reloadBtn) {
			reload();
		} else if (t == grid) {
			POApprovalService.Row r = selected();
			if (r != null) {
				selDocNo.setValue(r.documentNo);
				selInfo.setValue(r.docType + " | " + r.bp + " | " + r.amount);
			}
		} else if (t == detailBtn) {
			POApprovalService.Row r = selected();
			if (r != null) {
				if (r.fromTestTable) {
					Dialog.warn(getWindowNo(), "This row comes from the test table, so there is no order to open.");
					return;
				}
				AEnv.zoom(MOrder.Table_ID, r.orderId);
			}
		} else if (t == printBtn) {
			printSelected();
		} else if (t == attachBtn) {
			showAttachments();
		} else if (t == historyBtn) {
			showHistory();
		} else if (t == submitBtn) {
			submit();
		}
	}

	/**
	 * Opens the order so that it can be printed.
	 * <p>
	 * iDempiere 12 exposes no generic print entry point to a custom form: there is
	 * no {@code org.adempiere.webui.print} package and no print method on
	 * {@code AEnv}, both verified against this installation. Printing an order runs
	 * {@code AD_Process} 110 "Order Print", which the standard order window offers
	 * on its own toolbar. So the button opens that window, where the real print
	 * dialog with its preview and paper choice lives, instead of pretending to
	 * print and quietly producing nothing.
	 */
	private void printSelected() {
		POApprovalService.Row r = selected();
		if (r == null) {
			Dialog.warn(getWindowNo(), "Select a document first");
			return;
		}
		if (r.fromTestTable) {
			Dialog.warn(getWindowNo(), "This row comes from the test table, so there is no order to print.");
			return;
		}
		Dialog.info(getWindowNo(), "Print",
				"The order is open in its own window now. Use the Print button on that window's toolbar.");
		AEnv.zoom(MOrder.Table_ID, r.orderId);
	}

	/** The files attached to the selected order, or a note that there are none. */
	private void showAttachments() {
		POApprovalService.Row r = selected();
		if (r == null) {
			Dialog.warn(getWindowNo(), "Select a document first");
			return;
		}
		java.util.List<String> names = POApprovalService.attachments(r.orderId);
		StringBuilder sb = new StringBuilder();
		for (String n : names)
			sb.append(n).append('\n');
		if (sb.length() == 0)
			sb.append("This order has no attachments.");
		Dialog.info(getWindowNo(), "Attachment", sb.toString());
	}

	private void showHistory() {
		POApprovalService.Row r = selected();
		if (r == null) {
			Dialog.warn(getWindowNo(), "Select a document first");
			return;
		}
		if (r.fromTestTable) {
			Dialog.warn(getWindowNo(), "This row comes from the test table, so it has no approval history.");
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (KeyNamePair k : DB.getKeyNamePairs(
				"SELECT l.XX_PO_Approval_Log_ID, to_char(l.Created,'YYYY-MM-DD HH24:MI')||'  '||u.Name||'  '||l.Action"
				+ "||COALESCE(' -> '||f.Name,'')||COALESCE('  ['||l.Message||']','') "
				+ "FROM XX_PO_Approval_Log l JOIN AD_User u ON u.AD_User_ID=l.Approver_ID "
				+ "LEFT JOIN AD_User f ON f.AD_User_ID=l.ForwardTo_ID "
				+ "WHERE l.C_Order_ID=" + r.orderId + " ORDER BY l.XX_PO_Approval_Log_ID", false)) {
			sb.append(k.getName()).append("\n");
		}
		Dialog.info(getWindowNo(), "Approval History", sb.length() == 0 ? "No history" : sb.toString());
	}

	private void submit() {
		POApprovalService.Row r = selected();
		if (r == null) {
			Dialog.warn(getWindowNo(), "Select a document first");
			return;
		}
		if (r.fromTestTable) {
			// XX_Test rows carry an XX_Test_ID, and those numbers overlap the
			// purchase order ids. Acting on one would complete a different order,
			// so the action buttons stay off for this data.
			Dialog.warn(getWindowNo(),
					"This row comes from the test table, not from a purchase order, so it cannot be approved. "
					+ "It is shown for the supervisor mapping only.");
			return;
		}
		Listitem a = actionBox.getSelectedItem();
		String action = a == null ? "" : String.valueOf(a.getValue());
		if (action.isEmpty()) {
			Dialog.warn(getWindowNo(), "Select Approve / Reject / Forward");
			return;
		}
		String err = POApprovalService.act(r.orderId, action, Env.getAD_User_ID(Env.getCtx()),
				intValue(forwardBox), messageBox.getValue());
		if (err != null) {
			Dialog.error(getWindowNo(), "Error", err);
			return;
		}
		messageBox.setValue("");
		actionBox.setSelectedIndex(0);
		forwardBox.setSelectedIndex(0);
		reload();
		Dialog.info(getWindowNo(), "Done", "Document " + r.documentNo + ": " + action);
	}
}