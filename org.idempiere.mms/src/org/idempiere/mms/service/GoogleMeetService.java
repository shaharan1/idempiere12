package org.idempiere.mms.service;

import org.compiere.model.MSysConfig;
import org.compiere.util.DB;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GoogleMeetService — Creates Google Calendar events with Meet conferencing
 *
 * Configuration (stored in iDempiere AD_SysConfig — NEVER in source code):
 *
 *   Key: MMS_GOOGLE_SERVICE_ACCOUNT_EMAIL
 *   Value: serviceaccount@project.iam.gserviceaccount.com
 *
 *   Key: MMS_GOOGLE_PRIVATE_KEY
 *   Value: -----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----
 *
 *   Key: MMS_GOOGLE_CALENDAR_ID
 *   Value: primary (or specific calendar ID)
 *
 * To set these in iDempiere:
 *   System Admin → General Rules → System Configuration → System Config
 *   Add above keys with their values.
 *
 * IMPORTANT: The private key should ideally be stored encrypted.
 * For production, consider using a secrets manager or vault.
 *
 * Google API Prerequisites:
 *   1. Enable Google Calendar API in Google Cloud Console
 *   2. Create a Service Account
 *   3. Download JSON key file
 *   4. Grant the service account "Calendar Editor" permission on the calendar
 *   5. Add credentials to iDempiere SysConfig
 *
 * NOTE: This implementation uses a simplified HTTP approach.
 * For production, add the google-api-java-client JAR to lib/ folder
 * and update MANIFEST.MF Bundle-ClassPath accordingly.
 */
public class GoogleMeetService {

    private static final Logger log = Logger.getLogger(GoogleMeetService.class.getName());

    // SysConfig keys — credentials stored in DB, not in code
    private static final String SYSCONFIG_SERVICE_EMAIL = "MMS_GOOGLE_SERVICE_ACCOUNT_EMAIL";
    private static final String SYSCONFIG_PRIVATE_KEY   = "MMS_GOOGLE_PRIVATE_KEY";
    private static final String SYSCONFIG_CALENDAR_ID   = "MMS_GOOGLE_CALENDAR_ID";

    // =========================================================================
    // Result class
    // =========================================================================
    public static class MeetingResult {
        private final boolean success;
        private final String meetLink;
        private final String calendarEventId;
        private final String eventId;
        private final String errorMessage;

        public MeetingResult(boolean success, String meetLink, String calendarEventId,
                             String eventId, String errorMessage) {
            this.success         = success;
            this.meetLink        = meetLink;
            this.calendarEventId = calendarEventId;
            this.eventId         = eventId;
            this.errorMessage    = errorMessage;
        }

        public static MeetingResult success(String meetLink, String calendarEventId, String eventId) {
            return new MeetingResult(true, meetLink, calendarEventId, eventId, null);
        }

        public static MeetingResult failure(String errorMessage) {
            return new MeetingResult(false, null, null, null, errorMessage);
        }

        public boolean isSuccess()         { return success; }
        public String getMeetLink()        { return meetLink; }
        public String getCalendarEventId() { return calendarEventId; }
        public String getEventId()         { return eventId; }
        public String getErrorMessage()    { return errorMessage; }
    }

    // =========================================================================
    // Main API method
    // =========================================================================

