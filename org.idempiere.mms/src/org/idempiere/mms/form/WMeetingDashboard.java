package org.idempiere.mms.form;

import org.adempiere.webui.panel.ADForm;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zul.Button;
import org.zkoss.zul.Div;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Html;
import org.zkoss.zul.Label;
import org.zkoss.zul.Row;
import org.zkoss.zul.Rows;
import org.zkoss.zul.Vlayout;
import org.zkoss.zul.Window;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WMeetingDashboard — ZK Web Form for Meeting Dashboard
 *
 * Shows:
 *  - Summary cards: Today, Upcoming, Pending Approval, etc.
 *  - Today's meetings list
 *  - Overdue action items
 *
 * Register in iDempiere:
 *   Application Dictionary → Form
 *   Name: Meeting Dashboard
 *   ClassName: org.idempiere.mms.form.WMeetingDashboard
 *
 * Then add to Menu as a Form menu item.
 */
public class WMeetingDashboard extends ADForm {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(WMeetingDashboard.class.getName());

    private Vlayout mainLayout;

    @Override
    protected void initForm() {
        // Setup main layout
        mainLayout = new Vlayout();
        mainLayout.setWidth("100%");
        mainLayout.setSpacing("10px");
        mainLayout.setStyle("padding: 15px;");

        // Title
        Label title = new Label("Management Meeting Dashboard");
        title.setStyle("font-size: 22px; font-weight: bold; color: #2c3e50; padding-bottom: 10px;");
        mainLayout.appendChild(title);

        // Build dashboard
        buildSummaryCards();
        buildTodaysMeetings();
        buildOverdueActions();

        this.appendChild(mainLayout);
    }

    // =========================================================================
    // Summary Cards Row
    // =========================================================================
    private void buildSummaryCards() {
        int clientId = Env.getAD_Client_ID(Env.getCtx());

        // Query dashboard summary
        int todayMeetings    = getCount("SELECT COUNT(*) FROM adempiere.xx_managementmeeting WHERE AD_Client_ID=? AND IsActive='Y' AND DATE(MeetingDate)=CURRENT_DATE AND DocStatus NOT IN ('VO')", clientId);
        int upcomingMeetings = getCount("SELECT COUNT(*) FROM adempiere.xx_managementmeeting WHERE AD_Client_ID=? AND IsActive='Y' AND MeetingDate > NOW() AND DocStatus IN ('AP','SC')", clientId);
        int pendingApproval  = getCount("SELECT COUNT(*) FROM adempiere.xx_managementmeeting WHERE AD_Client_ID=? AND IsActive='Y' AND DocStatus='WA'", clientId);
        int completedMeet    = getCount("SELECT COUNT(*) FROM adempiere.xx_managementmeeting WHERE AD_Client_ID=? AND IsActive='Y' AND DocStatus='CO'", clientId);
        int openActions      = getCount("SELECT COUNT(*) FROM adempiere.xx_meetingaction a JOIN adempiere.xx_managementmeeting m ON m.XX_ManagementMeeting_ID=a.XX_ManagementMeeting_ID WHERE m.AD_Client_ID=? AND a.IsActive='Y' AND a.Status='OPEN'", clientId);
        int overdueActions   = getCount("SELECT COUNT(*) FROM adempiere.xx_meetingaction a JOIN adempiere.xx_managementmeeting m ON m.XX_ManagementMeeting_ID=a.XX_ManagementMeeting_ID WHERE m.AD_Client_ID=? AND a.IsActive='Y' AND a.Status='OVERDUE'", clientId);

        // Cards container
        Div cardsDiv = new Div();
        cardsDiv.setStyle("display: flex; flex-wrap: wrap; gap: 15px; margin-bottom: 20px;");

        cardsDiv.appendChild(createCard("Today's Meetings",   String.valueOf(todayMeetings),    "#3498db", "📅"));
        cardsDiv.appendChild(createCard("Upcoming",           String.valueOf(upcomingMeetings), "#2ecc71", "🗓️"));
        cardsDiv.appendChild(createCard("Pending Approval",   String.valueOf(pendingApproval),  "#f39c12", "⏳"));
        cardsDiv.appendChild(createCard("Completed",          String.valueOf(completedMeet),    "#27ae60", "✅"));
        cardsDiv.appendChild(createCard("Open Actions",       String.valueOf(openActions),      "#9b59b6", "📋"));
        cardsDiv.appendChild(createCard("Overdue Actions",    String.valueOf(overdueActions),   "#e74c3c", "⚠️"));

        mainLayout.appendChild(cardsDiv);
    }

