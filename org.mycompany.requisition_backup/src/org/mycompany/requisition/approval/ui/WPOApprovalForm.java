package org.mycompany.requisition.approval.ui;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.panel.ADForm;
import org.compiere.model.MOrder;
import org.compiere.model.MRequisition;
import org.compiere.model.MRfQ;
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
import org.mycompany.requisition.rfqapproval.RfQApprovalService;

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
    	org.mycompany.requisition.ui.XxTheme.wrap(this);

        userId = Env.getAD_User_ID(Env.getCtx());
        clientId = Env.getAD_Client_ID(Env.getCtx());

        setTitle("Document Approval");
        setWidth("100%");
        setHeight("100%");

        Vlayout root = new Vlayout();
        root.setWidth("100%");
        root.setHeight("100%");
        root.setSpacing("10px");
        root.setStyle("padding:12px;");

        // Form title
        Label title = new Label("Document Approval");
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
        head.setSizable(true);

        String[] colNames = { "Document Type", "Document No", "Created", "Created By", "Checked By", "Status", "Actions" };
        String[] colHflex = { "3", "2", "3", "2", "2", "2", "4" };

        for (int i = 0; i < colNames.length; i++) {
            Listheader header = new Listheader(colNames[i]);
            header.setHflex(colHflex[i]);
            header.setStyle("text-align:center;");
            head.appendChild(header);
        }

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


    // =====================================================
    // The original document behind an approval history row
    // =====================================================

    /** What kind of document a history row really points at. */
    private enum Kind { ORDER, REQUISITION, RFQ, UNKNOWN }

    /**
     * The real document behind one UT_ApprovalHistory row.
     * <p>
     * The history row only carries a document number and a C_DocType_ID, and for an
     * RFQ / Comparative Statement that doc type is the one of its <em>requisition</em>,
     * so showing it would label a CS as a requisition. This resolves the row against
     * the real tables, in the same order ut_process_approval uses when it completes
     * the document (order, requisition, RFQ), so what the inbox shows is what an
     * approval will act on.
     */
    private static final class Origin {
        Kind kind = Kind.UNKNOWN;
        /** Document type name as the document itself carries it, e.g. Sales Order. */
        String typeName;
        /** The document's own number. */
        String documentNo;
        int tableId;
        int recordId;
        int rfqId;
        boolean salesOrder;
    }

    /** Shown for a Comparative Statement; the only place this wording lives. */
    private static final String LABEL_CS  = "Comparative Statement (CS)";
    private static final String LABEL_RFQ = "RFQ";

    private Origin resolveOrigin(String documentNo, int docTypeId, String historyDocTypeName) {

        Origin o = new Origin();
        o.documentNo = documentNo;
        o.typeName = historyDocTypeName;
        if (documentNo == null || documentNo.trim().length() == 0)
            return o;

        // 1. Sales Order or Purchase Order
        String orderSql =
            "SELECT o.c_order_id, o.issotrx, dt.name " +
            "FROM c_order o " +
            "LEFT JOIN c_doctype dt ON dt.c_doctype_id = o.c_doctypetarget_id " +
            "WHERE o.documentno = ? AND o.c_doctypetarget_id = ? AND o.ad_client_id = ? " +
            "ORDER BY o.c_order_id DESC";
        try (PreparedStatement ps = DB.prepareStatement(orderSql, null)) {
            ps.setString(1, documentNo);
            ps.setInt(2, docTypeId);
            ps.setInt(3, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    o.kind = Kind.ORDER;
                    o.tableId = MOrder.Table_ID;
                    o.recordId = rs.getInt(1);
                    o.salesOrder = "Y".equals(rs.getString(2));
                    String name = rs.getString(3);
                    if (name != null && name.trim().length() > 0)
                        o.typeName = name;
                    else
                        o.typeName = o.salesOrder ? "Sales Order" : "Purchase Order";
                    return o;
                }
            }
        } catch (SQLException e) {
            log.warning("Could not resolve order " + documentNo + ": " + e.getMessage());
        }

        // 2. Requisition
        String reqSql =
            "SELECT r.m_requisition_id, dt.name " +
            "FROM m_requisition r " +
            "LEFT JOIN c_doctype dt ON dt.c_doctype_id = r.c_doctype_id " +
            "WHERE r.documentno = ? AND r.c_doctype_id = ? AND r.ad_client_id = ? " +
            "ORDER BY r.m_requisition_id DESC";
        try (PreparedStatement ps = DB.prepareStatement(reqSql, null)) {
            ps.setString(1, documentNo);
            ps.setInt(2, docTypeId);
            ps.setInt(3, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    o.kind = Kind.REQUISITION;
                    o.tableId = MRequisition.Table_ID;
                    o.recordId = rs.getInt(1);
                    String name = rs.getString(2);
                    o.typeName = (name != null && name.trim().length() > 0) ? name : "Requisition";
                    return o;
                }
            }
        } catch (SQLException e) {
            log.warning("Could not resolve requisition " + documentNo + ": " + e.getMessage());
        }

        // 3. RFQ / Comparative Statement
        int rfqId = RecommendWinnerDialog.findRfqId(documentNo, docTypeId);
        if (rfqId > 0) {
            o.kind = Kind.RFQ;
            o.tableId = MRfQ.Table_ID;
            o.recordId = rfqId;
            o.rfqId = rfqId;
            o.typeName = RfQApprovalService.isCsDocType(rfqId) ? LABEL_CS : LABEL_RFQ;
        }
        return o;
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
        	    "SELECT h.ut_approvalhistory_uu, h.c_doctype_id, " +
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
                    final String historyDocType = rs.getString("doctype_name");
                    final String documentNo = rs.getString("documentno");
                    final int docTypeId     = rs.getInt("c_doctype_id");

                    // the document type and number of the document itself,
                    // not of whatever the history row happens to carry
                    final Origin origin     = resolveOrigin(documentNo, docTypeId, historyDocType);
                    final String docType    = origin.typeName;
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
                        event -> updateHistoryStatus(historyId, origin, "A"));

                    Button rejectButton = new Button("Reject");
                    rejectButton.setStyle("background:#dc3545;color:white;");
                    rejectButton.addEventListener(Events.ON_CLICK,
                        event -> updateHistoryStatus(historyId, origin, "R"));

                    Button viewButton = new Button("View");
                    viewButton.addEventListener(Events.ON_CLICK,
                        event -> viewDocument(origin, created,
                            createdBy, checkedBy, status));

                    buttons.appendChild(approveButton);
                    buttons.appendChild(rejectButton);
                    buttons.appendChild(viewButton);

                    // Comparative Statement (RFQ): extra button to pick the winners
                    if (origin.kind == Kind.RFQ) {
                        final int rfqId = origin.rfqId;
                        Button winnerButton = new Button("Recommend Winner");
                        winnerButton.setStyle("background:#fd7e14;color:white;");
                        winnerButton.addEventListener(Events.ON_CLICK,
                            event -> RecommendWinnerDialog.open(this, rfqId, documentNo));
                        buttons.appendChild(winnerButton);
                    }

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
    private void viewDocument(Origin origin, String created,
        String createdBy, String checkedBy, String status) {

        if (origin.tableId > 0 && origin.recordId > 0) {
            try {
                // opens the document in its own window: Requisition, Sales Order,
                // Purchase Order or RFQ, whichever the row really is
                AEnv.zoom(origin.tableId, origin.recordId);
                return;
            } catch (Exception e) {
                log.warning("View: could not open " + origin.documentNo + ": " + e.getMessage());
            }
        }
        showDetails(origin.typeName, origin.documentNo, created, createdBy, checkedBy, status);
    }

    private void showDetails(
        String docType, String documentNo, String created,
        String createdBy, String checkedBy,
        String status
    ) {

        String details =
            "Document Type: " + docType
            + "\nDocument No: " + documentNo
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
        Origin origin,
        String newStatus
    ) {

        final String documentNo = origin.documentNo;
        String action = "A".equals(newStatus) ? "Approve" : "Reject";

        Messagebox.show(
            "Do you want to " + action
            + " " + origin.typeName + " " + documentNo + "?",
            "Confirm " + action,
            Messagebox.YES | Messagebox.NO,
            Messagebox.QUESTION,
            event -> {
                if (Messagebox.ON_YES.equals(event.getName())) {
                    processApproval(historyId, origin, newStatus);
                }
            });
    }

    // Runs only after the user clicked Yes
    private void processApproval(
        String historyId,
        Origin origin,
        String newStatus
    ) {

        final String documentNo = origin.documentNo;

        log.warning("POApproval: processApproval called, history="
            + historyId + ", action=" + newStatus + ", user=" + userId);

        String result = null;

        // A Comparative Statement cannot be approved before a winner is recommended
        if ("A".equals(newStatus)) {
            int rfqId = origin.kind == Kind.RFQ ? origin.rfqId : 0;
            if (rfqId > 0 && !RecommendWinnerDialog.hasWinner(rfqId)) {
                Messagebox.show("Click 'Recommend Winner' and save the winners before approving.",
                    "Winner required", Messagebox.OK, Messagebox.EXCLAMATION);
                return;
            }
        }

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

            // Last level approved: complete the Purchase Requisition
            if (result != null && result.startsWith("FINALREQ:")) {
                int reqId = Integer.parseInt(result.substring(9));
                MRequisition req = new MRequisition(Env.getCtx(), reqId, trxName);
                req.setIsApproved(true); // RequisitionApprovalHandler lets approved PRs through
                req.saveEx();
                req.setDocAction(DocAction.ACTION_Complete);
                if (!req.processIt(DocAction.ACTION_Complete)) {
                    throw new AdempiereException(
                        "Could not complete PR: " + req.getProcessMsg());
                }
                req.saveEx();
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
            message = "Document " + documentNo + " has been rejected.";
        } else if (result.startsWith("RETURNED:")) {
            message = documentNo + " has been rejected and sent back to the previous level approver.";
        } else if (result.startsWith("NEXT:")) {
            message = "Approved. Sent to the next level approver.";
        } else if (result.startsWith("FINALRFQ:")) {
            message = "Approved. " + origin.typeName + " " + documentNo + " is fully approved.";
        } else {
            // FINAL: (Sales / Purchase Order) and FINALREQ: (Requisition)
            message = "Approved. " + origin.typeName + " " + documentNo
                + " has been completed.";
        }

        Messagebox.show(message, "Result",
            Messagebox.OK, Messagebox.INFORMATION);

        loadPendingApprovals();
    }
}