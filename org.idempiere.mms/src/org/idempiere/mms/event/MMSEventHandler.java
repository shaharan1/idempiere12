package org.idempiere.mms.event;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.compiere.model.PO;
import org.idempiere.mms.model.MManagementMeeting;
import org.idempiere.mms.model.MMeetingAction;
import org.osgi.service.event.Event;

import java.sql.Timestamp;
import java.util.logging.Level;

/**
 * MMSEventHandler — Listens to model save events for MMS tables.
 *
 * Registered in: OSGI-INF/mms_event_handler.xml
 * Events: org/adempiere/model/beforeSave, org/adempiere/model/afterSave
 *
 * Responsibilities:
 * 1. Auto-detect overdue Action Items on save
 * 2. Validate status transitions
 * 3. Trigger online meeting creation (Google Meet / Zoom) after save
 * 4. Trigger invitation sending after meeting is scheduled
 */
public class MMSEventHandler extends AbstractEventHandler {

    @Override
    protected void initialize() {
        // Register table-specific event listeners
        registerTableEvent(IEventTopics.PO_BEFORE_SAVE, MManagementMeeting.Table_Name);
        registerTableEvent(IEventTopics.PO_AFTER_SAVE,  MManagementMeeting.Table_Name);
        registerTableEvent(IEventTopics.PO_BEFORE_SAVE, MMeetingAction.Table_Name);
        registerTableEvent(IEventTopics.PO_AFTER_SAVE,  MMeetingAction.Table_Name);
    }

    @Override
    protected void doHandleEvent(Event event) {
        String type = event.getTopic();
        PO po = getPO(event);

        if (po == null) return;

        try {
            if (po instanceof MManagementMeeting meeting) {
                handleMeetingEvent(type, meeting, event);
            } else if (po instanceof MMeetingAction action) {
                handleActionEvent(type, action, event);
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "MMSEventHandler error: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Meeting Events
    // =========================================================================

    private void handleMeetingEvent(String type, MManagementMeeting meeting, Event event) {
        boolean newRecord = isNewRecord(event);

        if (IEventTopics.PO_BEFORE_SAVE.equals(type)) {
            // Validate: endTime must be after startTime
            if (meeting.getStartTime() != null && meeting.getEndTime() != null) {
                if (meeting.getEndTime().before(meeting.getStartTime())) {
                    // This validation is also in beforeSave() of MManagementMeeting
                    // Extra check here for event-level validation
                    log.warning("MMS: EndTime is before StartTime for meeting: " + meeting.getDocumentNo());
                }
            }
        }

        if (IEventTopics.PO_AFTER_SAVE.equals(type)) {
            String status = meeting.getDocStatus();

            // When meeting is SCHEDULED and is an online meeting → trigger meeting creation
            // We run this async to not block the save transaction
            if (MManagementMeeting.DOCSTATUS_Scheduled.equals(status) && meeting.isOnline()) {
                if (meeting.getMeetingLink() == null || meeting.getMeetingLink().isBlank()) {
                    // Spawn async thread to create online meeting
                    // NOTE: In Phase 5/6, we call GoogleMeetService / ZoomService here
                    log.info("MMS: Online meeting link required for Meeting: " + meeting.getDocumentNo()
                           + " | Platform: " + meeting.getMeetingPlatform());
                    triggerOnlineMeetingCreation(meeting);
                }
            }

            // When meeting is APPROVED → send invitations
            if (MManagementMeeting.DOCSTATUS_Approved.equals(status) || 
                MManagementMeeting.DOCSTATUS_Scheduled.equals(status)) {
                log.info("MMS: Meeting " + meeting.getDocumentNo() + " status: " + status 
                       + " — invitation trigger point");
                // In Phase 7: call SendMeetingInvitationProcess
            }
        }
    }

    // =========================================================================
    // Action Item Events
    // =========================================================================

    private void handleActionEvent(String type, MMeetingAction action, Event event) {
        if (IEventTopics.PO_BEFORE_SAVE.equals(type)) {
            // Auto-detect overdue before every save
            if (!MMeetingAction.STATUS_Completed.equals(action.getStatus()) &&
                !MMeetingAction.STATUS_Cancelled.equals(action.getStatus())) {

                Timestamp dueDate = action.getDueDate();
                if (dueDate != null && dueDate.before(new Timestamp(System.currentTimeMillis()))) {
                    action.setStatus(MMeetingAction.STATUS_Overdue);
                    log.info("MMS: Action '" + action.getTask() + "' is now OVERDUE");
                }
            }
        }
    }

    // =========================================================================
    // Helper: Trigger online meeting creation in background thread
    // =========================================================================
    private void triggerOnlineMeetingCreation(MManagementMeeting meeting) {
        // Run in a separate thread to avoid blocking the main save transaction
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000); // Wait for transaction to commit
                String platform = meeting.getMeetingPlatform();
                int meetingId = meeting.getXX_ManagementMeeting_ID();

                if (MManagementMeeting.PLATFORM_GoogleMeet.equals(platform)) {
                    log.info("MMS: Creating Google Meet for meeting ID: " + meetingId);
                    // Phase 5: GoogleMeetService.createMeeting(meetingId);
                } else if (MManagementMeeting.PLATFORM_Zoom.equals(platform)) {
                    log.info("MMS: Creating Zoom meeting for meeting ID: " + meetingId);
                    // Phase 6: ZoomService.createMeeting(meetingId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MMS-OnlineMeeting-" + meeting.getXX_ManagementMeeting_ID());
        t.setDaemon(true);
        t.start();
    }
}
