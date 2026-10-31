package com.xx.approval.process;

import org.adempiere.base.annotation.Process;

/**
 * Approves the selected purchase order.
 * <p>
 * {@code approvalstatus} becomes 'A', {@code Approver_AD_User_ID} becomes the
 * logged-in user, and {@code Updated}/{@code UpdatedBy} are refreshed. The
 * rules that decide whether the record may be approved at all live in
 * {@link AbstractApprovalProcess} and are shared with the reject process.
 * <p>
 * The {@link Process} annotation gives the class its name in
 * {@code AD_Process.Value}; there is no Classname to type in, which is what the
 * annotation-based factory is for.
 */
@Process(name = "XXApproveProcess")
public class XXApproveProcess extends AbstractApprovalProcess {

	public XXApproveProcess() {
		super(STATUS_APPROVED, "Approved");
	}
}