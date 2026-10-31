package org.mycompany.requisition.approval.config;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * One approval step of a workflow.
 * <p>
 * Maps to {@code XX_PROCESS_LEVEL}. The level number is what orders the chain,
 * so reordering levels in the configuration UI is only a change of this field
 * and never a change of identity.
 * <p>
 * Who approves is described by {@link ResponsibleType} plus the matching
 * reference column. Only the field that matches the type is meaningful, which is
 * exactly what the UI shows and hides:
 *
 * <pre>
 * USER          → AD_User_ID
 * ROLE          → AD_Role_ID
 * SUPERVISOR    → resolved from the requester's hierarchy
 * DYNAMIC_USER  → resolved from a business partner or document field
 * DYNAMIC_ROLE  → resolved from the requester's roles
 * </pre>
 *
 * SLA and escalation live on the level because they describe how long that one
 * step may take, not the workflow as a whole.
 */
public class ApprovalLevel {

	private int id;
	private int processVersionId;
	private int levelNo;

	private String levelName;
	private String approvalStage;
	private ResponsibleType responsibleType = ResponsibleType.USER;

	private int userId;
	private int roleId;
	private int approverOrgId;

	private ApprovalType approvalType = ApprovalType.APPROVE;
	private boolean required = true;

	private Integer slaHours;
	private Integer warningBeforeHours;
	private boolean escalationEnabled;
	private Integer escalationAfterHours;
	private int escalationUserId;
	private int escalationRoleId;

	/**
	 * Department this level belongs to, used when the responsible type is
	 * DEPARTMENT. Kept beside the other responsible fields rather than in a table of
	 * its own, because exactly one of them is ever set and a level cannot be in two
	 * departments.
	 */
	private int departmentId;

	/** Authority band of this level, independent of the process rules. */
	private BigDecimal lowerLimit;
	private BigDecimal upperLimit;

	/** True when this level is the one that finally releases the document. */
	private boolean finalApprover;

	/**
	 * Process and category this level belongs to.
	 * <p>
	 * These are mirrors of what {@code XX_PROCESS_VERSION_ID} already decides. They
	 * are stored because the native assignment window can only show real columns, and
	 * {@code LevelConfigService} writes them from the version, so they never have to be
	 * entered by hand.
	 */
	private int processId;
	private int processCategoryId;

	private String description;

	/** XX_PROCESS_LEVEL_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** XX_PROCESS_VERSION_ID this level belongs to. */
	public int getProcessVersionId() {
		return processVersionId;
	}

	public void setProcessVersionId(int processVersionId) {
		this.processVersionId = processVersionId;
	}

	/** Position in the chain, 1 based. */
	public int getLevelNo() {
		return levelNo;
	}

	public void setLevelNo(int levelNo) {
		this.levelNo = levelNo;
	}

	/** Short name, for example "Purchase Manager". */
	public String getLevelName() {
		return levelName;
	}

	public void setLevelName(String levelName) {
		this.levelName = levelName;
	}

	/** Longer description of what the approver is expected to check. */
	public String getApprovalStage() {
		return approvalStage;
	}

	public void setApprovalStage(String approvalStage) {
		this.approvalStage = approvalStage;
	}

	public ResponsibleType getResponsibleType() {
		return responsibleType;
	}

	public void setResponsibleType(ResponsibleType responsibleType) {
		this.responsibleType = responsibleType == null ? ResponsibleType.USER : responsibleType;
	}

	/** AD_User_ID, meaningful when the responsible type is USER. */
	public int getUserId() {
		return userId;
	}

	public void setUserId(int userId) {
		this.userId = userId;
	}

	/** AD_Role_ID, meaningful when the responsible type is ROLE. */
	public int getRoleId() {
		return roleId;
	}

	public void setRoleId(int roleId) {
		this.roleId = roleId;
	}

	/** AD_Org_ID used by the supervisor resolution. */
	public int getApproverOrgId() {
		return approverOrgId;
	}

	public void setApproverOrgId(int approverOrgId) {
		this.approverOrgId = approverOrgId;
	}

	public ApprovalType getApprovalType() {
		return approvalType;
	}

	public void setApprovalType(ApprovalType approvalType) {
		this.approvalType = approvalType == null ? ApprovalType.APPROVE : approvalType;
	}

	/** False when the level exists in the design but is skipped when no rule selects it. */
	public boolean isRequired() {
		return required;
	}

	public void setRequired(boolean required) {
		this.required = required;
	}

	/** Response time in hours, null when no SLA applies. */
	public Integer getSlaHours() {
		return slaHours;
	}

	public void setSlaHours(Integer slaHours) {
		this.slaHours = slaHours;
	}

	/** Hours before the due date the approver gets a warning. */
	public Integer getWarningBeforeHours() {
		return warningBeforeHours;
	}

	public void setWarningBeforeHours(Integer warningBeforeHours) {
		this.warningBeforeHours = warningBeforeHours;
	}

	public boolean isEscalationEnabled() {
		return escalationEnabled;
	}

	public void setEscalationEnabled(boolean escalationEnabled) {
		this.escalationEnabled = escalationEnabled;
	}

