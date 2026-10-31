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
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ZoomService — Creates Zoom meetings via Zoom API v2
 *
 * Configuration (stored in iDempiere AD_SysConfig — NEVER in source code):
 *
 *   Key: MMS_ZOOM_ACCOUNT_ID
 *   Value: your-zoom-account-id (from Zoom Marketplace → App → App Credentials)
 *
 *   Key: MMS_ZOOM_CLIENT_ID
 *   Value: your-zoom-client-id
 *
 *   Key: MMS_ZOOM_CLIENT_SECRET
 *   Value: your-zoom-client-secret
 *
 * Steps to configure Zoom:
 *   1. Go to marketplace.zoom.us
 *   2. Create Server-to-Server OAuth app
 *   3. Note: Account ID, Client ID, Client Secret
 *   4. Add scope: meeting:write:admin
 *   5. Add above values in iDempiere:
 *      Admin → General Rules → System Configuration → System Config
 *
 * Zoom API: POST https://api.zoom.us/v2/users/me/meetings
 */
public class ZoomService {

    private static final Logger log = Logger.getLogger(ZoomService.class.getName());

    // SysConfig keys — all credentials from DB, never in code
    private static final String SYSCONFIG_ACCOUNT_ID    = "MMS_ZOOM_ACCOUNT_ID";
    private static final String SYSCONFIG_CLIENT_ID     = "MMS_ZOOM_CLIENT_ID";
    private static final String SYSCONFIG_CLIENT_SECRET = "MMS_ZOOM_CLIENT_SECRET";

    // Zoom API endpoints
    private static final String ZOOM_TOKEN_URL   = "https://zoom.us/oauth/token";
    private static final String ZOOM_MEETING_URL = "https://api.zoom.us/v2/users/me/meetings";

    // =========================================================================
    // Result class
    // =========================================================================
    public static class ZoomMeetingResult {
        private final boolean success;
        private final long    meetingId;
        private final String  joinUrl;
        private final String  password;
        private final String  startUrl;
        private final String  errorMessage;

        public ZoomMeetingResult(boolean success, long meetingId, String joinUrl,
                                 String password, String startUrl, String errorMessage) {
            this.success      = success;
            this.meetingId    = meetingId;
            this.joinUrl      = joinUrl;
            this.password     = password;
            this.startUrl     = startUrl;
            this.errorMessage = errorMessage;
        }

        public static ZoomMeetingResult success(long meetingId, String joinUrl, String password, String startUrl) {
            return new ZoomMeetingResult(true, meetingId, joinUrl, password, startUrl, null);
        }

        public static ZoomMeetingResult failure(String msg) {
            return new ZoomMeetingResult(false, 0, null, null, null, msg);
        }

        public boolean isSuccess()       { return success; }
        public long getMeetingId()       { return meetingId; }
        public String getJoinUrl()       { return joinUrl; }
        public String getPassword()      { return password; }
        public String getStartUrl()      { return startUrl; }
        public String getErrorMessage()  { return errorMessage; }
    }

    // =========================================================================
    // Main API method
    // =========================================================================

    /**
     * Creates a Zoom meeting.
     *
     * @param topic         Meeting topic/title
     * @param startTime     Start time
     * @param durationMin   Duration in minutes
     * @param clientId      AD_Client_ID (for reading SysConfig per client)
     * @return ZoomMeetingResult
     */
    public ZoomMeetingResult createMeeting(String topic, Timestamp startTime,
                                           int durationMin, int clientId) {
        // Read credentials from SysConfig
        String accountId     = MSysConfig.getValue(SYSCONFIG_ACCOUNT_ID, "", clientId, 0);
        String zoomClientId  = MSysConfig.getValue(SYSCONFIG_CLIENT_ID, "", clientId, 0);
        String clientSecret  = MSysConfig.getValue(SYSCONFIG_CLIENT_SECRET, "", clientId, 0);

        if (accountId.isBlank() || zoomClientId.isBlank() || clientSecret.isBlank()) {
            return ZoomMeetingResult.failure(
                "Zoom credentials not configured. Please set " +
                SYSCONFIG_ACCOUNT_ID + ", " + SYSCONFIG_CLIENT_ID + ", " +
                SYSCONFIG_CLIENT_SECRET + " in System Configuration."
            );
        }

        try {
            // Step 1: Get OAuth2 access token (Server-to-Server)
            String accessToken = getAccessToken(accountId, zoomClientId, clientSecret);

            // Step 2: Create Zoom meeting
            String meetingJson = buildMeetingJson(topic, startTime, durationMin);
            String response    = httpPost(ZOOM_MEETING_URL, accessToken, meetingJson);

            // Step 3: Parse response
            long meetingId = parseLongValue(response, "id");
            String joinUrl  = extractJsonValue(response, "join_url");
            String password = extractJsonValue(response, "password");
            String startUrl = extractJsonValue(response, "start_url");

            if (meetingId > 0 && joinUrl != null) {
                log.info("MMS: Zoom meeting created: ID=" + meetingId + " URL=" + joinUrl);
                return ZoomMeetingResult.success(meetingId, joinUrl, password, startUrl);
            } else {
                return ZoomMeetingResult.failure("Could not parse Zoom response: " + response);
            }

        } catch (Exception e) {
            log.log(Level.SEVERE, "Zoom API error: " + e.getMessage(), e);
            return ZoomMeetingResult.failure("API Error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Get Zoom Server-to-Server OAuth token
    // =========================================================================
    private String getAccessToken(String accountId, String clientId, String clientSecret) throws Exception {
        String credentials = java.util.Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes("UTF-8"));

        String tokenUrl = ZOOM_TOKEN_URL + "?grant_type=account_credentials&account_id=" + accountId;

        URL url = new URL(tokenUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Basic " + credentials);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            String response = new String(conn.getInputStream().readAllBytes(), "UTF-8");
            return extractJsonValue(response, "access_token");
        } else {
            String error = new String(conn.getErrorStream().readAllBytes(), "UTF-8");
            throw new IOException("Token request failed HTTP " + code + ": " + error);
        }
    }

    // =========================================================================
    // Build Zoom meeting JSON request body
    // =========================================================================
    private String buildMeetingJson(String topic, Timestamp startTime, int durationMin) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());

        // Zoom expects timezone name
        String tz = TimeZone.getDefault().getID();

        return "{\n" +
               "  \"topic\": \"" + escapeJson(topic) + "\",\n" +
               "  \"type\": 2,\n" +  // Scheduled meeting
               "  \"start_time\": \"" + sdf.format(startTime) + "\",\n" +
               "  \"duration\": " + durationMin + ",\n" +
               "  \"timezone\": \"" + tz + "\",\n" +
               "  \"settings\": {\n" +
               "    \"host_video\": true,\n" +
               "    \"participant_video\": true,\n" +
               "    \"join_before_host\": false,\n" +
               "    \"mute_upon_entry\": true,\n" +
               "    \"auto_recording\": \"none\",\n" +
               "    \"waiting_room\": false\n" +
               "  }\n" +
               "}";
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
    // JSON parsing helpers (no external library needed)
    // =========================================================================
    private String extractJsonValue(String json, String key) {
        // Handle string values
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start >= 0) {
            start += search.length();
            int end = json.indexOf("\"", start);
            if (end > start) return json.substring(start, end);
        }
        return null;
    }

    private long parseLongValue(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        // Skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) end++;
        if (end > start) {
            try { return Long.parseLong(json.substring(start, end)); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
