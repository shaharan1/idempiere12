package org.mycompany.requisition.approval.ui;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.webui.component.Datebox;
import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.Delegation;
import org.mycompany.requisition.approval.service.DelegationService;
import org.zkoss.zul.Combobox;

/**
 * Section 08, the approver delegations.
 * <p>
 * A delegation substitutes one person for another, optionally limited to a date
 * range and to one level. The engine applies it when a chain is created, so the
 * level keeps its responsibility and the history still shows who was really
 * supposed to approve.
 *
 * <h3>Why this list is not tied to the selected version</h3>
 * Delegation is tenant wide and the level scope is optional, so switching to
 * another process must not hide a delegation somebody is relying on. That is why
 * this panel stays populated when no version is selected, and why its level picker
 * offers "all levels" rather than pretending a delegation belongs to one process.
 */
public class DelegationPanel extends ConfigListPanel {
	private static final long serialVersionUID = 1L;

	/** Value of the level column when a delegation covers every level. */
	private static final int ALL_LEVELS = 0;

	private final WProcessApprovalWindow window;
	private List<Delegation> delegations = new ArrayList<Delegation>();
	private List<ApprovalLevel> levels = new ArrayList<ApprovalLevel>();
	private DelegationService service;

	private Combobox cmbUser;
	private Combobox cmbDelegate;
	private Combobox cmbLevel;
	private Datebox dtValidFrom;
	private Datebox dtValidTo;
	private org.adempiere.webui.component.Textbox txtReason;

	public DelegationPanel(WProcessApprovalWindow window) {
		super(window, "No delegations. Everyone approves their own levels.");
		this.window = window;
	}

	/**
	 * The service is opened lazily.
	 * <p>
	 * Delegation lives on the tenant, not on a version, so this panel has to work
	 * even when no version is selected. Failing to open the service would take the
	 * whole window down for a section the user did not ask about.
	 */
	private DelegationService serviceOrNull() {
		if (service == null) {
			try {
				service = new DelegationService();
			} catch (Exception e) {
				window.reportError("Delegations are not available: " + e.getMessage());
			}
		}
		return service;
	}

	/**
	 * Delegations do not belong to a version, so the list stays populated.
	 * <p>
	 * Overrides the base behaviour, which hides the list until a version is chosen.
	 */
	@Override
	protected boolean isVersionSelected() {
		return true;
	}

	// ─────────────────────────────────────────────────────────────
	//  data
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void reload() {
		delegations = new ArrayList<Delegation>();
		levels = new ArrayList<ApprovalLevel>();
		DelegationService svc = serviceOrNull();
		if (svc != null)
			delegations.addAll(svc.listAll());
		if (window.getSelectedVersionId() > 0)
			levels.addAll(window.levelService().listLevels(window.getSelectedVersionId()));
	}

	@Override
	protected List<Integer> ids() {
		List<Integer> result = new ArrayList<Integer>();
		for (Delegation d : delegations)
			result.add(Integer.valueOf(d.getId()));
		return result;
	}

	@Override
	protected String labelOf(int index) {
		Delegation d = delegations.get(index);
		StringBuilder sb = new StringBuilder();
		sb.append(userName(d.getUserId())).append(" to ").append(userName(d.getDelegateUserId()));
		sb.append("  [");
		sb.append(d.getLevelId() > 0 ? levelName(d.getLevelId()) : "all levels");
		sb.append(", ");
		sb.append(d.getValidFrom() == null ? "always" : d.getValidFrom().toString());
		sb.append(" to ");
		sb.append(d.getValidTo() == null ? "always" : d.getValidTo().toString());
		sb.append(']');
		boolean currentlyValid = d.isValidAt(new Timestamp(System.currentTimeMillis()));
		sb.append(currentlyValid ? "  in force" : "  not in force");
		return sb.toString();
	}

	private Delegation delegationOf(int id) {
		for (Delegation d : delegations)
			if (d.getId() == id)
				return d;
		return null;
	}

