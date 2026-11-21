package org.mycompany.requisition.rfq;

import java.math.BigDecimal;

public class RequisitionLineDTO {
	private int m_requisitionLineId;
	private int m_requisitionId;
	private String requisitionDocumentNo;
	private int m_productId;
	private String productName;
	private BigDecimal qty;
	private int lineNo;
	private boolean selected;
	
	public RequisitionLineDTO(int mRequisitionLineId, int mRequisitionId, String requisitionDocumentNo,
			int mProductId, String productName, BigDecimal qty, int lineNo) {
		this.m_requisitionLineId = mRequisitionLineId;
		this.m_requisitionId = mRequisitionId;
		this.requisitionDocumentNo = requisitionDocumentNo;
		this.m_productId = mProductId;
		this.productName = productName;
		this.qty = qty;
		this.lineNo = lineNo;
		this.selected = false;
	}

	public int getM_RequisitionLine_ID() {
		return m_requisitionLineId;
	}

	public void setM_RequisitionLine_ID(int mRequisitionLineId) {
		this.m_requisitionLineId = mRequisitionLineId;
	}

	public int getM_Requisition_ID() {
		return m_requisitionId;
	}

	public void setM_Requisition_ID(int mRequisitionId) {
		this.m_requisitionId = mRequisitionId;
	}

	public String getRequisitionDocumentNo() {
		return requisitionDocumentNo;
	}

	public void setRequisitionDocumentNo(String requisitionDocumentNo) {
		this.requisitionDocumentNo = requisitionDocumentNo;
	}

	public int getM_Product_ID() {
		return m_productId;
	}

	public void setM_Product_ID(int mProductId) {
		this.m_productId = mProductId;
	}

	public String getProductName() {
		return productName;
	}

	public void setProductName(String productName) {
		this.productName = productName;
	}

	public BigDecimal getQty() {
		return qty;
	}

	public void setQty(BigDecimal qty) {
		this.qty = qty;
	}

	public int getLineNo() {
		return lineNo;
	}

	public void setLineNo(int lineNo) {
		this.lineNo = lineNo;
	}

	public boolean isSelected() {
		return selected;
	}

	public void setSelected(boolean selected) {
		this.selected = selected;
	}
}
