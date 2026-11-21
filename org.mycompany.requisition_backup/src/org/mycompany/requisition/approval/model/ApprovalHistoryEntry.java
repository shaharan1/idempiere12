package org.mycompany.requisition.approval.model;

import java.sql.Timestamp;

/**
 * One immutable line of the approval audit trail.
 * <p>
 * Maps to {@code XX_APPROVAL_HISTORY}. Rows are append only: the engine inserts
 * on every state change and application code never updates or deletes them,
 * which is what makes the history usable as evidence rather than as a log.
 */
public class ApprovalHistoryEntry {

	private int id;
	private int transactionId;
	private int stepId;

	private int levelNo;
	private String levelName;

	private int userId;
	private int roleId;

	private ApprovalAction action = ApprovalAction.APPROVE;
	private Timestamp actionDate;
	private String comment;
	private StepStatus status;

	/** Minutes the step was open before this action was taken. */
	private Integer duration;

	/** XX_APPROVAL_HISTORY_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** XX_PROCESS_TRANSACTION_ID this entry belongs to. */
	public int getTransactionId() {
		return transactionId;
	}

	public void setTransactionId(int transactionId) {
		this.transactionId = transactionId;
	}

	/** XX_APPROVAL_STEP_ID, 0 for header level actions such as submit. */
	public int getStepId() {
		return stepId;
	}

	public void setStepId(int stepId) {
		this.stepId = stepId;
	}

	/** Level number the action was taken on, 0 for header actions. */
	public int getLevelNo() {
		return levelNo;
	}

	public void setLevelNo(int levelNo) {
		this.levelNo = levelNo;
	}

	/** Level name at the time of the action, kept so the row reads on its own. */
	public String getLevelName() {
		return levelName;
	}

	public void setLevelName(String levelName) {
		this.levelName = levelName;
	}

	/** AD_User_ID who acted. */
	public int getUserId() {
		return userId;
	}

	public void setUserId(int userId) {
		this.userId = userId;
	}

	/** AD_Role_ID the acting user held for this step. */
	public int getRoleId() {
		return roleId;
	}

	public void setRoleId(int roleId) {
		this.roleId = roleId;
	}

	public ApprovalAction getAction() {
		return action;
	}

	public void setAction(ApprovalAction action) {
		this.action = action == null ? ApprovalAction.APPROVE : action;
	}

	public Timestamp getActionDate() {
		return actionDate;
	}

	public void setActionDate(Timestamp actionDate) {
		this.actionDate = actionDate;
	}

	/** Comment of the actor. Mandatory for reject and return. */
	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	/** Step status after this action. */
	public StepStatus getStatus() {
		return status;
	}

	public void setStatus(StepStatus status) {
		this.status = status;
	}

	public Integer getDuration() {
		return duration;
	}

	public void setDuration(Integer duration) {
		this.duration = duration;
	}

	/** Computes the duration of a step in whole minutes, rounded up. */
	public static int minutesBetween(Timestamp opened, Timestamp completed) {
		if (opened == null || completed == null)
			return 0;
		long millis = completed.getTime() - opened.getTime();
		if (millis <= 0)
			return 0;
		return (int) ((millis + 59999L) / 60000L);
	}

	/** Label used by the history grid. */
	public String getDisplayName() {
		String l = levelNo > 0 ? "Level " + levelNo : "Document";
		return l + " - " + action.getLabel();
	}
}
