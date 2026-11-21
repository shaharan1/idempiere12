package org.mycompany.requisition.approval.config;

/**
 * Who is asked to approve at a level.
 * <p>
 * The code of every constant is exactly what is stored in
 * {@code XX_PROCESS_LEVEL.ResponsibleType}, so this enum is the single place
 * where the vocabulary is defined. The UI shows only the responsible field that
 * matches the selected type, for example a user field for {@link #USER} and a
 * role field for {@link #ROLE}.
 */
public enum ResponsibleType {

	/** One named user. Requires XX_PROCESS_LEVEL.AD_User_ID. */
	USER("User", true),
	/** Everyone holding the role, first in hierarchy order. Requires AD_Role_ID. */
	ROLE("Role", true),
	/**
	 * The responsible party of a department. Requires HR_Department_ID.
	 * <p>
	 * A fixed assignee rather than a dynamic one, because the department is stored
	 * with the level and does not depend on the document. Who inside that
	 * department approves is resolved when a document arrives.
	 */
	DEPARTMENT("Department", true),
	/** The head of the organisation or department. Resolved at run time. */
	SUPERVISOR("Supervisor", false),
	/** Resolved from a business partner or document field, not stored. */
	DYNAMIC_USER("Dynamic User", false),
	/** Resolved from a role of the requesting user's context. */
	DYNAMIC_ROLE("Dynamic Role", false);

	private final String label;
	private final boolean fixedAssignee;

	private ResponsibleType(String label, boolean fixedAssignee) {
		this.label = label;
		this.fixedAssignee = fixedAssignee;
	}

	/** Display label for the configuration UI. */
	public String getLabel() {
		return label;
	}

	/**
	 * True when the approver is stored with the level and therefore known before
	 * a document exists. Dynamic types are resolved per transaction.
	 */
	public boolean isFixedAssignee() {
		return fixedAssignee;
	}

	/**
	 * The stored field this type needs, as the name of the level column.
	 * <p>
	 * The UI uses it to decide which single field to show, and the level service
	 * uses it to decide which field to store. Having it here means the two can never
	 * disagree about, say, whether a department is a fixed assignee.
	 *
	 * @return the level column name, or null when nothing is stored
	 */
	public String storedColumn() {
		switch (this) {
		case USER:
			return "AD_User_ID";
		case ROLE:
			return "AD_Role_ID";
		case DEPARTMENT:
			return "HR_Department_ID";
		default:
			return null;
		}
	}

	/**
	 * Resolves a stored code.
	 *
	 * @param code value read from the database, may be null
	 * @return the matching constant, or {@link #USER} when the code is unknown so
	 *         that a missing value never breaks a running workflow
	 */
	public static ResponsibleType of(String code) {
		if (code == null)
			return USER;
		for (ResponsibleType t : values())
			if (t.name().equalsIgnoreCase(code.trim()))
				return t;
		return USER;
	}
}
