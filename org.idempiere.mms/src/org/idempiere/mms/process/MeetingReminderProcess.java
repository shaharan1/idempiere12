package org.idempiere.mms.process;

import org.compiere.model.MUser;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.EMail;
import org.idempiere.mms.model.MManagementMeeting;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.logging.Level;

/**
 * MeetingReminderProcess — Sends reminders before meetings
 *
 * Register in iDempiere:
 *   Application Dictionary → Scheduler
 *   Name: Meeting Reminder Job
 *   ClassName: org.idempiere.mms.process.MeetingReminderProcess
 *   Schedule: Every 15 minutes (*/15 * * * *)
 *
 * Checks for meetings happening in:
 *   - 24 hours (1440 min)
 *   - 1 hour (60 min)
 *   - 15 minutes
 *
 * Also detects OVERDUE Action Items and marks them.
 *
 * NOTE: API credentials (MAIL_SERVER, etc.) are read from
 * iDempiere SysConfig — never hardcoded.
 */
public class MeetingReminderProcess extends SvrProcess {

    // Reminder windows in minutes
    private static final int[] REMINDER_WINDOWS = {1440, 60, 15};

    @Override
    protected void prepare() {
        // No parameters needed — this is a scheduler job
    }

    @Override
    protected String doIt() throws Exception {
        int remindersSent = 0;
        int overdueUpdated = 0;

        // =====================================================================
        // 1. Send Reminders
        // =====================================================================
        for (int windowMin : REMINDER_WINDOWS) {
            remindersSent += sendRemindersForWindow(windowMin);
        }

        // =====================================================================
        // 2. Update Overdue Action Items
        // =====================================================================
        overdueUpdated = updateOverdueActions();

        return String.format("Reminders sent: %d | Overdue actions updated: %d",
                             remindersSent, overdueUpdated);
    }

    private int sendRemindersForWindow(int minutesBefore) {
        // Find meetings starting in exactly [window-2 min, window+2 min] from now
        // Using 2-minute tolerance to avoid missing runs
        String sql = "SELECT m.XX_ManagementMeeting_ID, m.Subject, m.StartTime, m.MeetingLink, " +
                     "       u.AD_User_ID, u.Name, u.EMail " +
                     "FROM adempiere.xx_managementmeeting m " +
                     "JOIN adempiere.xx_meetingparticipant p ON p.XX_ManagementMeeting_ID = m.XX_ManagementMeeting_ID " +
                     "JOIN adempiere.ad_user u ON u.AD_User_ID = p.AD_User_ID " +
                     "WHERE m.IsActive = 'Y' " +
                     "  AND m.DocStatus IN ('AP','SC','IP') " +
                     "  AND m.StartTime BETWEEN NOW() + INTERVAL '" + (minutesBefore - 2) + " minutes' " +
                     "                      AND NOW() + INTERVAL '" + (minutesBefore + 2) + " minutes' " +
                     "  AND u.EMail IS NOT NULL AND u.EMail <> ''";

        int count = 0;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            pstmt = DB.prepareStatement(sql, null);
            rs = pstmt.executeQuery();

            while (rs.next()) {
                String subject  = rs.getString("Subject");
                Timestamp start = rs.getTimestamp("StartTime");
                String link     = rs.getString("MeetingLink");
                String userName = rs.getString("Name");
                String email    = rs.getString("EMail");

                boolean ok = sendReminderEmail(email, userName, subject, start, link, minutesBefore);
                if (ok) count++;
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Reminder query error: " + e.getMessage(), e);
        } finally {
            DB.close(rs, pstmt);
        }

        return count;
    }

    private boolean sendReminderEmail(String toEmail, String userName, String subject,
                                      Timestamp startTime, String link, int minutesBefore) {
        SimpleDateFormat stf = new SimpleDateFormat("hh:mm a, MMMM dd yyyy");
        String timeStr = stf.format(startTime);

        String windowLabel = minutesBefore >= 1440
            ? "24 hours"
            : minutesBefore >= 60 ? "1 hour" : "15 minutes";

        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(userName).append(",\n\n");
        body.append("REMINDER: Your meeting starts in ").append(windowLabel).append("!\n\n");
        body.append("Meeting : ").append(subject).append("\n");
        body.append("Time    : ").append(timeStr).append("\n");
        if (link != null && !link.isBlank()) {
            body.append("Join URL: ").append(link).append("\n");
        }
        body.append("\nPlease be on time.\n\nBest regards,\nMeeting System");

        try {
            EMail email = new EMail(
                org.compiere.model.MSysConfig.getValue("MAIL_SERVER", "localhost"),
                org.compiere.model.MSysConfig.getValue("MAIL_FROM", "noreply@company.com"),
                toEmail,
                "REMINDER: " + windowLabel + " — " + subject,
                body.toString()
            );
            email.createAuthenticator(
                org.compiere.model.MSysConfig.getValue("MAIL_USER", ""),
                org.compiere.model.MSysConfig.getValue("MAIL_PASSWORD", "")
            );
            String result = email.send();
            return "OK".equals(result);
        } catch (Exception e) {
            log.log(Level.WARNING, "Reminder email error: " + e.getMessage(), e);
            return false;
        }
    }

    private int updateOverdueActions() {
        // Batch update overdue action items using SQL for efficiency
        String sql = "UPDATE adempiere.xx_meetingaction " +
                     "SET Status = 'OVERDUE', Updated = NOW() " +
                     "WHERE Status IN ('OPEN','IN_PROGRESS') " +
                     "  AND DueDate < NOW() " +
                     "  AND IsActive = 'Y'";
        int updated = DB.executeUpdateEx(sql, null);
        if (updated > 0) {
            addLog(updated + " action items marked as OVERDUE");
        }
        return updated;
    }
}
