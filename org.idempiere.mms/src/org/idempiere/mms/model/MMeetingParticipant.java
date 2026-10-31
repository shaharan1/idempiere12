package org.idempiere.mms.model;

import java.sql.ResultSet;
import java.util.Properties;

import org.compiere.model.PO;
import org.compiere.model.POInfo;

/**
 * MMeetingParticipant — Child of MManagementMeeting
 * Table: XX_MeetingParticipant
 */
public class MMeetingParticipant extends PO {

    private static final long serialVersionUID = 1L;

    public static final String Table_Name = "XX_MeetingParticipant";
    public static final int    Table_ID   = 0;

    // Role constants
    public static final String ROLE_Chairperson = "CHAIRPERSON";
    public static final String ROLE_Secretary   = "SECRETARY";
    public static final String ROLE_Participant = "PARTICIPANT";
    public static final String ROLE_Presenter   = "PRESENTER";
    public static final String ROLE_Observer    = "OBSERVER";

    // Attendance constants
    public static final String ATTENDANCE_Invited  = "INVITED";
    public static final String ATTENDANCE_Accepted = "ACCEPTED";
    public static final String ATTENDANCE_Declined = "DECLINED";
    public static final String ATTENDANCE_Present  = "PRESENT";
    public static final String ATTENDANCE_Absent   = "ABSENT";
    public static final String ATTENDANCE_Late     = "LATE";

    // Invitation constants
    public static final String INVITATION_Pending = "PENDING";
    public static final String INVITATION_Sent    = "SENT";
    public static final String INVITATION_Failed  = "FAILED";

    public MMeetingParticipant(Properties ctx, int XX_MeetingParticipant_ID, String trxName) {
        super(ctx, XX_MeetingParticipant_ID, trxName);
    }

    public MMeetingParticipant(Properties ctx, ResultSet rs, String trxName) {
        super(ctx, rs, trxName);
    }

    @Override
    protected int get_AccessLevel() { return accessLevel.intValue(); }
    private static final java.math.BigDecimal accessLevel = new java.math.BigDecimal(3);

    @Override
    public POInfo initPO(Properties ctx) { return POInfo.getPOInfo(ctx, Table_ID); }

    @Override
    public String toString() {
        return "MMeetingParticipant[" + get_ID() + "]";
    }

    // Getters & Setters
    public int getXX_MeetingParticipant_ID() { return get_ID(); }

    public int getXX_ManagementMeeting_ID() {
        Integer ii = (Integer) get_Value("XX_ManagementMeeting_ID");
        return (ii != null) ? ii : 0;
    }
    public void setXX_ManagementMeeting_ID(int id) { set_Value("XX_ManagementMeeting_ID", id); }

    public int getAD_User_ID() {
        Integer ii = (Integer) get_Value("AD_User_ID");
        return (ii != null) ? ii : 0;
    }
    public void setAD_User_ID(int AD_User_ID) { set_Value("AD_User_ID", AD_User_ID); }

    public String getParticipantRole() { return (String) get_Value("ParticipantRole"); }
    public void setParticipantRole(String role) { set_Value("ParticipantRole", role); }

    public String getAttendanceStatus() { return (String) get_Value("AttendanceStatus"); }
    public void setAttendanceStatus(String status) { set_Value("AttendanceStatus", status); }

    public String getResponseStatus() { return (String) get_Value("ResponseStatus"); }
    public void setResponseStatus(String status) { set_Value("ResponseStatus", status); }

    public String getInvitationStatus() { return (String) get_Value("InvitationStatus"); }
    public void setInvitationStatus(String status) { set_Value("InvitationStatus", status); }

    public String getRemarks() { return (String) get_Value("Remarks"); }
    public void setRemarks(String remarks) { set_Value("Remarks", remarks); }

    @Override
    protected boolean beforeSave(boolean newRecord) {
        if (newRecord) {
            if (getParticipantRole() == null) setParticipantRole(ROLE_Participant);
            if (getAttendanceStatus() == null) setAttendanceStatus(ATTENDANCE_Invited);
            if (getResponseStatus() == null) setResponseStatus("PENDING");
            if (getInvitationStatus() == null) setInvitationStatus(INVITATION_Pending);
        }
        return true;
    }
}
