package org.mycompany.requisition.approval.ui;

import java.util.ArrayList;
import java.util.List;

import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.ApprovalType;
import org.mycompany.requisition.approval.config.ResponsibleType;
import org.zkoss.zul.Cell;
import org.zkoss.zul.Combobox;

/**
 * Section 04, the approval levels of the selected version.
 * <p>
 * A level is one step of the chain: who approves, whether the step blocks the
 * document, and how long that person has. The chain order is the level number, and
 * Move Up and Move Down rewrite only that number, so the level identity - and
 * therefore the audit history of an approval that already ran - survives a
 * reorder.
 *
 * <h3>The responsible fields follow the responsible type</h3>
 * A level stores exactly one responsible field, matching its type. The editor
 * therefore shows the approver field of the chosen type and hides the others,
 * because a level that carried both a user and a role could be read either way and
 * the engine would have to guess.
 */
public class LevelPanel extends ConfigListPanel {
	private static final long serialVersionUID = 1L;

	private final WProcessApprovalWindow window;
	private List<ApprovalLevel> levels = new ArrayList<ApprovalLevel>();

	// editor
	private org.adempiere.webui.component.Textbox txtName;
	private org.adempiere.webui.component.Textbox txtStage;
	private Combobox cmbResponsibleType;
	private Combobox cmbUser;
	private Combobox cmbRole;
	private Combobox cmbApproverOrg;
	private Combobox cmbApprovalType;
	private org.adempiere.webui.component.Checkbox chkRequired;
	private org.adempiere.webui.component.Textbox txtSlaHours;
	private org.adempiere.webui.component.Textbox txtWarningHours;
	private org.adempiere.webui.component.Checkbox chkEscalation;
	private org.adempiere.webui.component.Textbox txtEscalationHours;
	private Combobox cmbEscalationUser;
	private Combobox cmbEscalationRole;
	private org.adempiere.webui.component.Textbox txtDescription;

	private Cell cellUser;
	private Cell cellRole;
	private Cell cellApproverOrg;
	private Cell cellEscalationUser;
	private Cell cellEscalationRole;

	public LevelPanel(WProcessApprovalWindow window) {
		super(window, "No approval levels yet. Press Add to create the first level.");
		this.window = window;
	}

	// ─────────────────────────────────────────────────────────────
	//  data
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void reload() {
		levels = new ArrayList<ApprovalLevel>();
		if (window.getSelectedVersionId() > 0)
			levels.addAll(window.levelService().listLevels(window.getSelectedVersionId()));
	}

	@Override
	protected List<Integer> ids() {
		List<Integer> result = new ArrayList<Integer>();
		for (ApprovalLevel l : levels)
			result.add(Integer.valueOf(l.getId()));
		return result;
	}

	@Override
	protected String labelOf(int index) {
		ApprovalLevel level = levels.get(index);
		StringBuilder sb = new StringBuilder();
		sb.append(level.getLevelNo()).append(". ").append(level.getDisplayName());
		sb.append("  [").append(level.getResponsibleType().getLabel()).append(", ");
		sb.append(level.getApprovalType().getLabel());
		sb.append(level.isRequired() ? ", required" : ", optional").append(']');
		if (level.getSlaHours() != null)
			sb.append("  SLA ").append(level.getSlaHours()).append("h");
		return sb.toString();
	}

	private ApprovalLevel levelOf(int id) {
		for (ApprovalLevel l : levels)
			if (l.getId() == id)
				return l;
		return null;
	}

