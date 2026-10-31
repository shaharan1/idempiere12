package org.mycompany.requisition;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.compiere.model.MClient;
import org.compiere.model.MOrg;
import org.compiere.model.MUser;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Column;
import org.adempiere.webui.component.Columns;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.ListItem;
import org.adempiere.webui.component.Listbox;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.panel.ADForm;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Cell;
import org.zkoss.zul.Center;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.South;
import org.zkoss.zul.Vlayout;

/**
 * Professional CRUD form for iDempiere 12 tables.
 * <p>
 * The user only enters the business {@code Name}. Client, organization, active
 * flag, audit columns and every other mandatory value are supplied by
 * {@link CrudService} from the iDempiere context and the table metadata. System
 * columns are shown read-only, they are never editable in the UI.
 * <p>
 * The table is not hard coded. It is taken from the AD_Menu entry that opens the
 * form (AD_Menu > Predefined Context Variables):
 *
 * <pre>
 * TableName=XX_GSM
 * NameColumn=Name
 * </pre>
 *
 * When the menu does not define them the defaults {@code XX_GSM} / {@code Name}
 * are used.
 */
public class WCrudForm extends ADForm implements EventListener<Event> {
	private static final long serialVersionUID = 1L;

	private static final CLogger log = CLogger.getCLogger(WCrudForm.class);

	/** Fallback configuration when the menu does not supply one. */
	private static final String DEFAULT_TABLE = "XX_GSM";
	private static final String DEFAULT_NAME_COLUMN = "Name";

	/** Context prefix used by AD_Menu predefined context variables. */
	private static final String VAR_PREFIX = Env.PREFIX_PREDEFINED_VARIABLE;

	private static final String MODE_NEW = "NEW RECORD";
	private static final String MODE_EDIT = "EDIT EXISTING RECORD";

	/** Placeholder for values iDempiere writes itself when the record is saved. */
	private static final String AUTO = "(set automatically on save)";

	/**
	 * Always read the context on demand. A form is constructed while the desktop
	 * builds the tab, so a context captured in a field can still be the one that
	 * was active before the window was opened - that is how a logged in
	 * GardenWorld user ended up with AD_Org_ID=0 here.
	 */
	private Properties ctx() {
		return Env.getCtx();
	}

	private CrudService service;

	// â”€â”€ header â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	private Label lblTitle;
	private Label lblMode;

	// â”€â”€ record selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	private Textbox txtSearch;
	private Listbox listRecords;
	private Button btnReload;
	private Button btnFind;

	// â”€â”€ form fields â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	private Textbox txtName;
	private Grid gridFields;
	private final Map<String, Textbox> systemFields = new LinkedHashMap<String, Textbox>();

	// â”€â”€ actions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	private Button btnSave;
	private Button btnUpdate;
	private Button btnDelete;
	private Button btnClear;
	private Label lblStatus;

	/** Primary key of the loaded record, 0 means "no record selected". */
	private int selectedId = 0;

