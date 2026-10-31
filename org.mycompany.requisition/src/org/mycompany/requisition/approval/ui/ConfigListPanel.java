package org.mycompany.requisition.approval.ui;

import java.util.List;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.ListItem;
import org.adempiere.webui.component.Listbox;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Vlayout;

/**
 * Shared shape of every configuration section that manages a list: a list of the
 * rows, a small editor underneath it and the buttons that act on the selection.
 *
 * <p>
 * Sections 04, 05, 08 and 09 all have this form, and all of them have the same two
 * awkward parts, which is why they are here rather than repeated:
 *
 * <ul>
 * <li>the list is bound by <em>index</em> to an id list, because a ZK list item
 * carries no payload of its own here</li>
 * <li>an edit in progress is dropped when the selection changes, and that is
 * stated on screen rather than happening silently</li>
 * </ul>
 *
 * <p>
 * A subclass supplies the data and the editor; this class owns the selection, the
 * button states and the messages.
 */
public abstract class ConfigListPanel extends Vlayout implements EventListener<Event>, Refreshable {
	private static final long serialVersionUID = 1L;

	private final WProcessApprovalWindow window;
	private final String emptyHint;

	private Listbox list;
	private Vlayout editorBox;
	private Label lblSelection;
	private Label lblHint;

	private Button btnAdd;
	private Button btnUpdate;
	private Button btnDelete;
	private Button btnExtraOne;
	private Button btnExtraTwo;

	/** Ids in the order the list shows them; index of the list maps into this. */
	private List<Integer> ids = new java.util.ArrayList<Integer>();

	/** Id being edited, 0 when the editor is empty and a new row is being made. */
	private int editingId;

	/**
	 * @param window   the owning window, for services and the current selection
	 * @param emptyHint what to say when the list has no rows yet
	 */
	protected ConfigListPanel(WProcessApprovalWindow window, String emptyHint) {
		this.window = window;
		this.emptyHint = emptyHint;
		build();
	}

	// ─────────────────────────────────────────────────────────────
	//  chrome
	// ─────────────────────────────────────────────────────────────

	private void build() {
		setSpacing("4px");
		setStyle("width: 100%;");

		list = new Listbox();
		list.setWidth("100%");
		list.setRows(6);
		list.addEventListener(Events.ON_SELECT, this);
		appendChild(list);

		lblSelection = new Label("");
		lblSelection.setStyle("color: #555555;");
		appendChild(lblSelection);

		Hlayout actions = UiLayouts.toolbar();
		btnAdd = UiLayouts.button("Add");
		btnAdd.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnAdd);

