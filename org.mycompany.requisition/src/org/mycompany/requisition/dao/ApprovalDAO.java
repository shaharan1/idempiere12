package org.mycompany.requisition.dao;

import java.util.List;

import org.mycompany.requisition.util.DBUtils;

/**
 * Database access for Approval transactions, rules and history.
 */
public class ApprovalDAO {

    /**
     * Checks if document is currently in approval process.
     */
    public boolean isInApprovalProcess(int tableId, int recordId) {
        String count = DBUtils.queryString(
                "SELECT COUNT(1) FROM XX_Approval_Transaction "
                        + "WHERE AD_Table_ID=? AND Record_ID=? AND Status IN ('PENDING', 'IN_PROGRESS')",
                tableId, recordId);
        return count != null && !count.isEmpty() && !count.equals("0");
    }

    /**
     * Finds active approval transaction status.
     */
    public String findTransactionStatus(int transactionId) {
        return DBUtils.queryString(
                "SELECT Status FROM XX_Approval_Transaction WHERE XX_Approval_Transaction_ID=?",
                transactionId);
    }
}