	@Override
	protected void initForm() {
		try {
			service = new CrudService(ctx(), configValue("TableName", DEFAULT_TABLE),
					configValue("NameColumn", DEFAULT_NAME_COLUMN));
		} catch (CrudService.CrudException e) {
			// A misconfigured form must still render and explain itself.
			log.log(java.util.logging.Level.WARNING, "Crud form not configured: " + e.getMessage(), e);
			appendChild(errorPanel(e.getMessage()));
			return;
		}

		lblTitle = new Label(getTitle() + " - " + service.getTableName());
		lblTitle.setStyle("font-size: 16px; font-weight: bold;");

		Borderlayout mainLayout = new Borderlayout();
		mainLayout.setWidth("100%");
		mainLayout.setHeight("100%");
		appendChild(mainLayout);

		// â”€â”€ header + record selection (one North region) â”€â”€
		North north = new North();
		Vlayout header = new Vlayout();
		header.setSpacing("6px");
		header.setStyle("border: none; background: #f9f9f9; padding: 12px;");
		header.appendChild(lblTitle);

		lblMode = new Label(MODE_NEW);
		lblMode.setStyle("font-weight: bold; color: #1f6f3f;");
		header.appendChild(lblMode);

		Hlayout searchLayout = new Hlayout();
		searchLayout.setSpacing("6px");

		searchLayout.appendChild(new Label("Records:"));
		txtSearch = new Textbox();
		txtSearch.setWidth("220px");
		searchLayout.appendChild(txtSearch);

		btnReload = new Button("Reload");
		btnReload.addEventListener(Events.ON_CLICK, this);
		searchLayout.appendChild(btnReload);

		btnFind = new Button("Find");
		btnFind.addEventListener(Events.ON_CLICK, this);
		searchLayout.appendChild(btnFind);

		listRecords = new Listbox();
		listRecords.setWidth("360px");
		listRecords.setRows(8);
		listRecords.addEventListener(Events.ON_SELECT, this);
		searchLayout.appendChild(listRecords);

		header.appendChild(searchLayout);
		north.appendChild(header);
		mainLayout.appendChild(north);

		// â”€â”€ fields â”€â”€
		Center center = new Center();
		center.setAutoscroll(true);
		buildFieldGrid();
		center.appendChild(gridFields);
		mainLayout.appendChild(center);

		// â”€â”€ actions â”€â”€
		South south = new South();
		Vlayout footer = new Vlayout();
		footer.setSpacing("6px");
		footer.setStyle("border: none; padding: 10px;");

		Hlayout actions = new Hlayout();
		actions.setSpacing("8px");
		btnSave = new Button("Save");
		btnSave.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnSave);

