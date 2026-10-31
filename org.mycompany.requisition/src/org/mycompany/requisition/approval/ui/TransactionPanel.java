package org.mycompany.requisition.approval.ui;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.ListItem;
import org.adempiere.webui.component.Listbox;
import org.adempiere.webui.component.Textbox;
import org.mycompany.requisition.approval.model.ApprovalAction;
import org.mycompany.requisition.approval.model.ApprovalStatus;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.service.WorkflowEngine;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Vlayout;

/**
 * Section 10, the approvals themselves.
 * <p>
 * A list of documents under approval plus the four things a person can do about
 * one: approve, reject, return and delegate. The list is addressed by document,
 * because that is what an approver knows; the engine addresses it by transaction.
 *
 * <h3>Authorisation is not the form's business</h3>
 * The buttons are offered whenever a step is open, and the engine decides whether
 * this user may actually act on it. Checking here as well would create a second
 * implementation of the same rule, and the two would eventually disagree - the
 * engine is the one that has to hold, because it is also reachable from a business
 * document and from a scheduled job.
 *
 * <h3>Selection is shared</h3>
 * Selecting a document here also drives the diagram in section 06, the history in
 * section 11 and the audit in section 12, so the four always show the same
 * document.
 */
public class TransactionPanel extends Vlayout implements EventListener<Event>, Refreshable {
	private static final long serialVersionUID = 1L;

	private final WProcessApprovalWindow window;

	private Listbox lstTransactions;
	private Label lblSelection;
	private Label lblDetail;
	private Textbox txtComment;

	private Button btnApprove;
	private Button btnReject;
	private Button btnReturn;
	private Button btnDelegate;
	private Button btnStart;
	private Button btnResubmit;
	private Button btnCancel;

	private List<ApprovalTransaction> transactions = new ArrayList<ApprovalTransaction>();

	/**
	 * Who a pending step is handed over to.
	 * <p>
	 * A dropdown on the panel rather than a text box in a dialog: ZK has no input
	 * prompt to reuse, and asking for a raw {@code AD_User_ID} means an approver
	 * has to look a number up somewhere else before they can delegate at all.
	 */
	private Combobox cmbDelegateTo;

	/** The document every other section is showing. */
	private int selectedTransactionId;

	public TransactionPanel(WProcessApprovalWindow window) {
		this.window = window;
		setSpacing("4px");
		setStyle("width: 100%;");
		build();
	}

	// ─────────────────────────────────────────────────────────────
	//  chrome
	// ─────────────────────────────────────────────────────────────

	private void build() {
		Label title = new Label("Approvals of the selected process. Choose a document to act on it.");
		title.setStyle("color: #555555;");
		appendChild(title);

		lstTransactions = new Listbox();
		lstTransactions.setWidth("100%");
		lstTransactions.setRows(6);
		lstTransactions.addEventListener(Events.ON_SELECT, this);
		appendChild(lstTransactions);

		lblSelection = new Label("");
		lblSelection.setStyle("color: #555555;");
		appendChild(lblSelection);

		Hlayout actions = UiLayouts.toolbar();
		btnApprove = UiLayouts.button("Approve");
		btnApprove.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnApprove);

