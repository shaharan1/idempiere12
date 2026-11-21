package org.mycompany.requisition.approval.config;

import java.sql.Timestamp;

/**
 * An effective dated version of a {@link ProcessDefinition}.
 * <p>
 * Maps to {@code XX_PROCESS_VERSION}. This is the unit that the rest of the
 * configuration hangs from: a workflow, its levels and its rules all belong to a
 * version, never to the process directly.
 * <p>
 * That indirection is what makes the framework safe to reconfigure. A document
 * that was submitted last month keeps a pointer to the version that was current
 * then, so reworking the approval chain today cannot rewrite what happened
 * yesterday. Activating a new version only changes the version used for new
 * submissions.
 */
public class ProcessVersion {

	private int id;
	private int processId;
	private String version;
	private String name;
	private Timestamp effectiveFrom;
	private Timestamp effectiveTo;
	private boolean current;
	private VersionStatus status = VersionStatus.DRAFT;
	private String description;

	/** XX_PROCESS_VERSION_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** XX_PROCESS_ID this version belongs to. */
	public int getProcessId() {
		return processId;
	}

	public void setProcessId(int processId) {
		this.processId = processId;
	}

	/** Version label shown to the user, for example "1.0". */
	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	/** First day the version may be used, null means "immediately". */
	public Timestamp getEffectiveFrom() {
		return effectiveFrom;
	}

	public void setEffectiveFrom(Timestamp effectiveFrom) {
		this.effectiveFrom = effectiveFrom;
	}

	/** Last day the version may be used, null means "open ended". */
	public Timestamp getEffectiveTo() {
		return effectiveTo;
	}

	public void setEffectiveTo(Timestamp effectiveTo) {
		this.effectiveTo = effectiveTo;
	}

	/** True when this is the version new submissions use. */
	public boolean isCurrent() {
		return current;
	}

	public void setCurrent(boolean current) {
		this.current = current;
	}

	public VersionStatus getStatus() {
		return status;
	}

	public void setStatus(VersionStatus status) {
		this.status = status == null ? VersionStatus.DRAFT : status;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * True when this version may be used for a new transaction: it has to be
	 * flagged current and be active. Draft versions stay visible in the
	 * configuration UI but are never used by the engine.
	 */
	public boolean isUsableForNewTransactions() {
		return current && status == VersionStatus.ACTIVE;
	}

	/**
	 * True when the given moment lies inside the effective period. A null bound
	 * means unbounded on that side.
	 */
	public boolean isEffectiveAt(Timestamp moment) {
		if (effectiveFrom != null && moment != null && moment.before(effectiveFrom))
			return false;
		if (effectiveTo != null && moment != null && moment.after(effectiveTo))
			return false;
		return true;
	}

	/** Label used by the UI, for example "1.0 - Purchase Requisition Approval". */
	public String getDisplayName() {
		String v = version == null || version.trim().length() == 0 ? "" : version;
		String n = name == null || name.trim().length() == 0 ? "" : name;
		if (v.length() == 0)
			return n;
		if (n.length() == 0)
			return v;
		return v + " - " + n;
	}

	@Override
	public String toString() {
		return getDisplayName();
	}
}
