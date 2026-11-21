package org.mycompany.requisition.approval.model;

import java.math.BigDecimal;

/**
 * The facts about one business document that approval rules are tested against.
 * <p>
 * Deliberately a flat value object with no reference to any process: the rule
 * engine only needs these values, so the same resolution code works for a
 * purchase requisition, a sales order or a custom document without knowing
 * anything about them.
 * <p>
 * Every reference is 0 when it does not apply, and the engine reads that as
 * "any". Only the amount is really mandatory, because the common case is an
 * amount band.
 */
public class DocumentContext {

	private int tableId;
	private int recordId;
	private String documentNo;

	private BigDecimal amount;

	private int orgId;
	private int userId;
	private int roleId;
	private int warehouseId;
	private int productCategoryId;
	private int businessPartnerId;
	private int projectId;
	private int documentTypeId;

	private int requestedBy;

	public DocumentContext() {
	}

	/** Convenience constructor for the plain amount case. */
	public DocumentContext(BigDecimal amount, int orgId, int requestedBy) {
		this.amount = amount;
		this.orgId = orgId;
		this.requestedBy = requestedBy;
	}

	/** AD_Table_ID of the document. */
	public int getTableId() {
		return tableId;
	}

	public void setTableId(int tableId) {
		this.tableId = tableId;
	}

	/** Primary key of the document inside that table. */
	public int getRecordId() {
		return recordId;
	}

	public void setRecordId(int recordId) {
		this.recordId = recordId;
	}

	public String getDocumentNo() {
		return documentNo;
	}

	public void setDocumentNo(String documentNo) {
		this.documentNo = documentNo;
	}

	/** Amount the rules are tested on, normally the gross document amount. */
	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	/** AD_Org_ID of the document. */
	public int getOrgId() {
		return orgId;
	}

	public void setOrgId(int orgId) {
		this.orgId = orgId;
	}

	/** AD_User_ID of the requester. */
	public int getUserId() {
		return userId;
	}

	public void setUserId(int userId) {
		this.userId = userId;
	}

	/** AD_Role_ID of the requester, used by role based rules. */
	public int getRoleId() {
		return roleId;
	}

	public void setRoleId(int roleId) {
		this.roleId = roleId;
	}

	public int getWarehouseId() {
		return warehouseId;
	}

	public void setWarehouseId(int warehouseId) {
		this.warehouseId = warehouseId;
	}

	public int getProductCategoryId() {
		return productCategoryId;
	}

	public void setProductCategoryId(int productCategoryId) {
		this.productCategoryId = productCategoryId;
	}

	public int getBusinessPartnerId() {
		return businessPartnerId;
	}

	public void setBusinessPartnerId(int businessPartnerId) {
		this.businessPartnerId = businessPartnerId;
	}

	public int getProjectId() {
		return projectId;
	}

	public void setProjectId(int projectId) {
		this.projectId = projectId;
	}

	public int getDocumentTypeId() {
		return documentTypeId;
	}

	public void setDocumentTypeId(int documentTypeId) {
		this.documentTypeId = documentTypeId;
	}

	/** AD_User_ID who submitted the document, not necessarily a rule condition. */
	public int getRequestedBy() {
		return requestedBy;
	}

	public void setRequestedBy(int requestedBy) {
		this.requestedBy = requestedBy;
	}

	/** Label used by the dashboard and the test result. */
	public String getDisplayName() {
		if (documentNo != null && documentNo.trim().length() > 0)
			return documentNo;
		if (tableId > 0)
			return "record " + tableId + "/" + recordId;
		return "document";
	}
}
