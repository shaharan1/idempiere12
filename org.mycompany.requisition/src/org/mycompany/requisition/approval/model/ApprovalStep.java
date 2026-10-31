package org.mycompany.requisition.approval.model;

import java.sql.Timestamp;

import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.ApprovalType;
import org.mycompany.requisition.approval.config.ResponsibleType;

/**
 * One level instance inside an approval transaction.
 * <p>
 * Maps to {@code XX_APPROVAL_STEP}. A step is created for every required level of
 * the workflow, but only the level the engine currently needs becomes
 * {@link StepStatus#PENDING}. Keeping the not yet reached levels as
 * {@link StepStatus#WAITING} is what lets the workflow diagram show the whole
 * chain instead of only the part that has run.
 * <p>
 * The approver is resolved when the step is opened, not when it is configured,
 * because a delegation may be active by then.
 */
public class ApprovalStep {

	private int id;
	private int transactionId;

	/** The configured level this instance was created from. */
	private ApprovalLevel level;

	private int levelNo;
	private StepStatus status = StepStatus.WAITING;
	private ApprovalType approvalType = ApprovalType.APPROVE;

	/** Responsible kind copied from the level, kept for the diagram. */
	private ResponsibleType responsibleType = ResponsibleType.USER;

	private int approverUserId;
	private int approverRoleId;
	private int delegatedByUserId;

	private Timestamp openedDate;
	private Timestamp dueDate;
	private Timestamp completedDate;
	private Timestamp escalatedDate;

	/** Derived on read, never trusted from the record. */
	private SlaState slaState = SlaState.NONE;

	private int escalatedByUserId;
	private String comment;

	/** XX_APPROVAL_STEP_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** XX_PROCESS_TRANSACTION_ID this step belongs to. */
	public int getTransactionId() {
		return transactionId;
	}

	public void setTransactionId(int transactionId) {
		this.transactionId = transactionId;
	}

	/** The level this instance was created from, may be null when read without config. */
	public ApprovalLevel getLevel() {
		return level;
	}

	public void setLevel(ApprovalLevel level) {
		this.level = level;
	}

	public int getLevelNo() {
		return levelNo;
	}

	public void setLevelNo(int levelNo) {
		this.levelNo = levelNo;
	}

	public StepStatus getStatus() {
		return status;
	}

	public void setStatus(StepStatus status) {
		this.status = status == null ? StepStatus.WAITING : status;
	}

	public ApprovalType getApprovalType() {
		return approvalType;
	}

	public void setApprovalType(ApprovalType approvalType) {
		this.approvalType = approvalType == null ? ApprovalType.APPROVE : approvalType;
	}

	public ResponsibleType getResponsibleType() {
		return responsibleType;
	}

	public void setResponsibleType(ResponsibleType responsibleType) {
		this.responsibleType = responsibleType == null ? ResponsibleType.USER : responsibleType;
	}

	/** AD_User_ID who currently has to act, 0 when the step is not assigned yet. */
	public int getApproverUserId() {
		return approverUserId;
	}

	public void setApproverUserId(int approverUserId) {
		this.approverUserId = approverUserId;
	}

	/** AD_Role_ID the step was addressed to. */
	public int getApproverRoleId() {
		return approverRoleId;
	}

	public void setApproverRoleId(int approverRoleId) {
		this.approverRoleId = approverRoleId;
	}

	/** AD_User_ID who handed the step over, 0 when there was no delegation. */
	public int getDelegatedByUserId() {
		return delegatedByUserId;
	}

	public void setDelegatedByUserId(int delegatedByUserId) {
		this.delegatedByUserId = delegatedByUserId;
	}

	public Timestamp getOpenedDate() {
		return openedDate;
	}

	public void setOpenedDate(Timestamp openedDate) {
		this.openedDate = openedDate;
	}

	/** Taken from the level SLA when the step was opened. */
	public Timestamp getDueDate() {
		return dueDate;
	}

	public void setDueDate(Timestamp dueDate) {
		this.dueDate = dueDate;
	}

	public Timestamp getCompletedDate() {
		return completedDate;
	}

	public void setCompletedDate(Timestamp completedDate) {
		this.completedDate = completedDate;
	}

	public Timestamp getEscalatedDate() {
		return escalatedDate;
	}

	public void setEscalatedDate(Timestamp escalatedDate) {
		this.escalatedDate = escalatedDate;
	}

	public SlaState getSlaState() {
		return slaState;
	}

	public void setSlaState(SlaState slaState) {
		this.slaState = slaState == null ? SlaState.NONE : slaState;
	}

	/** AD_User_ID the escalation was sent to. */
	public int getEscalatedByUserId() {
		return escalatedByUserId;
	}

	public void setEscalatedByUserId(int escalatedByUserId) {
		this.escalatedByUserId = escalatedByUserId;
	}

	/** Comment left by the approver. */
	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	/**
	 * Recomputes {@link #getSlaState()} from the clock instead of trusting the
	 * stored value, so a step can never show a stale condition.
	 *
	 * @param now current moment
	 */
	public void recomputeSlaState(Timestamp now) {
		if (dueDate == null || now == null || !status.isOpen()) {
			slaState = SlaState.NONE;
			return;
		}
		if (escalatedDate != null) {
			slaState = SlaState.ESCALATED;
			return;
		}
		if (now.after(dueDate)) {
			slaState = SlaState.OVERDUE;
			return;
		}
		Integer warn = level == null ? null : level.getWarningBeforeHours();
		if (warn != null && warn.intValue() > 0) {
			long warnMillis = warn.longValue() * 60L * 60L * 1000L;
			if (now.getTime() + warnMillis >= dueDate.getTime())
				slaState = SlaState.WARNING;
			else
				slaState = SlaState.NORMAL;
			return;
		}
		slaState = SlaState.NORMAL;
	}

	/** True when this step has already reached a decision. */
	public boolean isFinished() {
		return status == StepStatus.APPROVED || status == StepStatus.REJECTED
				|| status == StepStatus.RETURNED || status == StepStatus.SKIPPED;
	}

	/** Hours still available before the due date, negative when overdue. */
	public double getHoursRemaining(Timestamp now) {
		if (dueDate == null || now == null)
			return 0d;
		double hours = (dueDate.getTime() - now.getTime()) / (60d * 60d * 1000d);
		return Math.round(hours * 10d) / 10d;
	}

	/** Label used by the workflow diagram. */
	public String getDisplayName() {
		return "Level " + levelNo;
	}
}
