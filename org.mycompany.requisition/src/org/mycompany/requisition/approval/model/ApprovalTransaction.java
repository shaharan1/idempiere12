package org.mycompany.requisition.approval.model;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One business document moving through an approval chain.
 * <p>
 * Maps to {@code XX_PROCESS_TRANSACTION}. The header stores the target document
 * as {@code AD_Table_ID + Record_ID} rather than a foreign key to one table, so
 * the same framework governs a purchase requisition, a sales order and any
 * custom document without a schema per process.
 * <p>
 * The pinned {@code processVersionId} is the important field. It is written when
 * the document is submitted and never changed, which guarantees that later
 * reconfiguration of the approval chain cannot alter how this document was
 * actually approved.
 */
public class ApprovalTransaction {

	private int id;

	private int tableId;
	private int recordId;
	private String documentNo;

	private int processId;
	private int processVersionId;
	private int workflowId;

	private ApprovalStatus status = ApprovalStatus.CREATED;
	private int currentLevel;

	private int requestedBy;
	private Timestamp requestDate;
	private BigDecimal amount;

	private int approvedBy;
	private Timestamp approvedDate;
	private int rejectedBy;
	private Timestamp rejectedDate;

	private ApprovalAction lastAction;
	private Timestamp lastActionDate;

	/** Ordered level instances of this transaction. */
	private final List<ApprovalStep> steps = new ArrayList<ApprovalStep>();

	private final List<ApprovalHistoryEntry> history = new ArrayList<ApprovalHistoryEntry>();

	/** XX_PROCESS_TRANSACTION_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** AD_Table_ID of the governed document. */
	public int getTableId() {
		return tableId;
	}

	public void setTableId(int tableId) {
		this.tableId = tableId;
	}

	/** Primary key of the governed document inside that table. */
	public int getRecordId() {
		return recordId;
	}

	public void setRecordId(int recordId) {
		this.recordId = recordId;
	}

	/** Business document number, for example PR-10045. */
	public String getDocumentNo() {
		return documentNo;
	}

	public void setDocumentNo(String documentNo) {
		this.documentNo = documentNo;
	}

	/** XX_PROCESS_ID. */
	public int getProcessId() {
		return processId;
	}

	public void setProcessId(int processId) {
		this.processId = processId;
	}

	/**
	 * XX_PROCESS_VERSION_ID, pinned at submission time. Never rewritten, this is
	 * what makes the framework auditable.
	 */
	public int getProcessVersionId() {
		return processVersionId;
	}

	public void setProcessVersionId(int processVersionId) {
		this.processVersionId = processVersionId;
	}

	/** XX_PROCESS_WORKFLOW_ID the chain runs on. */
	public int getWorkflowId() {
		return workflowId;
	}

	public void setWorkflowId(int workflowId) {
		this.workflowId = workflowId;
	}

	public ApprovalStatus getStatus() {
		return status;
	}

	public void setStatus(ApprovalStatus status) {
		this.status = status == null ? ApprovalStatus.CREATED : status;
	}

	/** Level number that is currently open, 0 when the chain has not started. */
	public int getCurrentLevel() {
		return currentLevel;
	}

	public void setCurrentLevel(int currentLevel) {
		this.currentLevel = currentLevel;
	}

	/** AD_User_ID of the requester. */
	public int getRequestedBy() {
		return requestedBy;
	}

	public void setRequestedBy(int requestedBy) {
		this.requestedBy = requestedBy;
	}

	public Timestamp getRequestDate() {
		return requestDate;
	}

	public void setRequestDate(Timestamp requestDate) {
		this.requestDate = requestDate;
	}

	/** Document amount, used by the amount based approval rules. */
	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public int getApprovedBy() {
		return approvedBy;
	}

	public void setApprovedBy(int approvedBy) {
		this.approvedBy = approvedBy;
	}

	public Timestamp getApprovedDate() {
		return approvedDate;
	}

	public void setApprovedDate(Timestamp approvedDate) {
		this.approvedDate = approvedDate;
	}

	public int getRejectedBy() {
		return rejectedBy;
	}

	public void setRejectedBy(int rejectedBy) {
		this.rejectedBy = rejectedBy;
	}

	public Timestamp getRejectedDate() {
		return rejectedDate;
	}

	public void setRejectedDate(Timestamp rejectedDate) {
		this.rejectedDate = rejectedDate;
	}

	public ApprovalAction getLastAction() {
		return lastAction;
	}

	public void setLastAction(ApprovalAction lastAction) {
		this.lastAction = lastAction;
	}

	public Timestamp getLastActionDate() {
		return lastActionDate;
	}

	public void setLastActionDate(Timestamp lastActionDate) {
		this.lastActionDate = lastActionDate;
	}

	/** Level instances in chain order. */
	public List<ApprovalStep> getSteps() {
		return Collections.unmodifiableList(steps);
	}

	public void addStep(ApprovalStep step) {
		if (step != null)
			steps.add(step);
	}

	public ApprovalStep getStep(int stepId) {
		for (ApprovalStep s : steps)
			if (s.getId() == stepId)
				return s;
		return null;
	}

	/** Steps of the given level number. */
	public List<ApprovalStep> getStepsOfLevel(int levelNo) {
		List<ApprovalStep> found = new ArrayList<ApprovalStep>();
		for (ApprovalStep s : steps)
			if (s.getLevelNo() == levelNo)
				found.add(s);
		return found;
	}

	/** Audit rows in chronological order. */
	public List<ApprovalHistoryEntry> getHistory() {
		return Collections.unmodifiableList(history);
	}

	public void addHistory(ApprovalHistoryEntry entry) {
		if (entry != null)
			history.add(entry);
	}

	/**
	 * Steps that can be acted on right now, lowest level first. A waiting step is
	 * not among them, because it is not this approver's turn yet.
	 */
	public List<ApprovalStep> getOpenSteps() {
		List<ApprovalStep> open = new ArrayList<ApprovalStep>();
		for (ApprovalStep s : steps)
			if (s.getStatus().isOpen())
				open.add(s);
		sortByLevel(open);
		return open;
	}

	/** True when every step reached a decision. */
	public boolean isChainComplete() {
		for (ApprovalStep s : steps)
			if (!s.isFinished())
				return false;
		return !steps.isEmpty();
	}

	/**
	 * True when every blocking step is approved, which is what allows the
	 * document to be released. Advisory levels such as review or noted do not
	 * hold the document back, but a rejection or a return always stops it
	 * whatever the level type was, because that is what the actor expressed.
	 */
	public boolean isFullyApproved() {
		for (ApprovalStep s : steps) {
			if (s.getStatus() == StepStatus.REJECTED || s.getStatus() == StepStatus.RETURNED)
				return false;
			if (s.getStatus() == StepStatus.SKIPPED || s.getStatus() == StepStatus.WAITING)
				continue;
			if (s.getApprovalType().isBlocking() && s.getStatus() != StepStatus.APPROVED)
				return false;
		}
		return true;
	}

	/** Sorts the chain by level number, used whenever steps are presented. */
	private static void sortByLevel(List<ApprovalStep> list) {
		Collections.sort(list, new Comparator<ApprovalStep>() {
			@Override
			public int compare(ApprovalStep a, ApprovalStep b) {
				return Integer.compare(a.getLevelNo(), b.getLevelNo());
			}
		});
	}

	/** Label used by the dashboard and the history view. */
	public String getDisplayName() {
		return documentNo == null || documentNo.trim().length() == 0
				? "record " + recordId
				: documentNo;
	}
}
