package org.mycompany.requisition.approval.service;

import java.math.BigDecimal;

import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.model.ApprovalAction;
import org.mycompany.requisition.approval.model.ApprovalStatus;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.model.DocumentContext;
import org.mycompany.requisition.approval.model.RequiredApproval;
import org.mycompany.requisition.approval.model.ResolvedRoute;
import org.mycompany.requisition.approval.model.WorkflowTestResult;

/**
 * The dry run behind the Test Workflow button.
 * <p>
 * It answers "if a document like this arrives, who approves it" without writing
 * anything. It matters because the alternative is finding out the answer by
 * submitting a real document to a real approver, which is both rude and, on a
 * badly configured rule, embarrassing.
 *
 * <h3>It is the engine, not a second opinion</h3>
 * The run goes through {@link RuleResolver#resolve(int, DocumentContext)} - the
 * same call the engine makes when a document is submitted, including delegation.
 * A separate implementation of "who approves this" would be a second opinion about
 * the most important rule of the framework, and the two would eventually disagree.
 * The Test button therefore cannot show something the engine would not do.
 *
 * <p>
 * Because delegation is applied, the dry run also shows who would really be asked
 * while somebody is on leave.
 */
public class WorkflowTestService extends BaseConfigService {

	public WorkflowTestService() throws ConfigException {
		// this service owns no table of its own, it reads the configuration of a
		// version through the existing services and writes nothing at all
		super("XX_PROCESS_VERSION");
	}

	/**
	 * Simulates an approval for an amount.
	 *
	 * @param processVersionId version to simulate against
	 * @param amount           amount the rules are banded on, may be null
	 * @param orgId            organisation of the document, 0 for the session's
	 * @param userId           user of the document, 0 for the session's
	 * @param roleId           role of the document, 0 for the session's
	 * @param documentTypeId   document type, 0 when the process is not typed
	 * @return what would happen, never null
	 */
	public WorkflowTestResult test(int processVersionId, BigDecimal amount, int orgId, int userId, int roleId,
			int documentTypeId) throws ConfigException {

		if (processVersionId <= 0)
			throw new ConfigException("Choose a process version to test.");

		DocumentContext context = new DocumentContext();
		context.setAmount(amount);
		context.setOrgId(orgId > 0 ? orgId : orgId());
		context.setUserId(userId > 0 ? userId : sessionUser());
		context.setRoleId(roleId > 0 ? roleId : sessionRole());
		context.setDocumentTypeId(documentTypeId);
		context.setRequestedBy(sessionUser());
		context.setTableId(0);
		context.setRecordId(1);
		context.setDocumentNo("TEST-" + (amount == null ? "no amount" : amount.toPlainString()));

		return toResult(amount, processVersionId, new RuleResolver().resolve(processVersionId, context));
	}

	/**
	 * Turns a resolved route into the report the screen shows.
	 * <p>
	 * Every configured level appears in the result, including the ones the rule did
	 * not select. An administrator needs to see what was skipped as much as what was
	 * chosen, because "level 3 did not run" is usually the answer to the question
	 * being asked.
	 */
	private WorkflowTestResult toResult(BigDecimal amount, int processVersionId, ResolvedRoute route)
			throws ConfigException {

		WorkflowTestResult result = new WorkflowTestResult();
		result.setTestedAmount(amount);
		result.setMatchedRule(route.getMatchedRule() == null ? null : route.getMatchedRule().getRuleName());

		for (RequiredApproval required : route.getApprovals()) {
			ApprovalLevel level = required.getLevel();
			int approverUserId = required.getApproverUserId();
			String approverName;
			if (approverUserId > 0)
				approverName = userName(approverUserId);
			else if (required.getApproverRoleId() > 0)
				approverName = "role: " + roleName(required.getApproverRoleId());
			else
				approverName = null;

			result.addOutcome(WorkflowTestResult.LevelOutcome.of(required.getLevelNo(),
					level == null ? "" : level.getLevelName(), true, approverUserId, approverName,
					describeReason(required)));
		}

		// the levels the rule left out, so a skipped step is visible rather than
		// silently absent
		for (ApprovalLevel level : new LevelConfigService().listLevels(processVersionId)) {
			if (!contains(result, level.getLevelNo()))
				result.addOutcome(WorkflowTestResult.LevelOutcome.of(level.getLevelNo(), level.getLevelName(),
						false, 0, null, "not selected by the matching rule"));
		}

		sortByLevel(result);
		setFinalApprover(result);

		StringBuilder warning = new StringBuilder();
		if (route.getMatchedRule() == null)
			warning.append("No rule matched, so the levels flagged required were used. ");
		if (!result.hasRequiredLevels())
			warning.append("This document would be approved without anybody. ");
		if (amount == null)
			warning.append("No amount was given, so only the rules without an amount band could match. ");
		for (String w : route.getWarnings())
			warning.append(w).append(' ');
		result.setWarning(warning.toString().trim());

		return result;
	}