		btnUpdate = UiLayouts.button("Update");
		btnUpdate.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnUpdate);

		btnDelete = UiLayouts.button("Delete");
		btnDelete.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnDelete);

		btnExtraOne = UiLayouts.button("Move Up");
		btnExtraOne.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnExtraOne);

		btnExtraTwo = UiLayouts.button("Move Down");
		btnExtraTwo.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnExtraTwo);
		appendChild(actions);

		lblHint = new Label("");
		lblHint.setStyle("color: #a06000;");
		appendChild(lblHint);

		editorBox = new Vlayout();
		editorBox.setSpacing("2px");
		appendChild(editorBox);
	}

	/**
	 * Fills the editor area with the fields of this section.
	 * <p>
	 * Called once when the panel is built and again whenever the editor has to
	 * show a different row, so a subclass never has to remember to reset its own
	 * controls.
	 */
	protected abstract void buildEditor();

	/** Reloads {@link #ids()} and the rows from the database. */
	protected abstract void reload();

	/** The label of one row, in display order. */
	protected abstract String labelOf(int index);

	/** Ids in display order; the index of the list maps into this list. */
	protected abstract List<Integer> ids();

	/** Creates a row from the editor contents. */
	protected abstract void createRow() throws Exception;

	/** Stores the editor contents over the row with this id. */
	protected abstract void updateRow(int id) throws Exception;

	/** Removes the row with this id. */
	protected abstract void deleteRow(int id) throws Exception;

	/** Label of the first extra button; null hides it. */
	protected String firstActionLabel() {
		return "Move Up";
	}

	/** What the first extra button does. */
	protected void firstAction(int id) throws Exception {
		// sections that do not reorder override the label instead
	}

	/** Label of the second extra button; null hides it. */
	protected String secondActionLabel() {
		return "Move Down";
	}

	/** What the second extra button does. */
	protected void secondAction(int id) throws Exception {
		// sections that do not reorder override the label instead
	}

	// ─────────────────────────────────────────────────────────────
	//  lifecycle
	// ─────────────────────────────────────────────────────────────

	/**
	 * Reloads everything for the current selection.
	 * <p>
	 * Called by the window whenever the process or the version changes, so a panel
	 * can never show rows that belong to another version.
	 */
	@Override
	public final void refresh() {
		reload();
		fillList();
		clearEditor();
		updateButtons();
	}

	/** True when there is nothing to show because no version is selected. */
	protected boolean isVersionSelected() {
		return window.getSelectedVersionId() > 0;
	}

	private void fillList() {
		ids = isVersionSelected() ? ids() : new java.util.ArrayList<Integer>();
		UiLayouts.clear(list);
		for (int i = 0; i < ids.size(); i++)
			// the id travels on the list item itself, so the selection does not have
			// to be matched back to a position in a parallel list
			list.appendItem(labelOf(i), ids.get(i));

		if (ids.isEmpty()) {
			lblSelection.setValue(emptyHint);
			lblSelection.setStyle("color: #a06000;");
		} else {
			// start with the first row selected so the editor is never blank for no
			// reason; the user did not ask for a new row yet
			list.setSelectedIndex(0);
			onRowSelected();
		}
	}

	private void clearEditor() {
		UiLayouts.clear(editorBox);
		editingId = 0;
		buildEditor();
	}

	private void onRowSelected() {
		int index = list.getSelectedIndex();
		if (index < 0 || index >= ids.size()) {
			editingId = 0;
			lblSelection.setValue(emptyHint);
			updateButtons();
			return;
		}
		editingId = ids.get(index).intValue();
		lblSelection.setValue("Row " + (index + 1) + " of " + ids.size() + " selected.");
		lblSelection.setStyle("color: #555555;");
		UiLayouts.clear(editorBox);
		buildEditor();
		updateButtons();
	}
	private void updateButtons() {
		boolean haveSelection = editingId > 0;
		UiLayouts.enable(btnAdd, isVersionSelected());
		UiLayouts.enable(btnUpdate, haveSelection);
		UiLayouts.enable(btnDelete, haveSelection);

		String first = firstActionLabel();
		String second = secondActionLabel();
		btnExtraOne.setVisible(first != null);
		btnExtraTwo.setVisible(second != null);
		if (first != null)
			btnExtraOne.setLabel(first);
		if (second != null)
			btnExtraTwo.setLabel(second);

		// at the top of the list there is nothing to move up, and the same at the
		// bottom for down, so the button is disabled rather than doing nothing
		int index = list.getSelectedIndex();
		UiLayouts.enable(btnExtraOne, haveSelection && first != null && index > 0);
		UiLayouts.enable(btnExtraTwo, haveSelection && second != null && index >= 0
				&& index < ids.size() - 1);
	}

	@Override
	public void onEvent(Event event) throws Exception {
		if (Events.ON_SELECT.equals(event.getName()))
			onRowSelected();
		else if (Events.ON_CLICK.equals(event.getName()))
			onClick(event.getTarget());
	}

	private void onClick(Object target) throws Exception {
		if (target == btnAdd) {
			create();
		} else if (target == btnUpdate) {
			confirmAndRun("Update this row?", editingId, true);
		} else if (target == btnDelete) {
			confirmAndRun("Delete this row?\n\nThe row is deactivated rather than removed, because "
					+ "approvals that already ran refer to it.", editingId, false);
		} else if (target == btnExtraOne) {
			firstAction(editingId);
			refresh();
		} else if (target == btnExtraTwo) {
			secondAction(editingId);
			refresh();
		}
	}

	private void create() throws Exception {
		editingId = 0;
		lblSelection.setValue("New row. Fill in the fields, then press Add.");
		lblSelection.setStyle("color: #a06000;");
		UiLayouts.clear(editorBox);
		buildEditor();
		updateButtons();
		btnUpdate.setLabel("Update");
		btnUpdate.setDisabled(true);
		btnDelete.setDisabled(true);
	}

	private void confirmAndRun(String question, int id, boolean update) throws Exception {
		if (id <= 0)
			return;
		int answer = Messagebox.show(question, "Confirm", Messagebox.OK | Messagebox.CANCEL, Messagebox.QUESTION);
		if (answer != Messagebox.OK)
			return;
		if (update)
			updateRow(id);
		else
			deleteRow(id);
		refresh();
	}

	/**
	 * Runs an action and reports its failure instead of letting it disappear.
	 * <p>
	 * Every subclass method declares a checked exception, and swallowing them would
	 * turn a failed save into a form that looks like it worked.
	 */
	protected final void run(String what, Work work) {
		try {
			work.run();
		} catch (Exception e) {
			window.reportError(what + " failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
		}
	}

	/** Body of an action, so the failure handling stays in one place. */
	protected interface Work {
		void run() throws Exception;
	}

	// ─────────────────────────────────────────────────────────────
	//  helpers for subclasses
	// ─────────────────────────────────────────────────────────────

	protected WProcessApprovalWindow window() {
		return window;
	}

	protected int editingId() {
		return editingId;
	}

	protected Vlayout editor() {
		return editorBox;
	}

	/** Says why a value could not be used, in the same place as other messages. */
	protected void hint(String text) {
		lblHint.setValue(text == null ? "" : text);
	}

	/**
	 * Active users of the tenant, for an approver dropdown.
	 * <p>
	 * Delegates to {@link Lookups} so the window has exactly one query per lookup
	 * and a change to how a user is listed does not have to be made twice.
	 */
	protected static List<String> activeUsers(int clientId) {
		return Lookups.users(clientId);
	}

	/** Active roles of the tenant, for a responsible role dropdown. */
	protected static List<String> activeRoles(int clientId) {
		return Lookups.roles(clientId);
	}

	/** Active organisations of the tenant. */
	protected static List<String> activeOrgs(int clientId) {
		return Lookups.orgs(clientId);
	}

	/**
	 * Splits a {@code id|label} row produced by the lookup helpers.
	 *
	 * @return the id, or 0 when the row is empty
	 */
	protected static int idOf(String row) {
		return UiLayouts.idOf(row);
	}

	/** Fills a dropdown from {@code id|label} rows. */
	protected static void fill(org.zkoss.zul.Combobox combo, List<String> rows, boolean withEmpty) {
		UiLayouts.fill(combo, rows, withEmpty);
	}

	/** Reads an integer from a text box, or null when it is empty or not a number. */
	protected static Integer integerOf(org.adempiere.webui.component.Textbox box) {
		return UiLayouts.integerOf(box);
	}

	protected static String textOf(org.adempiere.webui.component.Textbox box) {
		return UiLayouts.textOf(box);
	}
}
