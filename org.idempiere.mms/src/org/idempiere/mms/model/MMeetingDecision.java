package org.idempiere.mms.model;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Properties;

import org.compiere.model.PO;
import org.compiere.model.POInfo;

/**
 * MMeetingDecision — Child of MManagementMeeting
 * Table: XX_MeetingDecision
 */
public class MMeetingDecision extends PO {

    private static final long serialVersionUID = 1L;

    public static final String Table_Name = "XX_MeetingDecision";
    public static final int    Table_ID   = 0;

    public static final String STATUS_Open       = "OPEN";
    public static final String STATUS_InProgress = "IN_PROGRESS";
    public static final String STATUS_Completed  = "COMPLETED";
    public static final String STATUS_Cancelled  = "CANCELLED";

    public MMeetingDecision(Properties ctx, int XX_MeetingDecision_ID, String trxName) {
        super(ctx, XX_MeetingDecision_ID, trxName);
    }
    public MMeetingDecision(Properties ctx, ResultSet rs, String trxName) {
        super(ctx, rs, trxName);
    }

    @Override
    protected int get_AccessLevel() { return 3; }

    @Override
    public POInfo initPO(Properties ctx) { return POInfo.getPOInfo(ctx, Table_ID); }

    public int getXX_MeetingDecision_ID() { return get_ID(); }

    public int getXX_ManagementMeeting_ID() {
        Integer ii = (Integer) get_Value("XX_ManagementMeeting_ID");
        return (ii != null) ? ii : 0;
    }
    public void setXX_ManagementMeeting_ID(int id) { set_Value("XX_ManagementMeeting_ID", id); }

    public int getXX_MeetingAgenda_ID() {
        Integer ii = (Integer) get_Value("XX_MeetingAgenda_ID");
        return (ii != null) ? ii : 0;
    }
    public void setXX_MeetingAgenda_ID(int id) { set_Value("XX_MeetingAgenda_ID", id); }

    public String getDecisionNo() { return (String) get_Value("DecisionNo"); }
    public void setDecisionNo(String no) { set_Value("DecisionNo", no); }

    public String getDecisionTitle() { return (String) get_Value("DecisionTitle"); }
    public void setDecisionTitle(String title) { set_Value("DecisionTitle", title); }

    public String getDecisionDescription() { return (String) get_Value("DecisionDescription"); }
    public void setDecisionDescription(String desc) { set_Value("DecisionDescription", desc); }

    public int getResponsibleUser_ID() {
        Integer ii = (Integer) get_Value("ResponsibleUser_ID");
        return (ii != null) ? ii : 0;
    }
    public void setResponsibleUser_ID(int id) { set_Value("ResponsibleUser_ID", id); }

    public Timestamp getDueDate() { return (Timestamp) get_Value("DueDate"); }
    public void setDueDate(Timestamp d) { set_Value("DueDate", d); }

    public String getPriority() { return (String) get_Value("Priority"); }
    public void setPriority(String p) { set_Value("Priority", p); }

    public String getStatus() { return (String) get_Value("Status"); }
    public void setStatus(String status) { set_ValueNoCheck("Status", status); }

    public String getRemarks() { return (String) get_Value("Remarks"); }
    public void setRemarks(String r) { set_Value("Remarks", r); }

    @Override
    protected boolean beforeSave(boolean newRecord) {
        if (newRecord) {
            if (getStatus() == null) setStatus(STATUS_Open);
            if (getPriority() == null) setPriority("5");
            // Auto decision number
            if (getDecisionNo() == null || getDecisionNo().isBlank()) {
                String docNo = org.compiere.model.MSequence.getDocumentNo(
                    getAD_Client_ID(), "XX_MeetingDecision", get_TrxName(), this);
                if (docNo != null) setDecisionNo(docNo);
            }
        }
        return true;
    }
}
