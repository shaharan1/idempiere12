package org.mycompany.requisition.approval.config;

/**
 * Lifecycle of a process version.
 * <p>
 * Stored in {@code XX_PROCESS_VERSION.Status}. Only an {@link #ACTIVE} version
 * that is also flagged {@code IsCurrent} is used for new transactions; existing
 * transactions keep pointing at the version they were created against, so
 * activating a new version never rewrites history.
 */
public enum VersionStatus {

	/** Being configured, not usable by business transactions yet. */
	DRAFT("Draft"),
	/** Usable for new transactions. */
	ACTIVE("Active"),
	/** Kept for historical transactions but no longer offered. */
	INACTIVE("Inactive");

	private final String label;

	private VersionStatus(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	/** Resolves a stored code, defaulting to draft so nothing runs unreviewed. */
	public static VersionStatus of(String code) {
		if (code == null)
			return DRAFT;
		for (VersionStatus s : values())
			if (s.name().equalsIgnoreCase(code.trim()))
				return s;
		return DRAFT;
	}
}
