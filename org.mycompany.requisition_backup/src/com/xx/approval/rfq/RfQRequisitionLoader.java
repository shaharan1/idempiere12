package com.xx.approval.rfq;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MProduct;
import org.compiere.model.MRequisition;
import org.compiere.model.MRequisitionLine;
import org.compiere.model.MRfQ;
import org.compiere.model.MRfQLine;
import org.compiere.model.MRfQLineQty;

/**
 * Copies the lines of the requisition chosen on an RfQ into C_RfQLine and
 * C_RfQLineQty. Shared by the button process and the automatic event handler,
 * so both behave the same way.
 */
public final class RfQRequisitionLoader {

	/** Name of the Requisition column on C_RfQ. */
	public static final String REQUISITION_COLUMN = "M_Requisition_ID";

	private RfQRequisitionLoader() {
	}

	/**
	 * @return number of RfQ lines created. A product already on the RfQ is skipped,
	 *         so running this twice never duplicates a line.
	 */
	public static int load(MRfQ rfq) {
		if (rfq.get_ColumnIndex(REQUISITION_COLUMN) < 0)
			throw new AdempiereException("C_RfQ has no column " + REQUISITION_COLUMN
					+ ". Fix REQUISITION_COLUMN in RfQRequisitionLoader.");

		int reqId = rfq.get_ValueAsInt(REQUISITION_COLUMN);
		if (reqId <= 0)
			return 0;

		Set<Integer> existing = new HashSet<Integer>();
		for (MRfQLine l : rfq.getLines())
			existing.add(Integer.valueOf(l.getM_Product_ID()));

		MRequisition req = new MRequisition(rfq.getCtx(), reqId, rfq.get_TrxName());
		int count = 0;
		for (MRequisitionLine rl : req.getLines()) {
			int productId = rl.getM_Product_ID();
			// a requisition line can be a charge; an RfQ line needs a product
			if (productId <= 0 || existing.contains(Integer.valueOf(productId)))
				continue;

			MProduct product = MProduct.get(rfq.getCtx(), productId, rfq.get_TrxName());
			if (product == null)
				continue;

			MRfQLine line = new MRfQLine(rfq);
			line.setLine(rl.getLine());
			line.setM_Product_ID(productId);
			line.setM_AttributeSetInstance_ID(rl.getM_AttributeSetInstance_ID());
			String desc = rl.getDescription();
			line.setDescription(desc != null && desc.trim().length() > 0 ? desc : product.getName());
			if (line.get_ColumnIndex("Qty") >= 0) {
				line.set_ValueOfColumn("Qty", rl.getQty() != null ? rl.getQty() : BigDecimal.ONE);
			}
			line.saveEx();

			MRfQLineQty qty = new MRfQLineQty(line);
			qty.setC_UOM_ID(product.getC_UOM_ID());
			qty.setQty(rl.getQty() != null ? rl.getQty() : BigDecimal.ONE);
			qty.setIsRfQQty(true);
			qty.setIsPurchaseQty(true);
			qty.saveEx();

			existing.add(Integer.valueOf(productId));
			count++;
		}
		return count;
	}
}
