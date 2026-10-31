package org.mycompany.requisition.rfq;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MProduct;
import org.compiere.model.MRfQ;
import org.compiere.model.MRfQLine;
import org.compiere.model.MRfQLineQty;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Trx;

public class RequisitionRFQService {
	private static final CLogger log = CLogger.getCLogger(RequisitionRFQService.class);
	
	public static final String COLUMN_M_REQUISITIONLINE_XX_RFQ_ID = "XX_RfQ_ID";
	public static final String COLUMN_M_RFQLINE_XX_REQUISITIONLINE_ID = "XX_RequisitionLine_ID";
	
	/**
	 * Get eligible requisition lines for a specific requisition (or all if reqId <= 0)
	 */
	public static List<RequisitionLineDTO> getEligibleRequisitionLines(Properties ctx, int requisitionId, int currentRfqId, String trxName) {
		List<RequisitionLineDTO> list = new ArrayList<RequisitionLineDTO>();
		System.out.println("getEligibleRequisitionLines called, requisitionId=" + requisitionId);
		
		StringBuilder sql = new StringBuilder();
		sql.append("SELECT rl.M_RequisitionLine_ID, rl.M_Requisition_ID, r.DocumentNo, ");
		sql.append("rl.M_Product_ID, p.Name, rl.Qty, rl.Line ");
		sql.append("FROM M_RequisitionLine rl ");
		sql.append("INNER JOIN M_Requisition r ON r.M_Requisition_ID = rl.M_Requisition_ID ");
		sql.append("LEFT JOIN M_Product p ON p.M_Product_ID = rl.M_Product_ID ");
		sql.append("WHERE rl.IsActive = 'Y' ");
		sql.append("AND rl.M_Product_ID IS NOT NULL AND rl.M_Product_ID > 0 ");
		sql.append("AND (rl.XX_RfQ_ID IS NULL OR rl.XX_RfQ_ID = 0) ");
		sql.append("AND rl.C_OrderLine_ID IS NULL ");
		if (requisitionId > 0) {
			sql.append("AND rl.M_Requisition_ID = ? ");
		}
		if (currentRfqId > 0) {
			sql.append("AND rl.M_RequisitionLine_ID NOT IN (");
			sql.append("    SELECT COALESCE(qline.XX_RequisitionLine_ID, 0) ");
			sql.append("    FROM C_RfQLine qline WHERE qline.C_RfQ_ID = ? AND qline.IsActive = 'Y'");
			sql.append(") ");
		}
		sql.append("ORDER BY r.DocumentNo, rl.Line ");
		
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), trxName);
			int paramIdx = 1;
			if (requisitionId > 0) {
				pstmt.setInt(paramIdx++, requisitionId);
			}
			if (currentRfqId > 0) {
				pstmt.setInt(paramIdx++, currentRfqId);
			}
			rs = pstmt.executeQuery();
			while (rs.next()) {
				int reqLineId = rs.getInt(1);
				int reqId = rs.getInt(2);
				String docNo = rs.getString(3);
				int productId = rs.getInt(4);
				String productName = rs.getString(5);
				BigDecimal qty = rs.getBigDecimal(6);
				int lineNo = rs.getInt(7);
				if (productName == null && productId > 0) {
					MProduct product = MProduct.get(ctx, productId);
					if (product != null) productName = product.getName();
				}
				list.add(new RequisitionLineDTO(reqLineId, reqId, docNo, productId, productName, qty, lineNo));
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, sql.toString(), e);
			throw new AdempiereException(e);
		} finally {
			DB.close(rs, pstmt);
		}
		System.out.println("getEligibleRequisitionLines returning " + list.size() + " lines");
		return list;
	}
	
	/**
	 * Create RFQ lines from selected requisition lines with proper locking
	 */
	public static void createRFQLinesFromRequisitionLines(MRfQ rfq, List<Integer> selectedRequisitionLineIds) {
		if (rfq == null || selectedRequisitionLineIds == null || selectedRequisitionLineIds.isEmpty()) {
			return;
		}
		
		Properties ctx = rfq.getCtx();
		String trxName = rfq.get_TrxName();
		if (trxName == null) {
			trxName = Trx.createTrxName("ReqRFQ-");
		}
		
		Trx trx = Trx.get(trxName, true);
		try {
			trx.start();
			
			int rfqId = rfq.get_ID();
			
			// Lock RFQ
			String lockRfqSql = "SELECT C_RfQ_ID FROM C_RfQ WHERE C_RfQ_ID = ? FOR UPDATE NOWAIT";
			PreparedStatement pstmtLock = DB.prepareStatement(lockRfqSql, trxName);
			pstmtLock.setInt(1, rfqId);
			ResultSet rsLock = pstmtLock.executeQuery();
			if (!rsLock.next()) {
				throw new AdempiereException("Could not lock RFQ");
			}
			DB.close(rsLock, pstmtLock);
			
			// Get existing RFQ line requisition line references to prevent duplicate line insertion
			Set<Integer> existingReqLineIds = new HashSet<Integer>();
			int maxLine = 0;
			for (MRfQLine line : rfq.getLines()) {
				if (line.getLine() > maxLine) {
					maxLine = line.getLine();
				}
				Object refObj = line.get_Value(COLUMN_M_RFQLINE_XX_REQUISITIONLINE_ID);
				if (refObj instanceof Number) {
					existingReqLineIds.add(((Number) refObj).intValue());
				}
			}
			
			for (Integer reqLineId : selectedRequisitionLineIds) {
				if (reqLineId == null || reqLineId <= 0 || existingReqLineIds.contains(reqLineId)) {
					continue;
				}
				
				// Lock requisition line
				String lockReqLineSql = "SELECT M_RequisitionLine_ID, M_Product_ID, Qty, M_Requisition_ID, " + COLUMN_M_REQUISITIONLINE_XX_RFQ_ID 
					+ " FROM M_RequisitionLine WHERE M_RequisitionLine_ID = ? FOR UPDATE NOWAIT";
				PreparedStatement pstmtReq = DB.prepareStatement(lockReqLineSql, trxName);
				pstmtReq.setInt(1, reqLineId);
				ResultSet rsReq = pstmtReq.executeQuery();
				if (!rsReq.next()) {
					DB.close(rsReq, pstmtReq);
					continue;
				}
				int productId = rsReq.getInt("M_Product_ID");
				BigDecimal qty = rsReq.getBigDecimal("Qty");
				int reqId = rsReq.getInt("M_Requisition_ID");
				int linkedRfqId = rsReq.getInt(COLUMN_M_REQUISITIONLINE_XX_RFQ_ID);
				DB.close(rsReq, pstmtReq);
				
				if (productId <= 0) {
					continue;
				}
				if (linkedRfqId > 0 && linkedRfqId != rfqId) {
					throw new AdempiereException("Requisition line is already linked to another RFQ");
				}
				
				// Create RFQ line
				MRfQLine rfqLine = new MRfQLine(rfq);
				maxLine += 10;
				rfqLine.setLine(maxLine);
				rfqLine.setM_Product_ID(productId);
				
				// Get requisition line info
				int attrSetInstanceId = 0;
				int uomId = 0;
				String reqLineDesc = null;
				String getReqLineSql = "SELECT M_AttributeSetInstance_ID, Description, C_UOM_ID FROM M_RequisitionLine WHERE M_RequisitionLine_ID = ?";
				PreparedStatement pstmtReqLine = DB.prepareStatement(getReqLineSql, trxName);
				pstmtReqLine.setInt(1, reqLineId);
				ResultSet rsReqLine = pstmtReqLine.executeQuery();
				if (rsReqLine.next()) {
					attrSetInstanceId = rsReqLine.getInt("M_AttributeSetInstance_ID");
					reqLineDesc = rsReqLine.getString("Description");
					uomId = rsReqLine.getInt("C_UOM_ID");
				}
				DB.close(rsReqLine, pstmtReqLine);
				
				MProduct product = MProduct.get(ctx, productId);
				String productName = product != null ? product.getName() : null;
				if (uomId <= 0 && product != null) {
					uomId = product.getC_UOM_ID();
				}
				
				rfqLine.setM_AttributeSetInstance_ID(attrSetInstanceId);
				rfqLine.setDescription(reqLineDesc != null && reqLineDesc.trim().length() > 0 ? reqLineDesc : productName);
				if (rfqLine.get_ColumnIndex(COLUMN_M_RFQLINE_XX_REQUISITIONLINE_ID) >= 0) {
					rfqLine.set_ValueOfColumn(COLUMN_M_RFQLINE_XX_REQUISITIONLINE_ID, reqLineId);
				}
				rfqLine.saveEx(trxName);
				
				// Create RFQ line qty
				MRfQLineQty rfqLineQty = new MRfQLineQty(rfqLine);
				if (uomId > 0) {
					rfqLineQty.setC_UOM_ID(uomId);
				}
				rfqLineQty.setQty(qty != null ? qty : BigDecimal.ONE);
				rfqLineQty.setIsRfQQty(true);
				rfqLineQty.setIsPurchaseQty(true);
				rfqLineQty.saveEx(trxName);
				
				existingReqLineIds.add(reqLineId);
			}
			
			trx.commit();
		} catch (Exception e) {
			trx.rollback();
			log.log(Level.SEVERE, e.getMessage(), e);
			if (e instanceof AdempiereException) {
				throw (AdempiereException) e;
			}
			throw new AdempiereException(e);
		} finally {
			trx.close();
		}
	}
	
	/**
	 * Void/delete RFQ - make requisition lines eligible again
	 */
	public static void releaseRequisitionLines(int rfqId, String trxName) {
		if (rfqId <= 0) {
			return;
		}
		
		String sql = "UPDATE M_RequisitionLine SET " + COLUMN_M_REQUISITIONLINE_XX_RFQ_ID + " = NULL WHERE " + COLUMN_M_REQUISITIONLINE_XX_RFQ_ID + " = ?";
		PreparedStatement pstmt = null;
		try {
			pstmt = DB.prepareStatement(sql, trxName);
			pstmt.setInt(1, rfqId);
			int count = pstmt.executeUpdate();
			if (log.isLoggable(Level.INFO)) {
				log.info("Released " + count + " requisition lines from RFQ " + rfqId);
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, sql, e);
			throw new AdempiereException(e);
		} finally {
			DB.close(pstmt);
		}
	}
}
