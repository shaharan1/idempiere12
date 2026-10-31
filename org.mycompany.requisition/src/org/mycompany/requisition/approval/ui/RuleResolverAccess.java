package org.mycompany.requisition.approval.ui;

import java.util.ArrayList;
import java.util.List;

import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.ApprovalRule;
import org.mycompany.requisition.approval.service.RuleResolver;

/**
 * Bridges the configuration model to the rule panel.
 * <p>
 * It exists so the panel never parses the stored level list itself. The list is a
 * text column in the database, and the one parser that knows how to read it is
 * {@link RuleResolver#parseLevels(String)} - the very same method the engine uses
 * when a document is submitted. A second parser here would be a second opinion
 * about the same string, and the two would eventually disagree.
 */
final class RuleResolverAccess {

	private RuleResolverAccess() {
		// helper class
	}

	/** Level numbers a stored rule selected, in ascending order. */
	static List<Integer> parse(String requiredLevels) {
		List<Integer> result = new ArrayList<Integer>();
		for (int no : RuleResolver.parseLevels(requiredLevels))
			result.add(Integer.valueOf(no));
		return result;
	}

	/** The levels a rule names, in the order the rule names them. */
	static List<ApprovalLevel> levelsOf(ApprovalRule rule, List<ApprovalLevel> available) {
		List<ApprovalLevel> result = new ArrayList<ApprovalLevel>();
		if (rule == null)
			return result;
		for (int no : RuleResolver.parseLevels(rule.getRequiredLevels()))
			for (ApprovalLevel level : available)
				if (level.getLevelNo() == no)
					result.add(level);
		return result;
	}
}
