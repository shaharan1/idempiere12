package org.mycompany.requisition.approval.config;

/**
 * A group of business processes, for example Purchasing or Human Resources.
 * <p>
 * Maps to {@code XX_PROCESS_CATEGORY}. Pure configuration: this class carries no
 * database access and is safe to build in a test.
 */
public class ProcessCategory {

	private int id;
	private String value;
	private String name;
	private String description;

	public ProcessCategory() {
	}

	/** Convenience constructor for a not yet persisted category. */
	public ProcessCategory(String value, String name) {
		this.value = value;
		this.name = name;
	}

	/** XX_PROCESS_CATEGORY_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** Machine code, unique per tenant. */
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	/** Display name shown in the process dropdown. */
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/** Label used by the UI: name when present, otherwise the code. */
	public String getDisplayName() {
		return name != null && name.trim().length() > 0 ? name : value;
	}

	@Override
	public String toString() {
		return getDisplayName();
	}
}
