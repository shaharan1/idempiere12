package org.mycompany.requisition.approval.model;

/**
 * Status of the whole approval transaction.
 * <p>
 * Stored in {@code XX_PROCESS_TRANSACTION.Status}. The flow is
 * {@code CREATED → SUBMITTED → PENDING → APPROVED}, with {@link #REJECTED} and
 * {@link #RETURNED} as the two exits that send the document back to the
 * requester, who can resubmit and continue on the same transaction so the
 * history stays complete.
 */
public enum ApprovalStatus {

	/** Header written, not yet submitted. */
	CREATED("Created"),
	/** Submitted and waiting for the engine to open the first level. */
	SUBMITTED("Submitted"),
	/** At least one level is open. */
	PENDING("Pending"),
	/** All required levels approved. */
	APPROVED("Approved"),
	/** A level rejected the document. */
	REJECTED("Rejected"),
	/** Returned to the requester for correction. */
	RETURNED("Returned"),
	/** Withdrawn by the requester before completion. */
	CANCELLED("Cancelled");

	private final String label;

	private ApprovalStatus(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	/** True while the document still has to be worked on. */
	public boolean isOpen() {
		return this == CREATED || this == SUBMITTED || this == PENDING || this == RETURNED;
	}

	/** True once the chain has reached a final decision. */
	public boolean isFinal() {
		return this == APPROVED || this == REJECTED || this == CANCELLED;
	}

	/** Resolves a stored code, defaulting to created. */
	public static ApprovalStatus of(String code) {
		if (code == null)
			return CREATED;
		for (ApprovalStatus s : values())
			if (s.name().equalsIgnoreCase(code.trim()))
				return s;
		return CREATED;
	}
}
