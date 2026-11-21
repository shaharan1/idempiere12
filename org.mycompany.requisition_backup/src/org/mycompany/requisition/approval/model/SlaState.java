package org.mycompany.requisition.approval.model;

/**
 * Derived SLA condition of an open step.
 * <p>
 * Stored in {@code XX_APPROVAL_STEP.SLAState}. The value is never trusted from
 * the record, it is recomputed from the due date by
 * {@code SlaMonitor} every time the step is read, so a state can never drift
 * away from the clock.
 */
public enum SlaState {

	/** Well inside the response time. */
	NORMAL("Normal"),
	/** Less than the warning window is left. */
	WARNING("Warning"),
	/** The due date has passed. */
	OVERDUE("Overdue"),
	/** The due date has passed and the escalation target has been notified. */
	ESCALATED("Escalated"),
	/** No SLA configured for this level. */
	NONE("None");

	private final String label;

	private SlaState(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	/** True when the step needs attention on the dashboard. */
	public boolean needsAttention() {
		return this == WARNING || this == OVERDUE || this == ESCALATED;
	}

	/** Resolves a stored code, defaulting to none. */
	public static SlaState of(String code) {
		if (code == null)
			return NONE;
		for (SlaState s : values())
			if (s.name().equalsIgnoreCase(code.trim()))
				return s;
		return NONE;
	}
}
