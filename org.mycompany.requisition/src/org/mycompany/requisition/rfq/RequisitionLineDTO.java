package org.mycompany.requisition.rfq;

import java.math.BigDecimal;

/**
 * Backward compatibility alias.
 * Canonical class is in {@link org.mycompany.requisition.model.RequisitionLineDTO}.
 */
public class RequisitionLineDTO extends org.mycompany.requisition.model.RequisitionLineDTO {

	public RequisitionLineDTO() {
		super();
	}

	public RequisitionLineDTO(int mRequisitionLineId, int mRequisitionId, String requisitionDocumentNo,
			int mProductId, String productName, BigDecimal qty, int lineNo) {
		super(mRequisitionLineId, mRequisitionId, requisitionDocumentNo, mProductId, productName, qty, lineNo);
	}
}
