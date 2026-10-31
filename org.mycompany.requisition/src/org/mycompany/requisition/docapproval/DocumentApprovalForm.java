package org.mycompany.requisition.docapproval;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Checkbox;
import org.adempiere.webui.component.Column;
import org.adempiere.webui.component.Columns;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Messagebox;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.session.SessionManager;
import org.compiere.model.MQuery;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.idempiere.ui.zk.annotation.Form;
import org.mycompany.requisition.approval.ui.UiLayouts;
import org.zkoss.zul.Cell;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Vlayout;
import org.zkoss.zk.ui.event.Events;

/**
 * The Document Approval Form.
 * <p>
 * Three panels, top to bottom, matching the layout the business asked for:
 * <ol>
 * <li><b>Search Option</b> - the filters and the running count of what matches
 * them.</li>
 * <li><b>Documents waiting for approval</b> - the documents this user is the
 * named supervisor for, and nothing else.</li>
 * <li><b>Approval Document</b> - the decision on the document chosen above.</li>
 * </ol>
 * <p>
 * The form holds no rules of its own. It reads the filters, hands them to
 * {@link DocumentApprovalService}, draws what comes back, and turns a button
 * press into a call. That way the question "may this user approve this
 * document" has one answer in the code base rather than one per screen.
 *
 * @author Shaharan Hossain
 */
@Form(name = "DocumentApprovalForm")
public class DocumentApprovalForm extends ADForm {

	/** The standard iDempiere window that shows M_Requisition, used by Detail. */
	private static final int WINDOW_REQUISITION = 322;

	/** Em dash used wherever a cell has nothing to show. */
	private static final String NONE = "—";

	private static final CLogger log = CLogger.getCLogger(DocumentApprovalForm.class);

	/**
	 * The translation of a literal, looked up in the session language.
	 * <p>
	 * This iDempiere build has no single argument {@code Msg.translate}, only the
	 * overloads that take a context, so every label goes through here rather than
	 * repeating the context.
	 */
	/** The label of the single button every message box carries. */
	private static String okLabel() {
		return Msg.getMsg(Env.getCtx(), "OK");
	}

	private static String msg(String text) {
		return Msg.translate(Env.getCtx(), text);
	}

	private final DocumentApprovalService service = new DocumentApprovalService();

	/** The documents currently drawn, and the tick boxes drawn beside them. */
	private final List<ApprovalDocument> documents = new ArrayList<ApprovalDocument>();
	private final List<Checkbox> selection = new ArrayList<Checkbox>();

	private Vlayout content;

	// Search panel
	private Label totalCount;
	private Combobox fOrg;
	private Datebox fFrom;
	private Datebox fTo;
	private Combobox fDocType;
	private Textbox fBusinessPartner;
	private Textbox fDocNo;
	private org.zkoss.zul.Decimalbox fAmount;

	// Document grid
	private Rows gridRows;

	// Approval panel
	private Label aDocNo;
	private Label aDocInfo;
	private Combobox fAction;
	private Textbox fMessage;
	private Combobox fForwardTo;

	public DocumentApprovalForm() {
		super();
	}

	@Override
	protected void initForm() {
		setTitle(msg("Document Approval Form"));
		setWidth("100%");
		setHeight("100%");

		content = new Vlayout();
		content.setWidth("100%");
		content.setStyle("padding: 6px;");
		this.appendChild(content);

		buildSearchPanel();
		buildGrid();
		buildApprovalPanel();
		fillDropdowns();

		reload();
	}

	// ------------------------------------------------------------------
	// 1. Search Option
	// ------------------------------------------------------------------