	// ─────────────────────────────────────────────────────────────
	//  editor
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void buildEditor() {
		ApprovalLevel level = editingId() > 0 ? levelOf(editingId()) : null;
		UiLayouts.FieldGrid fields = UiLayouts.grid(4);
		int client = clientId();

		org.adempiere.webui.component.Row row = UiLayouts.row();
		UiLayouts.labelCell(row, "Level Name");
		txtName = UiLayouts.text();
		txtName.setValue(level == null ? "" : nullSafe(level.getLevelName()));
		UiLayouts.valueCell(row, txtName);

		UiLayouts.labelCell(row, "Approval Stage");
		txtStage = UiLayouts.text();
		txtStage.setValue(level == null ? "" : nullSafe(level.getApprovalStage()));
		UiLayouts.valueCell(row, txtStage);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Responsible Type");
		cmbResponsibleType = UiLayouts.comboBox();
		for (ResponsibleType type : ResponsibleType.values())
			UiLayouts.combo(cmbResponsibleType, type.getLabel(), type.name(),
					level != null ? type == level.getResponsibleType() : type == ResponsibleType.USER);
		cmbResponsibleType.addEventListener(org.zkoss.zk.ui.event.Events.ON_CHANGE, e -> applyVisibility());
		UiLayouts.valueCell(row, cmbResponsibleType);

		UiLayouts.labelCell(row, "Approval Type");
		cmbApprovalType = UiLayouts.comboBox();
		for (ApprovalType type : ApprovalType.values())
			UiLayouts.combo(cmbApprovalType, type.getLabel(), type.name(),
					level != null ? type == level.getApprovalType() : type == ApprovalType.APPROVE);
		UiLayouts.valueCell(row, cmbApprovalType);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		cellUser = UiLayouts.labelCell(row, "Approver");
		cmbUser = UiLayouts.comboBox();
		fill(cmbUser, activeUsers(client), true);
		UiLayouts.selectValue(cmbUser, Integer.valueOf(level == null ? 0 : level.getUserId()));
		UiLayouts.valueCell(row, cmbUser);

		cellRole = UiLayouts.labelCell(row, "Approver Role");
		cmbRole = UiLayouts.comboBox();
		fill(cmbRole, activeRoles(client), true);
		UiLayouts.selectValue(cmbRole, Integer.valueOf(level == null ? 0 : level.getRoleId()));
		UiLayouts.valueCell(row, cmbRole);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Approver Organisation");
		cellApproverOrg = UiLayouts.valueCell(row, cmbApproverOrg = UiLayouts.comboBox());
		fill(cmbApproverOrg, activeOrgs(client), true);
		UiLayouts.selectValue(cmbApproverOrg, Integer.valueOf(level == null ? 0 : level.getApproverOrgId()));

		UiLayouts.labelCell(row, "Required");
		chkRequired = new org.adempiere.webui.component.Checkbox();
		chkRequired.setLabel("Must approve");
		chkRequired.setSelected(level == null || level.isRequired());
		UiLayouts.valueCell(row, chkRequired);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "SLA Hours");
		txtSlaHours = UiLayouts.smallText();
		txtSlaHours.setValue(level == null || level.getSlaHours() == null ? "" : String.valueOf(level.getSlaHours()));
		UiLayouts.valueCell(row, txtSlaHours);

		UiLayouts.labelCell(row, "Warn Before Hours");
		txtWarningHours = UiLayouts.smallText();
		txtWarningHours.setValue(level == null || level.getWarningBeforeHours() == null ? ""
				: String.valueOf(level.getWarningBeforeHours()));
		UiLayouts.valueCell(row, txtWarningHours);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Escalation");
		chkEscalation = new org.adempiere.webui.component.Checkbox();
		chkEscalation.setLabel("Escalate when overdue");
		chkEscalation.setSelected(level != null && level.isEscalationEnabled());
		UiLayouts.valueCell(row, chkEscalation);

		UiLayouts.labelCell(row, "Escalate After Hours");
		txtEscalationHours = UiLayouts.smallText();
		txtEscalationHours.setValue(level == null || level.getEscalationAfterHours() == null ? ""
				: String.valueOf(level.getEscalationAfterHours()));
		UiLayouts.valueCell(row, txtEscalationHours);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		cellEscalationUser = UiLayouts.labelCell(row, "Escalate To");
		cmbEscalationUser = UiLayouts.comboBox();
		fill(cmbEscalationUser, activeUsers(client), true);
		UiLayouts.selectValue(cmbEscalationUser, Integer.valueOf(level == null ? 0 : level.getEscalationUserId()));
		UiLayouts.valueCell(row, cmbEscalationUser);

		cellEscalationRole = UiLayouts.labelCell(row, "Escalate To Role");
		cmbEscalationRole = UiLayouts.comboBox();
		fill(cmbEscalationRole, activeRoles(client), true);
		UiLayouts.selectValue(cmbEscalationRole, Integer.valueOf(level == null ? 0 : level.getEscalationRoleId()));
		UiLayouts.valueCell(row, cmbEscalationRole);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Description");
		txtDescription = UiLayouts.text();
		txtDescription.setValue(level == null ? "" : nullSafe(level.getDescription()));
		UiLayouts.wideValueCell(row, txtDescription, 3);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.hintCell(row, "A review, verification or noted level is advisory: it still has to be "
				+ "looked at, but it does not hold the document back. A rejection stops the chain whatever "
				+ "the level type is.");
		fields.rows.appendChild(row);

