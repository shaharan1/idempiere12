package com.xx.approval.event;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.PO;
import org.compiere.process.DocAction;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.service.ApprovalEngineClient;
import org.mycompany.requisition.approval.service.BaseConfigService;
import org.osgi.service.event.Event;

/**
 * Intercepts document completion (Prepare / Complete) on business documents
 * (C_Order, M_Requisition) and automatically starts the Approval Workflow.
 * <p>
 * If an approval process is configured for this table and document type,
 * standard completion is prevented until the multi-level chain approves it.
 */
public class ApprovalSubmitHandler extends AbstractEventHandler {

	private static final String[] MONITORED_TABLES = { "C_Order", "M_Requisition" };

	private static final ThreadLocal<Boolean> PROCESSING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Override
	protected void initialize() {
		for (String tableName : MONITORED_TABLES) {
			registerTableEvent(IEventTopics.DOC_BEFORE_PREPARE, tableName);
		}
	}

	@Override
	protected void doHandleEvent(Event event) {
		PO po = getPO(event);
		if (!(po instanceof DocAction)) {
			return;
		}

		final int tableId = po.get_Table_ID();
		final int recordId = po.get_ID();
		if (tableId <= 0 || recordId <= 0) {
			return;
		}

		// If document is already approved or finalizer is completing it, let it pass
		Object isApproved = po.get_Value("IsApproved");
		if (Boolean.TRUE.equals(isApproved) || "Y".equalsIgnoreCase(String.valueOf(isApproved))) {
			return;
		}

		int docTypeId = intOf(po, "C_DocType_ID");
		if (docTypeId <= 0) {
			docTypeId = intOf(po, "C_DocTypeTarget_ID");
		}

		if (PROCESSING.get().booleanValue()) {
			return;
		}

		PROCESSING.set(Boolean.TRUE);
		try {
			ApprovalEngineClient client = new ApprovalEngineClient();
			ApprovalTransaction txn = client.start(tableId, recordId, null, docTypeId,
					"Auto-submitted via Document Action");

			if (txn != null) {
				throw new AdempiereException("Document submitted for Approval: " + txn.getDisplayName()
						+ ". It will be completed once fully approved.");
			}
		} catch (BaseConfigService.ConfigException e) {
			throw new AdempiereException("Approval configuration error: " + e.getMessage(), e);
		} finally {
			PROCESSING.set(Boolean.FALSE);
		}
	}

	private static int intOf(PO po, String column) {
		Object v = po.get_Value(column);
		return v instanceof Number ? ((Number) v).intValue() : 0;
	}
}
