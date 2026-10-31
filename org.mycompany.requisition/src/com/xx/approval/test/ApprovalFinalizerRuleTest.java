package com.xx.approval.test;

import com.xx.approval.event.ApprovalFinalizer;

/** Plain main(): java -cp bin:<idempiere jars> com.xx.approval.test.ApprovalFinalizerRuleTest */
public class ApprovalFinalizerRuleTest {

	static int failed;

	static void check(String name, boolean expected, boolean actual) {
		if (expected != actual) {
			failed++;
			System.out.println("FAIL " + name);
		} else
			System.out.println("ok   " + name);
	}

	public static void main(String[] a) {
		check("approved + drafted completes", true, ApprovalFinalizer.shouldComplete("APPROVED", "DR"));
		check("approved + in progress completes", true, ApprovalFinalizer.shouldComplete("APPROVED", "IP"));
		check("approved + already completed is skipped", false, ApprovalFinalizer.shouldComplete("APPROVED", "CO"));
		check("approved + closed is skipped", false, ApprovalFinalizer.shouldComplete("APPROVED", "CL"));
		check("pending never completes", false, ApprovalFinalizer.shouldComplete("PENDING", "DR"));
		check("rejected never completes", false, ApprovalFinalizer.shouldComplete("REJECTED", "DR"));
		check("returned never completes", false, ApprovalFinalizer.shouldComplete("RETURNED", "DR"));
		check("null chain status never completes", false, ApprovalFinalizer.shouldComplete(null, "DR"));
		if (failed > 0)
			System.exit(1);
		System.out.println("all passed");
	}
}
