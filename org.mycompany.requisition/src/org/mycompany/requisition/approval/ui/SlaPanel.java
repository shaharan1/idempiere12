package org.mycompany.requisition.approval.ui;

import java.util.ArrayList;
import java.util.List;

import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.zkoss.zul.Combobox;

/**
 * Section 07, the service level and escalation of the whole chain.
 * <p>
 * The SLA of a level is stored on the level, so section 04 could have carried it
 * and does. This section exists because SLA is read across the chain, not level by
 * level: an approver wants to see in one place where the time pressure is and who
 * an overdue step goes to. Editing here writes the same level row through the same
 * service, so there is only ever one value for a level's SLA.
 *
 * <p>
 * There is no Delete here on purpose. Removing a level is section 04's job, and a
 * second delete button that did something different would be a trap.
 */
public class SlaPanel extends ConfigListPanel {
	private static final long serialVersionUID = 1L;

	private final WProcessApprovalWindow window;
	private List<ApprovalLevel> levels = new ArrayList<ApprovalLevel>();

	private org.adempiere.webui.component.Textbox txtSlaHours;
	private org.adempiere.webui.component.Textbox txtWarningHours;
	private org.adempiere.webui.component.Checkbox chkEscalation;
	private org.adempiere.webui.component.Textbox txtEscalationHours;
	private Combobox cmbEscalationUser;
	private Combobox cmbEscalationRole;
	private org.adempiere.webui.component.Textbox txtSlaComment;

	public SlaPanel(WProcessApprovalWindow window) {
		super(window, "No levels yet. Define the approval levels in section 04 first.");
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
		sb.append("   SLA ");
		sb.append(level.getSlaHours() == null ? "not set" : level.getSlaHours() + "h");
		if (level.getWarningBeforeHours() != null)
			sb.append(", warning ").append(level.getWarningBeforeHours()).append("h before");
		if (level.isEscalationEnabled())
			sb.append(", escalates after ").append(level.getEscalationAfterHours()).append("h to ")
					.append(escalationTarget(level));
		else
			sb.append(", no escalation");
		return sb.toString();
	}

	private String escalationTarget(ApprovalLevel level) {
		if (level.getEscalationUserId() > 0)
			return userName(level.getEscalationUserId());
		if (level.getEscalationRoleId() > 0)
			return "role " + roleName(level.getEscalationRoleId());
		return "nobody - escalation is on but no target is set";
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
		UiLayouts.labelCell(row, "Level");
		org.adempiere.webui.component.Textbox txtLevel = UiLayouts.text();
		txtLevel.setReadonly(true);
		txtLevel.setValue(level == null ? "" : level.getLevelNo() + ". " + level.getDisplayName());
		UiLayouts.valueCell(row, txtLevel);

		UiLayouts.labelCell(row, "SLA Comment");
		txtSlaComment = UiLayouts.text();
		txtSlaComment.setReadonly(true);
		txtSlaComment.setValue(level == null ? "" : nullSafe(level.getDescription()));
		UiLayouts.valueCell(row, txtSlaComment);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "SLA Hours");
		txtSlaHours = UiLayouts.smallText();
		txtSlaHours.setValue(level == null || level.getSlaHours() == null ? ""
				: String.valueOf(level.getSlaHours()));
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
		chkEscalation.setLabel("Escalate when the step is overdue");
		chkEscalation.setSelected(level != null && level.isEscalationEnabled());
		chkEscalation.addEventListener(org.zkoss.zk.ui.event.Events.ON_CHANGE, e -> describeEscalation());
		UiLayouts.valueCell(row, chkEscalation);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Escalate After Hours");
		txtEscalationHours = UiLayouts.smallText();
		txtEscalationHours.setValue(level == null || level.getEscalationAfterHours() == null ? ""
				: String.valueOf(level.getEscalationAfterHours()));
		UiLayouts.valueCell(row, txtEscalationHours);

		UiLayouts.labelCell(row, "Escalate To");
		org.zkoss.zul.Hlayout targets = new org.zkoss.zul.Hlayout();
		targets.setSpacing("6px");
		cmbEscalationUser = UiLayouts.comboBox();
		fill(cmbEscalationUser, activeUsers(client), true);
		UiLayouts.selectValue(cmbEscalationUser, Integer.valueOf(level == null ? 0 : level.getEscalationUserId()));
		targets.appendChild(cmbEscalationUser);

