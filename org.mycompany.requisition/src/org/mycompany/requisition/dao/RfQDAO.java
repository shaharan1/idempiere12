package org.mycompany.requisition.dao;

import java.util.List;

import org.compiere.util.KeyNamePair;
import org.mycompany.requisition.util.DBUtils;

/**
 * Database access for RFQ and Quote data.
 */
public class RfQDAO {

    /**
     * Active RFQ document status.
     */
    public String findDocStatus(int rfqId) {
        return DBUtils.queryString(
                "SELECT DocStatus FROM C_RfQ WHERE C_RfQ_ID=?", rfqId);
    }

    /**
     * Active vendor response count for an RFQ.
     */
    public int countResponses(int rfqId) {
        return DBUtils.queryOne(
                "SELECT COUNT(1) FROM C_RfQResponse WHERE C_RfQ_ID=? AND IsActive='Y'",
                rs -> rs.getInt(1),
                rfqId);
    }
}
