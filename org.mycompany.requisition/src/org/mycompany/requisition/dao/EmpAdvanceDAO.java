package org.mycompany.requisition.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MRole;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.mycompany.requisition.model.AdvanceLine;
import org.mycompany.requisition.service.EmpAdvanceService.EmpInfo;
import org.mycompany.requisition.util.DBUtils;

/**
 * Database access for Employee Fund Requisition / Advance.
 */
public class EmpAdvanceDAO {

    /**
     * Loads the EMPLOYEE record linked to the given AD_User_ID.
     */
    public EmpInfo findEmployeeByUser(int adUserId) {
        EmpInfo e = new EmpInfo();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = DB.prepareStatement(
                    "SELECT c_bpartner_id, emp_code, emp_name, designation, department, section, date_of_join "
                            + "FROM xx_employee_info_v WHERE c_bpartner_id = "
                            + "(SELECT c_bpartner_id FROM ad_user WHERE ad_user_id=?)", null);
            ps.setInt(1, adUserId);
            rs = ps.executeQuery();
            if (rs.next()) {
                e.bpartnerId = rs.getInt(1);
                e.code = rs.getString(2);
                e.name = rs.getString(3);
                e.designation = rs.getString(4);
                e.department = rs.getString(5);
                e.section = rs.getString(6);
                e.joinDate = rs.getTimestamp(7);
            }
        } catch (SQLException ex) {
            throw new AdempiereException(ex.getMessage(), ex);
        } finally {
            DB.close(rs, ps);
        }
        return e;
    }

    /**
     * Active segments (C_Activity) available for the tenant.
     */
    public List<KeyNamePair> findSegments(int clientId) {
        return DBUtils.queryList(
                "SELECT C_Activity_ID, Name FROM C_Activity "
                        + "WHERE AD_Client_ID=? AND IsActive='Y' AND IsSummary='N' ORDER BY Name",
                rs -> new KeyNamePair(rs.getInt(1), rs.getString(2)),
                clientId);
    }

    /**
     * Finds default segment matching role name.
     */
    public KeyNamePair findDefaultSegmentForRole(int clientId, String roleName) {
        if (roleName == null || roleName.trim().isEmpty())
            return null;
        return DBUtils.queryOne(
                "SELECT C_Activity_ID, Name FROM C_Activity "
                        + "WHERE AD_Client_ID=? AND IsActive='Y' AND UPPER(Name) LIKE UPPER(?) "
                        + "FETCH FIRST 1 ROW ONLY",
                rs -> new KeyNamePair(rs.getInt(1), rs.getString(2)),
                clientId, "%" + roleName + "%");
    }

    /**
     * Finds the Advance to Employee charge.
     */
    public KeyNamePair findAdvanceCharge(int clientId, String chargeName) {
        return DBUtils.queryOne(
                "SELECT C_Charge_ID, Name FROM C_Charge "
                        + "WHERE AD_Client_ID IN (0,?) AND IsActive='Y' AND Name LIKE ? "
                        + "ORDER BY AD_Client_ID DESC, C_Charge_ID ASC FETCH FIRST 1 ROW ONLY",
                rs -> new KeyNamePair(rs.getInt(1), rs.getString(2)),
                clientId, chargeName);
    }

    /**
     * Finds org name by AD_Org_ID.
     */
    public String findOrgName(int orgId) {
        return DBUtils.queryString(
                "SELECT Name FROM AD_Org WHERE AD_Org_ID=?", orgId);
    }
}
