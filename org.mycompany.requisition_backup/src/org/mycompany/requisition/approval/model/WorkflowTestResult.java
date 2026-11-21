package org.mycompany.requisition.approval.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of the "Test Workflow" dry run.
 * <p>
 * The administrator uses it to check a chain before activating it. The run never
 * writes anything: it resolves the rule, resolves the approvers and reports what
 * would happen, which is exactly what makes it safe to try a risky configuration
 * on a live document.
 */
public class WorkflowTestResult {

	/** One line of the simulated chain. */
	public static class LevelOutcome {
		private final int levelNo;
		private final String levelName;
		private final boolean required;
		private final int approverUserId;
		private final String approverName;
		private final String reason;

		LevelOutcome(int levelNo, String levelName, boolean required, int approverUserId, String approverName,
				String reason) {
			this.levelNo = levelNo;
			this.levelName = levelName;
			this.required = required;
			this.approverUserId = approverUserId;
			this.approverName = approverName;
			this.reason = reason;
		}

		/**
		 * Builds one line of the result.
		 * <p>
		 * The constructor stays inside this class on purpose, so that the only way
		 * to produce a line of the result is through a factory that documents what
		 * each field means. The test service builds these; a caller cannot invent
		 * its own shape.
		 *
		 * @param levelNo        position of the level in the chain
		 * @param levelName      name of the level
		 * @param required       false when the rule did not select this level
		 * @param approverUserId resolved approver, 0 when unresolved or a role
		 * @param approverName   display name of the approver, may be null
		 * @param reason         why this level is or is not in the chain
		 * @return the line
		 */
		public static LevelOutcome of(int levelNo, String levelName, boolean required, int approverUserId,
				String approverName, String reason) {
			return new LevelOutcome(levelNo, levelName, required, approverUserId, approverName, reason);
		}

		public int getLevelNo() {
			return levelNo;
		}

		public String getLevelName() {
			return levelName;
		}

		/** False when the rule did not select this level for the tested document. */
		public boolean isRequired() {
			return required;
		}

		public int getApproverUserId() {
			return approverUserId;
		}

		/** Display name of the resolved approver, or a dash when unresolved. */
		public String getApproverName() {
			return approverName == null || approverName.trim().length() == 0 ? "-" : approverName;
		}

		/** Why the level is required or not required. */
		public String getReason() {
			return reason;
		}

		@Override
		public String toString() {
			return (required ? "[x] " : "[ ] ") + "Level " + levelNo + " - " + levelName + " -> "
					+ getApproverName();
		}
	}

	private BigDecimal testedAmount;
	private String matchedRule;
	private final List<LevelOutcome> outcomes = new ArrayList<LevelOutcome>();
	private int finalApproverUserId;
	private String finalApproverName;
	private String warning;

	/** Amount that was used for the rule test. */
	public BigDecimal getTestedAmount() {
		return testedAmount;
	}

	public void setTestedAmount(BigDecimal testedAmount) {
		this.testedAmount = testedAmount;
	}

	/** Name of the rule that matched, or a note when no rule matched. */
	public String getMatchedRule() {
		return matchedRule;
	}

	public void setMatchedRule(String matchedRule) {
		this.matchedRule = matchedRule;
	}

	/** Every level, required or not, in chain order. */
	public List<LevelOutcome> getOutcomes() {
		return Collections.unmodifiableList(outcomes);
	}

	public void addOutcome(LevelOutcome outcome) {
		if (outcome != null)
			outcomes.add(outcome);
	}

	/** The last required level, which is who finally releases the document. */
	public int getFinalApproverUserId() {
		return finalApproverUserId;
	}

	public void setFinalApproverUserId(int finalApproverUserId) {
		this.finalApproverUserId = finalApproverUserId;
	}

	public String getFinalApproverName() {
		return finalApproverName == null || finalApproverName.trim().length() == 0 ? "-" : finalApproverName;
	}

	public void setFinalApproverName(String finalApproverName) {
		this.finalApproverName = finalApproverName;
	}

	/** Non blocking problem found while testing, for example an unresolved approver. */
	public String getWarning() {
		return warning;
	}

	public void setWarning(String warning) {
		this.warning = warning;
	}

	/** True when at least one level would have to act. */
	public boolean hasRequiredLevels() {
		for (LevelOutcome o : outcomes)
			if (o.isRequired())
				return true;
		return false;
	}

	/** Count of levels the rule selected. */
	public int getRequiredLevelCount() {
		int n = 0;
		for (LevelOutcome o : outcomes)
			if (o.isRequired())
				n++;
		return n;
	}
}
