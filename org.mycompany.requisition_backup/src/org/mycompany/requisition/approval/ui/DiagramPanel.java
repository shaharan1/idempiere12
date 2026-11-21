package org.mycompany.requisition.approval.ui;

import java.util.ArrayList;
import java.util.List;

import org.adempiere.webui.component.Label;
import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.WorkflowDefinition;
import org.mycompany.requisition.approval.config.WorkflowType;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.model.SlaState;
import org.mycompany.requisition.approval.model.StepStatus;
import org.mycompany.requisition.approval.model.WorkflowTestResult;
import org.mycompany.requisition.approval.service.WorkflowEngine;
import org.mycompany.requisition.approval.service.WorkflowTestService;
import org.zkoss.zul.Box;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Vlayout;

/**
 * Section 06, the chain as it is configured, and as it is running.
 * <p>
 * A drawing of the configured chain: one box per level, in order, with what that
 * level needs to know at a glance - who approves, whether the step blocks the
 * document, and how long it has. When a transaction is selected the same boxes also
 * show where that document actually is, which is the question a stalled approval
 * actually needs answered.
 *
 * <p>
 * This is drawn from components rather than from a graph layout on purpose. A
 * generated picture would look more like a diagram and would tell an administrator
 * less: the text in a box is searchable, copyable and readable by a screen reader,
 * and it cannot drift away from the configuration because it is built from the same
 * level rows the engine reads.
 */
public class DiagramPanel extends Vlayout {
	private static final long serialVersionUID = 1L;

	private final WProcessApprovalWindow window;
	private Hlayout chain;
	private Hlayout testBar;
	private Label lblSummary;
	private Label lblState;
	private org.adempiere.webui.component.Listbox lstTest;
	private org.adempiere.webui.component.Textbox txtTestAmount;

	/** Transaction whose state is drawn, 0 when only the configuration is shown. */
	private int shownTransactionId;

	public DiagramPanel(WProcessApprovalWindow window) {
		this.window = window;
		setSpacing("4px");
		setStyle("width: 100%;");

		lblSummary = new Label("");
		lblSummary.setStyle("font-weight: bold;");
		appendChild(lblSummary);

		chain = new Hlayout();
		chain.setSpacing("2px");
		chain.setStyle("align-items: center; flex-wrap: wrap; padding: 4px 0;");
		appendChild(chain);

		lblState = new Label("");
		lblState.setStyle("color: #555555;");
		appendChild(lblState);

		buildTestBar();
	}

	/**
	 * The dry run controls.
	 * <p>
	 * Placed under the diagram rather than in its own section, because the answer
	 * belongs next to the picture it explains. The run writes nothing: it asks the
	 * same resolver the engine asks when a document is really submitted, so what
	 * it shows is what would actually happen.
	 */
	private void buildTestBar() {
		testBar = new Hlayout();
		testBar.setSpacing("6px");
		testBar.setStyle("align-items: center; padding: 6px 0; border-top: 1px solid #dddddd;");

		Label label = new Label("Test Workflow - amount:");
		testBar.appendChild(label);

		txtTestAmount = new org.adempiere.webui.component.Textbox();
		txtTestAmount.setWidth("140px");
		txtTestAmount.setTooltip("Leave empty to test the rules that have no amount band.");
		testBar.appendChild(txtTestAmount);

		org.adempiere.webui.component.Button btnTest = UiLayouts.button("Test");
		btnTest.addEventListener(org.zkoss.zk.ui.event.Events.ON_CLICK, e -> runTest());
		testBar.appendChild(btnTest);

		org.adempiere.webui.component.Button btnClear = UiLayouts.button("Clear Result");
		btnClear.addEventListener(org.zkoss.zk.ui.event.Events.ON_CLICK, e -> clearTest());
		testBar.appendChild(btnClear);
		appendChild(testBar);

		lstTest = new org.adempiere.webui.component.Listbox();
		lstTest.setWidth("100%");
		lstTest.setRows(4);
		UiLayouts.clear(lstTest);
		lstTest.setVisible(false);
		appendChild(lstTest);
	}

	/** Runs the dry run and shows the chain it would produce. */
	private void runTest() {
		int versionId = window.getSelectedVersionId();
		if (versionId <= 0) {
			window.reportError("Choose a process and a version to test.");
			return;
		}

		java.math.BigDecimal amount = null;
		String text = txtTestAmount.getValue() == null ? "" : txtTestAmount.getValue().trim();
		if (text.length() > 0) {
			try {
				amount = new java.math.BigDecimal(text);
				if (amount.signum() < 0) {
					window.reportError("The tested amount cannot be negative.");
					return;
				}
			} catch (NumberFormatException e) {
				window.reportError("The tested amount must be a number, for example 50000 or 50000.00.");
				return;
			}
		}

		try {
			WorkflowTestResult result = new WorkflowTestService().test(versionId, amount, 0, 0, 0, 0);
			showTestResult(amount, result);
		} catch (Exception e) {
			window.reportError("The test could not run: " + e.getMessage());
		}
	}

