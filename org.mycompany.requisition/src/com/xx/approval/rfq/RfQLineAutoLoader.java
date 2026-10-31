package com.xx.approval.rfq;

import org.adempiere.base.event.AbstractEventHandler;
import org.compiere.model.MRfQ;
import org.compiere.model.PO;
import org.osgi.service.event.Event;

/**
 * Disabled in favor of Requisition line selection dialog (org.mycompany.requisition.rfq).
 * Retained to prevent OSGi component loading errors without auto-adding all lines.
 */
public class RfQLineAutoLoader extends AbstractEventHandler {

	@Override
	protected void initialize() {
		// Do not register auto-load event listeners to allow granular line selection
	}

	@Override
	protected void doHandleEvent(Event event) {
		// No-op
	}
}
