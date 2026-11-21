package org.mycompany.requisition.dao;

import org.mycompany.requisition.util.DBUtils;

/**
 * Database access for Purchase Orders (C_Order).
 */
public class PODAO {

    /**
     * Finds active Purchase Order document status.
     */
    public String findDocStatus(int orderId) {
        return DBUtils.queryString(
                "SELECT DocStatus FROM C_Order WHERE C_Order_ID=?", orderId);
    }

    /**
     * Checks if a PO Line exists for the given RFQ Response Line.
     */
    public boolean existsLineForRfQResponseLine(int rfqResponseLineId) {
        String count = DBUtils.queryString(
                "SELECT COUNT(1) FROM C_OrderLine WHERE XX_RfQResponseLine_ID=?", rfqResponseLineId);
        return count != null && !count.isEmpty() && !count.equals("0");
    }
}
