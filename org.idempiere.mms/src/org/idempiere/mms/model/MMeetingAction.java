package org.idempiere.mms.model;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Properties;

import org.compiere.model.PO;
import org.compiere.model.POInfo;

/**
 * MMeetingAction — Action Items from Meeting
 * Table: XX_MeetingAction
 *
 * Key feature: System automatically detects OVERDUE actions
 * when DueDate < NOW() and Status != COMPLETED/CANCELLED
 */
public class MMeetingAction extends PO {

    private static final long serialVersionUID = 1L;

    public static final String Table_Name = "XX_MeetingAction";
    public static final int    Table_ID   = 0;

    public static final String STATUS_Open       = "OPEN";
    public static final String STATUS_InProgress = "IN_PROGRESS";
    public static final String STATUS_Completed  = "COMPLETED";
    public static final String STATUS_Overdue    = "OVERDUE";
    public static final String STATUS_Cancelled  = "CANCELLED";

    public MMeetingAction(Properties ctx, int XX_MeetingAction_ID, String trxName) {
        super(ctx, XX_MeetingAction_ID, trxName);
    }
    public MMeetingAction(Properties ctx, ResultSet rs, String trxName) {
        super(ctx, rs, trxName);
    }

    @Override
    protected int get_AccessLevel() { return 3; }

    @Override
    public POInfo initPO(Properties ctx) { return POInfo.getPOInfo(ctx, Table_ID); }

    public int getXX_MeetingAction_ID() { return get_ID(); }

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

    public int getXX_MeetingDecision_ID() {
        Integer ii = (Integer) get_Value("XX_MeetingDecision_ID");
        return (ii != null) ? ii : 0;
    }
    public void setXX_MeetingDecision_ID(int id) { set_Value("XX_MeetingDecision_ID", id); }

    public String getTask() { return (String) get_Value("Task"); }
    public void setTask(String task) { set_Value("Task", task); }

    public String getDescription() { return (String) get_Value("Description"); }
    public void setDescription(String desc) { set_Value("Description", desc); }

    public int getResponsibleUser_ID() {
        Integer ii = (Integer) get_Value("ResponsibleUser_ID");
        return (ii != null) ? ii : 0;
    }
    public void setResponsibleUser_ID(int id) { set_Value("ResponsibleUser_ID", id); }

    public String getPriority() { return (String) get_Value("Priority"); }
    public void setPriority(String p) { set_Value("Priority", p); }

    public Timestamp getStartDate() { return (Timestamp) get_Value("StartDate"); }
    public void setStartDate(Timestamp d) { set_Value("StartDate", d); }

    public Timestamp getDueDate() { return (Timestamp) get_Value("DueDate"); }
    public void setDueDate(Timestamp d) { set_Value("DueDate", d); }

    public Timestamp getCompletionDate() { return (Timestamp) get_Value("CompletionDate"); }
    public void setCompletionDate(Timestamp d) { set_Value("CompletionDate", d); }

    public String getStatus() { return (String) get_Value("Status"); }
    public void setStatus(String status) { set_ValueNoCheck("Status", status); }

    public BigDecimal getProgressPercent() {
        BigDecimal bd = (BigDecimal) get_Value("ProgressPercent");
        return (bd != null) ? bd : BigDecimal.ZERO;
    }
    public void setProgressPercent(BigDecimal pct) { set_Value("ProgressPercent", pct); }

    public String getRemarks() { return (String) get_Value("Remarks"); }
    public void setRemarks(String r) { set_Value("Remarks", r); }

    /**
     * Check if action is overdue (DueDate passed, not completed/cancelled).
     */
    public boolean isOverdue() {
        if (STATUS_Completed.equals(getStatus()) || STATUS_Cancelled.equals(getStatus()))
            return false;
        Timestamp dueDate = getDueDate();
        if (dueDate == null) return false;
        return dueDate.before(new Timestamp(System.currentTimeMillis()));
    }

    @Override
    protected boolean beforeSave(boolean newRecord) {
        if (newRecord && getStatus() == null) setStatus(STATUS_Open);
        if (newRecord && getPriority() == null) setPriority("5");

        // Auto-detect overdue
        if (!STATUS_Completed.equals(getStatus()) && !STATUS_Cancelled.equals(getStatus())) {
            if (isOverdue()) {
                setStatus(STATUS_Overdue);
            }
        }

        // Set completion date when completed
        if (STATUS_Completed.equals(getStatus()) && getCompletionDate() == null) {
            setCompletionDate(new Timestamp(System.currentTimeMillis()));
            // Auto set progress to 100%
            setProgressPercent(new BigDecimal("100"));
        }

        // Validate progress percent
        BigDecimal pct = getProgressPercent();
        if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(new BigDecimal("100")) > 0) {
            log.saveError("Error", "Progress must be between 0 and 100");
            return false;
        }

        return true;
    }
}
