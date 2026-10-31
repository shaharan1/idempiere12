package org.mycompany.requisition.approval.ui;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.panel.ADForm;
import org.compiere.model.MOrder;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Div;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Separator;
import org.zkoss.zul.Vlayout;

public class WPOApprovalForm extends ADForm {

    private static final long serialVersionUID = 1L;

    // Alternating row colors: odd rows light blue, even rows white
    private static final String ROW_BLUE  = "background:#BDD7EE;";
    private static final String ROW_WHITE = "background:#FFFFFF;";

    private static final CLogger log =
        CLogger.getCLogger(WPOApprovalForm.class);

    private Listbox approvalList;
    private Label countLabel;

    private int userId;
    private int clientId;

    @Override
    protected void initForm() {

        userId = Env.getAD_User_ID(Env.getCtx());
        clientId = Env.getAD_Client_ID(Env.getCtx());

        setTitle("Purchase Order Approval");
        setWidth("100%");
        setHeight("100%");

        Vlayout root = new Vlayout();
        root.setWidth("100%");
        root.setHeight("100%");
        root.setSpacing("10px");
        root.setStyle("padding:12px;");

        // Form title
        Label title = new Label("Purchase Order Approval");
        title.setStyle("font-size:22px;font-weight:bold;color:#23466f;");
        root.appendChild(title);

        root.appendChild(new Separator());

        // Pending list heading
        Hlayout listTitle = new Hlayout();
        listTitle.setWidth("100%");
        listTitle.setSpacing("10px");

        Label pendingTitle = new Label("Pending Approval List");
        pendingTitle.setStyle("font-size:17px;font-weight:bold;");

        countLabel = new Label("Loading...");

        Button refreshButton = new Button("Refresh");
        refreshButton.setTooltiptext("Reload pending approvals");
        refreshButton.addEventListener(Events.ON_CLICK,
            event -> loadPendingApprovals());

        listTitle.appendChild(pendingTitle);
        listTitle.appendChild(countLabel);
        listTitle.appendChild(refreshButton);
        root.appendChild(listTitle);

        // Scrollable table container
        Div scrollContainer = new Div();
        scrollContainer.setWidth("100%");
        scrollContainer.setStyle("overflow-x:auto;overflow-y:auto;");
        scrollContainer.setVflex("1");

        approvalList = new Listbox();
        approvalList.setWidth("100%");
        approvalList.setVflex("1");
        approvalList.setEmptyMessage("No pending approvals assigned to you.");

        Listhead head = new Listhead();
        head.setAttribute("sizable", Boolean.TRUE);
//        head.setSizable(true);

        addHeader(head, "Document Type", "170px");
        addHeader(head, "PO Number", "130px");
        addHeader(head, "Created", "160px");
        addHeader(head, "Created By", "150px");
//        addHeader(head, "Updated By", "150px");
        addHeader(head, "Checked By", "150px");
        addHeader(head, "Status", "110px");
//        addHeader(head, "Active", "80px");
        addHeader(head, "Actions", "260px");

        approvalList.appendChild(head);
        scrollContainer.appendChild(approvalList);
        root.appendChild(scrollContainer);

        appendChild(root);

        loadPendingApprovals();
    }

    private void addHeader(Listhead head, String title, String width) {
        Listheader header = new Listheader(title);
        header.setWidth(width);
        
        head.appendChild(header);
    }

    private String statusText(String code) {
        if ("P".equals(code)) return "Pending";
        if ("A".equals(code)) return "Approved";
        if ("R".equals(code)) return "Rejected";
        return code == null ? "" : code;
    }

