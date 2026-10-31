package org.mycompany.requisition.approval.model;

import org.mycompany.requisition.approval.config.ApprovalLevel;

/**
 * One approval the document has to pass, with the approver already resolved.
 * <p>
 * Produced by the rule resolver and consumed by the workflow engine when it
 * creates the steps. Keeping the resolved approver here means the engine never
 * has to repeat the assignment logic, and the Test Workflow button can show the
 * very same result without running anything.
 */
public class RequiredApproval {

	private final ApprovalLevel level;

	/** AD_User_ID that will be asked, 0 when the approver is still a role. */
	private int approverUserId;

	/** AD_Role_ID that will be asked, 0 when a single user is responsible. */
	private int approverRoleId;

	/** AD_User_ID who delegated, 0 when nobody did. */
	private int delegatedFromUserId;

	/** Why this approval is in the chain. */
	private final String reason;

	/** Set when the approver could not be determined. */
	private String warning;

	public RequiredApproval(ApprovalLevel level, String reason) {
		this.level = level;
		this.reason = reason;
	}

	/** The configured level this approval comes from. */
	public ApprovalLevel getLevel() {
		return level;
	}

	/** Level number, 1 based. */
	public int getLevelNo() {
		return level == null ? 0 : level.getLevelNo();
	}

	public int getApproverUserId() {
		return approverUserId;
	}

	public void setApproverUserId(int approverUserId) {
		this.approverUserId = approverUserId;
	}

	public int getApproverRoleId() {
		return approverRoleId;
	}

	public void setApproverRoleId(int approverRoleId) {
		this.approverRoleId = approverRoleId;
	}

	public int getDelegatedFromUserId() {
		return delegatedFromUserId;
	}

	public void setDelegatedFromUserId(int delegatedFromUserId) {
		this.delegatedFromUserId = delegatedFromUserId;
	}

	public String getReason() {
		return reason;
	}

	/** Non blocking problem, for example a level whose approver cannot be resolved. */
	public String getWarning() {
		return warning;
	}

	public void setWarning(String warning) {
		this.warning = warning;
	}

	/** True when somebody is definitely responsible for this step. */
	public boolean isAssignable() {
		return approverUserId > 0 || approverRoleId > 0;
	}

	/** True when a delegation replaced the configured approver. */
	public boolean isDelegated() {
		return delegatedFromUserId > 0;
	}

	/** Label used by the workflow diagram and the test result. */
	public String getDisplayName() {
		String n = level == null || level.getLevelName() == null
				? "Level " + getLevelNo()
				: level.getLevelName();
		return n;
	}
}
