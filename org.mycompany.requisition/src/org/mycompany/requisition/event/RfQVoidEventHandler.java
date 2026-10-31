package org.mycompany.requisition.event;

import java.util.logging.Level;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.compiere.model.MRfQ;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.mycompany.requisition.rfq.RequisitionRFQService;
import org.osgi.service.event.Event;

public class RfQVoidEventHandler extends AbstractEventHandler {
	private static final CLogger log = CLogger.getCLogger(RfQVoidEventHandler.class);

	@Override
	protected void initialize() {
		registerTableEvent(IEventTopics.PO_BEFORE_DELETE, MRfQ.Table_Name);
		registerTableEvent(IEventTopics.PO_AFTER_CHANGE, MRfQ.Table_Name);
	}

	@Override
	protected void doHandleEvent(Event event) {
		PO po = getPO(event);
		if (!(po instanceof MRfQ)) {
			return;
		}
		
		MRfQ rfq = (MRfQ) po;
		
		// On delete or void
		if (event.getTopic().equals(IEventTopics.PO_BEFORE_DELETE)) {
			try {
				RequisitionRFQService.releaseRequisitionLines(rfq.getC_RfQ_ID(), rfq.get_TrxName());
			} catch (Exception e) {
				log.log(Level.SEVERE, "Error releasing requisition lines on RFQ delete", e);
			}
		} else if (event.getTopic().equals(IEventTopics.PO_AFTER_CHANGE)) {
			// Check if voided
			if (rfq.isProcessed() && rfq.get_ValueAsBoolean("IsVoided")) {
				try {
					RequisitionRFQService.releaseRequisitionLines(rfq.getC_RfQ_ID(), rfq.get_TrxName());
				} catch (Exception e) {
					log.log(Level.SEVERE, "Error releasing requisition lines on RFQ void", e);
				}
			}
		}
	}
}
