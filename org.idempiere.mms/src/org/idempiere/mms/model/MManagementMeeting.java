package org.idempiere.mms.model;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Properties;

import org.compiere.model.PO;
import org.compiere.model.POInfo;

/**
 * MManagementMeeting — Main Meeting Header Model
 * Table: XX_ManagementMeeting
 *
 * Status (DocStatus) lifecycle:
 *   DR → WA → AP → SC → IP → CO
 *                       ↓
 *                      VO (Cancelled)
 */
public class MManagementMeeting extends PO {

    private static final long serialVersionUID = 1L;

    // =========================================================================
    // Table & Column Constants
    // =========================================================================
    public static final String Table_Name = "XX_ManagementMeeting";
    public static final int    Table_ID   = 0; // Will be set after AD_Table registration

    // DocStatus constants
    public static final String DOCSTATUS_Draft            = "DR";
    public static final String DOCSTATUS_PendingApproval  = "WA";
    public static final String DOCSTATUS_Approved         = "AP";
    public static final String DOCSTATUS_Scheduled        = "SC";
    public static final String DOCSTATUS_InProgress       = "IP";
    public static final String DOCSTATUS_Completed        = "CO";
    public static final String DOCSTATUS_Cancelled        = "VO";

    // MeetingMode constants
    public static final String MEETINGMODE_Physical = "PHYSICAL";
    public static final String MEETINGMODE_Online   = "ONLINE";
    public static final String MEETINGMODE_Hybrid   = "HYBRID";

    // MeetingPlatform constants
    public static final String PLATFORM_Internal   = "INTERNAL";
    public static final String PLATFORM_GoogleMeet = "GOOGLE_MEET";
    public static final String PLATFORM_Zoom       = "ZOOM";
    public static final String PLATFORM_Other      = "OTHER";

    // Priority constants (iDempiere standard)
    public static final String PRIORITY_Urgent = "1";
    public static final String PRIORITY_High   = "3";
    public static final String PRIORITY_Medium = "5";
    public static final String PRIORITY_Low    = "7";

    // =========================================================================
    // Constructors
    // =========================================================================
    public MManagementMeeting(Properties ctx, int XX_ManagementMeeting_ID, String trxName) {
        super(ctx, XX_ManagementMeeting_ID, trxName);
    }

    public MManagementMeeting(Properties ctx, ResultSet rs, String trxName) {
        super(ctx, rs, trxName);
    }

    @Override
    protected int get_AccessLevel() {
        // 3 = Client + Org
        return accessLevel.intValue();
    }

    private static final java.math.BigDecimal accessLevel = new java.math.BigDecimal(3);

    @Override
    public POInfo initPO(Properties ctx) {
        return POInfo.getPOInfo(ctx, Table_ID);
    }

    @Override
    public String toString() {
        return "MManagementMeeting[" + get_ID() + " - " + getDocumentNo() + "]";
    }

    // =========================================================================
    // Getters & Setters
    // =========================================================================

    public int getXX_ManagementMeeting_ID() {
        return get_ID();
    }

    public String getDocumentNo() {
        return (String) get_Value("DocumentNo");
    }
    public void setDocumentNo(String DocumentNo) {
        set_Value("DocumentNo", DocumentNo);
    }

    public String getSubject() {
        return (String) get_Value("Subject");
    }
    public void setSubject(String Subject) {
        set_Value("Subject", Subject);
    }

    public Timestamp getMeetingDate() {
        return (Timestamp) get_Value("MeetingDate");
    }
    public void setMeetingDate(Timestamp MeetingDate) {
        set_Value("MeetingDate", MeetingDate);
    }

    public Timestamp getStartTime() {
        return (Timestamp) get_Value("StartTime");
    }
    public void setStartTime(Timestamp StartTime) {
        set_Value("StartTime", StartTime);
    }

    public Timestamp getEndTime() {
        return (Timestamp) get_Value("EndTime");
    }
    public void setEndTime(Timestamp EndTime) {
        set_Value("EndTime", EndTime);
    }

    public int getXX_MeetingType_ID() {
        Integer ii = (Integer) get_Value("XX_MeetingType_ID");
        return (ii != null) ? ii : 0;
    }
    public void setXX_MeetingType_ID(int XX_MeetingType_ID) {
        set_Value("XX_MeetingType_ID", XX_MeetingType_ID);
    }

    public String getPriority() {
        return (String) get_Value("Priority");
    }
    public void setPriority(String Priority) {
        set_Value("Priority", Priority);
    }

    public String getDocStatus() {
        return (String) get_Value("DocStatus");
    }
    public void setDocStatus(String DocStatus) {
        set_ValueNoCheck("DocStatus", DocStatus);
    }

    public String getMeetingMode() {
        return (String) get_Value("MeetingMode");
    }
    public void setMeetingMode(String MeetingMode) {
        set_Value("MeetingMode", MeetingMode);
    }

    public String getMeetingPlatform() {
        return (String) get_Value("MeetingPlatform");
    }
    public void setMeetingPlatform(String MeetingPlatform) {
        set_Value("MeetingPlatform", MeetingPlatform);
    }