    // =====================================================
    // Load pending rows assigned to the logged-in user
    // =====================================================
    private void loadPendingApprovals() {

        approvalList.getItems().clear();

        String sql =
        	    "SELECT h.ut_approvalhistory_uu, " +
        	    "       COALESCE(dt.name, CAST(h.c_doctype_id AS varchar)) AS doctype_name, " +
        	    "       h.documentno, h.created, " +
        	    "       COALESCE(cu.name, CAST(h.createdby AS varchar)) AS created_by, " +
        	    "       COALESCE(ck.name, h.checked) AS checked_by, " +
        	    "       h.status " +
        	    "FROM adempiere.ut_approvalhistory h " +
        	    "LEFT JOIN adempiere.c_doctype dt ON dt.c_doctype_id = h.c_doctype_id " +
        	    "LEFT JOIN adempiere.ad_user cu ON cu.ad_user_id = h.createdby " +
        	    "LEFT JOIN adempiere.ad_user ck ON CAST(ck.ad_user_id AS varchar) = h.checked " +
        	    "WHERE h.ad_client_id = ? " +
        	    "  AND h.checked = ? " +
        	    "  AND h.status = 'P' " +
        	    "  AND h.isactive = 'Y' " +
        	    "ORDER BY h.created ASC";

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        int rowCount = 0;

        try (PreparedStatement pstmt = DB.prepareStatement(sql, null)) {

            pstmt.setInt(1, clientId);
            pstmt.setString(2, String.valueOf(userId));

            try (ResultSet rs = pstmt.executeQuery()) {

                while (rs.next()) {

                    final String historyId  = rs.getString("ut_approvalhistory_uu");
                    final String docType    = rs.getString("doctype_name");
                    final String documentNo = rs.getString("documentno");
                    Timestamp ts            = rs.getTimestamp("created");
                    final String created    = ts == null ? "" : fmt.format(ts);
                    final String createdBy  = rs.getString("created_by");
//                    final String updatedBy  = rs.getString("updated_by");
                    final String checkedBy  = rs.getString("checked_by");
                    final String status     = statusText(rs.getString("status"));
//                    final String active     = rs.getString("isactive");

                    Listitem row = new Listitem();

                    addCell(row, docType);
                    addCell(row, documentNo);
                    addCell(row, created);
                    addCell(row, createdBy);
//                    addCell(row, updatedBy);
                    addCell(row, checkedBy);
                    addCell(row, status);
//                    addCell(row, active);

                    Listcell actions = new Listcell();
                    Hlayout buttons = new Hlayout();
                    buttons.setSpacing("5px");

                    Button approveButton = new Button("Approve");
                    approveButton.setStyle("background:#198754;color:white;");
                    approveButton.addEventListener(Events.ON_CLICK,
                        event -> updateHistoryStatus(historyId, documentNo, "A"));

                    Button rejectButton = new Button("Reject");
                    rejectButton.setStyle("background:#dc3545;color:white;");
                    rejectButton.addEventListener(Events.ON_CLICK,
                        event -> updateHistoryStatus(historyId, documentNo, "R"));

                    Button viewButton = new Button("View");
                    viewButton.addEventListener(Events.ON_CLICK,
                        event -> showDetails(docType, documentNo, created,
                            createdBy, checkedBy, status));

                    buttons.appendChild(approveButton);
                    buttons.appendChild(rejectButton);
                    buttons.appendChild(viewButton);

                    actions.appendChild(buttons);
                    row.appendChild(actions);

                    String rowStyle = (rowCount % 2 == 0) ? ROW_BLUE : ROW_WHITE;
                    row.setStyle(rowStyle);
                    for (Component cell : row.getChildren()) {
                        ((Listcell) cell).setStyle(rowStyle);
                    }

                    approvalList.appendChild(row);
                    rowCount++;
                }
            }

            countLabel.setValue("Pending records: " + rowCount);

        } catch (SQLException e) {

            countLabel.setValue("Unable to load records");

            Messagebox.show(
                "Could not load approval records.\n" + e.getMessage(),
                "Database Error",
                Messagebox.OK,
                Messagebox.ERROR);
        }
    }

   
	private void addCell(Listitem row, String value) {
        row.appendChild(new Listcell(value == null ? "" : value));
    }

    // =====================================================
    // Show the selected history record
    // =====================================================
    private void showDetails(
        String docType, String documentNo, String created,
        String createdBy, String checkedBy,
        String status
    ) {

        String details =
            "Document Type: " + docType
            + "\nPO Number: " + documentNo
            + "\nCreated: " + created
            + "\nCreated By: " + createdBy
//            + "\nUpdated By: " + updatedBy
            + "\nChecked By: " + checkedBy
            + "\nStatus: " + status;
//            + "\nActive: " + active;

        Messagebox.show(
            details,
            "Approval Record Details",
            Messagebox.OK,
            Messagebox.INFORMATION);
    }

    // =====================================================
    // Update the selected pending history record
    // =====================================================
    private void updateHistoryStatus(
        String historyId,
        String documentNo,
        String newStatus
    ) {

        String action = "A".equals(newStatus) ? "Approve" : "Reject";

        Messagebox.show(
            "Do you want to " + action
            + " Purchase Order " + documentNo + "?",
            "Confirm " + action,
            Messagebox.YES | Messagebox.NO,
            Messagebox.QUESTION,
            event -> {
                if (Messagebox.ON_YES.equals(event.getName())) {
                    processApproval(historyId, documentNo, newStatus);
                }
            });
    }

    // Runs only after the user clicked Yes
    private void processApproval(
        String historyId,
        String documentNo,
        String newStatus
    ) {

        log.warning("POApproval: processApproval called, history="
            + historyId + ", action=" + newStatus + ", user=" + userId);

        String result = null;
        String trxName = Trx.createTrxName("POApproval");
        Trx trx = Trx.get(trxName, true);

        try {
            String sql = "SELECT adempiere.ut_process_approval(?, ?, ?, ?)";

            try (PreparedStatement pstmt = DB.prepareStatement(sql, trxName)) {
                pstmt.setString(1, historyId);
                pstmt.setInt(2, userId);
                pstmt.setString(3, newStatus);
                pstmt.setInt(4, clientId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        result = rs.getString(1);
                    }
                }
            }

            // Last level approved: complete the Purchase Order
            // in the same transaction.
            if (result != null && result.startsWith("FINAL:")) {
                int orderId = Integer.parseInt(result.substring(6));
                MOrder order = new MOrder(Env.getCtx(), orderId, trxName);
                order.setDocAction(DocAction.ACTION_Complete);
                if (!order.processIt(DocAction.ACTION_Complete)) {
                    throw new AdempiereException(
                        "Could not complete PO: " + order.getProcessMsg());
                }
                order.saveEx();
            }

            trx.commit(true);
            log.warning("POApproval: result=" + result);

        } catch (Exception e) {
            trx.rollback();
            Messagebox.show(
                "Could not process approval.\n" + e.getMessage(),
                "Error",
                Messagebox.OK,
                Messagebox.ERROR);
            loadPendingApprovals();
            return;
        } finally {
            trx.close();
        }

        String message;
        if (result == null || "NOT_FOUND".equals(result)) {
            message = "No record was changed. It may already have been "
                + "processed, or it may not be assigned to your user.";
        } else if ("REJECTED".equals(result)) {
            message = "Purchase Order " + documentNo + " has been rejected.";
        } else if (result.startsWith("NEXT:")) {
            message = "Approved. Sent to the next level approver.";
        } else {
            message = "Approved. Purchase Order " + documentNo
                + " has been completed.";
        }

        Messagebox.show(message, "Result",
            Messagebox.OK, Messagebox.INFORMATION);

        loadPendingApprovals();
    }
}