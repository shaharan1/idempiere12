package org.mycompany.requisition.model;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * One document as the approval grid shows it.
 */
public class ApprovalDocument {

	private int serialNo;
	private int id;
	private String documentNo;
	private int docTypeId;
	private String docTypeName;
	private int orgId;
	private String orgName;
	private Timestamp receiveDate;
	private Timestamp created;
	private int createdBy;
	private String preparedBy;
	private String docStatus;
	private BigDecimal amount;
	private String salesType;
	private String businessPartner;
	private boolean finalApprover;

	/** Position in the grid, 1 based. */
	public int getSerialNo() {
		return serialNo;
	}

	public void setSerialNo(int serialNo) {
		this.serialNo = serialNo;
	}

	/** Primary key in the document table. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getDocumentNo() {
		return documentNo;
	}

	public void setDocumentNo(String documentNo) {
		this.documentNo = documentNo;
	}

	public int getDocTypeId() {
		return docTypeId;
	}

	public void setDocTypeId(int docTypeId) {
		this.docTypeId = docTypeId;
	}

	public String getDocTypeName() {
		return docTypeName;
	}

	public void setDocTypeName(String docTypeName) {
		this.docTypeName = docTypeName;
	}

	public int getOrgId() {
		return orgId;
	}

	public void setOrgId(int orgId) {
		this.orgId = orgId;
	}

	public String getOrgName() {
		return orgName;
	}

	public void setOrgName(String orgName) {
		this.orgName = orgName;
	}

	public Timestamp getReceiveDate() {
		return receiveDate;
	}

	public void setReceiveDate(Timestamp receiveDate) {
		this.receiveDate = receiveDate;
	}

	public Timestamp getCreated() {
		return created;
	}

	public void setCreated(Timestamp created) {
		this.created = created;
	}

	public int getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(int createdBy) {
		this.createdBy = createdBy;
	}

	public String getPreparedBy() {
		return preparedBy;
	}

	public void setPreparedBy(String preparedBy) {
		this.preparedBy = preparedBy;
	}

	public String getDocStatus() {
		return docStatus;
	}

	public void setDocStatus(String docStatus) {
		this.docStatus = docStatus;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public String getSalesType() {
		return salesType;
	}

	public void setSalesType(String salesType) {
		this.salesType = salesType;
	}

	public String getBusinessPartner() {
		return businessPartner;
	}

	public void setBusinessPartner(String businessPartner) {
		this.businessPartner = businessPartner;
	}

	public boolean isFinalApprover() {
		return finalApprover;
	}

	public void setFinalApprover(boolean finalApprover) {
		this.finalApprover = finalApprover;
	}

	@Override
	public String toString() {
		return "ApprovalDocument#" + id + " [" + documentNo + " / " + docTypeName + "]";
	}
}
