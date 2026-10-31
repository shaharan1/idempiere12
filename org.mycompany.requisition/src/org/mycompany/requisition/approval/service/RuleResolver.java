package org.mycompany.requisition.approval.service;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.compiere.util.DB;
import org.compiere.util.Env;
import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.ApprovalRule;
import org.mycompany.requisition.approval.config.Delegation;
import org.mycompany.requisition.approval.config.ResponsibleType;
import org.mycompany.requisition.approval.model.DocumentContext;
import org.mycompany.requisition.approval.model.RequiredApproval;
import org.mycompany.requisition.approval.model.ResolvedRoute;

/**
 * Decides which approval levels a document has to pass.
 * <p>
 * The evaluation is deliberately split in two halves:
 *
 * <ol>
 * <li>{@link #evaluate(List, List, DocumentContext)} is pure. It takes the rules
 * and levels that are already loaded plus the document facts, and returns the
 * chain. It touches no database, so the most important rule of the framework is
 * fully unit testable.</li>
 * <li>{@link #resolve(int, DocumentContext)} is the thin part that loads the
 * data and resolves approvers, including delegation.</li>
 * </ol>
 *
 * Evaluation order, which is also the documented behaviour:
 *
 * <ol>
 * <li>active rules of the process version, ascending priority</li>
 * <li>first rule whose conditions all match wins</li>
 * <li>its level numbers become the chain, sorted and de-duplicated</li>
 * <li>if no rule matches, every level flagged {@code IsRequired} is used</li>
 * </ol>
 */
public class RuleResolver {

	/**
	 * Pure evaluation of the rule chain.
	 *
	 * @param rules   rules of the process version, already ordered by priority
	 * @param levels  levels of the process version, already ordered by level number
	 * @param context facts about the document
	 * @return the approvals the document has to pass, in chain order
	 */
	public static ResolvedRoute evaluate(List<ApprovalRule> rules, List<ApprovalLevel> levels,
			DocumentContext context) {

		ResolvedRoute route = new ResolvedRoute();

		ApprovalRule matched = firstMatch(rules, context);
		Set<Integer> wantedLevels = new LinkedHashSet<Integer>();

		if (matched != null) {
			route.setMatchedRule(matched);
			for (int no : parseLevels(matched.getRequiredLevels())) {
				if (findLevel(levels, no) != null)
					wantedLevels.add(Integer.valueOf(no));
			}
			route.addWarning(overlapHint(rules));
		} else {
			// no rule covers this document: fall back to the configured defaults
			route.setFallbackUsed(true);
			for (ApprovalLevel l : levels)
				if (l.isRequired())
					wantedLevels.add(Integer.valueOf(l.getLevelNo()));
		}

		// always deliver the chain sorted by level number, whatever the rule stored
		List<Integer> ordered = new ArrayList<Integer>(wantedLevels);
		java.util.Collections.sort(ordered);

		for (Integer no : ordered) {
			ApprovalLevel level = findLevel(levels, no.intValue());
			if (level == null)
				continue;
			String reason = matched != null
					? "required by rule \"" + matched + "\""
					: "required by the level default";
			RequiredApproval approval = new RequiredApproval(level, reason);
			if (level.getResponsibleType() == ResponsibleType.USER)
				approval.setApproverUserId(level.getUserId());
			else if (level.getResponsibleType() == ResponsibleType.ROLE)
				approval.setApproverRoleId(level.getRoleId());
			else
				approval.setWarning("Approver is resolved at run time for type "
						+ level.getResponsibleType().name());
			route.addApproval(approval);
		}

		return route;
	}

	/** First rule, in the given order, whose conditions all match. */
	private static ApprovalRule firstMatch(List<ApprovalRule> rules, DocumentContext context) {
		if (rules == null || context == null)
			return null;
		for (ApprovalRule rule : rules)
			if (rule.matches(context.getOrgId(), context.getUserId(), context.getRoleId(),
					context.getWarehouseId(), context.getProductCategoryId(), context.getBusinessPartnerId(),
					context.getProjectId(), context.getDocumentTypeId(), context.getAmount()))
				return rule;
		return null;
	}

	/**
	 * Parses a stored level list such as "1,2,3" into level numbers.
	 * <p>
	 * Tolerates spaces and empty entries, and silently drops anything that is not
	 * a number. A malformed entry must never stop a running workflow, so the bad
	 * part is skipped instead of raising.
	 *
	 * @param requiredLevels the stored list, may be null
	 * @return the level numbers in the order they appear
	 */
	public static int[] parseLevels(String requiredLevels) {
		if (requiredLevels == null)
			return new int[0];
		String[] parts = requiredLevels.split(",");
		List<Integer> numbers = new ArrayList<Integer>();
		for (String part : parts) {
			String t = part.trim();
			if (t.length() == 0)
				continue;
			try {
				numbers.add(Integer.valueOf(t));
			} catch (NumberFormatException e) {
				// a malformed entry is ignored rather than stopping the whole chain
			}
		}
		int[] result = new int[numbers.size()];
		for (int i = 0; i < result.length; i++)
			result[i] = numbers.get(i).intValue();
		return result;
	}

