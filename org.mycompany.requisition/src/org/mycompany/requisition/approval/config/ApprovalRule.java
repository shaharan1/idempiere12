package org.mycompany.requisition.approval.config;

import java.math.BigDecimal;

/**
 * A condition that decides which approval levels a document needs.
 * <p>
 * Maps to {@code XX_PROCESS_APPROVAL_RULE}. Rules are evaluated by ascending
 * {@link #getPriority()}, and the first rule whose conditions all match wins.
 * A rule with only an amount band, for example "50,001 to 500,000", is therefore
 * all that is needed for the common case.
 * <p>
 * Every optional condition is null when it does not apply, which means "any".
 * Nothing is hard coded: which condition columns exist is driven by the rule
 * fields below, and a new condition type needs a new column rather than a code
 * change.
 */
public class ApprovalRule {

	private int id;
	private int processVersionId;

	private String ruleName;
	private String condition = "AMOUNT";

	private BigDecimal minimumAmount;
	private BigDecimal maximumAmount;

	private int orgId;
	private int userId;
	private int roleId;
	private int warehouseId;
	private int productCategoryId;
	private int businessPartnerId;
	private int projectId;
	private int documentTypeId;

	/** Comma separated list of level numbers this rule selects, for example "1,2". */
	private String requiredLevels;

	private int priority = 10;
	private String description;

	/** XX_PROCESS_APPROVAL_RULE_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** XX_PROCESS_VERSION_ID this rule belongs to. */
	public int getProcessVersionId() {
		return processVersionId;
	}

	public void setProcessVersionId(int processVersionId) {
		this.processVersionId = processVersionId;
	}

	/** Label shown in the rule list. */
	public String getRuleName() {
		return ruleName;
	}

	public void setRuleName(String ruleName) {
		this.ruleName = ruleName;
	}

	/** Condition kind, AMOUNT by default. */
	public String getCondition() {
		return condition;
	}

	public void setCondition(String condition) {
		this.condition = condition;
	}

	/** Inclusive lower bound, null means no lower bound. */
	public BigDecimal getMinimumAmount() {
		return minimumAmount;
	}

	public void setMinimumAmount(BigDecimal minimumAmount) {
		this.minimumAmount = minimumAmount;
	}

	/** Inclusive upper bound, null means no upper bound, so "500,001 and above". */
	public BigDecimal getMaximumAmount() {
		return maximumAmount;
	}

	public void setMaximumAmount(BigDecimal maximumAmount) {
		this.maximumAmount = maximumAmount;
	}

	/** AD_Org_ID condition, 0 means any organisation. */
	public int getOrgId() {
		return orgId;
	}

	public void setOrgId(int orgId) {
		this.orgId = orgId;
	}

	/** AD_User_ID condition, 0 means any user. */
	public int getUserId() {
		return userId;
	}

	public void setUserId(int userId) {
		this.userId = userId;
	}

	/** AD_Role_ID condition, 0 means any role. */
	public int getRoleId() {
		return roleId;
	}

	public void setRoleId(int roleId) {
		this.roleId = roleId;
	}

	/** M_Warehouse_ID condition, 0 means any warehouse. */
	public int getWarehouseId() {
		return warehouseId;
	}

	public void setWarehouseId(int warehouseId) {
		this.warehouseId = warehouseId;
	}

	/** M_Product_Category_ID condition, 0 means any category. */
	public int getProductCategoryId() {
		return productCategoryId;
	}

	public void setProductCategoryId(int productCategoryId) {
		this.productCategoryId = productCategoryId;
	}

	/** C_BP_ID condition, 0 means any business partner. */
	public int getBusinessPartnerId() {
		return businessPartnerId;
	}

	public void setBusinessPartnerId(int businessPartnerId) {
		this.businessPartnerId = businessPartnerId;
	}

	/** C_Project_ID condition, 0 means any project. */
	public int getProjectId() {
		return projectId;
	}

	public void setProjectId(int projectId) {
		this.projectId = projectId;
	}

	/** C_DocType_ID condition, 0 means any document type. */
	public int getDocumentTypeId() {
		return documentTypeId;
	}

	public void setDocumentTypeId(int documentTypeId) {
		this.documentTypeId = documentTypeId;
	}

	/** Comma separated level numbers, for example "1,2,3". */
	public String getRequiredLevels() {
		return requiredLevels;
	}

	public void setRequiredLevels(String requiredLevels) {
		this.requiredLevels = requiredLevels;
	}

	/** Lower value is evaluated first. */
	public int getPriority() {
		return priority;
	}

	public void setPriority(int priority) {
		this.priority = priority;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * Amount test. An open bound of "500,001 and above" is expressed by leaving
	 * {@link #getMaximumAmount()} null.
	 */
	public boolean matchesAmount(BigDecimal amount) {
		if (minimumAmount == null && maximumAmount == null)
			return true;
		if (amount == null)
			return false;
		if (minimumAmount != null && amount.compareTo(minimumAmount) < 0)
			return false;
		if (maximumAmount != null && amount.compareTo(maximumAmount) > 0)
			return false;
		return true;
	}

	/**
	 * Generic reference test: a configured value has to match the document, an
	 * unset value (0 or null) matches everything.
	 *
	 * @param configured the value stored on the rule
	 * @param actual     the value read from the document
	 */
	private static boolean match(int configured, int actual) {
		return configured <= 0 || configured == actual;
	}

	/** True when every configured condition of this rule matches the document. */
	public boolean matches(int actualOrgId, int actualUserId, int actualRoleId, int actualWarehouseId,
			int actualProductCategoryId, int actualBusinessPartnerId, int actualProjectId,
			int actualDocumentTypeId, BigDecimal actualAmount) {

		if (!matchesAmount(actualAmount))
			return false;
		return match(orgId, actualOrgId)
				&& match(userId, actualUserId)
				&& match(roleId, actualRoleId)
				&& match(warehouseId, actualWarehouseId)
				&& match(productCategoryId, actualProductCategoryId)
				&& match(businessPartnerId, actualBusinessPartnerId)
				&& match(projectId, actualProjectId)
				&& match(documentTypeId, actualDocumentTypeId);
	}

	/** Human readable amount band, for example "50,001 - 500,000" or "500,001 and above". */
	public String getAmountBandLabel() {
		if (minimumAmount == null && maximumAmount == null)
			return "any amount";
		if (maximumAmount == null)
			return strip(minimumAmount) + " and above";
		if (minimumAmount == null)
			return "up to " + strip(maximumAmount);
		return strip(minimumAmount) + " - " + strip(maximumAmount);
	}

	/** Drops the decimals that BigDecimal keeps from the scale of the column. */
	private static String strip(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}

	@Override
	public String toString() {
		return ruleName != null ? ruleName : getAmountBandLabel();
	}
}
