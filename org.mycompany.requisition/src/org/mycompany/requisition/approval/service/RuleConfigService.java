package org.mycompany.requisition.approval.service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.mycompany.requisition.approval.config.ApprovalRule;

/**
 * Read and write access to the approval rules of a process version.
 * <p>
 * This is the service behind section 05 of the window. Rules are stored with a
 * priority and evaluated by {@link RuleResolver}; this class only maintains them.
 * <p>
 * {@link #moveUp(int)} and {@link #moveDown(int)} change the evaluation order
 * from the configuration UI, so the administrator can decide which band wins
 * without editing numbers by hand.
 */
public class RuleConfigService extends BaseConfigService {

	private static final String T_RULE = "XX_PROCESS_APPROVAL_RULE";

	public RuleConfigService() throws ConfigException {
		super(T_RULE);
	}

	/** Rules of one version, in evaluation order. */
	public List<ApprovalRule> listRules(int processVersionId) {
		List<ApprovalRule> result = new ArrayList<ApprovalRule>();
		String sql = "SELECT XX_PROCESS_APPROVAL_RULE_ID, XX_PROCESS_VERSION_ID, RuleName, Condition,"
				+ " MinimumAmount, MaximumAmount, AD_Org_ID_Rule, AD_User_ID, AD_Role_ID, M_Warehouse_ID,"
				+ " M_Product_Category_ID, C_BP_ID, C_Project_ID, C_DocType_ID, RequiredLevels, Priority,"
				+ " Description FROM " + T_RULE
				+ " WHERE XX_PROCESS_VERSION_ID=? AND IsActive='Y' ORDER BY Priority, Created";
		PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, processVersionId);
			rs = pstmt.executeQuery();
			while (rs.next())
				result.add(read(rs));
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	private ApprovalRule read(java.sql.ResultSet rs) throws java.sql.SQLException {
		ApprovalRule r = new ApprovalRule();
		r.setId(rs.getInt(1));
		r.setProcessVersionId(rs.getInt(2));
		r.setRuleName(rs.getString(3));
		r.setCondition(rs.getString(4));

		BigDecimal min = rs.getBigDecimal(5);
		r.setMinimumAmount(min);
		BigDecimal max = rs.getBigDecimal(6);
		r.setMaximumAmount(max);

		r.setOrgId(rs.getInt(7));
		r.setUserId(rs.getInt(8));
		r.setRoleId(rs.getInt(9));
		r.setWarehouseId(rs.getInt(10));
		r.setProductCategoryId(rs.getInt(11));
		r.setBusinessPartnerId(rs.getInt(12));
		r.setProjectId(rs.getInt(13));
		r.setDocumentTypeId(rs.getInt(14));
		r.setRequiredLevels(rs.getString(15));
		r.setPriority(rs.getInt(16));
		r.setDescription(rs.getString(17));
		return r;
	}

