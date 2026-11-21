package org.mycompany.requisition.approval.config;

/**
 * What the approver is expected to do at a level.
 * <p>
 * Stored in {@code XX_PROCESS_LEVEL.ApprovalType}. Only {@link #APPROVE} lets the
 * transaction advance on its own; the other types are advisory and are reported
 * in the history but never block the chain. That keeps a "noted by" step from
 * stalling a purchase order.
 */
public enum ApprovalType {

	/** Blocking approval, the level must be approved to continue. */
	APPROVE("Approval", true),
	/** Review and comment, does not block. */
	REVIEW("Review", false),
	/** Verification of a specialised check, does not block. */
	VERIFY("Verification", false),
	/** Information only, the user is notified and nothing is required. */
	NOTED("Noted", false),
	/** Formal sign off, blocking, kept separate from a plain approval. */
	SIGN("Sign Off", true);

	private final String label;
	private final boolean blocking;

	private ApprovalType(String label, boolean blocking) {
		this.label = label;
		this.blocking = blocking;
	}

	public String getLabel() {
		return label;
	}

	/** True when the transaction cannot continue until this level is approved. */
	public boolean isBlocking() {
		return blocking;
	}

	/** Resolves a stored code, defaulting to the blocking approval type. */
	public static ApprovalType of(String code) {
		if (code == null)
			return APPROVE;
		for (ApprovalType t : values())
			if (t.name().equalsIgnoreCase(code.trim()))
				return t;
		return APPROVE;
	}
}