	private void buildSearchPanel() {
		Vlayout panel = new Vlayout();
		content.appendChild(panel);

		UiLayouts.FieldGrid fg = UiLayouts.grid(8);
		panel.appendChild(fg.grid);

		// the count comes first so it is read before anything else on the line
		Row line1 = UiLayouts.row();
		fg.rows.appendChild(line1);
		UiLayouts.labelCell(line1, msg("Total Document"));
		totalCount = new Label("0");
		totalCount.setStyle("color: #0055cc; font-weight: bold; font-size: 14px;");
		UiLayouts.fieldCell(line1, totalCount);
		UiLayouts.labelCell(line1, msg("Organization"));
		fOrg = UiLayouts.comboBox();
		UiLayouts.fieldCell(line1, fOrg);
		UiLayouts.labelCell(line1, msg("Date From"));
		fFrom = new Datebox();
		fFrom.setWidth("140px");
		UiLayouts.fieldCell(line1, fFrom);
		UiLayouts.labelCell(line1, msg("Date To"));
		fTo = new Datebox();
		fTo.setWidth("140px");
		UiLayouts.fieldCell(line1, fTo);

		Row line2 = UiLayouts.row();
		fg.rows.appendChild(line2);
		UiLayouts.labelCell(line2, msg("Document Type"));
		fDocType = UiLayouts.comboBox();
		UiLayouts.fieldCell(line2, fDocType);
		UiLayouts.labelCell(line2, msg("Business Partner"));
		fBusinessPartner = UiLayouts.text();
		UiLayouts.fieldCell(line2, fBusinessPartner);
		UiLayouts.labelCell(line2, msg("Document No"));
		fDocNo = UiLayouts.smallText();
		UiLayouts.fieldCell(line2, fDocNo);
		UiLayouts.labelCell(line2, msg("Amount"));
		fAmount = UiLayouts.decimal("120px");
		UiLayouts.fieldCell(line2, fAmount);

		setCurrentMonth();

		Button reloadButton = UiLayouts.button(msg("Reload"));
		reloadButton.setStyle("float: right; margin-top: 4px;");
		reloadButton.addEventListener(Events.ON_CLICK, e -> reload());
		Hlayout buttons = UiLayouts.toolbar();
		buttons.appendChild(reloadButton);
		panel.appendChild(buttons);
	}

	/** The search covers the current month unless the user changes it. */
	private void setCurrentMonth() {
		Calendar cal = Calendar.getInstance();
		cal.set(Calendar.DAY_OF_MONTH, 1);
		cal.set(Calendar.HOUR_OF_DAY, 0);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);
		fFrom.setValue(cal.getTime());

		cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
		cal.set(Calendar.HOUR_OF_DAY, 23);
		cal.set(Calendar.MINUTE, 59);
		cal.set(Calendar.SECOND, 59);
		fTo.setValue(cal.getTime());
	}

	private void fillDropdowns() {
		UiLayouts.clearItems(fOrg);
		UiLayouts.combo(fOrg, "- all -", "", true);
		for (String name : service.organizationOptions())
			UiLayouts.combo(fOrg, name, name, false);

		UiLayouts.clearItems(fDocType);
		UiLayouts.combo(fDocType, "- all -", "", true);
		for (String name : service.documentTypeOptions())
			UiLayouts.combo(fDocType, name, name, false);

		UiLayouts.clearItems(fForwardTo);
		for (String name : service.userOptions())
			UiLayouts.combo(fForwardTo, name, name, false);
	}

	// ------------------------------------------------------------------
	// 2. Documents waiting for approval
	// ------------------------------------------------------------------

	private void buildGrid() {
		Grid grid = new Grid();
		grid.setWidth("100%");
		grid.makeNoStrip();
		grid.setStyle("width: 100%; margin-top: 6px;");

		Columns cols = new Columns();
		cols.setWidth("100%");
		grid.appendChild(cols);

		addColumn(cols, msg("Select"), "60px");
		addColumn(cols, msg("Serial No"), "70px");
		addColumn(cols, msg("Organization"), "140px");
		addColumn(cols, msg("Receive Date"), "110px");
		addColumn(cols, msg("Document Type"), "160px");
		addColumn(cols, msg("Document No"), "130px");
		addColumn(cols, msg("Business Partner"), "180px");
		addColumn(cols, msg("Amount"), "110px");
		addColumn(cols, msg("Prepared By"), "150px");
		addColumn(cols, msg("Sales Type"), "110px");

		// ZK accepts only a Grid as the parent of a rows component
		gridRows = grid.newRows();
		content.appendChild(grid);
	}

	private void addColumn(Columns cols, String title, String width) {
		Column col = new Column();
		col.setWidth(width);
		Label label = new Label(title);
		label.setStyle("font-weight: bold;");
		col.appendChild(label);
		cols.appendChild(col);
	}

	// ------------------------------------------------------------------
	// 3. Approval Document
	// ------------------------------------------------------------------

	private void buildApprovalPanel() {
		Vlayout panel = new Vlayout();
		panel.setStyle("margin-top: 8px;");
		content.appendChild(panel);

		UiLayouts.FieldGrid fg = UiLayouts.grid(6);
		panel.appendChild(fg.grid);

		Row line1 = UiLayouts.row();
		fg.rows.appendChild(line1);
		UiLayouts.labelCell(line1, msg("Document No"));
		aDocNo = new Label(NONE);
		aDocNo.setStyle("font-weight: bold;");
		UiLayouts.fieldCell(line1, aDocNo);
		UiLayouts.labelCell(line1, msg("Document Info"));
		aDocInfo = new Label(msg("No document selected"));
		UiLayouts.fieldCell(line1, aDocInfo);

		Row line2 = UiLayouts.row();
		fg.rows.appendChild(line2);
		UiLayouts.labelCell(line2, msg("Detail"));
		Button bDetail = UiLayouts.button(msg("Open"));
		bDetail.addEventListener(Events.ON_CLICK, e -> openDetail());
		UiLayouts.fieldCell(line2, bDetail);

		UiLayouts.labelCell(line2, msg("Print"));
		Button bPrint = UiLayouts.button(msg("Print"));
		bPrint.addEventListener(Events.ON_CLICK, e -> showPrintHint());
		UiLayouts.fieldCell(line2, bPrint);

		UiLayouts.labelCell(line2, msg("Attachment"));
		Button bAttach = UiLayouts.button(msg("Show"));
		bAttach.addEventListener(Events.ON_CLICK, e -> showAttachments());
		UiLayouts.fieldCell(line2, bAttach);

		UiLayouts.labelCell(line2, msg("Approval History"));
		Button bHistory = UiLayouts.button(msg("Show"));
		bHistory.addEventListener(Events.ON_CLICK, e -> showHistory());
		UiLayouts.fieldCell(line2, bHistory);

		Row line3 = UiLayouts.row();
		fg.rows.appendChild(line3);
		UiLayouts.requiredLabelCell(line3, msg("Action"), "150px");
		fAction = UiLayouts.comboBox();
		UiLayouts.combo(fAction, msg("Approve"), "APPROVE", true);
		UiLayouts.combo(fAction, msg("Reject"), "REJECT", false);
		UiLayouts.fieldCell(line3, fAction);

		UiLayouts.labelCell(line3, msg("Forward To"));
		fForwardTo = UiLayouts.comboBox();
		UiLayouts.fieldCell(line3, fForwardTo);

		UiLayouts.labelCell(line3, msg("Message"));
		fMessage = UiLayouts.text();
		UiLayouts.fieldCell(line3, fMessage);

		Button bSubmit = UiLayouts.button(msg("Submit"));
		bSubmit.setStyle("float: right;");
		bSubmit.addEventListener(Events.ON_CLICK, e -> submit());
		Hlayout actions = UiLayouts.toolbar();
		actions.appendChild(bSubmit);
		panel.appendChild(actions);
	}

	// ------------------------------------------------------------------
	// Data
	// ------------------------------------------------------------------

	private void reload() {
		Timestamp from = UiLayouts.dateOf(fFrom);
		Timestamp to = UiLayouts.dateOf(fTo);
		String org = textOf(fOrg);
		String docType = textOf(fDocType);
		String docNo = fDocNo == null ? null : fDocNo.getValue();
		String businessPartner = fBusinessPartner == null ? null : fBusinessPartner.getValue();
		BigDecimal amount = UiLayouts.decimalOf(fAmount);

		List<ApprovalDocument> found =
				service.listPendingDocuments(from, to, org, docType, docNo, amount, businessPartner);

		drawGrid(found);
		totalCount.setText(String.valueOf(found.size()));
		clearSelection();
	}

	private void drawGrid(List<ApprovalDocument> found) {
		documents.clear();
		documents.addAll(found);
		selection.clear();

		UiLayouts.clear(gridRows);

		SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
		for (ApprovalDocument d : documents) {
			Row row = UiLayouts.row();

			Checkbox pick = new Checkbox();
			row.appendChild(cellOf(pick));
			selection.add(pick);

			row.appendChild(textCell(d.getSerialNo()));
			row.appendChild(textCell(d.getOrgName()));
			row.appendChild(textCell(d.getReceiveDate() == null ? NONE : fmt.format(d.getReceiveDate())));
			row.appendChild(textCell(d.getDocTypeName()));
			row.appendChild(textCell(d.getDocumentNo()));
			row.appendChild(textCell(isEmpty(d.getBusinessPartner()) ? NONE : d.getBusinessPartner()));
			row.appendChild(textCell(d.getAmount() == null ? "0.00" : d.getAmount().toPlainString()));
			row.appendChild(textCell(d.getPreparedBy()));
			row.appendChild(textCell(isEmpty(d.getSalesType()) ? NONE : d.getSalesType()));

			gridRows.appendChild(row);
		}
	}

	private Cell textCell(Object value) {
		Cell c = new Cell();
		c.appendChild(new Label(value == null ? NONE : value.toString()));
		return c;
	}

	private Cell cellOf(org.zkoss.zk.ui.Component component) {
		Cell c = new Cell();
		c.appendChild(component);
		return c;
	}

	/** The documents the user ticked, in the order the grid shows them. */
	private List<ApprovalDocument> checked() {
		List<ApprovalDocument> result = new ArrayList<ApprovalDocument>();
		for (int i = 0; i < selection.size() && i < documents.size(); i++)
			if (selection.get(i).isChecked())
				result.add(documents.get(i));
		return result;
	}

	private void clearSelection() {
		for (Checkbox box : selection)
			box.setChecked(false);
		if (aDocNo != null) {
			aDocNo.setText(NONE);
			aDocInfo.setText(msg("No document selected"));
		}
	}

	/** The ticked document, when exactly one is ticked. Null otherwise. */
	private ApprovalDocument singleSelection() {
		List<ApprovalDocument> picked = checked();
		return picked.size() == 1 ? picked.get(0) : null;
	}

	private String textOf(Combobox combo) {
		Object value = UiLayouts.comboValue(combo);
		return value == null ? null : value.toString();
	}

	private static boolean isEmpty(String s) {
		return s == null || s.trim().length() == 0;
	}

	// ------------------------------------------------------------------
	// Actions
	// ------------------------------------------------------------------

	private void openDetail() {
		ApprovalDocument doc = singleSelection();
		if (doc == null) {
			showInfo(msg("Please tick exactly one document."));
			return;
		}
		try {
			MQuery query = new MQuery(DocumentApprovalService.T_DOCUMENT);
			query.addRestriction(DocumentApprovalService.T_DOCUMENT + "_ID", MQuery.EQUAL, Integer.valueOf(doc.getId()));
			query.setRecordCount(0);
			SessionManager.getAppDesktop().showZoomWindow(WINDOW_REQUISITION, query);
			aDocNo.setText(doc.getDocumentNo());
			aDocInfo.setText(describe(doc));
		} catch (Exception e) {
			log.warning("The document could not be opened: " + e.getMessage());
			Messagebox.showDialog(msg("The document could not be opened: ") + e.getMessage(),
					msg("Document Approval Form"), Messagebox.OK, Messagebox.ERROR);
		}
	}

	private void showPrintHint() {
		if (singleSelection() == null) {
			showInfo(msg("Please tick exactly one document."));
			return;
		}
		showInfo(msg("Use Detail to open the document, then print it from that window."));
	}

	private void showAttachments() {
		ApprovalDocument doc = singleSelection();
		if (doc == null) {
			showInfo(msg("Please tick exactly one document."));
			return;
		}
		StringBuilder text = new StringBuilder();
		for (String file : service.attachments(doc))
			text.append(file).append('\n');
		if (text.length() == 0)
			text.append(msg("This document has no attachments."));
		Messagebox.showDialog(text.toString(), msg("Attachment"), Messagebox.OK, Messagebox.INFORMATION);
	}

	private void showHistory() {
		ApprovalDocument doc = singleSelection();
		if (doc == null) {
			showInfo(msg("Please tick exactly one document."));
			return;
		}
		StringBuilder text = new StringBuilder();
		for (ApprovalHistoryLine line : service.history(doc))
			text.append(line.getActionDateText()).append("   ")
			    .append(line.getUserName()).append("   ")
			    .append(line.getAction()).append("   ")
			    .append(line.getComment() == null ? "" : line.getComment()).append('\n');
		if (text.length() == 0)
			text.append(msg("This document has no approval history yet."));
Messagebox.showDialog(text.toString(), msg("Approval History"), Messagebox.OK, Messagebox.INFORMATION);
	}

	/** One line describing a document, shown next to its number. */
	private String describe(ApprovalDocument doc) {
		return doc.getDocTypeName() + "   " + doc.getOrgName() + "   "
				+ (doc.getAmount() == null ? "0.00" : doc.getAmount().toPlainString())
				+ "   " + msg("Prepared by ") + doc.getPreparedBy()
				+ (doc.isFinalApprover() ? "   " + msg("Final approver") : "");
	}

	private void showInfo(String text) {
		Messagebox.showDialog(text, msg("Document Approval Form"),
				Messagebox.OK, Messagebox.INFORMATION);
	}

	/**
	 * Carries out the chosen action on every ticked document.
	 * <p>
	 * One failing document does not stop the others: each is reported at the end
	 * so the approver knows exactly what happened to every one of them.
	 */
	private void submit() {
		List<ApprovalDocument> picked = checked();
		if (picked.isEmpty()) {
			showInfo(msg("Please tick at least one document."));
			return;
		}

		String action = textOf(fAction);
		String message = fMessage == null ? null : fMessage.getValue();
		String forwardTo = textOf(fForwardTo);

		if (DocumentApprovalService.ACTION_REJECT.equals(action) && isEmpty(message)) {
			Messagebox.showDialog(msg("A message is required when rejecting a document."),
					msg("Document Approval Form"), Messagebox.OK, Messagebox.EXCLAMATION);
			return;
		}
		if (!isEmpty(forwardTo) && picked.size() > 1) {
			Messagebox.showDialog(msg("Forwarding is done one document at a time."),
					msg("Document Approval Form"), Messagebox.OK, Messagebox.EXCLAMATION);
			return;
		}

		int done = 0;
		StringBuilder problems = new StringBuilder();
		for (ApprovalDocument doc : picked) {
			try {
				if (!isEmpty(forwardTo))
					service.forward(doc, service.userIdByName(forwardTo));
				else if (DocumentApprovalService.ACTION_REJECT.equals(action))
					service.reject(doc, message);
				else
					service.approve(doc, message);
				done++;
			} catch (ApprovalException e) {
				problems.append(doc.getDocumentNo()).append(": ").append(e.getMessage()).append('\n');
			} catch (Exception e) {
				log.warning("The approval of " + doc.getDocumentNo() + " failed: " + e.getMessage());
				problems.append(doc.getDocumentNo()).append(": ")
				         .append(msg("Unexpected error")).append('\n');
			}
		}

		if (problems.length() > 0) {
			Messagebox.showDialog(problems.toString(), msg("Document Approval Form"),
					Messagebox.OK, Messagebox.ERROR);
		} else {
			showInfo(msg("Done. Documents processed: ") + done);
		}
		reload();
	}

	/** Exposed so the form can be exercised without a browser. */
	public DocumentApprovalService getService() {
		return service;
	}

	/** The count label, which the specification shows in blue. */
	public Label getTotalCount() {
		return totalCount;
	}
}