package org.mycompany.requisition.approval.model;

/**
 * An action an approver can perform on a level.
 * <p>
 * Stored in {@code XX_APPROVAL_HISTORY.Action} and mirrored on
 * {@code XX_PROCESS_TRANSACTION.LastAction}. The engine records exactly one
 * history row per action and never updates an existing row, so the history is a
 * true audit trail.
 */
public enum ApprovalAction {

	/** Level approved. Comment optional. */
	APPROVE("Approve"),
	/** Level rejected the document. Comment mandatory, the chain stops. */
	REJECT("Reject"),
	/** Sent back to the requester. Comment mandatory, the chain stops. */
	RETURN("Return"),
	/** Approver handed the responsibility to somebody else. */
	DELEGATE("Delegate"),
	/** The rule engine decided this level was not needed. */
	SKIP("Skip"),
	/** Document submitted and the first level opened. */
	SUBMIT("Submit"),
	/** Rejected document submitted again by the requester. */
	RESUBMIT("Resubmit");

	private final String label;

	private ApprovalAction(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	/** True when the action ends the current chain. */
	public boolean isTerminal() {
		return this == REJECT || this == RETURN;
	}

	/** True when a comment must be supplied. */
	public boolean isCommentMandatory() {
		return this == REJECT || this == RETURN;
	}

	/** Resolves a stored code, defaulting to approve. */
	public static ApprovalAction of(String code) {
		if (code == null)
			return APPROVE;
		for (ApprovalAction a : values())
			if (a.name().equalsIgnoreCase(code.trim()))
				return a;
		return APPROVE;
	}
}
