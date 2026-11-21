package org.mycompany.requisition.approval.ui;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.ApprovalRule;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Listitem;

/**
 * Section 05, the approval rules of the selected version.
 * <p>
 * A rule answers one question: which levels does a document of this shape have to
 * pass. The conditions are all optional, and a rule with no condition set matches
 * everything - which is why the evaluation order matters and why Move Up and
 * Move Down are on this section as much as the level number is.
 *
 * <h3>Why the levels are a multiple selection</h3>
 * The engine stores the required levels as a list such as {@code "1,2"}, which
 * came out of the design as a text field. That was the wrong choice for a screen:
 * a typed comma list produces {@code "1, 2"}, {@code "1;2"} and {@code "1,,2"} as
 * silently different values, and the rule then quietly selects one level instead
 * of two. The list is therefore picked from the levels that exist, and the engine
 * still receives the same canonical {@code "1,2"} it always did.
 */
public class RulePanel extends ConfigListPanel {
	private static final long serialVersionUID = 1L;

	/** Condition stored on a rule; amount is the documented starting point. */
	private static final String CONDITION_AMOUNT = "AMOUNT";

	private final WProcessApprovalWindow window;
	private List<ApprovalRule> rules = new ArrayList<ApprovalRule>();
	private List<ApprovalLevel> levels = new ArrayList<ApprovalLevel>();

	// editor
	private org.adempiere.webui.component.Textbox txtName;
	private org.adempiere.webui.component.Textbox txtMinAmount;
	private org.adempiere.webui.component.Textbox txtMaxAmount;
	private Combobox cmbOrg;
	private Combobox cmbUser;
	private Combobox cmbRole;
	private Combobox cmbWarehouse;
	private Combobox cmbCategory;
	private Combobox cmbBusinessPartner;
	private Combobox cmbProject;
	private Combobox cmbDocType;
	private org.adempiere.webui.component.Listbox lstLevels;
	private org.adempiere.webui.component.Textbox txtDescription;

	public RulePanel(WProcessApprovalWindow window) {
		super(window, "No approval rules yet. Without a rule the levels flagged required are used.");
		this.window = window;
	}

	// ─────────────────────────────────────────────────────────────
	//  data
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void reload() {
		rules = new ArrayList<ApprovalRule>();
		levels = new ArrayList<ApprovalLevel>();
		if (window.getSelectedVersionId() > 0) {
			rules.addAll(window.ruleService().listRules(window.getSelectedVersionId()));
			levels.addAll(window.levelService().listLevels(window.getSelectedVersionId()));
		}
	}

	@Override
	protected List<Integer> ids() {
		List<Integer> result = new ArrayList<Integer>();
		for (ApprovalRule r : rules)
			result.add(Integer.valueOf(r.getId()));
		return result;
	}

	@Override
	protected String labelOf(int index) {
		ApprovalRule rule = rules.get(index);
		StringBuilder sb = new StringBuilder();
		sb.append(rule.getPriority()).append(". ").append(rule.getRuleName());
		sb.append("  [amount ").append(rule.getMinimumAmount() == null ? "any" : rule.getMinimumAmount().toPlainString());
		sb.append(" to ");
		sb.append(rule.getMaximumAmount() == null ? "any" : rule.getMaximumAmount().toPlainString());
		sb.append(" -> levels ").append(rule.getRequiredLevels()).append(']');
		int conditions = extraConditionCount(rule);
		if (conditions > 0)
			sb.append("  +").append(conditions).append(" more condition").append(conditions == 1 ? "" : "s");
		return sb.toString();
	}

	/** How many conditions besides the amount band are set on a rule. */
	private int extraConditionCount(ApprovalRule rule) {
		int count = 0;
		if (rule.getOrgId() > 0)
			count++;
		if (rule.getUserId() > 0)
			count++;
		if (rule.getRoleId() > 0)
			count++;
		if (rule.getWarehouseId() > 0)
			count++;
		if (rule.getProductCategoryId() > 0)
			count++;
		if (rule.getBusinessPartnerId() > 0)
			count++;
		if (rule.getProjectId() > 0)
			count++;
		if (rule.getDocumentTypeId() > 0)
			count++;
		return count;
	}

	private ApprovalRule ruleOf(int id) {
		for (ApprovalRule r : rules)
			if (r.getId() == id)
				return r;
		return null;
	}