	private void showTestResult(java.math.BigDecimal amount, WorkflowTestResult result) {
		UiLayouts.clear(lstTest);
		lstTest.setVisible(true);

		String heading = "Would approve" + (amount == null ? " (no amount given)"
				: " for " + amount.toPlainString())
				+ (result.getMatchedRule() == null ? ", no rule matched" : ", rule \"" + result.getMatchedRule() + "\"")
				+ ": "
				+ (result.hasRequiredLevels() ? result.getRequiredLevelCount() + " level(s)"
						: "nobody at all");
		if (result.getFinalApproverName() != null && result.getFinalApproverName().trim().length() > 0)
			heading += ", finally released by " + result.getFinalApproverName();

		lstTest.appendItem(heading, Integer.valueOf(0));
		for (WorkflowTestResult.LevelOutcome outcome : result.getOutcomes())
			lstTest.appendItem(rowOf(outcome), Integer.valueOf(outcome.getLevelNo()));

		if (result.getWarning() != null && result.getWarning().trim().length() > 0)
			lstTest.appendItem("Warning: " + result.getWarning(), Integer.valueOf(-1));
	}

	private String rowOf(WorkflowTestResult.LevelOutcome outcome) {
		StringBuilder sb = new StringBuilder();
		if (outcome.isRequired()) {
			sb.append("level ").append(outcome.getLevelNo());
			sb.append("  ").append(outcome.getLevelName());
			sb.append("  ->  ").append(outcome.getApproverName());
			if (outcome.getReason() != null)
				sb.append("   (").append(outcome.getReason()).append(')');
		} else {
			sb.append("level ").append(outcome.getLevelNo());
			sb.append("  ").append(outcome.getLevelName());
			sb.append("  ->  not needed for this document");
		}
		return sb.toString();
	}

	private void clearTest() {
		UiLayouts.clear(lstTest);
		lstTest.setVisible(false);
	}

	/** Redraws for the currently selected version. */
	public void refresh() {
		shownTransactionId = 0;
		draw();
	}

	/**
	 * Redraws and marks where a document is.
	 *
	 * @param transactionId the document to show, 0 for the configuration only
	 */
	public void showTransaction(int transactionId) {
		shownTransactionId = transactionId;
		draw();
	}

	private void draw() {
		UiLayouts.clear(chain);

		int versionId = window.getSelectedVersionId();
		if (versionId <= 0) {
			lblSummary.setValue("No version selected.");
			lblSummary.setStyle("font-weight: bold; color: #a06000;");
			lblState.setValue("Choose a process and a version to see its chain.");
			lblState.setStyle("color: #555555;");
			return;
		}

		describeWorkflow(versionId);

		List<ApprovalLevel> levels = window.levelService().listLevels(versionId);
		if (levels.isEmpty()) {
			lblState.setValue("This version has no levels yet, so it cannot approve anything. "
					+ "Add levels in section 04.");
			lblState.setStyle("color: #a06000;");
			return;
		}

		ApprovalTransaction transaction = loadTransaction();
		for (int i = 0; i < levels.size(); i++) {
			if (i > 0)
				chain.appendChild(arrow(transaction, i));
			chain.appendChild(node(levels.get(i), transaction));
		}
		describeState(levels, transaction);
	}

	/** A one line summary of how the workflow behaves. */
	private void describeWorkflow(int versionId) {
		WorkflowDefinition workflow = window.processService().getWorkflow(versionId);
		WorkflowType type = workflow == null || workflow.getWorkflowType() == null
				? WorkflowType.SEQUENTIAL
				: workflow.getWorkflowType();
		boolean approvalRequired = workflow == null || workflow.isApprovalRequired();
		boolean autoStart = workflow == null || workflow.isAutoStart();

		StringBuilder sb = new StringBuilder();
		sb.append(type.getLabel()).append(" chain");
		sb.append(approvalRequired ? ", approval required" : ", no approval required");
		sb.append(autoStart ? ", starts automatically" : ", waits to be started");
		lblSummary.setValue(sb.toString());
		lblSummary.setStyle("font-weight: bold; color: #1f6f3f;");
	}

