package org.mycompany.requisition.approval.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.mycompany.requisition.approval.config.ApprovalRule;

/**
 * What the rule resolver decided for one document: which rule matched and which
 * approvals the document now has to pass.
 * <p>
 * This is a plain result object with no behaviour against the database, so the
 * same class is produced by the live engine and by the Test Workflow dry run.
 */
public class ResolvedRoute {

	private ApprovalRule matchedRule;

	/** True when no rule matched and the required flag of the levels was used. */
	private boolean fallbackUsed;

	private final List<RequiredApproval> approvals = new ArrayList<RequiredApproval>();

	private final List<String> warnings = new ArrayList<String>();

	/** The rule that matched, or null when the level defaults were used. */
	public ApprovalRule getMatchedRule() {
		return matchedRule;
	}

	public void setMatchedRule(ApprovalRule matchedRule) {
		this.matchedRule = matchedRule;
	}

	/** True when the resolver fell back to levels flagged IsRequired. */
	public boolean isFallbackUsed() {
		return fallbackUsed;
	}

	public void setFallbackUsed(boolean fallbackUsed) {
		this.fallbackUsed = fallbackUsed;
	}

	/** Required approvals in chain order. */
	public List<RequiredApproval> getApprovals() {
		return Collections.unmodifiableList(approvals);
	}

	public void addApproval(RequiredApproval approval) {
		if (approval != null)
			approvals.add(approval);
	}

	/** Non blocking problems found while resolving. */
	public List<String> getWarnings() {
		return Collections.unmodifiableList(warnings);
	}

	public void addWarning(String warning) {
		if (warning != null && warning.trim().length() > 0)
			warnings.add(warning);
	}

	/** True when at least one approval is required. */
	public boolean hasApprovals() {
		return !approvals.isEmpty();
	}

	/** Number of approvals the document has to pass. */
	public int size() {
		return approvals.size();
	}

	/** The last approval, which is who finally releases the document. */
	public RequiredApproval getFinalApproval() {
		return approvals.isEmpty() ? null : approvals.get(approvals.size() - 1);
	}

	/**
	 * Level numbers of the chain as a compact list, for example "1,2,3".
	 * <p>
	 * This is the canonical form used for logging, tests and the transaction
	 * view; the human readable text comes from {@link #getSummary()}.
	 */
	public String getLevelNumbers() {
		StringBuilder sb = new StringBuilder();
		for (RequiredApproval a : approvals) {
			if (sb.length() > 0)
				sb.append(',');
			sb.append(a.getLevelNo());
		}
		return sb.toString();
	}

	/** Label used by the transaction view and the test result. */
	public String getSummary() {
		if (matchedRule != null) {
			if (approvals.isEmpty())
				return "Rule \"" + matchedRule + "\" requires no usable level - check its level list";
			return "Rule \"" + matchedRule + "\" requires " + size() + " approval(s): levels "
					+ getLevelNumbers();
		}
		if (fallbackUsed && !approvals.isEmpty())
			return "No rule matched, " + size() + " approval(s) from the level defaults: levels "
					+ getLevelNumbers();
		return "No approval required";
	}
}
