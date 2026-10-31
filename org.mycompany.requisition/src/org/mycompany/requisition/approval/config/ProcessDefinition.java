package org.mycompany.requisition.approval.config;

/**
 * One business process the approval framework can govern, for example Purchase
 * Requisition or Sales Order.
 * <p>
 * Maps to {@code XX_PROCESS}. The process is intentionally only a pointer: it
 * names the document table and the window to zoom into, but never stores the
 * approval chain itself. The chain lives on the
 * {@link ProcessVersion current version}, so the process row stays stable while
 * the workflow is reworked.
 * <p>
 * No identifier is hard coded here. {@link #getTableId()}, {@link #getWindowId()}
 * and {@link #getWorkflowId()} are filled from the dictionary rows the
 * administrator links, which is why the target may be any registered table,
 * including custom ones.
 */
public class ProcessDefinition {

	private int id;
	private int categoryId;
	private String value;
	private String name;
	private String description;
	private String processType;

	private int tableId;
	private int windowId;
	private int workflowId;
	private int documentTypeId;

	/** XX_PROCESS_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** XX_PROCESS_CATEGORY_ID. */
	public int getCategoryId() {
		return categoryId;
	}

	public void setCategoryId(int categoryId) {
		this.categoryId = categoryId;
	}

	/** Machine code of the process, unique per tenant. */
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

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

	/** Free classification such as Transaction or Report. */
	public String getProcessType() {
		return processType;
	}

	public void setProcessType(String processType) {
		this.processType = processType;
	}

	/** AD_Table_ID of the document this process governs, 0 when not linked. */
	public int getTableId() {
		return tableId;
	}

	public void setTableId(int tableId) {
		this.tableId = tableId;
	}

	/** AD_Window_ID to zoom into, 0 when not linked. */
	public int getWindowId() {
		return windowId;
	}

	public void setWindowId(int windowId) {
		this.windowId = windowId;
	}

	/**
	 * AD_Workflow_ID of a native iDempiere workflow, 0 when the custom engine is
	 * used alone. Only a cross reference: the engine keeps authority over the
	 * chain so that SLA, delegation and audit are not lost.
	 */
	public int getWorkflowId() {
		return workflowId;
	}

	public void setWorkflowId(int workflowId) {
		this.workflowId = workflowId;
	}

	/** C_DocType_ID of the document type, 0 when the process is not typed. */
	public int getDocumentTypeId() {
		return documentTypeId;
	}

	public void setDocumentTypeId(int documentTypeId) {
		this.documentTypeId = documentTypeId;
	}

	/** True when the target document table is known. */
	public boolean hasTargetTable() {
		return tableId > 0;
	}

	/** Label used by the UI: "Name (VALUE)" as shown in the design. */
	public String getDisplayName() {
		if (name == null || name.trim().length() == 0)
			return value;
		if (value == null || value.trim().length() == 0)
			return name;
		return name + " (" + value + ")";
	}

	@Override
	public String toString() {
		return getDisplayName();
	}
}