	// ─────────────────────────────────────────────────────────────
	//  editor
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void buildEditor() {
		ApprovalRule rule = editingId() > 0 ? ruleOf(editingId()) : null;
		UiLayouts.FieldGrid fields = UiLayouts.grid(4);
		int client = clientId();

		org.adempiere.webui.component.Row row = UiLayouts.row();
		UiLayouts.labelCell(row, "Rule Name");
		txtName = UiLayouts.text();
		txtName.setValue(rule == null ? "" : nullSafe(rule.getRuleName()));
		UiLayouts.valueCell(row, txtName);

		UiLayouts.labelCell(row, "Required Levels");
		lstLevels = new org.adempiere.webui.component.Listbox();
		lstLevels.setWidth("100%");
		lstLevels.setRows(3);
		lstLevels.setMultiple(true);
		fillLevelSelection(rule);
		UiLayouts.valueCell(row, lstLevels);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Minimum Amount");
		txtMinAmount = UiLayouts.smallText();
		txtMinAmount.setValue(rule == null ? "" : plain(rule.getMinimumAmount()));
		UiLayouts.valueCell(row, txtMinAmount);

		UiLayouts.labelCell(row, "Maximum Amount");
		txtMaxAmount = UiLayouts.smallText();
		txtMaxAmount.setValue(rule == null ? "" : plain(rule.getMaximumAmount()));
		UiLayouts.valueCell(row, txtMaxAmount);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Organisation");
		cmbOrg = UiLayouts.comboBox();
		fill(cmbOrg, activeOrgs(client), true);
		UiLayouts.selectValue(cmbOrg, Integer.valueOf(rule == null ? 0 : rule.getOrgId()));
		UiLayouts.valueCell(row, cmbOrg);

		UiLayouts.labelCell(row, "User");
		cmbUser = UiLayouts.comboBox();
		fill(cmbUser, activeUsers(client), true);
		UiLayouts.selectValue(cmbUser, Integer.valueOf(rule == null ? 0 : rule.getUserId()));
		UiLayouts.valueCell(row, cmbUser);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Role");
		cmbRole = UiLayouts.comboBox();
		fill(cmbRole, activeRoles(client), true);
		UiLayouts.selectValue(cmbRole, Integer.valueOf(rule == null ? 0 : rule.getRoleId()));
		UiLayouts.valueCell(row, cmbRole);

		UiLayouts.labelCell(row, "Warehouse");
		cmbWarehouse = UiLayouts.comboBox();
		fill(cmbWarehouse, Lookups.warehouses(client), true);
		UiLayouts.selectValue(cmbWarehouse, Integer.valueOf(rule == null ? 0 : rule.getWarehouseId()));
		UiLayouts.valueCell(row, cmbWarehouse);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Product Category");
		cmbCategory = UiLayouts.comboBox();
		fill(cmbCategory, Lookups.productCategories(client), true);
		UiLayouts.selectValue(cmbCategory, Integer.valueOf(rule == null ? 0 : rule.getProductCategoryId()));
		UiLayouts.valueCell(row, cmbCategory);

		UiLayouts.labelCell(row, "Business Partner");
		cmbBusinessPartner = UiLayouts.comboBox();
		fill(cmbBusinessPartner, Lookups.businessPartners(client), true);
		UiLayouts.selectValue(cmbBusinessPartner, Integer.valueOf(rule == null ? 0 : rule.getBusinessPartnerId()));
		UiLayouts.valueCell(row, cmbBusinessPartner);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Project");
		cmbProject = UiLayouts.comboBox();
		fill(cmbProject, Lookups.projects(client), true);
		UiLayouts.selectValue(cmbProject, Integer.valueOf(rule == null ? 0 : rule.getProjectId()));
		UiLayouts.valueCell(row, cmbProject);

		UiLayouts.labelCell(row, "Document Type");
		cmbDocType = UiLayouts.comboBox();
		fill(cmbDocType, Lookups.documentTypes(client), true);
		UiLayouts.selectValue(cmbDocType, Integer.valueOf(rule == null ? 0 : rule.getDocumentTypeId()));
		UiLayouts.valueCell(row, cmbDocType);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Description");
		txtDescription = UiLayouts.text();
		txtDescription.setValue(rule == null ? "" : nullSafe(rule.getDescription()));
		UiLayouts.wideValueCell(row, txtDescription, 3);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.hintCell(row, "A condition left at \"- not set -\" matches anything. The first rule that "
				+ "matches decides the whole chain, so a specific rule belongs above a general one. "
				+ "Use Move Up and Move Down to set that order.");
		fields.rows.appendChild(row);

		editor().appendChild(fields.grid);
		hint(overlapWarning());
	}

	/** Fills the level picker and preselects what the stored rule selected. */
	private void fillLevelSelection(ApprovalRule rule) {
		Set<Integer> selected = new LinkedHashSet<Integer>();
		if (rule != null)
			for (int no : RuleResolverAccess.parse(rule.getRequiredLevels()))
				selected.add(Integer.valueOf(no));

		for (ApprovalLevel level : levels)
			lstLevels.appendItem(level.getLevelNo() + ". " + level.getDisplayName(),
					Integer.valueOf(level.getLevelNo()));

		for (Listitem item : lstLevels.getItems()) {
			Object value = item.getValue();
			if (value != null && selected.contains(Integer.valueOf(value.toString().trim())))
				lstLevels.addItemToSelection(item);
		}
	}