	private ApprovalTransaction loadTransaction() {
		if (shownTransactionId <= 0)
			return null;
		try {
			WorkflowEngine engine = window.engine();
			return engine == null ? null : engine.loadById(shownTransactionId);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The box of one level, with the state of that step when a document is shown. */
	private Box node(ApprovalLevel level, ApprovalTransaction transaction) {
		Vlayout content = new Vlayout();
		content.setSpacing("1px");

		Label title = new Label(level.getLevelNo() + ". " + level.getDisplayName());
		title.setStyle("font-weight: bold;");
		content.appendChild(title);

		content.appendChild(small("approves: " + responsibleOf(level)));
		content.appendChild(small(level.getApprovalType().getLabel()
				+ (level.isBlocking() ? " (blocks the document)" : " (advisory)")));
		content.appendChild(small(level.getSlaHours() == null ? "no SLA"
				: "SLA " + level.getSlaHours() + "h"
						+ (level.getWarningBeforeHours() == null ? "" : ", warn " + level.getWarningBeforeHours() + "h before")));

		ApprovalStep step = stepOf(transaction, level.getLevelNo());
		if (step != null) {
			content.appendChild(small("state: " + step.getStatus().getLabel()));
			String sla = step.getSlaState() == null ? null : step.getSlaState().getLabel();
			if (sla != null && step.getSlaState() != SlaState.NONE)
				content.appendChild(small("SLA: " + sla));
			if (step.getApproverUserId() > 0)
				content.appendChild(small("with " + userName(step.getApproverUserId())));
			else if (step.getApproverRoleId() > 0)
				content.appendChild(small("with role " + roleName(step.getApproverRoleId())));
			else
				content.appendChild(small("nobody assigned yet"));
		} else if (transaction != null) {
			content.appendChild(small("not part of this document"));
		}

		Box box = new Box();
		box.appendChild(content);
		box.setStyle(borderOf(level, step));
		box.setWidth("190px");
		return box;
	}

	/** The arrow between two boxes, which also shows what the chain is doing. */
	private Label arrow(ApprovalTransaction transaction, int index) {
		ApprovalLevel level = levelAt(index);
		ApprovalStep step = stepOf(transaction, level == null ? -1 : level.getLevelNo());
		String mark = "→";
		if (step != null && step.getStatus() == StepStatus.SKIPPED)
			mark = "⇢";
		Label label = new Label(mark);
		label.setStyle("font-size: 16px; color: #888888; padding: 0 4px;");
		return label;
	}

	private ApprovalLevel levelAt(int index) {
		List<ApprovalLevel> levels = window.levelService().listLevels(window.getSelectedVersionId());
		return index < levels.size() ? levels.get(index) : null;
	}

	private ApprovalStep stepOf(ApprovalTransaction transaction, int levelNo) {
		if (transaction == null || levelNo <= 0)
			return null;
		for (ApprovalStep step : transaction.getSteps())
			if (step.getLevelNo() == levelNo)
				return step;
		return null;
	}

	/**
	 * Border colour of a box.
	 * <p>
	 * Colour carries the state of the step, and the text says the same thing, so
	 * the meaning does not depend on being able to tell the colours apart.
	 */
	private String borderOf(ApprovalLevel level, ApprovalStep step) {
		String colour = "#cccccc";
		if (step != null) {
			switch (step.getStatus()) {
			case APPROVED:
				colour = "#2e7d32";
				break;
			case REJECTED:
			case RETURNED:
				colour = "#b00020";
				break;
			case PENDING:
				colour = step.getSlaState() != null && step.getSlaState().needsAttention() ? "#e07b00" : "#1565c0";
				break;
			case SKIPPED:
				colour = "#9e9e9e";
				break;
			default:
				colour = "#bbbbbb";
				break;
			}
		} else if (!level.isRequired()) {
			colour = "#d5d5d5";
		}
		return "border: 2px solid " + colour + "; border-radius: 4px; padding: 6px; margin: 2px;";
	}

	/** Says where a selected document currently is. */
	private void describeState(List<ApprovalLevel> levels, ApprovalTransaction transaction) {
		if (transaction == null) {
			lblState.setValue(levels.size() + " level(s) configured. The chain of a document is drawn here "
					+ "once a document is selected in section 10.");
			lblState.setStyle("color: #555555;");
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Document ").append(transaction.getDisplayName());
		sb.append(" is ").append(transaction.getStatus().getLabel().toLowerCase());
		if (transaction.getStatus().getLabel().equals("Pending") && transaction.getCurrentLevel() > 0)
			sb.append(" at level ").append(transaction.getCurrentLevel());
		List<ApprovalStep> open = transaction.getOpenSteps();
		if (!open.isEmpty()) {
			sb.append(", waiting on ");
			List<String> names = new ArrayList<String>();
			for (ApprovalStep step : open) {
				if (step.getApproverUserId() > 0)
					names.add(userName(step.getApproverUserId()));
				else if (step.getApproverRoleId() > 0)
					names.add("role " + roleName(step.getApproverRoleId()));
				else
					names.add("level " + step.getLevelNo() + " (nobody assigned)");
			}
			for (int i = 0; i < names.size(); i++) {
				if (i > 0)
					sb.append(", ");
				sb.append(names.get(i));
			}
		}
		lblState.setValue(sb.toString());
		lblState.setStyle("color: #333333; font-weight: bold;");
	}

	private String responsibleOf(ApprovalLevel level) {
		if (level.getResponsibleType().isFixedAssignee() && level.getFixedApproverId() > 0) {
			if (level.getResponsibleType() == org.mycompany.requisition.approval.config.ResponsibleType.USER)
				return userName(level.getUserId());
			return roleName(level.getRoleId());
		}
		return level.getResponsibleType().getLabel() + " (resolved per document)";
	}

	private Label small(String text) {
		Label label = new Label(text);
		label.setStyle("font-size: 11px; color: #444444;");
		return label;
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
}