    private Div createCard(String title, String value, String color, String icon) {
        Div card = new Div();
        card.setStyle(
            "background: " + color + "; color: white; border-radius: 10px; " +
            "padding: 20px 25px; min-width: 150px; text-align: center; " +
            "box-shadow: 0 4px 6px rgba(0,0,0,0.15); flex: 1;"
        );

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("font-size: 28px; display: block;");
        card.appendChild(iconLabel);

        Label valueLabel = new Label(value);
        valueLabel.setStyle("font-size: 36px; font-weight: bold; display: block; margin: 5px 0;");
        card.appendChild(valueLabel);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("font-size: 13px; opacity: 0.9; display: block;");
        card.appendChild(titleLabel);

        return card;
    }

    // =========================================================================
    // Today's Meetings
    // =========================================================================
    private void buildTodaysMeetings() {
        int clientId = Env.getAD_Client_ID(Env.getCtx());

        Label sectionTitle = new Label("Today's Meetings");
        sectionTitle.setStyle("font-size: 17px; font-weight: bold; color: #2c3e50; " +
                              "border-left: 4px solid #3498db; padding-left: 10px; margin: 10px 0;");
        mainLayout.appendChild(sectionTitle);

        // Table header
        Grid grid = new Grid();
        grid.setWidth("100%");
        grid.setStyle("border: 1px solid #ddd; border-radius: 8px;");

        // Columns
        grid.appendChild(new org.zkoss.zul.Columns());
        grid.getColumns().appendChild(makeCol("Time", "120px"));
        grid.getColumns().appendChild(makeCol("Subject", "auto"));
        grid.getColumns().appendChild(makeCol("Type", "120px"));
        grid.getColumns().appendChild(makeCol("Mode", "100px"));
        grid.getColumns().appendChild(makeCol("Status", "100px"));
        grid.getColumns().appendChild(makeCol("Action", "100px"));

        Rows rows = new Rows();
        grid.appendChild(rows);

        String sql = "SELECT m.XX_ManagementMeeting_ID, m.DocumentNo, m.Subject, " +
                     "       m.StartTime, m.EndTime, m.DocStatus, m.MeetingMode, " +
                     "       m.MeetingPlatform, m.MeetingLink, t.Name AS MeetingType " +
                     "FROM adempiere.xx_managementmeeting m " +
                     "LEFT JOIN adempiere.xx_meetingtype t ON t.XX_MeetingType_ID = m.XX_MeetingType_ID " +
                     "WHERE m.AD_Client_ID = ? AND m.IsActive = 'Y' " +
                     "  AND DATE(m.MeetingDate) = CURRENT_DATE " +
                     "  AND m.DocStatus NOT IN ('VO') " +
                     "ORDER BY m.StartTime";

        PreparedStatement pstmt = null;
        ResultSet rs = null;
        SimpleDateFormat tf = new SimpleDateFormat("hh:mm a");

        try {
            pstmt = DB.prepareStatement(sql, null);
            pstmt.setInt(1, clientId);
            rs = pstmt.executeQuery();

            boolean hasRows = false;
            while (rs.next()) {
                hasRows = true;
                Row row = new Row();

                String startStr = tf.format(rs.getTimestamp("StartTime"));
                String endStr   = tf.format(rs.getTimestamp("EndTime"));
                String timeStr  = startStr + " - " + endStr;

                row.appendChild(new Label(timeStr));
                row.appendChild(new Label(rs.getString("Subject")));
                row.appendChild(new Label(rs.getString("MeetingType") != null ? rs.getString("MeetingType") : "—"));
                row.appendChild(new Label(rs.getString("MeetingMode")));
                row.appendChild(createStatusBadge(rs.getString("DocStatus")));

                // Join button for online meetings
                String link = rs.getString("MeetingLink");
                if (link != null && !link.isBlank()) {
                    Button joinBtn = new Button("Join");
                    joinBtn.setStyle("background: #27ae60; color: white; border: none; " +
                                    "padding: 5px 12px; border-radius: 5px; cursor: pointer;");
                    final String finalLink = link;
                    joinBtn.addEventListener("onClick", (EventListener<Event>) e -> {
                        org.zkoss.zk.ui.Executions.getCurrent()
                            .sendRedirect(finalLink, "_blank");
                    });
                    row.appendChild(joinBtn);
                } else {
                    row.appendChild(new Label("—"));
                }

                rows.appendChild(row);
            }

            if (!hasRows) {
                Row emptyRow = new Row();
                Label empty = new Label("No meetings scheduled for today");
                empty.setStyle("color: #999; font-style: italic;");
                emptyRow.appendChild(empty);
                emptyRow.getChildren().get(0);
                rows.appendChild(emptyRow);
            }

        } catch (Exception e) {
            log.log(Level.SEVERE, "Error loading today's meetings: " + e.getMessage(), e);
        } finally {
            DB.close(rs, pstmt);
        }

        mainLayout.appendChild(grid);
    }

