package org.idempiere.mms.process;

import org.compiere.process.SvrProcess;
import org.compiere.process.ProcessInfoParameter;
import org.idempiere.mms.model.MManagementMeeting;
import org.idempiere.mms.service.GoogleMeetService;
import org.idempiere.mms.service.ZoomService;

import java.util.logging.Level;

/**
 * CreateOnlineMeetingProcess — Creates Google Meet or Zoom meeting
 *
 * Register in iDempiere:
 *   Application Dictionary → Process
 *   Name: Create Online Meeting
 *   ClassName: org.idempiere.mms.process.CreateOnlineMeetingProcess
 *   Table: XX_ManagementMeeting
 *
 * This process is triggered from the Meeting window toolbar button.
 * It reads the MeetingPlatform field and calls the appropriate service.
 */
public class CreateOnlineMeetingProcess extends SvrProcess {

    private int p_XX_ManagementMeeting_ID = 0;

    @Override
    protected void prepare() {
        // Read process parameters
        ProcessInfoParameter[] para = getParameter();
        for (ProcessInfoParameter p : para) {
            String name = p.getParameterName();
            if ("XX_ManagementMeeting_ID".equals(name)) {
                p_XX_ManagementMeeting_ID = p.getParameterAsInt();
            }
        }

        // If run from window, get record ID from process info
        if (p_XX_ManagementMeeting_ID == 0) {
            p_XX_ManagementMeeting_ID = getRecord_ID();
        }
    }

    @Override
    protected String doIt() throws Exception {
        if (p_XX_ManagementMeeting_ID == 0) {
            return "Error: No Meeting selected";
        }

        MManagementMeeting meeting = new MManagementMeeting(getCtx(), p_XX_ManagementMeeting_ID, get_TrxName());

        if (meeting.get_ID() == 0) {
            return "Error: Meeting not found";
        }

        // Check if already has a meeting link
        if (meeting.getMeetingLink() != null && !meeting.getMeetingLink().isBlank()) {
            return "Meeting link already exists: " + meeting.getMeetingLink();
        }

        String platform = meeting.getMeetingPlatform();
        String result;

        try {
            if (MManagementMeeting.PLATFORM_GoogleMeet.equals(platform)) {
                result = createGoogleMeet(meeting);
            } else if (MManagementMeeting.PLATFORM_Zoom.equals(platform)) {
                result = createZoomMeeting(meeting);
            } else {
                return "Platform " + platform + " does not require online meeting creation";
            }

            // Save the updated meeting record
            if (!meeting.save()) {
                return "Error: Could not save meeting link to database";
            }

            return result;

        } catch (Exception e) {
            log.log(Level.SEVERE, "Error creating online meeting: " + e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    private String createGoogleMeet(MManagementMeeting meeting) {
        log.info("MMS: Creating Google Meet for " + meeting.getDocumentNo());

        GoogleMeetService service = new GoogleMeetService();
        GoogleMeetService.MeetingResult result = service.createMeeting(
            meeting.getSubject(),
            meeting.getMeetingDate(),
            meeting.getStartTime(),
            meeting.getEndTime(),
            meeting.getXX_ManagementMeeting_ID(),
            get_TrxName()
        );

        if (result.isSuccess()) {
            meeting.setMeetingLink(result.getMeetLink());
            meeting.setCalendarEventId(result.getCalendarEventId());
            meeting.setExternalMeetingId(result.getEventId());
            addLog("Google Meet created: " + result.getMeetLink());
            return "Google Meet created successfully! Link: " + result.getMeetLink();
        } else {
            return "Google Meet creation failed: " + result.getErrorMessage();
        }
    }

    private String createZoomMeeting(MManagementMeeting meeting) {
        log.info("MMS: Creating Zoom meeting for " + meeting.getDocumentNo());

        ZoomService service = new ZoomService();
        ZoomService.ZoomMeetingResult result = service.createMeeting(
            meeting.getSubject(),
            meeting.getMeetingDate(),
            (int)((meeting.getEndTime().getTime() - meeting.getStartTime().getTime()) / 60000),
            meeting.getAD_Client_ID()
        );

        if (result.isSuccess()) {
            meeting.setMeetingLink(result.getJoinUrl());
            meeting.setExternalMeetingId(String.valueOf(result.getMeetingId()));
            meeting.setMeetingPassword(result.getPassword());
            addLog("Zoom Meeting ID: " + result.getMeetingId());
            addLog("Join URL: " + result.getJoinUrl());
            return "Zoom meeting created! ID: " + result.getMeetingId() + " | URL: " + result.getJoinUrl();
        } else {
            return "Zoom creation failed: " + result.getErrorMessage();
        }
    }
}
