package org.mycompany.requisition.approval.model;

/**
 * Status of an approval level instance.
 * <p>
 * Stored in {@code XX_APPROVAL_STEP.Status}. A level is created as
 * {@link #WAITING} for every required level, but only the level(s) the workflow
 * currently needs become {@link #PENDING}. That distinction is what makes the
 * workflow diagram able to show waiting levels separately from the active one.
 */
public enum StepStatus {

	/** Created but not yet opened by the workflow. */
	WAITING("Waiting"),
	/** Open, waiting for the approver to act. */
	PENDING("Pending", true),
	/** Approved by this level. */
	APPROVED("Approved"),
	/** This level rejected the document, the transaction stops here. */
	REJECTED("Rejected"),
	/** Returned to the requester for correction and resubmission. */
	RETURNED("Returned"),
	/** The approval rule did not require this level for this document. */
	SKIPPED("Skipped"),
	/** Another approver acted, for example through a delegation. */
	DELEGATED("Delegated", true);

	private final String label;
	private final boolean open;

	private StepStatus(String label) {
		this(label, false);
	}

	private StepStatus(String label, boolean open) {
		this.label = label;
		this.open = open;
	}

	public String getLabel() {
		return label;
	}

	/**
	 * True while the step is the one being worked on right now, which is what the
	 * dashboard and the authorisation check mean by open.
	 * <p>
	 * {@link #WAITING} is deliberately not open: it has not had its turn yet. It
	 * is also not finished, so a chain that only has waiting levels left is
	 * neither being worked on nor settled.
	 */
	public boolean isOpen() {
		return open;
	}

	/** Resolves a stored code, defaulting to waiting. */
	public static StepStatus of(String code) {
		if (code == null)
			return WAITING;
		for (StepStatus s : values())
			if (s.name().equalsIgnoreCase(code.trim()))
				return s;
		return WAITING;
	}
}