		btnReject = UiLayouts.button("Reject");
		btnReject.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnReject);

		btnReturn = UiLayouts.button("Return");
		btnReturn.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnReturn);

		btnDelegate = UiLayouts.button("Delegate");
		btnDelegate.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnDelegate);

		btnStart = UiLayouts.button("Start");
		btnStart.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnStart);

		btnResubmit = UiLayouts.button("Resubmit");
		btnResubmit.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnResubmit);

		btnCancel = UiLayouts.button("Withdraw");
		btnCancel.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnCancel);
		appendChild(actions);

		Hlayout commentRow = UiLayouts.toolbar();
		Label label = new Label("Comment:");
		commentRow.appendChild(label);
		txtComment = new Textbox();
		txtComment.setWidth("420px");
		commentRow.appendChild(txtComment);

		Label toLabel = new Label("Hand over to:");
		toLabel.setStyle("margin-left: 10px;");
		commentRow.appendChild(toLabel);
		cmbDelegateTo = UiLayouts.comboBox();
		cmbDelegateTo.setWidth("240px");
		UiLayouts.fill(cmbDelegateTo, Lookups.users(org.compiere.util.Env.getAD_Client_ID(window.context())),
				true);
		commentRow.appendChild(cmbDelegateTo);
		appendChild(commentRow);

		lblDetail = new Label("");
		lblDetail.setStyle("color: #333333;");
		appendChild(lblDetail);
	}

	// ─────────────────────────────────────────────────────────────
	//  data
	// ─────────────────────────────────────────────────────────────

	/** Reloads the approvals of the selected process. */
	@Override
	public void refresh() {
		transactions = new ArrayList<ApprovalTransaction>();
		WorkflowEngine engine = window.engine();
		if (engine != null)
			transactions.addAll(loadTransactions(engine));
		fill();
	}

	private List<ApprovalTransaction> loadTransactions(WorkflowEngine engine) {
		List<ApprovalTransaction> result = new ArrayList<ApprovalTransaction>();
		int versionId = window.getSelectedVersionId();
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		// the engine has no listing method by version yet, so the ids are read here
		// and each transaction is loaded by the engine, which keeps the reading of
		// a transaction in exactly one place
		String sql = "SELECT XX_PROCESS_TRANSACTION_ID FROM XX_PROCESS_TRANSACTION"
				+ " WHERE XX_PROCESS_VERSION_ID=? AND IsActive='Y' AND AD_Client_ID=?"
				+ " ORDER BY Created DESC LIMIT 200";
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, versionId);
			pstmt.setInt(2, org.compiere.util.Env.getAD_Client_ID(window.context()));
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ApprovalTransaction chain = engine.loadById(rs.getInt(1));
				if (chain != null)
					result.add(chain);
			}
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	private void fill() {
		int previous = selectedTransactionId;
		UiLayouts.clear(lstTransactions);
		for (ApprovalTransaction chain : transactions)
			lstTransactions.appendItem(rowOf(chain), Integer.valueOf(chain.getId()));

		if (transactions.isEmpty()) {
			selectedTransactionId = 0;
			lblSelection.setValue("No document of this process has been submitted for approval yet.");
			lblSelection.setStyle("color: #555555;");
			updateButtons();
			notifySelectionChanged();
			return;
		}

		boolean found = false;
		for (int i = 0; i < transactions.size(); i++) {
			if (transactions.get(i).getId() == previous) {
				lstTransactions.setSelectedIndex(i);
				found = true;
				break;
			}
		}
		if (!found)
			lstTransactions.setSelectedIndex(0);
		onRowSelected();
	}

	/** One row of the list, showing where the document stands. */
	private String rowOf(ApprovalTransaction chain) {
		StringBuilder sb = new StringBuilder();
		sb.append(chain.getDisplayName());
		sb.append("  [").append(chain.getStatus().getLabel());
		if (chain.getCurrentLevel() > 0)
			sb.append(", at level ").append(chain.getCurrentLevel());
		if (chain.getAmount() != null)
			sb.append(", ").append(chain.getAmount().toPlainString());
		if (chain.getRequestedBy() > 0)
			sb.append(", by ").append(userName(chain.getRequestedBy()));
		sb.append(']');
		return sb.toString();
	}

	private ApprovalTransaction selected() {
		for (ApprovalTransaction chain : transactions)
			if (chain.getId() == selectedTransactionId)
				return chain;
		return null;
	}

	private void onRowSelected() {
		int index = lstTransactions.getSelectedIndex();
		if (index < 0 || index >= transactions.size()) {
			selectedTransactionId = 0;
			updateButtons();
			notifySelectionChanged();
			return;
		}
		selectedTransactionId = transactions.get(index).getId();
		describeSelected();
		updateButtons();
		notifySelectionChanged();
	}

	private void describeSelected() {
		ApprovalTransaction chain = selected();
		if (chain == null)
			return;
		StringBuilder sb = new StringBuilder();
		sb.append("Level / approver: ");
		List<ApprovalStep> open = chain.getOpenSteps();
		if (open.isEmpty()) {
			sb.append("nothing is open, the document is ").append(chain.getStatus().getLabel().toLowerCase());
		} else {
			List<String> parts = new ArrayList<String>();
			for (ApprovalStep step : open) {
				String who;
				if (step.getApproverUserId() > 0)
					who = userName(step.getApproverUserId());
				else if (step.getApproverRoleId() > 0)
					who = "role " + roleName(step.getApproverRoleId());
				else
					who = "nobody assigned yet";
				parts.add(step.getLevelNo() + " (" + step.getStatus().getLabel() + ") with " + who);
			}
			for (int i = 0; i < parts.size(); i++) {
				if (i > 0)
					sb.append("; ");
				sb.append(parts.get(i));
			}
		}
		if (chain.getLastAction() != null)
			sb.append("   Last action: ").append(chain.getLastAction().getLabel());
		lblDetail.setValue(sb.toString());
		lblDetail.setStyle("color: #333333;");
	}

	private void updateButtons() {
		ApprovalTransaction chain = selected();
		boolean haveChain = chain != null;
		ApprovalStatus status = haveChain ? chain.getStatus() : null;
		boolean running = status == ApprovalStatus.PENDING;
		boolean actionable = running && actionableStep() != null;

		UiLayouts.enable(btnApprove, actionable);
		UiLayouts.enable(btnReject, actionable);
		UiLayouts.enable(btnReturn, actionable);
		UiLayouts.enable(btnDelegate, running);
		UiLayouts.enable(btnStart, haveChain && status == ApprovalStatus.CREATED);
		UiLayouts.enable(btnResubmit, haveChain && (status == ApprovalStatus.REJECTED
				|| status == ApprovalStatus.RETURNED || status == ApprovalStatus.CANCELLED));
		UiLayouts.enable(btnCancel, running && haveChain && chain.getRequestedBy() > 0);
	}

	/** The step this user may act on, or null; the engine decides, not the form. */
	private ApprovalStep actionableStep() {
		ApprovalTransaction chain = selected();
		WorkflowEngine engine = window.engine();
		if (chain == null || engine == null)
			return null;
		int user = org.compiere.util.Env.getAD_User_ID(window.context());
		int role = org.compiere.util.Env.getAD_Role_ID(window.context());
		for (ApprovalStep step : chain.getSteps())
			if (engine.canAct(step, user, role))
				return step;
		return null;
	}

	/** Tells the diagram, the history and the audit that the selection moved. */
	private void notifySelectionChanged() {
		window.onTransactionSelected(selectedTransactionId);
	}

	// ─────────────────────────────────────────────────────────────
	//  events
	// ─────────────────────────────────────────────────────────────

	@Override
	public void onEvent(Event event) throws Exception {
		if (Events.ON_SELECT.equals(event.getName())) {
			onRowSelected();
		} else if (Events.ON_CLICK.equals(event.getName())) {
			onClick(event.getTarget());
		}
	}

	private void onClick(Object target) {
		if (target == btnApprove)
			act(ApprovalAction.APPROVE, null);
		else if (target == btnReject)
			act(ApprovalAction.REJECT, "Reject document \"");
		else if (target == btnReturn)
			act(ApprovalAction.RETURN, "Return document \"");
		else if (target == btnDelegate)
			delegate();
		else if (target == btnStart)
			start();
		else if (target == btnResubmit)
			resubmit();
		else if (target == btnCancel)
			cancel();
	}

	private void act(ApprovalAction action, String question) {
		ApprovalTransaction chain = selected();
		ApprovalStep step = actionableStep();
		WorkflowEngine engine = window.engine();
		if (chain == null || step == null || engine == null)
			return;

		String comment = txtComment.getValue() == null ? "" : txtComment.getValue().trim();
		if (question != null) {
			String prompt = question + chain.getDisplayName() + "\"?\n\nLevel "
					+ step.getLevelNo() + " of " + chain.getSteps().size() + ".";
			if (action == ApprovalAction.REJECT || action == ApprovalAction.RETURN) {
				if (comment.length() == 0) {
					window.reportError("A comment is required to " + action.getLabel().toLowerCase()
							+ ", so the requester knows what to change.");
					return;
				}
				prompt = prompt + "\nA comment is required and will be stored in the approval history.";
			}
			if (Messagebox.show(prompt, "Confirm " + action.getLabel(), Messagebox.OK | Messagebox.CANCEL,
					Messagebox.QUESTION) != Messagebox.OK)
				return;
		}

		try {
			engine.act(chain.getId(), step.getId(), action, comment);
			txtComment.setValue("");
			window.reportStatus(action.getLabel() + " recorded for document " + chain.getDisplayName() + ".");
			reload();
		} catch (Exception e) {
			window.reportError(action.getLabel() + " failed: " + e.getMessage());
		}
	}

	private void delegate() {
		ApprovalTransaction chain = selected();
		ApprovalStep step = actionableStep();
		WorkflowEngine engine = window.engine();
		if (chain == null || step == null || engine == null)
			return;

		int delegateTo = UiLayouts.comboId(cmbDelegateTo);
		if (delegateTo <= 0) {
			window.reportError("Choose who approves level " + step.getLevelNo() + " instead, using the "
					+ "\"Hand over to\" field.");
			return;
		}
		if (delegateTo == step.getApproverUserId()) {
			window.reportError("That person already approves this step.");
			return;
		}

		String comment = txtComment.getValue() == null ? "" : txtComment.getValue().trim();
		if (Messagebox.show("Hand over level " + step.getLevelNo() + " of document "
				+ chain.getDisplayName() + " to " + userName(delegateTo) + "?",
				"Delegate", Messagebox.OK | Messagebox.CANCEL, Messagebox.QUESTION) != Messagebox.OK)
			return;

		try {
			engine.delegate(chain.getId(), step.getId(), delegateTo, comment.length() == 0 ? null : comment);
			txtComment.setValue("");
			window.reportStatus("Level " + step.getLevelNo() + " of " + chain.getDisplayName()
					+ " was handed over to " + userName(delegateTo) + ".");
			reload();
		} catch (Exception e) {
			window.reportError("Delegating failed: " + e.getMessage());
		}
	}

	private void start() {
		ApprovalTransaction chain = selected();
		WorkflowEngine engine = window.engine();
		if (chain == null || engine == null)
			return;
		if (Messagebox.show("Start the approval chain of \"" + chain.getDisplayName() + "\"?",
				"Confirm", Messagebox.OK | Messagebox.CANCEL, Messagebox.QUESTION) != Messagebox.OK)
			return;
		try {
			engine.start(chain.getId());
			window.reportStatus("Approval chain started for " + chain.getDisplayName() + ".");
			reload();
		} catch (Exception e) {
			window.reportError("Starting failed: " + e.getMessage());
		}
	}

	private void resubmit() {
		ApprovalTransaction chain = selected();
		WorkflowEngine engine = window.engine();
		if (chain == null || engine == null)
			return;
		if (Messagebox.show("Resubmit \"" + chain.getDisplayName() + "\"?\n\n"
				+ "The chain is built again from the rules, using the amount stored on the document, "
				+ "so a document whose amount changed in the meantime is routed by the rule that matches it.",
				"Confirm", Messagebox.OK | Messagebox.CANCEL, Messagebox.QUESTION) != Messagebox.OK)
			return;
		try {
			engine.resubmit(chain.getId(), contextOf(chain));
			window.reportStatus("Document " + chain.getDisplayName() + " was resubmitted.");
			reload();
		} catch (Exception e) {
			window.reportError("Resubmitting failed: " + e.getMessage());
		}
	}

	private void cancel() {
		ApprovalTransaction chain = selected();
		WorkflowEngine engine = window.engine();
		if (chain == null || engine == null)
			return;
		String comment = txtComment.getValue() == null ? "" : txtComment.getValue().trim();
		if (Messagebox.show("Withdraw the approval of \"" + chain.getDisplayName() + "\"?\n\n"
				+ "The reason stays in the approval history.", "Confirm",
				Messagebox.OK | Messagebox.CANCEL, Messagebox.QUESTION) != Messagebox.OK)
			return;
		try {
			engine.cancel(chain.getId(), comment.length() == 0 ? null : comment);
			txtComment.setValue("");
			window.reportStatus("Approval of " + chain.getDisplayName() + " was withdrawn.");
			reload();
		} catch (Exception e) {
			window.reportError("Withdrawing failed: " + e.getMessage());
		}
	}

	/** The document facts a resubmission needs, rebuilt from the stored transaction. */
	private org.mycompany.requisition.approval.model.DocumentContext contextOf(ApprovalTransaction chain) {
		org.mycompany.requisition.approval.model.DocumentContext context =
				new org.mycompany.requisition.approval.model.DocumentContext();
		context.setTableId(chain.getTableId());
		context.setRecordId(chain.getRecordId());
		context.setDocumentNo(chain.getDocumentNo());
		context.setAmount(chain.getAmount());
		context.setRequestedBy(chain.getRequestedBy());
		context.setUserId(org.compiere.util.Env.getAD_User_ID(window.context()));
		context.setRoleId(org.compiere.util.Env.getAD_Role_ID(window.context()));
		context.setOrgId(org.compiere.util.Env.getAD_Org_ID(window.context()));
		return context;
	}

	private void reload() {
		refresh();
	}

	/** Document every other section is showing, 0 when none. */
	public int getSelectedTransactionId() {
		return selectedTransactionId;
	}

	/** The chain every other section is showing. */
	public ApprovalTransaction getSelectedTransaction() {
		return selected();
	}

	private String userName(int userId) {
		if (userId <= 0)
			return "?";
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_User WHERE AD_User_ID=?", Integer.valueOf(userId));
		return name == null ? ("user " + userId) : name;
	}

	private String roleName(int roleId) {
		if (roleId <= 0)
			return "?";
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_Role WHERE AD_Role_ID=?", Integer.valueOf(roleId));
		return name == null ? String.valueOf(roleId) : name;
	}

	/** Moment, shared so a reload and a drawing cannot disagree. */
	static Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}
}
