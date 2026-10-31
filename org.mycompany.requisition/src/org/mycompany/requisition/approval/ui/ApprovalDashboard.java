package org.mycompany.requisition.approval.ui;

import java.util.ArrayList;
import java.util.List;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.ListItem;
import org.adempiere.webui.component.Listbox;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.panel.ADForm;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.mycompany.requisition.approval.model.ApprovalAction;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.model.SlaState;
import org.mycompany.requisition.approval.service.ApprovalEngineClient;
import org.mycompany.requisition.approval.service.SlaMonitor;
import org.mycompany.requisition.approval.service.WorkflowEngine;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Cell;
import org.zkoss.zul.Center;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.South;
import org.zkoss.zul.Vlayout;

/**
 * The approval dashboard.
 * <p>
 * The screen somebody opens first in the morning, and therefore the one that has to
 * answer its questions without being read carefully:
 *
 * <ol>
 * <li>what is waiting for me</li>
 * <li>what is late, whoever it is waiting for</li>
 * <li>what is waiting for nobody, which is always a configuration mistake</li>
 * </ol>
 *
 * <p>
 * The counters at the top are the same three answers in one line, so a manager can
 * see the state of the whole tenant without scrolling.
 *
 * <h3>Nothing is decided here that the engine would not decide</h3>
 * Approving from the dashboard calls the same {@link WorkflowEngine#act} the
 * configuration window calls. The dashboard only makes it convenient; it does not
 * have a second opinion about whether the user may act, and it does not mark
 * anything itself.
 */
public class ApprovalDashboard extends ADForm implements EventListener<Event> {
	private static final long serialVersionUID = 1L;

	private static final CLogger log = CLogger.getCLogger(ApprovalDashboard.class);

	/** AD_Preference attribute the filter is remembered under. */
	private static final String FILTER_ATTRIBUTE = "ApprovalDashboardFilter";

	private Label lblMyPending;
	private Label lblMyOverdue;
	private Label lblRunning;
	private Label lblOverdue;
	private Label lblUnassigned;

	private Listbox lstMine;
	private Listbox lstOverdue;
	private Listbox lstUnassigned;
	private Textbox txtComment;

	private Button btnApprove;
	private Button btnReject;
	private Button btnRefresh;

	private Combobox cmbProcess;
	private org.adempiere.webui.component.Checkbox chkOnlyOverdue;
	private org.adempiere.webui.component.Checkbox chkRemember;

	private Label lblStatus;

	private List<ApprovalTransaction> mine = new ArrayList<ApprovalTransaction>();
	private List<ApprovalTransaction> overdue = new ArrayList<ApprovalTransaction>();
	private List<ApprovalStep> unassigned = new ArrayList<ApprovalStep>();

	@Override
	protected void initForm() {
		Borderlayout layout = new Borderlayout();
		layout.setWidth("100%");
		layout.setHeight("100%");
		appendChild(layout);

		layout.appendChild(buildHeader());
		layout.appendChild(buildBody());
		layout.appendChild(buildFooter());

		loadFilter();
		reload();
	}

	// ─────────────────────────────────────────────────────────────
	//  header
	// ─────────────────────────────────────────────────────────────

	private North buildHeader() {
		North north = new North();
		Vlayout header = new Vlayout();
		header.setSpacing("4px");
		header.setStyle("border: none; background: #f9f9f9; padding: 8px 12px;");

		Label title = new Label("Approval Dashboard");
		title.setStyle("font-size: 15px; font-weight: bold;");
		header.appendChild(title);

		UiLayouts.FieldGrid counters = UiLayouts.grid(4);

		org.adempiere.webui.component.Row row = UiLayouts.row();
		lblMyPending = counter(row, "Waiting for me");
		lblMyOverdue = counter(row, "Of those, late");
		counters(counters, row);

		row = UiLayouts.row();
		lblRunning = counter(row, "Running in this tenant");
		lblOverdue = counter(row, "Late in this tenant");
		counters(counters, row);

		row = UiLayouts.row();
		lblUnassigned = counter(row, "Waiting for no one");
		UiLayouts.labelCell(row, "");
		UiLayouts.valueCell(row, new Label(""));
		counters(counters, row);

		org.adempiere.webui.component.Row hint = UiLayouts.row();
		UiLayouts.hintCell(hint, "\"Waiting for no one\" means a step was created without an approver. "
				+ "That can only come from a level with a dynamic responsible type, and it needs an "
				+ "administrator to assign it in the configuration window.");
		counters.rows.appendChild(hint);
		header.appendChild(counters.grid);

		Hlayout actions = UiLayouts.toolbar();
		btnRefresh = UiLayouts.button("Refresh");
		btnRefresh.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnRefresh);
		header.appendChild(actions);

