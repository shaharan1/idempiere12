package org.mycompany.requisition.approval.config;

/**
 * The workflow header of a process version.
 * <p>
 * Maps to {@code XX_PROCESS_WORKFLOW}. It only decides how the levels of
 * {@link ApprovalLevel} are opened and whether an approval is needed at all; the
 * levels themselves are the real content.
 */
public class WorkflowDefinition {

	private int id;
	private int processVersionId;
	private String value;
	private String name;
	private WorkflowType workflowType = WorkflowType.SEQUENTIAL;
	private boolean approvalRequired = true;
	private boolean autoStart = true;
	private String description;

	public WorkflowDefinition() {
	}

	/**
	 * @param processVersionId the version this workflow belongs to
	 * @param name             display name
	 * @param workflowType     sequential or parallel
	 */
	public WorkflowDefinition(int processVersionId, String name, WorkflowType workflowType) {
		this.processVersionId = processVersionId;
		this.name = name;
		this.workflowType = workflowType == null ? WorkflowType.SEQUENTIAL : workflowType;
	}

	/** XX_PROCESS_WORKFLOW_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** XX_PROCESS_VERSION_ID this workflow belongs to. */
	public int getProcessVersionId() {
		return processVersionId;
	}

	public void setProcessVersionId(int processVersionId) {
		this.processVersionId = processVersionId;
	}

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

	/** How the required levels are opened. */
	public WorkflowType getWorkflowType() {
		return workflowType;
	}

	public void setWorkflowType(WorkflowType workflowType) {
		this.workflowType = workflowType == null ? WorkflowType.SEQUENTIAL : workflowType;
	}

	/**
	 * False when the document is only registered and reported on without asking
	 * anybody for approval.
	 */
	public boolean isApprovalRequired() {
		return approvalRequired;
	}

	public void setApprovalRequired(boolean approvalRequired) {
		this.approvalRequired = approvalRequired;
	}

	/** True when the workflow opens by itself as soon as the document is saved. */
	public boolean isAutoStart() {
		return autoStart;
	}

	public void setAutoStart(boolean autoStart) {
		this.autoStart = autoStart;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@Override
	public String toString() {
		return name == null ? value : name;
	}
}