	/** Hours after opening when the step is escalated. */
	public Integer getEscalationAfterHours() {
		return escalationAfterHours;
	}

	public void setEscalationAfterHours(Integer escalationAfterHours) {
		this.escalationAfterHours = escalationAfterHours;
	}

	/** AD_User_ID the step is escalated to. */
	public int getEscalationUserId() {
		return escalationUserId;
	}

	public void setEscalationUserId(int escalationUserId) {
		this.escalationUserId = escalationUserId;
	}

	/** AD_Role_ID the step is escalated to. */
	public int getEscalationRoleId() {
		return escalationRoleId;
	}

	public void setEscalationRoleId(int escalationRoleId) {
		this.escalationRoleId = escalationRoleId;
	}

	/** HR_Department_ID this level belongs to, 0 when it is not by department. */
	public int getDepartmentId() {
		return departmentId;
	}

	public void setDepartmentId(int departmentId) {
		this.departmentId = departmentId;
	}

	/** Lowest document amount this level may approve, null for an open band. */
	public BigDecimal getLowerLimit() {
		return lowerLimit;
	}

	public void setLowerLimit(BigDecimal lowerLimit) {
		this.lowerLimit = lowerLimit;
	}

	/** Highest document amount this level may approve, null for an open band. */
	public BigDecimal getUpperLimit() {
		return upperLimit;
	}

	public void setUpperLimit(BigDecimal upperLimit) {
		this.upperLimit = upperLimit;
	}

	/** True when this level finally releases the document. */
	public boolean isFinalApprover() {
		return finalApprover;
	}

	public void setFinalApprover(boolean finalApprover) {
		this.finalApprover = finalApprover;
	}

	/** XX_Process_ID this level belongs to, 0 when unknown. */
	public int getProcessId() {
		return processId;
	}

	public void setProcessId(int processId) {
		this.processId = processId;
	}

	/** XX_Process_Category_ID this level belongs to, 0 when unknown. */
	public int getProcessCategoryId() {
		return processCategoryId;
	}

	public void setProcessCategoryId(int processCategoryId) {
		this.processCategoryId = processCategoryId;
	}

	/**
	 * True when the stored band is a closed range, that is when both limits are set.
	 */
	public boolean hasClosedBand() {
		return lowerLimit != null && upperLimit != null;
	}

	/**
	 * Whether a document amount falls inside this level's authority band.
	 *
	 * @param amount the document amount, null when unknown
	 * @return true when the level is responsible for that amount
	 */
	public boolean coversAmount(BigDecimal amount) {
		if (amount == null)
			return true;
		if (lowerLimit != null && amount.compareTo(lowerLimit) < 0)
			return false;
		if (upperLimit != null && amount.compareTo(upperLimit) > 0)
			return false;
		return true;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/** True when at least one response time is configured. */
	public boolean hasSla() {
		return slaHours != null && slaHours.intValue() > 0;
	}

	/** True when escalation has a target and a delay. */
	public boolean hasEscalation() {
		return escalationEnabled && escalationAfterHours != null && escalationAfterHours.intValue() > 0
				&& (escalationUserId > 0 || escalationRoleId > 0);
	}

	/**
	 * Due date for a step that opens at the given moment, or null when no SLA is
	 * configured.
	 */
	public Timestamp getDueDate(Timestamp openedAt) {
		if (!hasSla() || openedAt == null)
			return null;
		long millis = slaHours.longValue() * 60L * 60L * 1000L;
		return new Timestamp(openedAt.getTime() + millis);
	}

	/**
	 * Configured approver of this level for a fixed responsible type, or 0 when
	 * the approver has to be resolved at run time.
	 */
public int getFixedApproverId() {
   		if (responsibleType == ResponsibleType.USER)
   			return userId;
   		if (responsibleType == ResponsibleType.ROLE)
   			return roleId;
   		return 0;
  	}

  	/**
  	 * True when the field matching the responsible type carries a value.
  	 * <p>
  	 * Written in terms of {@link ResponsibleType#storedColumn()} rather than by
  	 * checking a user and a role id, because a department is a third stored field
  	 * and a check that only knew about the first two would silently treat every
  	 * department level as unconfigured.
  	 *
  	 * @return true when the level names who or what is responsible
  	 */
  	public boolean hasStoredResponsible() {
  		switch (responsibleType == null ? ResponsibleType.USER : responsibleType) {
  		case USER:
  			return userId > 0;
  		case ROLE:
  			return roleId > 0;
  		case DEPARTMENT:
  			return departmentId > 0;
  		default:
  			// a dynamic type is resolved per document, so nothing is expected here
  			return true;
  		}
  	}

	/** Label used by the workflow diagram and the level grid. */
	public String getDisplayName() {
		if (levelName != null && levelName.trim().length() > 0)
			return "Level " + levelNo + " - " + levelName;
		return "Level " + levelNo;
	}

	/** Only for the rule engine: a level must be required and blocking. */
	public boolean isBlocking() {
		return required && approvalType.isBlocking();
	}

	@Override
	public String toString() {
		return getDisplayName();
	}
}