	/**
	 * Creates a rule at the end of the evaluation order.
	 *
	 * @return the new XX_PROCESS_APPROVAL_RULE_ID
	 */
	public int createRule(int processVersionId, ApprovalRule rule) throws ConfigException {
		if (processVersionId <= 0)
			throw new ConfigException("The process version of the rule is unknown.");
		if (rule == null)
			throw new ConfigException("No rule data was entered.");
		validateBands(rule);

		final String trxName = newTrx("Rule");
		return inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_RULE).getPO(0, trxName);
			if (po == null)
				throw new ConfigException("Table " + T_RULE + " is not registered in iDempiere.");
			po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));

			set(po, "XX_PROCESS_VERSION_ID", Integer.valueOf(processVersionId));
			set(po, "Priority", Integer.valueOf(nextPriority(processVersionId)));
			apply(po, rule);
			save(po, trxName);
			return Integer.valueOf(po.get_ID());
		}).intValue();
	}

	/** Updates an existing rule. */
	public void updateRule(ApprovalRule rule) throws ConfigException {
		if (rule == null || rule.getId() <= 0)
			throw new ConfigException("No rule is selected for updating.");
		validateBands(rule);

		final String trxName = newTrx("RuleUpd");
		inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_RULE).getPO(rule.getId(), trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Rule " + rule.getId() + " was not found.");
			apply(po, rule);
			save(po, trxName);
			return null;
		});
	}

	private void validateBands(ApprovalRule rule) throws ConfigException {
		if (rule.getMinimumAmount() != null && rule.getMaximumAmount() != null
				&& rule.getMaximumAmount().compareTo(rule.getMinimumAmount()) < 0)
			throw new ConfigException("Maximum Amount must be greater than or equal to Minimum Amount.");

		if (trim(rule.getRequiredLevels()) == null)
			throw new ConfigException("Select at least one required approval level.");
	}

	private void apply(PO po, ApprovalRule rule) {
		set(po, "RuleName", trim(rule.getRuleName()));
		set(po, "Condition", rule.getCondition() == null ? "AMOUNT" : rule.getCondition());
		set(po, "MinimumAmount", rule.getMinimumAmount());
		set(po, "MaximumAmount", rule.getMaximumAmount());
		set(po, "AD_Org_ID_Rule", rule.getOrgId() > 0 ? Integer.valueOf(rule.getOrgId()) : null);
		set(po, "AD_User_ID", rule.getUserId() > 0 ? Integer.valueOf(rule.getUserId()) : null);
		set(po, "AD_Role_ID", rule.getRoleId() > 0 ? Integer.valueOf(rule.getRoleId()) : null);
		set(po, "M_Warehouse_ID", rule.getWarehouseId() > 0 ? Integer.valueOf(rule.getWarehouseId()) : null);
		set(po, "M_Product_Category_ID",
				rule.getProductCategoryId() > 0 ? Integer.valueOf(rule.getProductCategoryId()) : null);
		set(po, "C_BP_ID", rule.getBusinessPartnerId() > 0 ? Integer.valueOf(rule.getBusinessPartnerId()) : null);
		set(po, "C_Project_ID", rule.getProjectId() > 0 ? Integer.valueOf(rule.getProjectId()) : null);
		set(po, "C_DocType_ID", rule.getDocumentTypeId() > 0 ? Integer.valueOf(rule.getDocumentTypeId()) : null);
		set(po, "RequiredLevels", trim(rule.getRequiredLevels()));
		set(po, "Description", rule.getDescription());
		set(po, "IsActive", "Y");
		if (rule.getPriority() > 0)
			set(po, "Priority", Integer.valueOf(rule.getPriority()));
	}

	/**
	 * Deactivates a rule. The row is kept so that an audit of which rules once
	 * existed stays possible.
	 */
	public void deleteRule(int ruleId) throws ConfigException {
		if (ruleId <= 0)
			return;
		final String trxName = newTrx("RuleDel");
		inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_RULE).getPO(ruleId, trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Rule " + ruleId + " was not found.");
			set(po, "IsActive", "N");
			save(po, trxName);
			return null;
		});
	}

	/** Moves a rule one position earlier in the evaluation order. */
	public void moveUp(int ruleId) throws ConfigException {
		move(ruleId, -1);
	}

	/** Moves a rule one position later in the evaluation order. */
	public void moveDown(int ruleId) throws ConfigException {
		move(ruleId, 1);
	}

	private void move(int ruleId, int offset) throws ConfigException {
		List<ApprovalRule> rules = rulesOf(ruleId);
		if (rules.isEmpty())
			throw new ConfigException("Rule " + ruleId + " was not found.");

		int index = -1;
		for (int i = 0; i < rules.size(); i++)
			if (rules.get(i).getId() == ruleId)
				index = i;
		if (index < 0)
			throw new ConfigException("Rule " + ruleId + " was not found.");

		int target = index + offset;
		if (target < 0 || target >= rules.size())
			return;

		final String trxName = newTrx("RuleMove");
		final ApprovalRule a = rules.get(index);
		final ApprovalRule b = rules.get(target);
		inTrx(trxName, () -> {
			writePriority(a.getId(), b.getPriority(), trxName);
			writePriority(b.getId(), a.getPriority(), trxName);
			return null;
		});
	}

	private List<ApprovalRule> rulesOf(int ruleId) {
		List<ApprovalRule> result = new ArrayList<ApprovalRule>();
		int versionId = org.compiere.util.DB.getSQLValue(null,
				"SELECT XX_PROCESS_VERSION_ID FROM " + T_RULE + " WHERE XX_PROCESS_APPROVAL_RULE_ID=?",
				Integer.valueOf(ruleId));
		if (versionId <= 0)
			return result;
		return listRules(versionId);
	}

	private void writePriority(int ruleId, int priority, String trxName) {
		PO po = MTable.get(ctx(), T_RULE).getPO(ruleId, trxName);
		if (po == null || !po.load(trxName, null))
			return;
		set(po, "Priority", Integer.valueOf(priority));
		try {
			po.save();
		} catch (RuntimeException e) {
			// a collision here is repaired by the next renumber
		}
	}

	private int nextPriority(int processVersionId) {
		return org.compiere.util.DB.getSQLValue(null,
				"SELECT COALESCE(MAX(Priority), 0) FROM " + T_RULE + " WHERE XX_PROCESS_VERSION_ID=?",
				Integer.valueOf(processVersionId)) + 10;
	}

	/**
	 * Checks the configured bands for overlaps. Two rules that both match the
	 * same amount make the evaluation order silently decide the outcome, which is
	 * almost always a configuration mistake.
	 *
	 * @return a human readable warning, or null when the bands are clean
	 */
	public String findOverlap(int processVersionId) {
		List<ApprovalRule> rules = listRules(processVersionId);
		for (int i = 0; i < rules.size(); i++) {
			for (int j = i + 1; j < rules.size(); j++) {
				ApprovalRule a = rules.get(i);
				ApprovalRule b = rules.get(j);
				if (a.getMinimumAmount() == null || a.getMaximumAmount() == null
						|| b.getMinimumAmount() == null || b.getMaximumAmount() == null)
					continue;
				boolean overlaps = a.getMinimumAmount().compareTo(b.getMaximumAmount()) <= 0
						&& b.getMinimumAmount().compareTo(a.getMaximumAmount()) <= 0;
				if (overlaps)
					return "Rule \"" + a + "\" and \"" + b + "\" overlap between "
							+ (a.getMinimumAmount().max(b.getMinimumAmount())).toPlainString()
							+ " and " + (a.getMaximumAmount().min(b.getMaximumAmount())).toPlainString()
							+ ". The first rule in the list wins there.";
			}
		}
		return null;
	}
}
