package org.mycompany.requisition.callout;

import java.util.Properties;

import org.compiere.model.Callout;
import org.compiere.model.GridField;
import org.compiere.model.GridTab;
import org.compiere.model.MRfQ;
import org.compiere.util.CLogger;
import org.mycompany.requisition.rfq.WRequisitionLineSelectionDialog;

public class CalloutRfQRequisition implements Callout {

	private static final CLogger log = CLogger.getCLogger(CalloutRfQRequisition.class);

	@Override
	public String start (Properties ctx, String calloutName, int WindowNo, GridTab mTab, GridField mField, Object value, Object oldValue) {
		log.info("=== CALLOUT FIRED === value=" + value);
		if (value == null) {
			return null;
		}

		int requisitionId = 0;
		if (value instanceof Number) {
			requisitionId = ((Number) value).intValue();
		} else {
			try {
				requisitionId = Integer.parseInt(value.toString());
			} catch (NumberFormatException e) {
				return null;
			}
		}

		if (requisitionId <= 0) {
			return null;
		}

		// Ensure RFQ header record is saved to have an ID before lines can be attached
		int rfqId = mTab.getRecord_ID();
		if (rfqId <= 0) {
			try {
				// Don't auto-save with requisition filled if that triggers unexpected side-effects, 
				// simply save the current tab
				if (mTab.dataSave(false)) {
					rfqId = mTab.getRecord_ID();
				}
			} catch (Exception e) {
				log.warning("Save warning: " + e.getMessage());
			}
		}

		if (rfqId > 0) {
			try {
				MRfQ rfq = new MRfQ(ctx, rfqId, null);
				if (rfq.get_ID() > 0) {
					// Show the line selection dialog specifically for the selected requisition
					WRequisitionLineSelectionDialog.showDialog(rfq, requisitionId, mTab);
				}
			} catch (Exception e) {
				log.log(java.util.logging.Level.SEVERE, "Error opening dialog", e);
			}
		}

		return null;
	}

	@Override
	public String convert (String columnName, String value) {
		return null;
	}
}
