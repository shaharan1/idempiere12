package org.mycompany.requisition.approval.config;

import java.sql.Timestamp;

/**
 * A temporary substitution of an approval responsibility.
 * <p>
 * Maps to {@code XX_PROCESS_DELEGATION}. A delegation never changes the level
 * itself, it only changes who receives the pending step while it is valid. That
 * keeps the audit trail meaningful: the history still records the original level
 * and the step records both the original approver and the delegate.
 * <p>
 * {@link #getLevelId()} is optional. When it is set the delegation applies only
 * to that one level, otherwise it covers every level of every process the user
 * approves.
 */
public class Delegation {

	private int id;
	private int userId;
	private int delegateUserId;
	private int levelId;
	private Timestamp validFrom;
	private Timestamp validTo;
	private String reason;

	/** XX_PROCESS_DELEGATION_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** AD_User_ID of the approver who steps away. */
	public int getUserId() {
		return userId;
	}

	public void setUserId(int userId) {
		this.userId = userId;
	}

	/** AD_User_ID who receives the approval. */
	public int getDelegateUserId() {
		return delegateUserId;
	}

	public void setDelegateUserId(int delegateUserId) {
		this.delegateUserId = delegateUserId;
	}

	/** XX_PROCESS_LEVEL_ID scope, 0 for "every level". */
	public int getLevelId() {
		return levelId;
	}

	public void setLevelId(int levelId) {
		this.levelId = levelId;
	}

	/** First day the delegation applies, null means immediately. */
	public Timestamp getValidFrom() {
		return validFrom;
	}

	public void setValidFrom(Timestamp validFrom) {
		this.validFrom = validFrom;
	}

	/** Last day the delegation applies, null means open ended. */
	public Timestamp getValidTo() {
		return validTo;
	}

	public void setValidTo(Timestamp validTo) {
		this.validTo = validTo;
	}

	/** Free text, for example "Annual Leave". */
	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	/** True when the delegation covers every level. */
	public boolean isGlobal() {
		return levelId <= 0;
	}

	/** True when the moment lies inside the delegation period. */
	public boolean isValidAt(Timestamp moment) {
		if (validFrom != null && moment != null && moment.before(validFrom))
			return false;
		if (validTo != null && moment != null && moment.after(validTo))
			return false;
		return true;
	}

	/**
	 * True when this delegation applies to the given level. A level scoped
	 * delegation only matches its own level, a global one matches all.
	 */
	public boolean appliesToLevel(int candidateLevelId) {
		return isGlobal() || levelId == candidateLevelId;
	}

	@Override
	public String toString() {
		return userId + " -> " + delegateUserId + (reason == null ? "" : " (" + reason + ")");
	}
}
