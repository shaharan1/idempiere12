package com.xx.approval.event;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.DocAction;
import org.osgi.service.event.Event;

/**
 * When the approval engine finishes a chain as APPROVED, completes the business
 * document with the normal iDempiere complete action.
 * <p>
 * The engine deliberately never writes DocStatus (see ApprovalEngineClient), so
 * this handler is the one place that does. It runs inside the engine's own
 * transaction: if the document cannot be completed, the exception rolls the
 * approval back too, and the approver is told why. Approval and completion are
 * therefore never out of step.
 * <p>
 * Registered through OSGI-INF/com.xx.approval.event.ApprovalFinalizer.xml.
 */
public class ApprovalFinalizer extends AbstractEventHandler {

    /**
     * Registered under both spellings because the handler must match
     * AD_Table.TableName exactly and a wrong case fails silently. The handler is
     * idempotent, so a table matching only one spelling is fine.
     */
    private static final String[] TXN_TABLES = { "XX_PROCESS_TRANSACTION", "XX_Process_Transaction" };

    @Override
    protected void initialize() {
        for (String t : TXN_TABLES)
            registerTableEvent(IEventTopics.PO_AFTER_CHANGE, t);
    }

    @Override
    protected void doHandleEvent(Event event) {
        PO txn = getPO(event);
        if (txn == null || !txn.is_ValueChanged("STATUS"))
            return;
        if (!shouldComplete(str(txn.get_Value("STATUS")), null))
            return;

        int tableId = intOf(txn, "AD_TABLE_ID");
        int recordId = intOf(txn, "RECORD_ID");
        if (tableId <= 0 || recordId <= 0)
            return;

        PO doc = MTable.get(txn.getCtx(), tableId).getPO(recordId, txn.get_TrxName());
        if (!(doc instanceof DocAction) || doc.get_ID() != recordId)
            return; // not a DocAction document: nothing to complete

        DocAction da = (DocAction) doc;
        if (!shouldComplete("APPROVED", da.getDocStatus()))
            return; // already completed or closed: idempotent

        doc.set_ValueOfColumn(DocAction.DOC_COLUMNNAME_DocAction, DocAction.ACTION_Complete);
        try {
            if (!da.processIt(DocAction.ACTION_Complete))
                throw new AdempiereException("Approved, but the document could not be completed: "
                        + da.getProcessMsg());
        } catch (AdempiereException ae) {
            throw ae;
        } catch (Exception ex) {
            throw new AdempiereException("Approved, but the document could not be completed: " + ex.getMessage(), ex);
        }
        doc.saveEx(); // processIt does not persist DocStatus by itself
    }

    /** Pure rule, tested without a database: complete only an APPROVED chain on an open document. */
    public static boolean shouldComplete(String chainStatus, String docStatus) {
        if (!"APPROVED".equals(chainStatus))
            return false;
        if (docStatus == null)
            return true;
        return DocAction.STATUS_Drafted.equals(docStatus) || DocAction.STATUS_InProgress.equals(docStatus)
                || DocAction.STATUS_Invalid.equals(docStatus);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static int intOf(PO po, String column) {
        Object v = po.get_Value(column);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