	private String describeReason(RequiredApproval required) {
		if (required.isDelegated())
			return required.getReason() + " (delegated from user " + required.getDelegatedFromUserId() + ")";
		return required.getReason();
	}

	private boolean contains(WorkflowTestResult result, int levelNo) {
		for (WorkflowTestResult.LevelOutcome outcome : result.getOutcomes())
			if (outcome.getLevelNo() == levelNo)
				return true;
		return false;
	}

	private void sortByLevel(WorkflowTestResult result) {
		java.util.Collections.sort(result.getOutcomes(),
				new java.util.Comparator<WorkflowTestResult.LevelOutcome>() {
					@Override
					public int compare(WorkflowTestResult.LevelOutcome a, WorkflowTestResult.LevelOutcome b) {
						return Integer.compare(a.getLevelNo(), b.getLevelNo());
					}
				});
	}

	/** The person who would finally release the document, when there is one. */
	private void setFinalApprover(WorkflowTestResult result) {
		WorkflowTestResult.LevelOutcome last = null;
		for (WorkflowTestResult.LevelOutcome outcome : result.getOutcomes())
			if (outcome.isRequired())
				last = outcome;
		if (last != null) {
			result.setFinalApproverUserId(last.getApproverUserId());
			result.setFinalApproverName(last.getApproverName());
		}
	}

	// ─────────────────────────────────────────────────────────────
	//  extra reports
	// ─────────────────────────────────────────────────────────────

	/**
	 * What the chain of a running document would look like, level by level, with
	 * what has already happened.
	 * <p>
	 * Used by the dashboard, where the question is not "who would approve" but
	 * "where is this stuck".
	 */
	public WorkflowTestResult describe(ApprovalTransaction chain) {
		WorkflowTestResult result = new WorkflowTestResult();
		if (chain == null)
			return result;
		result.setTestedAmount(chain.getAmount());
		result.setMatchedRule(null);
		for (ApprovalStep step : chain.getSteps()) {
			String name = step.getLevel() == null ? "" : step.getLevel().getLevelName();
			result.addOutcome(WorkflowTestResult.LevelOutcome.of(step.getLevelNo(), name,
					step.getStatus().isOpen() || step.getStatus() == org.mycompany.requisition.approval.model.StepStatus.APPROVED,
					step.getApproverUserId(),
					step.getApproverUserId() > 0 ? userName(step.getApproverUserId()) : null,
					"status: " + step.getStatus().getLabel()));
		}
		setFinalApprover(result);
		return result;
	}

	/** True when the whole chain of this transaction has been decided. */
	public static boolean isFinished(ApprovalTransaction chain) {
		return chain != null && chain.getStatus() != null && chain.getStatus().isFinal();
	}

	/** Label of the action that produced this status, for a one line summary. */
	public static String lastActionLabel(ApprovalTransaction chain) {
		ApprovalAction action = chain == null ? null : chain.getLastAction();
		return action == null ? "" : action.getLabel();
	}

	/** Status label, never null. */
	public static String statusLabel(ApprovalStatus status) {
		return status == null ? "" : status.getLabel();
	}

	// ─────────────────────────────────────────────────────────────
	//  helpers
	// ─────────────────────────────────────────────────────────────

	private int sessionUser() {
		return org.compiere.util.Env.getAD_User_ID(ctx());
	}

	private int sessionRole() {
		return org.compiere.util.Env.getAD_Role_ID(ctx());
	}

	private String userName(int userId) {
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_User WHERE AD_User_ID=?", Integer.valueOf(userId));
		return name == null ? ("user " + userId) : name;
	}

	private String roleName(int roleId) {
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_Role WHERE AD_Role_ID=?", Integer.valueOf(roleId));
		return name == null ? String.valueOf(roleId) : name;
	}
}
