package com.xx.approval.process;

import org.adempiere.base.annotation.Process;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.service.ApprovalEngineClient;
import org.mycompany.requisition.approval.service.BaseConfigService;

/**
 * "Submit for Approval" for any document table (C_Order, M_Requisition, ...).
 * <p>
 * Linked to a table through AD_Table_Process, so it shows in the window's process
 * menu and no column has to be added to the business table. It only calls
 * {@link ApprovalEngineClient#start(int, int, java.math.BigDecimal, int, String)};
 * the engine decides the chain. It never touches DocStatus.
 * <p>
 * Lives in com.xx.approval.process, which XXProcessFactory already scans, so no
 * existing file needs to change.
 */
@Process(name = "XXSubmitForApprovalProcess")
public class XXSubmitForApprovalProcess extends SvrProcess {

    @Override
    protected void prepare() {
        // no parameters
    }

    @Override
    protected String doIt() throws Exception {
        final int tableId = getTable_ID();
        final int recordId = getRecord_ID();
        if (tableId <= 0 || recordId <= 0)
            throw new AdempiereException("Open the document and press Submit for Approval again.");

        PO doc = MTable.get(getCtx(), tableId).getPO(recordId, get_TrxName());
        if (doc == null || doc.get_ID() != recordId)
            throw new AdempiereException("Document " + tableId + "/" + recordId + " was not found.");

        String docStatus = str(doc.get_Value("DocStatus"));
        if (docStatus != null && !"DR".equals(docStatus) && !"IP".equals(docStatus) && !"IN".equals(docStatus))
            throw new AdempiereException("Only a Drafted or In Progress document can be submitted (status "
                    + docStatus + ").");

        // A drafted C_Order normally has C_DocType_ID = 0 ("** New **"); the real
        // type is in C_DocTypeTarget_ID. Routing on C_DocType_ID alone finds nothing.
        int docTypeId = intOf(doc, "C_DocType_ID");
        if (docTypeId <= 0)
            docTypeId = intOf(doc, "C_DocTypeTarget_ID");

        try {
            ApprovalTransaction t = new ApprovalEngineClient().start(tableId, recordId, null, docTypeId,
                    "Submitted from the document window");
            if (t == null)
                throw new AdempiereException("No approval process is configured for this document (table "
                        + tableId + ", document type " + docTypeId + "). Nothing was submitted.");
            return "Submitted for approval: " + t.getDisplayName();
        } catch (BaseConfigService.ConfigException e) {
            throw new AdempiereException(e.getMessage(), e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static int intOf(PO po, String column) {
        Object v = po.get_Value(column);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