		btnUpdate = new Button("Update");
		btnUpdate.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnUpdate);

		btnDelete = new Button("Delete");
		btnDelete.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnDelete);

		btnClear = new Button("New");
		btnClear.addEventListener(Events.ON_CLICK, this);
		actions.appendChild(btnClear);

		lblStatus = new Label("");
		lblStatus.setStyle("color: #555555;");
		actions.appendChild(lblStatus);
		footer.appendChild(actions);
		south.appendChild(footer);
		mainLayout.appendChild(south);

		loadRecords();
		resetToNewMode();
	}

	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	//  Layout helpers
	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	/**
	 * Creates the form grid. The business name is the only editable field, all
	 * other columns of the table are rendered read-only.
	 */
	private void buildFieldGrid() {
		gridFields = new Grid();
		gridFields.setWidth("100%");

		Columns columns = new Columns();
		Column colLabel = new Column("Field");
		colLabel.setWidth("220px");
		columns.appendChild(colLabel);
		Column colValue = new Column("Value");
		columns.appendChild(colValue);
		gridFields.appendChild(columns);

		Rows rows = new Rows();

		// the one field the user edits
		Row nameRow = new Row();
		nameRow.appendCellChild(labelFor(service.getNameColumn() + " *"));
		txtName = new Textbox();
		txtName.setWidth("360px");
		txtName.setTooltiptext("Required - " + service.getNameColumn() + " of the new record");
		nameRow.appendCellChild(txtName);
		rows.appendChild(nameRow);

		// iDempiere managed values, shown so the user can verify them
		addReadOnlyRow(rows, "Client", "AD_Client_ID");
		addReadOnlyRow(rows, "Organization", "AD_Org_ID");
		addReadOnlyRow(rows, "Active", "IsActive");

		// remaining business columns of the table
		for (String column : service.getDisplayColumns()) {
			if (column.equalsIgnoreCase(service.getNameColumn()))
				continue;
			addReadOnlyRow(rows, labelFor(column), column);
		}

		// audit information last
		addReadOnlyRow(rows, "Created", "Created");
		addReadOnlyRow(rows, "Created By", "CreatedBy");
		addReadOnlyRow(rows, "Updated", "Updated");
		addReadOnlyRow(rows, "Updated By", "UpdatedBy");

		gridFields.appendChild(rows);
	}

	private void addReadOnlyRow(Rows rows, Cell labelCell, String column) {
		if (!service.hasColumn(column))
			return;
		Row row = new Row();
		row.appendCellChild(labelCell);
		Textbox box = new Textbox();
		box.setReadonly(true);
		box.setWidth("360px");
		box.setStyle("background-color: #f0f0f0;");
		row.appendCellChild(box);
		rows.appendChild(row);
		systemFields.put(column, box);
	}

	private void addReadOnlyRow(Rows rows, String label, String column) {
		addReadOnlyRow(rows, labelFor(label), column);
	}

	private static Cell labelFor(String text) {
		Cell cell = new Cell();
		Label label = new Label(text);
		label.setStyle("font-weight: bold;");
		cell.appendChild(label);
		return cell;
	}

	private Vlayout errorPanel(String message) {
		Vlayout panel = new Vlayout();
		panel.setStyle("padding: 20px;");
		Label label = new Label("This form is not configured correctly: " + message);
		label.setStyle("color: #b00020; font-weight: bold;");
		panel.appendChild(label);
		return panel;
	}

	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	//  Modes and button states
	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	/** NEW mode: Save enabled, Update/Delete disabled. */
	private void resetToNewMode() {
		selectedId = 0;
		txtName.setValue("");
		for (Textbox box : systemFields.values())
			box.setValue("");
		for (Map.Entry<String, Textbox> entry : systemFields.entrySet())
			fillDefaultDisplay(entry.getKey(), entry.getValue());

		lblMode.setValue(MODE_NEW);
		lblMode.setStyle("font-weight: bold; color: #1f6f3f;");
		lblStatus.setValue("");
		applyButtonStates();
	}

	/** EDIT mode: Update/Delete enabled, Save disabled. */
	private void enterEditMode(int id, CrudService.RecordData data) {
		selectedId = id;
		txtName.setValue(data.getName());

		List<String> columns = data.getColumns();
		List<String> values = data.getValues();
		for (int i = 0; i < columns.size(); i++)
			setSystemValue(columns.get(i), values.get(i));

		lblMode.setValue(MODE_EDIT);
		lblMode.setStyle("font-weight: bold; color: #0b5394;");
		lblStatus.setValue("Record " + id + " loaded.");
		applyButtonStates();
	}

	private void applyButtonStates() {
		boolean isNew = selectedId <= 0;
		btnSave.setDisabled(!isNew);
		btnUpdate.setDisabled(isNew);
		btnDelete.setDisabled(isNew);
	}

	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	//  Data loading
	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private void loadRecords() {
		try {
			listRecords.removeAllItems();
			List<CrudService.RecordRef> records = service.list(txtSearch.getValue());
			for (CrudService.RecordRef ref : records)
				listRecords.appendItem(ref.getName(), Integer.valueOf(ref.getId()));
			lblStatus.setValue(records.size() + " record(s).");
		} catch (CrudService.CrudException e) {
			showError(e.getMessage());
		}
	}

	/**
	 * Fills the read-only system boxes for a new record. Values that iDempiere
	 * will maintain itself are shown as such instead of being faked, so the user
	 * only sees real values once the record exists.
	 */
	private void fillDefaultDisplay(String column, Textbox box) {
		if (column.equalsIgnoreCase("AD_Client_ID"))
			box.setValue(clientLabel());
		else if (column.equalsIgnoreCase("AD_Org_ID"))
			box.setValue(orgLabel());
		else if (column.equalsIgnoreCase("IsActive"))
			box.setValue("Yes");
		else if (column.equals("Created") || column.equals("Updated")
				|| column.equals("CreatedBy") || column.equals("UpdatedBy"))
			box.setValue(AUTO);
	}

	private void setSystemValue(String column, String value) {
		Textbox box = systemFields.get(column);
		if (box != null)
			box.setValue(value == null ? "" : value);
	}

	private String clientLabel() {
		MClient client = MClient.get(ctx(), Env.getAD_Client_ID(ctx()));
		return client == null ? "" : client.get_ValueAsString("Name");
	}

	private String orgLabel() {
		MOrg organization = MOrg.get(ctx(), Env.getAD_Org_ID(ctx()));
		return organization == null ? "" : organization.get_ValueAsString("Name");
	}

	private String userLabel() {
		MUser user = MUser.get(ctx(), Env.getAD_User_ID(ctx()));
		return user == null ? "" : user.get_ValueAsString("Name");
	}

	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	//  Events
	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	@Override
	public void onEvent(Event event) throws Exception {
		if (service == null || btnSave == null)
			return;

		if (event.getTarget() == btnSave) {
			onSave();
		} else if (event.getTarget() == btnUpdate) {
			onUpdate();
		} else if (event.getTarget() == btnDelete) {
			onDelete();
		} else if (event.getTarget() == btnClear) {
			resetToNewMode();
		} else if (event.getTarget() == btnReload || event.getTarget() == btnFind) {
			loadRecords();
		} else if (Events.ON_SELECT.equalsIgnoreCase(event.getName()) && event.getTarget() == listRecords) {
			onRecordSelected();
		}
	}

	private void onSave() {
		String name = txtName.getValue();
		if (isBlank(name)) {
			showError(service.getNameColumn() + " is required.");
			txtName.setFocus(true);
			return;
		}
		try {
			int id = service.create(name);
			loadRecords();
			selectInList(id);
			enterEditMode(id, service.load(id));
			Messagebox.show("Record " + id + " created.", "Saved", Messagebox.OK, Messagebox.INFORMATION);
		} catch (CrudService.CrudException e) {
			showError(e.getMessage());
		}
	}

	private void onUpdate() {
		if (selectedId <= 0) {
			showError("No record selected.");
			return;
		}
		String name = txtName.getValue();
		if (isBlank(name)) {
			showError(service.getNameColumn() + " is required.");
			txtName.setFocus(true);
			return;
		}
		try {
			service.update(selectedId, name);
			loadRecords();
			enterEditMode(selectedId, service.load(selectedId));
			Messagebox.show("Record " + selectedId + " updated.", "Updated",
					Messagebox.OK, Messagebox.INFORMATION);
		} catch (CrudService.CrudException e) {
			showError(e.getMessage());
		}
	}

	/** Confirmation dialog first, the delete only runs when the user answers yes. */
	private void onDelete() {
		if (selectedId <= 0) {
			showError("No record selected.");
			return;
		}
		final int id = selectedId;
		Messagebox.show("Are you sure you want to delete record " + id + "?",
				"Confirm Delete",
				new Messagebox.Button[] { Messagebox.Button.YES, Messagebox.Button.NO },
				null,
				"question",
				Messagebox.Button.NO,
				(Messagebox.ClickEvent click) -> {
					if (Messagebox.Button.YES.equals(click.getButton()))
						doDelete(id);
				});
	}

	private void doDelete(int id) {
		try {
			service.delete(id);
			loadRecords();
			resetToNewMode();
			Messagebox.show("Record " + id + " deleted.", "Deleted",
					Messagebox.OK, Messagebox.INFORMATION);
		} catch (CrudService.CrudException e) {
			showError(e.getMessage());
		}
	}

	private void onRecordSelected() {
		ListItem item = listRecords.getSelectedItem();
		if (item == null)
			return;
		Object value = item.getValue();
		if (!(value instanceof Integer))
			return;
		int id = ((Integer) value).intValue();
		try {
			enterEditMode(id, service.load(id));
		} catch (CrudService.CrudException e) {
			showError(e.getMessage());
		}
	}

	private void selectInList(int id) {
		for (int i = 0; i < listRecords.getItemCount(); i++) {
			ListItem item = listRecords.getItemAtIndex(i);
			if (item.getValue() instanceof Integer && ((Integer) item.getValue()).intValue() == id) {
				listRecords.setSelectedItem(item);
				return;
			}
		}
	}

	private void showError(String message) {
		lblStatus.setValue(message == null ? "" : message);
		Messagebox.show(message == null ? "Operation failed." : message,
				"Error", Messagebox.OK, Messagebox.ERROR);
	}

	/** Reads a configuration value from the AD_Menu predefined context variables. */
	private String configValue(String name, String fallback) {
		String value = Env.getContext(ctx(), m_WindowNo, VAR_PREFIX + name);
		if (value == null || value.trim().length() == 0)
			value = Env.getContext(ctx(), VAR_PREFIX + name);
		if (value == null || value.trim().length() == 0)
			return fallback;
		return value.trim();
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().length() == 0;
	}
}