		cmbEscalationRole = UiLayouts.comboBox();
		fill(cmbEscalationRole, activeRoles(client), true);
		UiLayouts.selectValue(cmbEscalationRole, Integer.valueOf(level == null ? 0 : level.getEscalationRoleId()));
		targets.appendChild(cmbEscalationRole);
		UiLayouts.valueCell(row, targets);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.hintCell(row, "The countdown starts when the level opens, not when the document was "
				+ "submitted, and an escalation is sent once. The SLA state is always recomputed from the "
				+ "due date, so it cannot go stale.");
		fields.rows.appendChild(row);

		editor().appendChild(fields.grid);
		describeEscalation();
	}

	/** Says what the escalation will actually do, before it is saved. */
	private void describeEscalation() {
		if (!chkEscalation.isSelected()) {
			hint("");
			return;
		}
		Integer hours = integerOf(txtEscalationHours);
		if (hours == null)
			hint("Escalation is on but the escalation time is empty, so it would never trigger.");
		else if (UiLayouts.comboId(cmbEscalationUser) <= 0 && UiLayouts.comboId(cmbEscalationRole) <= 0)
			hint("Escalation is on but nobody is chosen, so it would only look as if nothing happened.");
		else if (UiLayouts.comboId(cmbEscalationUser) > 0 && UiLayouts.comboId(cmbEscalationRole) > 0)
			hint("Both a user and a role are chosen. The user is used and the role is ignored.");
		else
			hint("");
	}

	// ─────────────────────────────────────────────────────────────
	//  actions
	// ─────────────────────────────────────────────────────────────

	@Override
	protected String firstActionLabel() {
		return null;
	}

	@Override
	protected String secondActionLabel() {
		return null;
	}

	@Override
	protected void createRow() {
		run("Adding an SLA", () -> {
			throw new IllegalStateException("An SLA belongs to an approval level. Add the level in "
					+ "section 04 and set its SLA here.");
		});
	}

	@Override
	protected void updateRow(int id) {
		run("Saving the SLA", () -> {
			ApprovalLevel level = levelOf(id);
			if (level == null)
				throw new IllegalStateException("Level " + id + " is no longer part of this version.");
			applyTo(level);
			window.levelService().updateLevel(level);
			window.reportStatus("SLA of level " + level.getLevelNo() + " saved.");
			refresh();
		});
	}

	@Override
	protected void deleteRow(int id) {
		run("Removing the SLA", () -> {
			ApprovalLevel level = levelOf(id);
			if (level == null)
				return;
			level.setSlaHours(null);
			level.setWarningBeforeHours(null);
			level.setEscalationEnabled(false);
			level.setEscalationAfterHours(null);
			level.setEscalationUserId(0);
			level.setEscalationRoleId(0);
			window.levelService().updateLevel(level);
			window.reportStatus("SLA of level " + level.getLevelNo() + " cleared. The level itself stays.");
			refresh();
		});
	}

	/** Copies the editor into the level, checking only what belongs to the SLA. */
	private void applyTo(ApprovalLevel level) throws Exception {
		level.setSlaHours(SlaFields.hoursOrNull(txtSlaHours, "SLA Hours"));
		level.setWarningBeforeHours(SlaFields.hoursOrNull(txtWarningHours, "Warn Before Hours"));
		level.setEscalationEnabled(chkEscalation.isSelected());
		level.setEscalationAfterHours(SlaFields.hoursOrNull(txtEscalationHours, "Escalate After Hours"));
		level.setEscalationUserId(UiLayouts.comboId(cmbEscalationUser));
		level.setEscalationRoleId(UiLayouts.comboId(cmbEscalationRole));

		if (level.getSlaHours() != null && level.getWarningBeforeHours() != null
				&& level.getWarningBeforeHours().intValue() > level.getSlaHours().intValue())
			throw new IllegalArgumentException("Warn Before Hours is larger than SLA Hours, so this level "
					+ "would be in warning from the moment it opens.");
		if (level.isEscalationEnabled()) {
			if (level.getEscalationAfterHours() == null)
				throw new IllegalArgumentException("Escalation is on, so the escalation time is required.");
			if (level.getEscalationUserId() <= 0 && level.getEscalationRoleId() <= 0)
				throw new IllegalArgumentException("Escalation is on, so choose who the step is escalated to.");
		}
	}

	private int clientId() {
		return org.compiere.util.Env.getAD_Client_ID(window.context());
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

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
