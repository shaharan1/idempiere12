package org.idempiere.mms.model;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Properties;

import org.compiere.model.PO;
import org.compiere.model.POInfo;

/**
 * MMeetingMinutes — Child of MManagementMeeting
 * Table: XX_MeetingMinutes
 */
public class MMeetingMinutes extends PO {

    private static final long serialVersionUID = 1L;

    public static final String Table_Name = "XX_MeetingMinutes";
    public static final int    Table_ID   = 0;

    public static final String STATUS_Draft     = "DRAFT";
    public static final String STATUS_Submitted = "SUBMITTED";
    public static final String STATUS_Approved  = "APPROVED";
    public static final String STATUS_Rejected  = "REJECTED";

    public MMeetingMinutes(Properties ctx, int XX_MeetingMinutes_ID, String trxName) {
        super(ctx, XX_MeetingMinutes_ID, trxName);
    }
    public MMeetingMinutes(Properties ctx, ResultSet rs, String trxName) {
        super(ctx, rs, trxName);
    }

    @Override
    protected int get_AccessLevel() { return 3; }

    @Override
    public POInfo initPO(Properties ctx) { return POInfo.getPOInfo(ctx, Table_ID); }

    public int getXX_MeetingMinutes_ID() { return get_ID(); }

    public int getXX_ManagementMeeting_ID() {
        Integer ii = (Integer) get_Value("XX_ManagementMeeting_ID");
        return (ii != null) ? ii : 0;
    }
    public void setXX_ManagementMeeting_ID(int id) { set_Value("XX_ManagementMeeting_ID", id); }

    public String getSummary() { return (String) get_Value("Summary"); }
    public void setSummary(String s) { set_Value("Summary", s); }

    public String getDiscussion() { return (String) get_Value("Discussion"); }
    public void setDiscussion(String s) { set_Value("Discussion", s); }

    public String getConclusion() { return (String) get_Value("Conclusion"); }
    public void setConclusion(String s) { set_Value("Conclusion", s); }

    public int getPreparedBy_ID() {
        Integer ii = (Integer) get_Value("PreparedBy_ID");
        return (ii != null) ? ii : 0;
    }
    public void setPreparedBy_ID(int id) { set_Value("PreparedBy_ID", id); }

    public Timestamp getPreparedDate() { return (Timestamp) get_Value("PreparedDate"); }
    public void setPreparedDate(Timestamp d) { set_Value("PreparedDate", d); }

    public int getApprovedBy_ID() {
        Integer ii = (Integer) get_Value("ApprovedBy_ID");
        return (ii != null) ? ii : 0;
    }
    public void setApprovedBy_ID(int id) { set_Value("ApprovedBy_ID", id); }

    public Timestamp getApprovalDate() { return (Timestamp) get_Value("ApprovalDate"); }
    public void setApprovalDate(Timestamp d) { set_Value("ApprovalDate", d); }

    public String getStatus() { return (String) get_Value("Status"); }
    public void setStatus(String status) { set_ValueNoCheck("Status", status); }

    public String getNotes() { return (String) get_Value("Notes"); }
    public void setNotes(String notes) { set_Value("Notes", notes); }

    @Override
    protected boolean beforeSave(boolean newRecord) {
        if (newRecord && getStatus() == null) setStatus(STATUS_Draft);
        // Set prepared date when first saving
        if (newRecord && getPreparedDate() == null) {
            setPreparedDate(new Timestamp(System.currentTimeMillis()));
        }
        // Set approval date when approved
        if (!newRecord && STATUS_Approved.equals(getStatus()) && getApprovalDate() == null) {
            setApprovalDate(new Timestamp(System.currentTimeMillis()));
            // Set approved by current user
            int currentUserId = org.compiere.util.Env.getAD_User_ID(getCtx());
            if (getApprovedBy_ID() == 0 && currentUserId > 0)
                setApprovedBy_ID(currentUserId);
        }
        return true;
    }
}
