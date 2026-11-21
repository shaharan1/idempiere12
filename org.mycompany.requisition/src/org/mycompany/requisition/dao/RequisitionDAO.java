package org.mycompany.requisition.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.util.DB;
import org.mycompany.requisition.model.RequisitionLineDTO;
import org.mycompany.requisition.util.DBUtils;

/**
 * Database access for Requisitions (M_Requisition and M_RequisitionLine).
 */
public class RequisitionDAO {

    /**
     * Get eligible requisition lines for RFQ creation.
     */
    public List<RequisitionLineDTO> findEligibleRequisitionLines(int requisitionId, int currentRfqId, String trxName) {
        List<RequisitionLineDTO> list = new ArrayList<RequisitionLineDTO>();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT rl.M_RequisitionLine_ID, rl.M_Requisition_ID, r.DocumentNo, ");
        sql.append("rl.M_Product_ID, p.Name, rl.Qty, rl.Line ");
        sql.append("FROM M_RequisitionLine rl ");
        sql.append("INNER JOIN M_Requisition r ON r.M_Requisition_ID = rl.M_Requisition_ID ");
        sql.append("LEFT JOIN M_Product p ON p.M_Product_ID = rl.M_Product_ID ");
        sql.append("WHERE rl.IsActive = 'Y' AND r.DocStatus = 'CO' ");
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
            int idx = 1;
            if (requisitionId > 0) {
                pstmt.setInt(idx++, requisitionId);
            }
            if (currentRfqId > 0) {
                pstmt.setInt(idx++, currentRfqId);
            }

            rs = pstmt.executeQuery();
            while (rs.next()) {
                RequisitionLineDTO dto = new RequisitionLineDTO();
                dto.setM_RequisitionLine_ID(rs.getInt("M_RequisitionLine_ID"));
                dto.setM_Requisition_ID(rs.getInt("M_Requisition_ID"));
                dto.setDocumentNo(rs.getString("DocumentNo"));
                dto.setM_Product_ID(rs.getInt("M_Product_ID"));
                dto.setProductName(rs.getString("Name"));
                dto.setQty(rs.getBigDecimal("Qty"));
                dto.setLine(rs.getInt("Line"));
                list.add(dto);
            }
        } catch (SQLException e) {
            throw new AdempiereException("Error loading eligible requisition lines: " + e.getMessage(), e);
        } finally {
            DB.close(rs, pstmt);
        }
        return list;
    }

    /**
     * Check if a requisition line is already linked to an active RFQ.
     */
    public int findLinkedRfqId(int requisitionLineId, String trxName) {
        String sql = "SELECT XX_RfQ_ID FROM M_RequisitionLine WHERE M_RequisitionLine_ID = ?";
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = DB.prepareStatement(sql, trxName);
            pstmt.setInt(1, requisitionLineId);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new AdempiereException(e.getMessage(), e);
        } finally {
            DB.close(rs, pstmt);
        }
        return 0;
    }
}