	private static ApprovalLevel findLevel(List<ApprovalLevel> levels, int levelNo) {
		if (levels == null)
			return null;
		for (ApprovalLevel l : levels)
			if (l.getLevelNo() == levelNo)
				return l;
		return null;
	}

	/** Warns when two closed amount bands overlap, because the order then decides. */
	private static String overlapHint(List<ApprovalRule> rules) {
		if (rules == null)
			return null;
		for (int i = 0; i < rules.size(); i++) {
			for (int j = i + 1; j < rules.size(); j++) {
				ApprovalRule a = rules.get(i);
				ApprovalRule b = rules.get(j);
				if (a.getMinimumAmount() == null || a.getMaximumAmount() == null
						|| b.getMinimumAmount() == null || b.getMaximumAmount() == null)
					continue;
				if (a.getMinimumAmount().compareTo(b.getMaximumAmount()) <= 0
						&& b.getMinimumAmount().compareTo(a.getMaximumAmount()) <= 0)
					return "Rule \"" + a + "\" overlaps \"" + b
							+ "\". The rule listed first decides that range.";
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------
	//  loading part
	// ------------------------------------------------------------------------------

	/**
	 * Resolves the chain for a document, loading the configuration and applying
	 * any active delegation.
	 *
	 * @param processVersionId version to resolve against
	 * @param context          facts about the document
	 * @return the chain, never null
	 * @throws ConfigException when the version has no readable configuration
	 */
	public ResolvedRoute resolve(int processVersionId, DocumentContext context) throws BaseConfigService.ConfigException {
		if (processVersionId <= 0)
			throw new BaseConfigService.ConfigException("The process version of the document is unknown.");
		if (context == null)
			throw new BaseConfigService.ConfigException("No document data was supplied.");

		List<ApprovalRule> rules = new RuleConfigService().listRules(processVersionId);
		List<ApprovalLevel> levels = new LevelConfigService().listLevels(processVersionId);
		return resolve(rules, levels, context);
	}

	/**
	 * Same as {@link #resolve(int, DocumentContext)} but for an already loaded
	 * configuration. Used by the engine on every document and by the Test Workflow
	 * button, so both produce an identical result.
	 */
	public ResolvedRoute resolve(List<ApprovalRule> rules, List<ApprovalLevel> levels,
			DocumentContext context) throws BaseConfigService.ConfigException {

		ResolvedRoute route = evaluate(rules, levels, context);
		applyDelegation(route, context, new Timestamp(System.currentTimeMillis()));
		return route;
	}

	/**
	 * Replaces the approver of every level with its delegate when a valid
	 * delegation exists. The level itself is untouched, so the history still
	 * shows the original responsibility.
	 */
	private void applyDelegation(ResolvedRoute route, DocumentContext context, Timestamp now) {
		List<Delegation> delegations = loadActiveDelegations();
		if (delegations.isEmpty())
			return;

		for (RequiredApproval approval : route.getApprovals()) {
			int original = approval.getApproverUserId();
			if (original <= 0)
				continue;
			int levelId = approval.getLevel() == null ? 0 : approval.getLevel().getId();

			for (Delegation d : delegations) {
				if (d.getUserId() != original)
					continue;
				if (!d.isValidAt(now) || !d.appliesToLevel(levelId))
					continue;
				approval.setApproverUserId(d.getDelegateUserId());
				approval.setDelegatedFromUserId(original);
				break;
			}
		}
	}

	/** Delegations of the tenant that are active, loaded once per resolve call. */
	private List<Delegation> loadActiveDelegations() {
		List<Delegation> result = new ArrayList<Delegation>();
		String sql = "SELECT XX_PROCESS_DELEGATION_ID, AD_User_ID, Delegate_AD_User_ID, XX_PROCESS_LEVEL_ID,"
				+ " ValidFrom, ValidTo, Reason FROM XX_PROCESS_DELEGATION"
				+ " WHERE IsActive='Y' AND AD_Client_ID=? ORDER BY Created";
		PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			rs = pstmt.executeQuery();
			while (rs.next()) {
				Delegation d = new Delegation();
				d.setId(rs.getInt(1));
				d.setUserId(rs.getInt(2));
				d.setDelegateUserId(rs.getInt(3));
				d.setLevelId(rs.getInt(4));
				d.setValidFrom(rs.getTimestamp(5));
				d.setValidTo(rs.getTimestamp(6));
				d.setReason(rs.getString(7));
				result.add(d);
			}
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return result;
	}

	/**
	 * Only level numbers the document would have to pass, in order. Handy for a
	 * quick check or a dashboard summary.
	 */
	public static int[] evaluateLevelNumbers(List<ApprovalRule> rules, List<ApprovalLevel> levels,
			DocumentContext context) {
		ResolvedRoute route = evaluate(rules, levels, context);
		List<Integer> numbers = new ArrayList<Integer>();
		for (RequiredApproval a : route.getApprovals())
			numbers.add(Integer.valueOf(a.getLevelNo()));
		int[] result = new int[numbers.size()];
		for (int i = 0; i < result.length; i++)
			result[i] = numbers.get(i).intValue();
		return result;
	}
}