		north.appendChild(header);
		return north;
	}

	/**
	 * One caption and one big number on the same row.
	 * <p>
	 * Both cells are built before the row is attached to the grid. Attaching a row
	 * and then adding a cell to it leaves ZK with a row it has already laid out,
	 * which shows up as columns that do not line up rather than as an error.
	 */
	private Label counter(org.adempiere.webui.component.Row row, String caption) {
		Label value = new Label("-");
		value.setStyle("font-size: 20px; font-weight: bold; color: #1565c0;");
		UiLayouts.labelCell(row, caption);
		UiLayouts.valueCell(row, value);
		return value;
	}

	/** Adds a finished counter row to the counter grid. */
	private void counters(UiLayouts.FieldGrid fields, org.adempiere.webui.component.Row row) {
		fields.rows.appendChild(row);
	}

	// ─────────────────────────────────────────────────────────────
	//  body
	// ─────────────────────────────────────────────────────────────

	private Center buildBody() {
		Center center = new Center();
		Vlayout sections = new Vlayout();
		sections.setSpacing("2px");

		Groupbox mineBox = UiLayouts.frame("1. Waiting for me", true, sections);
		mineBox.appendChild(buildFilterRow());
		lstMine = list(8);
		lstMine.addEventListener(Events.ON_SELECT, this);
		mineBox.appendChild(lstMine);
		mineBox.appendChild(buildActionRow());

		lstOverdue = list(6);
		UiLayouts.frame("2. Late in this tenant", true, sections).appendChild(lstOverdue);

		lstUnassigned = list(4);
		UiLayouts.frame("3. Waiting for no one", true, sections).appendChild(lstUnassigned);

		center.appendChild(UiLayouts.scrollAreaAnd(sections));
		return center;
	}

	private Listbox list(int rows) {
		Listbox box = new Listbox();
		box.setWidth("100%");
		box.setRows(rows);
		return box;
	}

	/** The filter, in the same section as the list it filters. */
	private Hlayout buildFilterRow() {
		Hlayout row = UiLayouts.toolbar();
		row.appendChild(new Label("Process:"));
		cmbProcess = UiLayouts.comboBox();
		cmbProcess.setWidth("260px");
		cmbProcess.addEventListener(Events.ON_SELECT, this);
		row.appendChild(cmbProcess);

		chkOnlyOverdue = new org.adempiere.webui.component.Checkbox();
		chkOnlyOverdue.setLabel("Only late ones");
		chkOnlyOverdue.addEventListener(Events.ON_CHECK, this);
		row.appendChild(chkOnlyOverdue);

		chkRemember = new org.adempiere.webui.component.Checkbox();
		chkRemember.setLabel("Remember this filter");
		row.appendChild(chkRemember);
		return row;
	}

	private Hlayout buildActionRow() {
		Hlayout row = UiLayouts.toolbar();
		btnApprove = UiLayouts.button("Approve");
		btnApprove.addEventListener(Events.ON_CLICK, this);
		row.appendChild(btnApprove);

		btnReject = UiLayouts.button("Reject");
		btnReject.addEventListener(Events.ON_CLICK, this);
		row.appendChild(btnReject);

		row.appendChild(new Label("Comment (required to reject):"));
		txtComment = new Textbox();
		txtComment.setWidth("420px");
		row.appendChild(txtComment);
		return row;
	}

	private South buildFooter() {
		South south = new South();
		Vlayout footer = new Vlayout();
		footer.setStyle("border: none; padding: 6px 12px;");
		lblStatus = new Label("Ready.");
		lblStatus.setStyle("color: #1f6f3f;");
		footer.appendChild(lblStatus);
		south.appendChild(footer);
		return south;
	}

	// ─────────────────────────────────────────────────────────────
	//  loading
	// ─────────────────────────────────────────────────────────────

	private void reload() {
		fillProcesses();
		reloadMine();
		reloadOverdue();
		reloadUnassigned();
		updateCounters();
		updateButtons();
	}

	private void fillProcesses() {
		int previous = UiLayouts.comboId(cmbProcess);
		UiLayouts.clearItems(cmbProcess);
		UiLayouts.combo(cmbProcess, "all processes", Integer.valueOf(0), true);
		try {
			for (org.mycompany.requisition.approval.config.ProcessDefinition p
					: new org.mycompany.requisition.approval.service.ProcessConfigService().listProcesses(0))
				UiLayouts.combo(cmbProcess, p.getDisplayName(), Integer.valueOf(p.getId()), false);
		} catch (Exception e) {
			log.log(java.util.logging.Level.WARNING, "The process list could not be read", e);
		}
		UiLayouts.selectValue(cmbProcess, Integer.valueOf(previous));
	}

	private void reloadMine() {
		mine = new ArrayList<ApprovalTransaction>();
		try {
			mine.addAll(new ApprovalEngineClient().pendingForMe());
		} catch (RuntimeException e) {
			log.log(java.util.logging.Level.WARNING, "The pending approvals could not be read", e);
		}
		int processId = UiLayouts.comboId(cmbProcess);
		boolean onlyOverdue = chkOnlyOverdue.isSelected();
		List<ApprovalTransaction> shown = new ArrayList<ApprovalTransaction>();
		for (ApprovalTransaction chain : mine)
			if (matchesProcess(chain, processId) && (!onlyOverdue || isLate(chain)))
				shown.add(chain);

		UiLayouts.clear(lstMine);
		for (ApprovalTransaction chain : shown)
			lstMine.appendItem(rowOf(chain, true), Integer.valueOf(chain.getId()));
		if (shown.isEmpty()) {
			Label empty = new Label("Nothing is waiting for you"
					+ (onlyOverdue ? " that is late" : "") + ".");
			empty.setStyle("color: #555555; padding: 4px;");
			lstMine.appendChild(labelAsItem(empty));
		}
	}

	/**
	 * A label that looks like an empty list.
	 * <p>
	 * An empty list box with nothing in it reads as a form that failed to load, so
	 * the "nothing here" message is shown inside the list instead of beside it.
	 */
	private ListItem labelAsItem(Label label) {
		ListItem item = new ListItem();
		item.setStyle("white-space: normal;");
		org.zkoss.zul.Listcell cell = new org.zkoss.zul.Listcell();
		cell.appendChild(label);
		item.appendChild(cell);
		return item;
	}

	private void reloadOverdue() {
		overdue = new ArrayList<ApprovalTransaction>();
		List<ApprovalStep> late = new ArrayList<ApprovalStep>();
		try {
			late.addAll(new SlaMonitor().listOverdue());
		} catch (Exception e) {
			log.log(java.util.logging.Level.WARNING, "The overdue list could not be read", e);
		}
		int processId = UiLayouts.comboId(cmbProcess);
		for (ApprovalStep step : late) {
			ApprovalTransaction chain = findTransaction(step.getTransactionId());
			if (chain != null && matchesProcess(chain, processId) && !overdue.contains(chain))
				overdue.add(chain);
		}

		UiLayouts.clear(lstOverdue);
		for (ApprovalTransaction chain : overdue)
			lstOverdue.appendItem(rowOf(chain, false), Integer.valueOf(chain.getId()));
		if (overdue.isEmpty()) {
			Label empty = new Label("Nothing is late in this tenant.");
			empty.setStyle("color: #555555; padding: 4px;");
			lstOverdue.appendChild(labelAsItem(empty));
		}
	}

	private void reloadUnassigned() {
		unassigned = new ArrayList<ApprovalStep>();
		try {
			unassigned.addAll(new SlaMonitor().listUnassigned());
		} catch (Exception e) {
			log.log(java.util.logging.Level.WARNING, "The unassigned steps could not be read", e);
		}

		UiLayouts.clear(lstUnassigned);
		for (ApprovalStep step : unassigned) {
			ApprovalTransaction chain = findTransaction(step.getTransactionId());
			String document = chain == null ? ("transaction " + step.getTransactionId()) : chain.getDisplayName();
			lstUnassigned.appendItem("Level " + step.getLevelNo() + " of " + document
					+ " has no approver. Assign it in the configuration window.", Integer.valueOf(step.getId()));
		}
		if (unassigned.isEmpty()) {
			Label empty = new Label("Every pending step has an approver.");
			empty.setStyle("color: #555555; padding: 4px;");
			lstUnassigned.appendChild(labelAsItem(empty));
		}
	}

	private ApprovalTransaction findTransaction(int transactionId) {
		if (transactionId <= 0)
			return null;
		WorkflowEngine engine = engineOrNull();
		return engine == null ? null : engine.loadById(transactionId);
	}

	private WorkflowEngine engineOrNull() {
		try {
			return new WorkflowEngine();
		} catch (Exception e) {
			log.log(java.util.logging.Level.WARNING, "The approval engine is not available", e);
			return null;
		}
	}

	private void updateCounters() {
		int myOverdue = 0;
		for (ApprovalTransaction chain : mine)
			if (isLate(chain))
				myOverdue++;
		lblMyPending.setValue(String.valueOf(mine.size()));
		lblMyOverdue.setValue(String.valueOf(myOverdue));
		lblMyOverdue.setStyle("font-size: 20px; font-weight: bold; color: "
				+ (myOverdue > 0 ? "#e07b00;" : "#666666;"));
		lblRunning.setValue(String.valueOf(countRunning()));
		lblOverdue.setValue(String.valueOf(overdue.size()));
		lblOverdue.setStyle("font-size: 20px; font-weight: bold; color: "
				+ (overdue.isEmpty() ? "#2e7d32;" : "#e07b00;"));
		lblUnassigned.setValue(String.valueOf(unassigned.size()));
		lblUnassigned.setStyle("font-size: 20px; font-weight: bold; color: "
				+ (unassigned.isEmpty() ? "#2e7d32;" : "#b00020;"));
	}

	/** Approvals still running in the tenant, whoever they are waiting for. */
	private int countRunning() {
		return DB.getSQLValue(null,
				"SELECT COUNT(*) FROM XX_PROCESS_TRANSACTION WHERE IsActive='Y' AND AD_Client_ID=?"
						+ " AND Status IN ('PENDING','CREATED')",
				Integer.valueOf(Env.getAD_Client_ID(Env.getCtx())));
	}

	private boolean matchesProcess(ApprovalTransaction chain, int processId) {
		return processId <= 0 || chain.getProcessId() == processId;
	}

	private boolean isLate(ApprovalTransaction chain) {
		WorkflowEngine engine = engineOrNull();
		if (engine == null)
			return false;
		try {
			// asking the monitor is what keeps the counter and the list in step
			return !new SlaMonitor().needingAttention(chain.getId()).isEmpty();
		} catch (Exception e) {
			for (ApprovalStep step : chain.getSteps())
				if (step.getStatus().isOpen() && step.getSlaState() != null
						&& step.getSlaState() != SlaState.NONE
						&& step.getSlaState().needsAttention())
					return true;
			return false;
		}
	}

	private String rowOf(ApprovalTransaction chain, boolean withLevel) {
		StringBuilder sb = new StringBuilder();
		sb.append(chain.getDisplayName());
		sb.append("  [").append(chain.getStatus().getLabel());
		if (withLevel && chain.getCurrentLevel() > 0)
			sb.append(", level ").append(chain.getCurrentLevel());
		if (chain.getAmount() != null)
			sb.append(", ").append(chain.getAmount().toPlainString());
		sb.append(", requested by ").append(userName(chain.getRequestedBy()));
		List<ApprovalStep> open = chain.getOpenSteps();
		if (!open.isEmpty()) {
			sb.append(" - waiting on ");
			List<String> who = new ArrayList<String>();
			for (ApprovalStep step : open)
				who.add(step.getApproverUserId() > 0 ? userName(step.getApproverUserId())
						: step.getApproverRoleId() > 0 ? "role " + roleName(step.getApproverRoleId())
								: "nobody");
			for (int i = 0; i < who.size(); i++) {
				if (i > 0)
					sb.append(", ");
				sb.append(who.get(i));
			}
		}
		List<ApprovalStep> late = lateSteps(chain);
		if (!late.isEmpty())
			sb.append("  -  LATE since ").append(late.get(0).getDueDate());
		sb.append(']');
		return sb.toString();
	}

	private List<ApprovalStep> lateSteps(ApprovalTransaction chain) {
		List<ApprovalStep> result = new ArrayList<ApprovalStep>();
		try {
			result.addAll(new SlaMonitor().needingAttention(chain.getId()));
		} catch (Exception e) {
			// the row is still worth showing, only without the late marker
			return new ArrayList<ApprovalStep>();
		}
		return result;
	}

	// ─────────────────────────────────────────────────────────────
	//  filter memory
	// ─────────────────────────────────────────────────────────────

	/**
	 * The filter is remembered per user through {@code AD_PPreference}.
	 * <p>
	 * Stored as plain values rather than as a query object, because the only three
	 * things worth remembering are the process, whether to hide the healthy ones,
	 * and whether to keep remembering at all.
	 */
	private void loadFilter() {
		String value = preferenceValue();
		if (value == null || value.trim().length() == 0)
			return;
		String[] parts = value.split("\\|");
		try {
			if (parts.length > 0)
				UiLayouts.selectValue(cmbProcess, Integer.valueOf(parts[0].trim()));
			if (parts.length > 1)
				chkOnlyOverdue.setSelected("Y".equalsIgnoreCase(parts[1].trim()));
			if (parts.length > 2)
				chkRemember.setSelected("Y".equalsIgnoreCase(parts[2].trim()));
		} catch (NumberFormatException e) {
			status("The remembered filter could not be read, so the default is used.");
		}
	}

	/**
	 * Stores the filter as an {@code AD_Preference} row of this user.
	 * <p>
	 * Written through the iDempiere model rather than with hand written SQL, for the
	 * same reason the rest of this framework writes through {@code PO}: the audit
	 * columns and the tenant rules are then filled the way iDempiere fills them,
	 * not the way this class guesses. Failing to remember the filter is only worth
	 * a log line; the dashboard works either way.
	 */
	private void saveFilter() {
		if (!chkRemember.isSelected())
			return;

		String value = UiLayouts.comboId(cmbProcess) + "|" + (chkOnlyOverdue.isSelected() ? "Y" : "N") + "|Y";
		String trxName = Trx.createTrxName("ApprDashFilter");
		try {
			PO po = findPreference(trxName);
			if (po == null) {
				po = org.compiere.model.MTable.get(Env.getCtx(), "AD_Preference").getPO(0, trxName);
				if (po == null)
					throw new IllegalStateException("Table AD_Preference is not registered.");
				po.set_ValueOfColumnReturningBoolean("AD_Client_ID",
						Integer.valueOf(Env.getAD_Client_ID(Env.getCtx())));
				po.set_ValueOfColumnReturningBoolean("AD_Org_ID",
						Integer.valueOf(Env.getAD_Org_ID(Env.getCtx())));
			}
			po.set_ValueOfColumnReturningBoolean("AD_User_ID", Integer.valueOf(Env.getAD_User_ID(Env.getCtx())));
			po.set_ValueOfColumnReturningBoolean("Attribute", FILTER_ATTRIBUTE);
			po.set_ValueOfColumnReturningBoolean("Value", value);
			// PreferenceFor is mandatory in the database; 'W' is what iDempiere uses
			// for a preference that belongs to no particular window or process
			po.set_ValueOfColumnReturningBoolean("PreferenceFor", "W");
			if (!po.save(trxName))
				throw new IllegalStateException("The preference row was not stored.");
			Trx.get(trxName, false).commit();
			Trx.get(trxName, false).close();
		} catch (RuntimeException e) {
			log.log(java.util.logging.Level.WARNING, "The dashboard filter could not be stored", e);
			try {
				Trx.get(trxName, false).rollback();
				Trx.get(trxName, false).close();
			} catch (RuntimeException ignored) {
				// nothing more can be done about a failed rollback
			}
		}
	}

	private PO findPreference(String trxName) {
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement("SELECT AD_Preference_ID FROM AD_Preference WHERE AD_User_ID=?"
					+ " AND AD_Client_ID=? AND Attribute=? AND AD_Window_ID IS NULL ORDER BY Updated DESC"
					+ " LIMIT 1", trxName);
			pstmt.setInt(1, Env.getAD_User_ID(Env.getCtx()));
			pstmt.setInt(2, Env.getAD_Client_ID(Env.getCtx()));
			pstmt.setString(3, FILTER_ATTRIBUTE);
			rs = pstmt.executeQuery();
			if (rs.next())
				return org.compiere.model.MTable.get(Env.getCtx(), "AD_Preference").getPO(rs.getInt(1), trxName);
		} catch (java.sql.SQLException e) {
			return null;
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return null;
	}

	private String preferenceValue() {
		return DB.getSQLValueString(null,
				"SELECT Value FROM AD_Preference WHERE AD_User_ID=? AND AD_Client_ID=? AND Attribute=?"
						+ " AND AD_Window_ID IS NULL ORDER BY Updated DESC LIMIT 1",
				Integer.valueOf(Env.getAD_User_ID(Env.getCtx())),
				Integer.valueOf(Env.getAD_Client_ID(Env.getCtx())), FILTER_ATTRIBUTE);
	}

	// ─────────────────────────────────────────────────────────────
	//  events
	// ─────────────────────────────────────────────────────────────

	@Override
	public void onEvent(Event event) throws Exception {
		String name = event.getName();
		if (Events.ON_CLICK.equals(name))
			onClick(event.getTarget());
		else if (Events.ON_SELECT.equals(name) || Events.ON_CHECK.equals(name))
			reload();
	}

	private void onClick(Object target) {
		if (target == btnRefresh) {
			saveFilter();
			reload();
			status("Refreshed.");
		} else if (target == btnApprove) {
			act(ApprovalAction.APPROVE);
		} else if (target == btnReject) {
			act(ApprovalAction.REJECT);
		}
	}

	private void act(ApprovalAction action) {
		ApprovalTransaction chain = selectedMine();
		WorkflowEngine engine = engineOrNull();
		if (chain == null || engine == null)
			return;

		ApprovalStep step = null;
		int user = Env.getAD_User_ID(Env.getCtx());
		int role = Env.getAD_Role_ID(Env.getCtx());
		for (ApprovalStep candidate : chain.getSteps())
			if (engine.canAct(candidate, user, role)) {
				step = candidate;
				break;
			}
		if (step == null) {
			error("No step of " + chain.getDisplayName() + " is waiting for you.");
			return;
		}

		String comment = txtComment.getValue() == null ? "" : txtComment.getValue().trim();
		if (action.isCommentMandatory() && comment.length() == 0) {
			error("A comment is required to reject, so the requester knows what to change.");
			return;
		}
		if (Messagebox.show(action.getLabel() + " level " + step.getLevelNo() + " of "
				+ chain.getDisplayName() + "?", "Confirm", Messagebox.OK | Messagebox.CANCEL,
				Messagebox.QUESTION) != Messagebox.OK)
			return;

		try {
			engine.act(chain.getId(), step.getId(), action, comment);
			txtComment.setValue("");
			status(action.getLabel() + " recorded for " + chain.getDisplayName() + ".");
			reload();
		} catch (Exception e) {
			error(action.getLabel() + " failed: " + e.getMessage());
		}
	}

	private ApprovalTransaction selectedMine() {
		int index = lstMine.getSelectedIndex();
		if (index < 0)
			return null;
		List<ApprovalTransaction> shown = shownMine();
		if (index >= shown.size())
			return null;
		return shown.get(index);
	}

	/** The rows currently in the first list, in the same order. */
	private List<ApprovalTransaction> shownMine() {
		List<ApprovalTransaction> result = new ArrayList<ApprovalTransaction>();
		int processId = UiLayouts.comboId(cmbProcess);
		boolean onlyOverdue = chkOnlyOverdue.isSelected();
		for (ApprovalTransaction chain : mine)
			if (matchesProcess(chain, processId) && (!onlyOverdue || isLate(chain)))
				result.add(chain);
		return result;
	}

	private void updateButtons() {
		ApprovalTransaction chain = selectedMine();
		WorkflowEngine engine = engineOrNull();
		boolean actionable = chain != null && engine != null;
		if (actionable) {
			int user = Env.getAD_User_ID(Env.getCtx());
			int role = Env.getAD_Role_ID(Env.getCtx());
			actionable = false;
			for (ApprovalStep step : chain.getSteps())
				if (engine.canAct(step, user, role)) {
					actionable = true;
					break;
				}
		}
		UiLayouts.enable(btnApprove, actionable);
		UiLayouts.enable(btnReject, actionable);
	}

	private String userName(int userId) {
		if (userId <= 0)
			return "?";
		String name = DB.getSQLValueString(null, "SELECT Name FROM AD_User WHERE AD_User_ID=?",
				Integer.valueOf(userId));
		return name == null ? ("user " + userId) : name;
	}

	private String roleName(int roleId) {
		if (roleId <= 0)
			return "?";
		String name = DB.getSQLValueString(null, "SELECT Name FROM AD_Role WHERE AD_Role_ID=?",
				Integer.valueOf(roleId));
		return name == null ? String.valueOf(roleId) : name;
	}

	private void status(String message) {
		lblStatus.setValue(message);
		lblStatus.setStyle("color: #1f6f3f;");
	}

	private void error(String message) {
		String text = message == null || message.trim().length() == 0 ? "Operation failed." : message;
		lblStatus.setValue(text);
		lblStatus.setStyle("color: #b00020;");
		Messagebox.show(text, "Error", Messagebox.OK, Messagebox.ERROR);
	}
}