		editor().appendChild(fields.grid);
		applyVisibility();
	}

	/** Shows only the responsible fields that apply to the chosen type. */
	private void applyVisibility() {
		ResponsibleType type = responsibleType();
		boolean user = type == ResponsibleType.USER || type == ResponsibleType.DYNAMIC_USER;
		boolean role = type == ResponsibleType.ROLE || type == ResponsibleType.DYNAMIC_ROLE;
		boolean dynamic = type == ResponsibleType.DYNAMIC_USER || type == ResponsibleType.DYNAMIC_ROLE;

		show(cellUser, user);
		show(cmbUser, user);
		show(cellRole, role);
		show(cmbRole, role);
		show(cellApproverOrg, true);

		if (dynamic)
			hint("This responsible type is resolved when the document is known, so the approver is not "
					+ "known while configuring. The step is created unassigned and an administrator assigns "
					+ "it when the first document reaches this level.");
		else
			hint("");
	}

	private void show(Cell cell, boolean visible) {
		if (cell != null)
			cell.setVisible(visible);
	}

	private void show(Combobox combo, boolean visible) {
		if (combo != null)
			combo.setVisible(visible);
	}

	private ResponsibleType responsibleType() {
		Object value = UiLayouts.comboValue(cmbResponsibleType);
		ResponsibleType type = ResponsibleType.of(value == null ? null : value.toString());
		return type == null ? ResponsibleType.USER : type;
	}

	// ─────────────────────────────────────────────────────────────
	//  actions
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void createRow() {
		run("Adding a level", () -> {
			ApprovalLevel level = fromEditor();
			int id = window.levelService().addLevel(window.getSelectedVersionId(), level);
			window.reportStatus("Level \"" + level.getLevelName() + "\" added as level " + id + " of the chain.");
			refresh();
		});
	}

	@Override
	protected void updateRow(int id) {
		run("Updating the level", () -> {
			ApprovalLevel level = fromEditor();
			level.setId(id);
			window.levelService().updateLevel(level);
			window.reportStatus("Level \"" + level.getLevelName() + "\" updated.");
			refresh();
		});
	}

	@Override
	protected void deleteRow(int id) {
		run("Deleting the level", () -> {
			ApprovalLevel level = levelOf(id);
			window.levelService().deleteLevel(id);
			window.reportStatus("Level \"" + (level == null ? id : level.getLevelName())
					+ "\" was removed from the chain. Approvals that already ran keep their history.");
			refresh();
		});
	}

	@Override
	protected String firstActionLabel() {
		return "Move Up";
	}

	@Override
	protected void firstAction(int id) {
		run("Moving the level", () -> window.levelService().moveUp(id));
	}

	@Override
	protected String secondActionLabel() {
		return "Move Down";
	}

	@Override
	protected void secondAction(int id) {
		run("Moving the level", () -> window.levelService().moveDown(id));
	}

	/** Reads the editor into a level, after refusing the combinations that cannot work. */
	private ApprovalLevel fromEditor() throws Exception {
		ApprovalLevel level = new ApprovalLevel();
		level.setProcessVersionId(window.getSelectedVersionId());

		String name = textOf(txtName);
		if (name == null)
			throw new IllegalArgumentException("Level Name is required.");
		level.setLevelName(name);
		level.setApprovalStage(textOf(txtStage));

		ResponsibleType type = responsibleType();
		level.setResponsibleType(type);
		level.setUserId(type == ResponsibleType.USER ? UiLayouts.comboId(cmbUser) : 0);
		level.setRoleId(type == ResponsibleType.ROLE ? UiLayouts.comboId(cmbRole) : 0);
		level.setApproverOrgId(UiLayouts.comboId(cmbApproverOrg));

		Object approvalType = UiLayouts.comboValue(cmbApprovalType);
		ApprovalType typeOfApproval = ApprovalType.of(approvalType == null ? null : approvalType.toString());
		level.setApprovalType(typeOfApproval == null ? ApprovalType.APPROVE : typeOfApproval);
		level.setRequired(chkRequired.isSelected());

		if (type.isFixedAssignee() && type == ResponsibleType.USER && level.getUserId() <= 0)
			throw new IllegalArgumentException("Choose the approver of this level.");
		if (type == ResponsibleType.ROLE && level.getRoleId() <= 0)
			throw new IllegalArgumentException("Choose the role that approves this level.");

		level.setSlaHours(SlaFields.hoursOrNull(txtSlaHours, "SLA Hours"));
		level.setWarningBeforeHours(SlaFields.hoursOrNull(txtWarningHours, "Warn Before Hours"));
		level.setEscalationEnabled(chkEscalation.isSelected());
		level.setEscalationAfterHours(SlaFields.hoursOrNull(txtEscalationHours, "Escalate After Hours"));
		level.setEscalationUserId(UiLayouts.comboId(cmbEscalationUser));
		level.setEscalationRoleId(UiLayouts.comboId(cmbEscalationRole));
		level.setDescription(textOf(txtDescription));

		if (level.isEscalationEnabled() && level.getEscalationUserId() <= 0 && level.getEscalationRoleId() <= 0)
			throw new IllegalArgumentException("Escalation is switched on, so choose who the step is "
					+ "escalated to. Escalating to nobody would only look as if nothing happened.");

		if (level.getSlaHours() != null && level.getWarningBeforeHours() != null
				&& level.getWarningBeforeHours().intValue() > level.getSlaHours().intValue())
			hint("Warn Before Hours is larger than SLA Hours, so this level is in warning from the start.");

		return level;
	}

	private int clientId() {
		return org.compiere.util.Env.getAD_Client_ID(window.context());
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
