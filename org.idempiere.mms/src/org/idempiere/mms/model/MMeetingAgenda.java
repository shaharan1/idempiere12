package org.idempiere.mms.model;

import java.sql.ResultSet;
import java.util.Properties;

import org.compiere.model.PO;
import org.compiere.model.POInfo;

/**
 * MMeetingAgenda — Child of MManagementMeeting
 * Table: XX_MeetingAgenda
 */
public class MMeetingAgenda extends PO {

    private static final long serialVersionUID = 1L;

    public static final String Table_Name = "XX_MeetingAgenda";
    public static final int    Table_ID   = 0;

    // Status constants
    public static final String STATUS_Open      = "OPEN";
    public static final String STATUS_Discussed = "DISCUSSED";
    public static final String STATUS_Deferred  = "DEFERRED";
    public static final String STATUS_Completed = "COMPLETED";

    public MMeetingAgenda(Properties ctx, int XX_MeetingAgenda_ID, String trxName) {
        super(ctx, XX_MeetingAgenda_ID, trxName);
    }

    public MMeetingAgenda(Properties ctx, ResultSet rs, String trxName) {
        super(ctx, rs, trxName);
    }

    @Override
    protected int get_AccessLevel() { return accessLevel.intValue(); }
    private static final java.math.BigDecimal accessLevel = new java.math.BigDecimal(3);

    @Override
    public POInfo initPO(Properties ctx) { return POInfo.getPOInfo(ctx, Table_ID); }

    @Override
    public String toString() {
        return "MMeetingAgenda[" + get_ID() + " - " + getAgendaTitle() + "]";
    }

    // Getters & Setters
    public int getXX_MeetingAgenda_ID() { return get_ID(); }

    public int getXX_ManagementMeeting_ID() {
        Integer ii = (Integer) get_Value("XX_ManagementMeeting_ID");
        return (ii != null) ? ii : 0;
    }
    public void setXX_ManagementMeeting_ID(int id) { set_Value("XX_ManagementMeeting_ID", id); }

    public int getLineNo() {
        Integer ii = (Integer) get_Value("LineNo");
        return (ii != null) ? ii : 10;
    }
    public void setLineNo(int LineNo) { set_Value("LineNo", LineNo); }

    public String getAgendaTitle() { return (String) get_Value("AgendaTitle"); }
    public void setAgendaTitle(String title) { set_Value("AgendaTitle", title); }

    public String getDescription() { return (String) get_Value("Description"); }
    public void setDescription(String desc) { set_Value("Description", desc); }

    public int getPresentedBy_ID() {
        Integer ii = (Integer) get_Value("PresentedBy_ID");
        return (ii != null) ? ii : 0;
    }
    public void setPresentedBy_ID(int id) { set_Value("PresentedBy_ID", id); }

    public int getAllocatedMinutes() {
        Integer ii = (Integer) get_Value("AllocatedMinutes");
        return (ii != null) ? ii : 0;
    }
    public void setAllocatedMinutes(int minutes) { set_Value("AllocatedMinutes", minutes); }

    public String getPriority() { return (String) get_Value("Priority"); }
    public void setPriority(String priority) { set_Value("Priority", priority); }

    public String getStatus() { return (String) get_Value("Status"); }
    public void setStatus(String status) { set_Value("Status", status); }

    public String getRemarks() { return (String) get_Value("Remarks"); }
    public void setRemarks(String remarks) { set_Value("Remarks", remarks); }

    @Override
    protected boolean beforeSave(boolean newRecord) {
        if (newRecord) {
            if (getStatus() == null) setStatus(STATUS_Open);
            if (getPriority() == null) setPriority("5"); // Medium

            // Auto line number
            if (getLineNo() == 10 && get_ID() == 0) {
                // Get max line no for this meeting
                int meetingId = getXX_ManagementMeeting_ID();
                if (meetingId > 0) {
                    int maxLine = getMaxLineNo(meetingId);
                    setLineNo(maxLine + 10);
                }
            }
        }
        return true;
    }

    private int getMaxLineNo(int meetingId) {
        String sql = "SELECT COALESCE(MAX(LineNo), 0) FROM adempiere.xx_meetingagenda WHERE XX_ManagementMeeting_ID = ?";
        return org.compiere.util.DB.getSQLValueEx(get_TrxName(), sql, meetingId);
    }
}
