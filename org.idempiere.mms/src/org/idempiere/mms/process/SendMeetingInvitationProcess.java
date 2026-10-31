package org.idempiere.mms.process;

import org.compiere.model.MUser;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.EMail;
import org.compiere.util.Env;
import org.idempiere.mms.model.MManagementMeeting;
import org.idempiere.mms.model.MMeetingAgenda;
import org.idempiere.mms.model.MMeetingParticipant;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * SendMeetingInvitationProcess — Sends email invitations to all participants
 *
 * Register in iDempiere:
 *   Application Dictionary → Process
 *   Name: Send Meeting Invitations
 *   ClassName: org.idempiere.mms.process.SendMeetingInvitationProcess
 *   Table: XX_ManagementMeeting
 *
 * Reads participants, builds invitation email with meeting details,
 * and sends via iDempiere's built-in EMail facility.
 * Updates InvitationStatus to SENT or FAILED.
 */
public class SendMeetingInvitationProcess extends SvrProcess {

    private int p_MeetingId = 0;

    @Override
    protected void prepare() {
        ProcessInfoParameter[] para = getParameter();
        for (ProcessInfoParameter p : para) {
            if ("XX_ManagementMeeting_ID".equals(p.getParameterName()))
                p_MeetingId = p.getParameterAsInt();
        }
        if (p_MeetingId == 0) p_MeetingId = getRecord_ID();
    }

    @Override
    protected String doIt() throws Exception {
        if (p_MeetingId == 0) return "Error: No meeting selected";

        MManagementMeeting meeting = new MManagementMeeting(getCtx(), p_MeetingId, get_TrxName());
        if (meeting.get_ID() == 0) return "Error: Meeting not found";

        // Load agenda items for the invitation body
        List<String> agendaItems = getAgendaItems(p_MeetingId);

        // Load participants
        List<Integer> participantIds = getParticipantIds(p_MeetingId);

        if (participantIds.isEmpty()) {
            return "No participants found for this meeting";
        }

        int sent = 0;
        int failed = 0;

        for (int participantId : participantIds) {
            MMeetingParticipant participant = new MMeetingParticipant(getCtx(), participantId, get_TrxName());
            MUser user = MUser.get(getCtx(), participant.getAD_User_ID());

            if (user == null || user.getEMail() == null || user.getEMail().isBlank()) {
                addLog("Skipped: User ID " + participant.getAD_User_ID() + " has no email");
                failed++;
                continue;
            }

            try {
                boolean ok = sendInvitationEmail(user, meeting, agendaItems);
                if (ok) {
                    participant.setInvitationStatus(MMeetingParticipant.INVITATION_Sent);
                    participant.save();
                    sent++;
                    addLog("Sent to: " + user.getEMail());
                } else {
                    participant.setInvitationStatus(MMeetingParticipant.INVITATION_Failed);
                    participant.save();
                    failed++;
                    addLog("Failed: " + user.getEMail());
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to send invitation to " + user.getEMail(), e);
                participant.setInvitationStatus(MMeetingParticipant.INVITATION_Failed);
                participant.save();
                failed++;
            }
        }

        return String.format("Invitations: %d sent, %d failed out of %d participants",
                             sent, failed, participantIds.size());
    }

    private boolean sendInvitationEmail(MUser user, MManagementMeeting meeting, List<String> agendaItems) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMMM dd, yyyy");
        SimpleDateFormat stf = new SimpleDateFormat("hh:mm a");

        String dateStr = sdf.format(meeting.getMeetingDate());
        String startStr = stf.format(meeting.getStartTime());
        String endStr   = stf.format(meeting.getEndTime());

        StringBuilder sb = new StringBuilder();
        sb.append("Dear ").append(user.getName()).append(",\n\n");
        sb.append("You are invited to the following meeting:\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Meeting : ").append(meeting.getSubject()).append("\n");
        sb.append("Date    : ").append(dateStr).append("\n");
        sb.append("Time    : ").append(startStr).append(" - ").append(endStr).append("\n");

        // Location or online link
        if (meeting.isOnline() && meeting.getMeetingLink() != null) {
            sb.append("Platform: ").append(meeting.getMeetingPlatform()).append("\n");
            sb.append("Join URL: ").append(meeting.getMeetingLink()).append("\n");
            if (meeting.getMeetingPassword() != null && !meeting.getMeetingPassword().isBlank()) {
                sb.append("Password: ").append(meeting.getMeetingPassword()).append("\n");
            }
        } else if (meeting.getLocation() != null) {
            sb.append("Location: ").append(meeting.getLocation()).append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        if (!agendaItems.isEmpty()) {
            sb.append("AGENDA:\n");
            for (int i = 0; i < agendaItems.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(agendaItems.get(i)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Please confirm your attendance.\n\n");
        sb.append("Best regards,\nManagement Team\n");

        // Use iDempiere's built-in EMail
        try {
            EMail email = new EMail(
                org.compiere.model.MSysConfig.getValue("MAIL_SERVER", "localhost"),
                org.compiere.model.MSysConfig.getValue("MAIL_FROM", "noreply@company.com"),
                user.getEMail(),
                "Meeting Invitation: " + meeting.getSubject(),
                sb.toString()
            );
            email.createAuthenticator(
                org.compiere.model.MSysConfig.getValue("MAIL_USER", ""),
                org.compiere.model.MSysConfig.getValue("MAIL_PASSWORD", "")
            );
            String result = email.send();
            return "OK".equals(result);
        } catch (Exception e) {
            log.log(Level.WARNING, "Email send error: " + e.getMessage(), e);
            return false;
        }
    }

    private List<String> getAgendaItems(int meetingId) {
        List<String> items = new ArrayList<>();
        String sql = "SELECT AgendaTitle FROM adempiere.xx_meetingagenda " +
                     "WHERE XX_ManagementMeeting_ID = ? AND IsActive = 'Y' " +
                     "ORDER BY LineNo";
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = DB.prepareStatement(sql, get_TrxName());
            pstmt.setInt(1, meetingId);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                items.add(rs.getString(1));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Error loading agenda: " + e.getMessage(), e);
        } finally {
            DB.close(rs, pstmt);
        }
        return items;
    }

    private List<Integer> getParticipantIds(int meetingId) {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT XX_MeetingParticipant_ID FROM adempiere.xx_meetingparticipant " +
                     "WHERE XX_ManagementMeeting_ID = ? AND IsActive = 'Y'";
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = DB.prepareStatement(sql, get_TrxName());
            pstmt.setInt(1, meetingId);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Error loading participants: " + e.getMessage(), e);
        } finally {
            DB.close(rs, pstmt);
        }
        return ids;
    }
}
