package com.xx.approval.process;

import org.adempiere.base.annotation.Process;

/**
 * Rejects the selected purchase order.
 * <p>
 * {@code approvalstatus} becomes 'R', {@code Approver_AD_User_ID} becomes the
 * logged-in user, and {@code Updated}/{@code UpdatedBy} are refreshed. The rules
 * that decide whether the record may be rejected at all live in
 * {@link AbstractApprovalProcess} and are shared with the approve process.
 */
@Process(name = "XXRejectProcess")
public class XXRejectProcess extends AbstractApprovalProcess {

	public XXRejectProcess() {
		super(STATUS_REJECTED, "Rejected");
	}
}