    // =========================================================================
    // Overdue Action Items
    // =========================================================================
    private void buildOverdueActions() {
        int clientId = Env.getAD_Client_ID(Env.getCtx());

        Label sectionTitle = new Label("⚠️ Overdue Action Items");
        sectionTitle.setStyle("font-size: 17px; font-weight: bold; color: #e74c3c; " +
                              "border-left: 4px solid #e74c3c; padding-left: 10px; margin: 20px 0 10px 0;");
        mainLayout.appendChild(sectionTitle);

        Grid grid = new Grid();
        grid.setWidth("100%");
        grid.setStyle("border: 1px solid #f5c6cb; border-radius: 8px; background: #fff8f8;");

        grid.appendChild(new org.zkoss.zul.Columns());
        grid.getColumns().appendChild(makeCol("Task", "auto"));
        grid.getColumns().appendChild(makeCol("Meeting", "200px"));
        grid.getColumns().appendChild(makeCol("Responsible", "150px"));
        grid.getColumns().appendChild(makeCol("Due Date", "120px"));
        grid.getColumns().appendChild(makeCol("Days Overdue", "100px"));
        grid.getColumns().appendChild(makeCol("Progress", "80px"));

        Rows rows = new Rows();
        grid.appendChild(rows);

        String sql = "SELECT a.Task, m.Subject, u.Name AS Responsible, " +
                     "       a.DueDate, " +
                     "       EXTRACT(DAY FROM (NOW() - a.DueDate)) AS DaysOverdue, " +
                     "       a.ProgressPercent " +
                     "FROM adempiere.xx_meetingaction a " +
                     "JOIN adempiere.xx_managementmeeting m ON m.XX_ManagementMeeting_ID = a.XX_ManagementMeeting_ID " +
                     "LEFT JOIN adempiere.ad_user u ON u.AD_User_ID = a.ResponsibleUser_ID " +
                     "WHERE m.AD_Client_ID = ? AND a.IsActive = 'Y' " +
                     "  AND a.Status IN ('OVERDUE','OPEN','IN_PROGRESS') " +
                     "  AND a.DueDate < NOW() " +
                     "ORDER BY a.DueDate ASC " +
                     "LIMIT 20";

        PreparedStatement pstmt = null;
        ResultSet rs = null;
        SimpleDateFormat df = new SimpleDateFormat("dd-MMM-yyyy");

        try {
            pstmt = DB.prepareStatement(sql, null);
            pstmt.setInt(1, clientId);
            rs = pstmt.executeQuery();

            boolean hasRows = false;
            while (rs.next()) {
                hasRows = true;
                Row row = new Row();
                row.setStyle("background: #fff5f5;");

                row.appendChild(new Label(rs.getString("Task")));
                row.appendChild(new Label(rs.getString("Subject")));
                row.appendChild(new Label(rs.getString("Responsible") != null ? rs.getString("Responsible") : "—"));
                row.appendChild(new Label(df.format(rs.getTimestamp("DueDate"))));

                int daysOverdue = rs.getInt("DaysOverdue");
                Label daysLabel = new Label(daysOverdue + " days");
                daysLabel.setStyle("color: #e74c3c; font-weight: bold;");
                row.appendChild(daysLabel);

                row.appendChild(new Label(rs.getInt("ProgressPercent") + "%"));

                rows.appendChild(row);
            }

            if (!hasRows) {
                Row emptyRow = new Row();
                Label empty = new Label("✅ No overdue actions! Great job.");
                empty.setStyle("color: #27ae60; font-style: italic;");
                emptyRow.appendChild(empty);
                rows.appendChild(emptyRow);
            }

        } catch (Exception e) {
            log.log(Level.SEVERE, "Error loading overdue actions: " + e.getMessage(), e);
        } finally {
            DB.close(rs, pstmt);
        }

        mainLayout.appendChild(grid);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private org.zkoss.zul.Column makeCol(String label, String width) {
        org.zkoss.zul.Column col = new org.zkoss.zul.Column(label);
        col.setWidth(width);
        col.setStyle("background: #f8f9fa; font-weight: bold; padding: 8px;");
        return col;
    }

    private Label createStatusBadge(String status) {
        String label = switch (status) {
            case "DR" -> "Draft";
            case "WA" -> "Pending";
            case "AP" -> "Approved";
            case "SC" -> "Scheduled";
            case "IP" -> "In Progress";
            case "CO" -> "Completed";
            case "VO" -> "Cancelled";
            default   -> status;
        };
        String color = switch (status) {
            case "AP", "SC" -> "#2ecc71";
            case "IP"       -> "#3498db";
            case "CO"       -> "#27ae60";
            case "WA"       -> "#f39c12";
            case "VO"       -> "#e74c3c";
            default         -> "#95a5a6";
        };
        Label badge = new Label(label);
        badge.setStyle("background:" + color + "; color:white; padding:3px 8px; " +
                       "border-radius:12px; font-size:11px; font-weight:bold;");
        return badge;
    }

    private int getCount(String sql, int clientId) {
        try {
            return DB.getSQLValue(null, sql, clientId);
        } catch (Exception e) {
            log.log(Level.WARNING, "Count query error: " + e.getMessage(), e);
            return 0;
        }
    }
}