	// ─────────────────────────────────────────────────────────────
	//  editor
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void buildEditor() {
		Delegation d = editingId() > 0 ? delegationOf(editingId()) : null;
		UiLayouts.FieldGrid fields = UiLayouts.grid(4);
		int client = clientId();

		org.adempiere.webui.component.Row row = UiLayouts.row();
		UiLayouts.labelCell(row, "Approver");
		cmbUser = UiLayouts.comboBox();
		fill(cmbUser, activeUsers(client), true);
		UiLayouts.selectValue(cmbUser, Integer.valueOf(d == null ? 0 : d.getUserId()));
		UiLayouts.valueCell(row, cmbUser);

		UiLayouts.labelCell(row, "Delegated To");
		cmbDelegate = UiLayouts.comboBox();
		fill(cmbDelegate, activeUsers(client), true);
		UiLayouts.selectValue(cmbDelegate, Integer.valueOf(d == null ? 0 : d.getDelegateUserId()));
		UiLayouts.valueCell(row, cmbDelegate);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Only For Level");
		cmbLevel = UiLayouts.comboBox();
		UiLayouts.combo(cmbLevel, "all levels", Integer.valueOf(ALL_LEVELS), true);
		for (ApprovalLevel level : levels)
			UiLayouts.combo(cmbLevel, level.getLevelNo() + ". " + level.getDisplayName(),
					Integer.valueOf(level.getId()), false);
		UiLayouts.selectValue(cmbLevel, Integer.valueOf(d == null ? ALL_LEVELS : d.getLevelId()));
		UiLayouts.valueCell(row, cmbLevel);

		UiLayouts.labelCell(row, "Reason");
		txtReason = UiLayouts.text();
		txtReason.setValue(d == null ? "" : nullSafe(d.getReason()));
		UiLayouts.valueCell(row, txtReason);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Valid From");
		dtValidFrom = new Datebox();
		dtValidFrom.setWidth("180px");
		dtValidFrom.setValue(d == null ? null : d.getValidFrom());
		UiLayouts.valueCell(row, dtValidFrom);

		UiLayouts.labelCell(row, "Valid To");
		dtValidTo = new Datebox();
		dtValidTo.setWidth("180px");
		dtValidTo.setValue(d == null ? null : d.getValidTo());
		UiLayouts.valueCell(row, dtValidTo);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.hintCell(row, "Leave both dates empty for an open ended delegation, which is what a "
				+ "long absence needs. A delegation is only read when a chain is created, so it applies "
				+ "to documents submitted while it is in force.");
		fields.rows.appendChild(row);

		editor().appendChild(fields.grid);
		if (levels.isEmpty())
			hint("No levels of the selected version, so this delegation can only cover all levels.");
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
		run("Adding a delegation", () -> {
			DelegationService svc = requireService();
			Delegation d = fromEditor();
			svc.create(d);
			window.reportStatus("Delegation from " + userName(d.getUserId()) + " to "
					+ userName(d.getDelegateUserId()) + " saved.");
			refresh();
		});
	}

	@Override
	protected void updateRow(int id) {
		run("Saving the delegation", () -> {
			DelegationService svc = requireService();
			Delegation d = fromEditor();
			d.setId(id);
			svc.update(d);
			window.reportStatus("Delegation updated.");
			refresh();
		});
	}

	@Override
	protected void deleteRow(int id) {
		run("Ending the delegation", () -> {
			DelegationService svc = requireService();
			Delegation d = delegationOf(id);
			svc.delete(id);
			window.reportStatus(d == null ? "Delegation ended."
					: "Delegation from " + userName(d.getUserId()) + " ended. Approvals that already ran "
							+ "keep their history.");
			refresh();
		});
	}

	private Delegation fromEditor() {
		Delegation d = new Delegation();
		d.setUserId(UiLayouts.comboId(cmbUser));
		d.setDelegateUserId(UiLayouts.comboId(cmbDelegate));
		d.setLevelId(UiLayouts.comboId(cmbLevel));
		d.setValidFrom(UiLayouts.dateOf(dtValidFrom));
		d.setValidTo(UiLayouts.dateOf(dtValidTo));
		d.setReason(textOf(txtReason));
		return d;
	}

	private DelegationService requireService() throws Exception {
		DelegationService svc = serviceOrNull();
		if (svc == null)
			throw new IllegalStateException("The delegation table is not available.");
		return svc;
	}

	private int clientId() {
		return org.compiere.util.Env.getAD_Client_ID(window.context());
	}

	private String userName(int userId) {
		if (userId <= 0)
			return "?";
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_User WHERE AD_User_ID=?", Integer.valueOf(userId));
		return name == null ? ("user " + userId) : name;
	}

	private String levelName(int levelId) {
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT LevelName FROM XX_PROCESS_LEVEL WHERE XX_PROCESS_LEVEL_ID=?", Integer.valueOf(levelId));
		return name == null ? String.valueOf(levelId) : name;
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