	/**
	 * The overlap warning of the whole rule list.
	 * <p>
	 * Shown under the editor rather than only at save time, because two overlapping
	 * bands make the evaluation order silently decide the outcome and that has to
	 * be visible while the rules are being written, not after.
	 */
	private String overlapWarning() {
		if (window.getSelectedVersionId() <= 0)
			return "";
		String warning = window.ruleService().findOverlap(window.getSelectedVersionId());
		return warning == null ? "" : "Warning: " + warning;
	}

	// ─────────────────────────────────────────────────────────────
	//  actions
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void createRow() {
		run("Adding a rule", () -> {
			ApprovalRule rule = fromEditor();
			window.ruleService().createRule(window.getSelectedVersionId(), rule);
			window.reportStatus("Rule \"" + rule.getRuleName() + "\" added.");
			refresh();
		});
	}

	@Override
	protected void updateRow(int id) {
		run("Updating the rule", () -> {
			ApprovalRule rule = fromEditor();
			rule.setId(id);
			window.ruleService().updateRule(rule);
			window.reportStatus("Rule \"" + rule.getRuleName() + "\" updated.");
			refresh();
		});
	}

	@Override
	protected void deleteRow(int id) {
		run("Deleting the rule", () -> {
			ApprovalRule rule = ruleOf(id);
			window.ruleService().deleteRule(id);
			window.reportStatus("Rule \"" + (rule == null ? id : rule.getRuleName()) + "\" deactivated.");
			refresh();
		});
	}

	@Override
	protected String firstActionLabel() {
		return "Move Up";
	}

	@Override
	protected void firstAction(int id) {
		run("Moving the rule", () -> window.ruleService().moveUp(id));
	}

	@Override
	protected String secondActionLabel() {
		return "Move Down";
	}

	@Override
	protected void secondAction(int id) {
		run("Moving the rule", () -> window.ruleService().moveDown(id));
	}

	/** Reads the editor into a rule, refusing what the engine could not use. */
	private ApprovalRule fromEditor() throws Exception {
		ApprovalRule rule = new ApprovalRule();
		rule.setProcessVersionId(window.getSelectedVersionId());
		rule.setCondition(CONDITION_AMOUNT);

		String name = textOf(txtName);
		if (name == null)
			throw new IllegalArgumentException("Rule Name is required.");
		rule.setRuleName(name);

		BigDecimal min = amountOf(txtMinAmount, "Minimum Amount");
		BigDecimal max = amountOf(txtMaxAmount, "Maximum Amount");
		if (min != null && max != null && max.compareTo(min) < 0)
			throw new IllegalArgumentException("Maximum Amount must be greater than or equal to "
					+ "Minimum Amount.");
		rule.setMinimumAmount(min);
		rule.setMaximumAmount(max);

		rule.setOrgId(UiLayouts.comboId(cmbOrg));
		rule.setUserId(UiLayouts.comboId(cmbUser));
		rule.setRoleId(UiLayouts.comboId(cmbRole));
		rule.setWarehouseId(UiLayouts.comboId(cmbWarehouse));
		rule.setProductCategoryId(UiLayouts.comboId(cmbCategory));
		rule.setBusinessPartnerId(UiLayouts.comboId(cmbBusinessPartner));
		rule.setProjectId(UiLayouts.comboId(cmbProject));
		rule.setDocumentTypeId(UiLayouts.comboId(cmbDocType));

		rule.setRequiredLevels(requiredLevelsOf());
		rule.setDescription(textOf(txtDescription));
		return rule;
	}

	/**
	 * The selected levels, in ascending order, in the canonical form the engine
	 * parses.
	 * <p>
	 * Sorted here rather than in list order, so a rule reads the same whichever end
	 * of the list the user picked from.
	 */
	private String requiredLevelsOf() {
		List<Integer> chosen = new ArrayList<Integer>();
		for (Listitem item : lstLevels.getSelectedItems()) {
			Object value = item.getValue();
			if (value == null)
				continue;
			chosen.add(Integer.valueOf(value.toString().trim()));
		}
		if (chosen.isEmpty())
			throw new IllegalArgumentException("Select at least one required approval level.");

		java.util.Collections.sort(chosen);
		StringBuilder sb = new StringBuilder();
		for (Integer no : chosen) {
			if (sb.length() > 0)
				sb.append(',');
			sb.append(no);
		}
		return sb.toString();
	}

	private BigDecimal amountOf(org.adempiere.webui.component.Textbox box, String label) throws Exception {
		String text = textOf(box);
		if (text == null)
			return null;
		try {
			BigDecimal value = new BigDecimal(text.trim());
			if (value.signum() < 0)
				throw new IllegalArgumentException(label + " cannot be negative.");
			return value;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(label + " must be a number, for example 50000 or 50000.00.");
		}
	}

	private int clientId() {
		return org.compiere.util.Env.getAD_Client_ID(window.context());
	}

	private static String plain(BigDecimal value) {
		return value == null ? "" : value.toPlainString();
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