    /**
     * Creates a Google Calendar event with Google Meet conference link.
     *
     * @param subject       Meeting subject/title
     * @param meetingDate   Meeting date
     * @param startTime     Start time
     * @param endTime       End time
     * @param meetingId     XX_ManagementMeeting_ID (for loading participants)
     * @param trxName       Transaction name
     * @return MeetingResult with meet link and event ID
     */
    public MeetingResult createMeeting(String subject, Timestamp meetingDate,
                                       Timestamp startTime, Timestamp endTime,
                                       int meetingId, String trxName) {
        // Read credentials from SysConfig (never hardcoded!)
        String serviceEmail = MSysConfig.getValue(SYSCONFIG_SERVICE_EMAIL, "");
        String privateKey   = MSysConfig.getValue(SYSCONFIG_PRIVATE_KEY, "");
        String calendarId   = MSysConfig.getValue(SYSCONFIG_CALENDAR_ID, "primary");

        if (serviceEmail.isBlank() || privateKey.isBlank()) {
            return MeetingResult.failure(
                "Google Meet credentials not configured. " +
                "Please set " + SYSCONFIG_SERVICE_EMAIL + " and " + SYSCONFIG_PRIVATE_KEY +
                " in System Configuration (Admin → General Rules → System Configuration)."
            );
        }

        try {
            // Get access token using Service Account JWT
            String accessToken = getServiceAccountToken(serviceEmail, privateKey);

            // Load attendee emails from participants
            List<String> attendeeEmails = getParticipantEmails(meetingId, trxName);

            // Build the Calendar API request body (JSON)
            String eventJson = buildEventJson(subject, startTime, endTime, attendeeEmails);

            // Call Google Calendar API: insert event with conferenceDataVersion=1
            String calendarApiUrl = "https://www.googleapis.com/calendar/v3/calendars/"
                + calendarId + "/events?conferenceDataVersion=1&sendUpdates=all";

            String responseJson = httpPost(calendarApiUrl, accessToken, eventJson);

            // Parse response to extract Meet link and event ID
            String meetLink       = extractJsonValue(responseJson, "uri");
            String calendarEventId = extractJsonValue(responseJson, "id");

            if (meetLink != null && !meetLink.isBlank()) {
                log.info("MMS: Google Meet created: " + meetLink);
                return MeetingResult.success(meetLink, calendarEventId, calendarEventId);
            } else {
                return MeetingResult.failure("Could not extract Meet link from response: " + responseJson);
            }

        } catch (Exception e) {
            log.log(Level.SEVERE, "Google Meet creation error: " + e.getMessage(), e);
            return MeetingResult.failure("API Error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Build Google Calendar Event JSON
    // =========================================================================
    private String buildEventJson(String subject, Timestamp startTime, Timestamp endTime,
                                  List<String> attendeeEmails) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        sdf.setTimeZone(TimeZone.getDefault());

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"summary\": \"").append(escapeJson(subject)).append("\",\n");
        json.append("  \"start\": {\"dateTime\": \"").append(sdf.format(startTime)).append("\"},\n");
        json.append("  \"end\": {\"dateTime\": \"").append(sdf.format(endTime)).append("\"},\n");

        // Conference data request — Google will create Meet link
        json.append("  \"conferenceData\": {\n");
        json.append("    \"createRequest\": {\n");
        json.append("      \"requestId\": \"mms-").append(System.currentTimeMillis()).append("\",\n");
        json.append("      \"conferenceSolutionKey\": {\"type\": \"hangoutsMeet\"}\n");
        json.append("    }\n");
        json.append("  },\n");

        // Attendees
        if (!attendeeEmails.isEmpty()) {
            json.append("  \"attendees\": [\n");
            for (int i = 0; i < attendeeEmails.size(); i++) {
                json.append("    {\"email\": \"").append(escapeJson(attendeeEmails.get(i))).append("\"}");
                if (i < attendeeEmails.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  ],\n");
        }

        json.append("  \"reminders\": {\n");
        json.append("    \"useDefault\": false,\n");
        json.append("    \"overrides\": [\n");
        json.append("      {\"method\": \"email\", \"minutes\": 1440},\n");  // 24h
        json.append("      {\"method\": \"popup\", \"minutes\": 60},\n");    // 1h
        json.append("      {\"method\": \"popup\", \"minutes\": 15}\n");     // 15min
        json.append("    ]\n");
        json.append("  }\n");
        json.append("}");

        return json.toString();
    }

    // =========================================================================
    // Get OAuth2 token using Service Account (JWT assertion)
    // =========================================================================
    private String getServiceAccountToken(String serviceEmail, String privateKey) throws Exception {
        // Build JWT header + claims
        long now = System.currentTimeMillis() / 1000;
        String scope = "https://www.googleapis.com/auth/calendar";

        String header = base64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = base64url(
            "{\"iss\":\"" + serviceEmail + "\"," +
            "\"scope\":\"" + scope + "\"," +
            "\"aud\":\"https://oauth2.googleapis.com/token\"," +
            "\"exp\":" + (now + 3600) + "," +
            "\"iat\":" + now + "}"
        );

        String unsignedJWT = header + "." + claims;

        // Sign with RS256 using private key
        java.security.PrivateKey pk = loadPrivateKey(privateKey);
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(pk);
        sig.update(unsignedJWT.getBytes("UTF-8"));
        String signature = java.util.Base64.getUrlEncoder().withoutPadding()
                               .encodeToString(sig.sign());

        String jwt = unsignedJWT + "." + signature;

        // Exchange JWT for access token
        String tokenUrl = "https://oauth2.googleapis.com/token";
        String body = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + jwt;

        URL url = new URL(tokenUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }

        String response = new String(conn.getInputStream().readAllBytes(), "UTF-8");
        return extractJsonValue(response, "access_token");
    }

    private java.security.PrivateKey loadPrivateKey(String pem) throws Exception {
        // Strip PEM headers and decode
        String key = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] keyBytes = java.util.Base64.getDecoder().decode(key);
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    // =========================================================================
    // HTTP helper
    // =========================================================================
    private String httpPost(String apiUrl, String accessToken, String jsonBody) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            return new String(conn.getInputStream().readAllBytes(), "UTF-8");
        } else {
            String error = new String(conn.getErrorStream().readAllBytes(), "UTF-8");
            throw new IOException("HTTP " + code + ": " + error);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Load participant emails from DB */
    private List<String> getParticipantEmails(int meetingId, String trxName) {
        List<String> emails = new ArrayList<>();
        String sql = "SELECT DISTINCT u.EMail " +
                     "FROM adempiere.xx_meetingparticipant p " +
                     "JOIN adempiere.ad_user u ON u.AD_User_ID = p.AD_User_ID " +
                     "WHERE p.XX_ManagementMeeting_ID = ? " +
                     "  AND p.IsActive = 'Y' " +
                     "  AND u.EMail IS NOT NULL AND u.EMail <> ''";
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = DB.prepareStatement(sql, trxName);
            pstmt.setInt(1, meetingId);
            rs = pstmt.executeQuery();
            while (rs.next()) emails.add(rs.getString(1));
        } catch (Exception e) {
            log.log(Level.WARNING, "getParticipantEmails error: " + e.getMessage(), e);
        } finally {
            DB.close(rs, pstmt);
        }
        return emails;
    }

    private String base64url(String input) throws Exception {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                   .encodeToString(input.getBytes("UTF-8"));
    }

    /** Simple JSON value extractor (no external library needed) */
    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return (end > start) ? json.substring(start, end) : null;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