    public String getLocation() {
        return (String) get_Value("Location");
    }
    public void setLocation(String Location) {
        set_Value("Location", Location);
    }

    public int getXX_MeetingRoom_ID() {
        Integer ii = (Integer) get_Value("XX_MeetingRoom_ID");
        return (ii != null) ? ii : 0;
    }
    public void setXX_MeetingRoom_ID(int XX_MeetingRoom_ID) {
        set_Value("XX_MeetingRoom_ID", XX_MeetingRoom_ID);
    }

    public String getMeetingLink() {
        return (String) get_Value("MeetingLink");
    }
    public void setMeetingLink(String MeetingLink) {
        set_Value("MeetingLink", MeetingLink);
    }

    public String getExternalMeetingId() {
        return (String) get_Value("ExternalMeetingId");
    }
    public void setExternalMeetingId(String ExternalMeetingId) {
        set_Value("ExternalMeetingId", ExternalMeetingId);
    }

    public String getMeetingPassword() {
        return (String) get_Value("MeetingPassword");
    }
    public void setMeetingPassword(String MeetingPassword) {
        set_Value("MeetingPassword", MeetingPassword);
    }

    public String getCalendarEventId() {
        return (String) get_Value("CalendarEventId");
    }
    public void setCalendarEventId(String CalendarEventId) {
        set_Value("CalendarEventId", CalendarEventId);
    }

    public int getChairperson_ID() {
        Integer ii = (Integer) get_Value("Chairperson_ID");
        return (ii != null) ? ii : 0;
    }
    public void setChairperson_ID(int Chairperson_ID) {
        set_Value("Chairperson_ID", Chairperson_ID);
    }

    public int getSecretary_ID() {
        Integer ii = (Integer) get_Value("Secretary_ID");
        return (ii != null) ? ii : 0;
    }
    public void setSecretary_ID(int Secretary_ID) {
        set_Value("Secretary_ID", Secretary_ID);
    }

    public String getDescription() {
        return (String) get_Value("Description");
    }
    public void setDescription(String Description) {
        set_Value("Description", Description);
    }

    public String getNotes() {
        return (String) get_Value("Notes");
    }
    public void setNotes(String Notes) {
        set_Value("Notes", Notes);
    }

    // =========================================================================
    // Business Methods
    // =========================================================================

    /**
     * Check if this meeting is an online meeting (Google Meet or Zoom).
     */
    public boolean isOnline() {
        return MEETINGMODE_Online.equals(getMeetingMode())
            || MEETINGMODE_Hybrid.equals(getMeetingMode());
    }

    /**
     * Check if this meeting uses Google Meet.
     */
    public boolean isGoogleMeet() {
        return PLATFORM_GoogleMeet.equals(getMeetingPlatform());
    }

    /**
     * Check if this meeting uses Zoom.
     */
    public boolean isZoom() {
        return PLATFORM_Zoom.equals(getMeetingPlatform());
    }

    /**
     * Transition meeting status through the lifecycle.
     * @param newStatus target status
     * @return true if transition is valid
     */
    public boolean changeStatus(String newStatus) {
        String current = getDocStatus();
        boolean valid = switch (newStatus) {
            case DOCSTATUS_PendingApproval -> DOCSTATUS_Draft.equals(current);
            case DOCSTATUS_Approved        -> DOCSTATUS_PendingApproval.equals(current);
            case DOCSTATUS_Scheduled       -> DOCSTATUS_Approved.equals(current);
            case DOCSTATUS_InProgress      -> DOCSTATUS_Scheduled.equals(current) || DOCSTATUS_Approved.equals(current);
            case DOCSTATUS_Completed       -> DOCSTATUS_InProgress.equals(current) || DOCSTATUS_Scheduled.equals(current);
            case DOCSTATUS_Cancelled       -> !DOCSTATUS_Completed.equals(current) && !DOCSTATUS_Cancelled.equals(current);
            default                        -> false;
        };

        if (valid) {
            setDocStatus(newStatus);
        }
        return valid;
    }

    // =========================================================================
    // beforeSave / afterSave hooks
    // =========================================================================

    @Override
    protected boolean beforeSave(boolean newRecord) {
        // Auto-generate document number for new records
        if (newRecord && (getDocumentNo() == null || getDocumentNo().isBlank())) {
            String docNo = org.compiere.model.MSequence.getDocumentNo(
                getAD_Client_ID(), "XX_ManagementMeeting", get_TrxName(), this);
            if (docNo != null && !docNo.isBlank())
                setDocumentNo(docNo);
        }

        // Default status
        if (newRecord && getDocStatus() == null) {
            setDocStatus(DOCSTATUS_Draft);
        }

        // Validate end time > start time
        if (getStartTime() != null && getEndTime() != null) {
            if (getEndTime().before(getStartTime())) {
                log.saveError("Error", "End Time must be after Start Time");
                return false;
            }
        }

        return true;
    }

    @Override
    protected boolean afterSave(boolean newRecord, boolean success) {
        return success;
    }
}
