package org.mycompany.requisition.approval.config;

/**
 * How the required levels of a workflow are opened.
 * <p>
 * Stored in {@code XX_PROCESS_WORKFLOW.WorkflowType}.
 */
public enum WorkflowType {

	/** One level at a time, the next opens when the previous is approved. */
	SEQUENTIAL("Sequential"),
	/** All required levels of the current stage open together. */
	PARALLEL("Parallel");

	private final String label;

	private WorkflowType(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	/** Resolves a stored code, defaulting to the safer sequential chain. */
	public static WorkflowType of(String code) {
		if (code == null)
			return SEQUENTIAL;
		for (WorkflowType t : values())
			if (t.name().equalsIgnoreCase(code.trim()))
				return t;
		return SEQUENTIAL;
	}
}
