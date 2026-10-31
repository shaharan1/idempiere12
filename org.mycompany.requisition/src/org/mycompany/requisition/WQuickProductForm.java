package org.mycompany.requisition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Checkbox;
import org.adempiere.webui.component.Column;
import org.adempiere.webui.component.Columns;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Listbox;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.editor.WTableDirEditor;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.window.Dialog;
import org.adempiere.util.Callback;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MProduct;
import org.compiere.model.X_M_Product;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Caption;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Div;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.Messagebox;

/**
 * Simplified "create product" form for iDempiere 12.
 * <p>
 * The user enters the Product Name and, optionally, a handful of business
 * fields. Everything else is filled from the current iDempiere context and from
 * the configured master data defaults, so a valid product is created by entering
 * only the name. Nothing that the standard Product Window shows for technical
 * reasons (client, organization, audit columns, ids) is exposed here.
 * <p>
 * The product is created with the standard {@link MProduct} model, so the new
 * record behaves exactly like a product created in the standard Product Window.
 * <p>
 * The name is intentionally different from the standard product window form so
 * that the two never collide.
 */
public class WQuickProductForm extends ADForm implements EventListener<Event> {
	private static final long serialVersionUID = 1L;

	/**
	 * Column names on M_Product. The AD_Column ids are resolved from the
	 * dictionary at runtime, so no identifier is hard coded here and a column that
	 * this installation does not have simply makes its field disappear.
	 */
	private static final String COL_PRODUCT_CATEGORY = "M_Product_Category_ID";
	private static final String COL_UOM = "C_UOM_ID";
	private static final String COL_TAX_CATEGORY = "C_TaxCategory_ID";
	private static final String COL_GSM = "XX_GSM_ID";
	private static final String COL_COLOUR = "XX_Colour_ID";

	/** Allowed M_Product.ProductType codes, taken from the generated model. */
	private static final String[][] PRODUCT_TYPES = {
			{ X_M_Product.PRODUCTTYPE_Item, "Item" },
			{ X_M_Product.PRODUCTTYPE_Service, "Service" },
			{ X_M_Product.PRODUCTTYPE_Resource, "Resource" },
			{ X_M_Product.PRODUCTTYPE_ExpenseType, "Expense Type" },
			{ X_M_Product.PRODUCTTYPE_Asset, "Asset" },
			{ X_M_Product.PRODUCTTYPE_Online, "Online" } };

	/** Resolved AD_Column ids of the reference columns, filled once per session. */
	private final Map<String, Integer> adColumnIds = new HashMap<String, Integer>();

	/**
	 * Always read the context on demand. A form is constructed while the desktop
	 * builds the tab, so a context captured in a field can still be the one that
	 * was active before the window was opened - that is how a logged in
	 * GardenWorld user ended up with AD_Org_ID=0 here.
	 */
	private Properties ctx() {
		return Env.getCtx();
	}

	private ProductDefaultService defaults;
	private ProductCreationService products;

	/** Default service bound to the context of right now. */
	private ProductDefaultService defaultsNow() {
		return new ProductDefaultService(ctx());
	}

	// input fields
	private Textbox txtName;
	private Textbox txtCode;
	private WTableDirEditor fldProductCategory;
	private Combobox cmbProductType;
	private WTableDirEditor fldUOM;
	private WTableDirEditor fldTaxCategory;
	private WTableDirEditor fldGSM;
	private WTableDirEditor fldColour;
	private Textbox txtDescription;
	private Checkbox chkSold;
	private Checkbox chkPurchased;
	private Checkbox chkStocked;
	private Checkbox chkActive;
	private Checkbox chkSystemToggle;

	// read only system information segment
	private Textbox txtSysId;
	private Textbox txtSysClient;
	private Textbox txtSysOrg;
	private Textbox txtSysCreated;
	private Textbox txtSysCreatedBy;
	private Textbox txtSysUpdated;
	private Textbox txtSysUpdatedBy;

	/**
 * The single scroll container of the form. Header, all five frames, the action
 * area and the product list are placed inside it, so one mouse wheel scrolls the
 * whole form and nothing is ever cut off.
 */
	private Div scrollArea;

	private Button btnSave;
	private Button btnClear;
	private Label lblDefaults;
	private Label lblResult;
	private Listbox listCreated;
	private Button btnRefresh;
	private Button btnUpdate;
	private Button btnDelete;
	private Label lblListInfo;
	private Button btnEdit;
	private Button btnCancelEdit;

	/** M_Product_ID being edited, 0 means "creating a new product". */
	private int editId = 0;

	@Override
	protected void initForm() {
		defaults = defaultsNow();
		products = new ProductCreationService(ctx(), defaults);

		lblResult = new Label("");
		lblResult.setStyle("color: #1f6f3f; white-space: normal;");

		btnSave = new Button("SAVE PRODUCT");
		btnSave.addEventListener(Events.ON_CLICK, this);

		btnClear = new Button("CLEAR");
		btnClear.addEventListener(Events.ON_CLICK, this);

		// Keep the form at a readable, standard size instead of stretching it over
		// the whole tab: on a wide monitor a 100% wide grid makes every field look
		// oversized and the label column loses its alignment.
// ---- layout rule ----
		// ADForm is a ZK Window that the tab already sizes to 100% x 99%, and its
		// content is clipped. So the form uses the full available width and puts
		// everything into ONE scroll area; the frames, the buttons and the product
		// list therefore scroll together as a single continuous page instead of
		// every frame getting its own scrollbar.
		this.setStyle("position: relative;");
		this.setWidth("100%");

		scrollArea = new Div();
		scrollArea.setWidth("100%");
		scrollArea.setStyle("width: 100%; height: 100%; overflow-x: hidden; overflow-y: auto;"
				+ " padding: 8px 12px 16px 12px; box-sizing: border-box;");
		this.appendChild(scrollArea);

		scrollArea.appendChild(titleLabel());

		// ---- the five business segments, each in its own framed section ----
		scrollArea.appendChild(buildBasicInformationFrame());
		scrollArea.appendChild(buildClassificationFrame());
		scrollArea.appendChild(buildAttributesFrame());
		scrollArea.appendChild(buildSalesPurchaseFrame());
		scrollArea.appendChild(buildSystemInformationFrame());

		scrollArea.appendChild(buildActionPanel());

		applyDefaults();
		refreshSystemInformation();
		appendCreatedList();
	}

	// ------------------------------------------------------------------------------
	//  section builders - one per logical segment
	// ------------------------------------------------------------------------------

	/** Segment 1: the fields the user types freely. */
	private Component buildBasicInformationFrame() {
		Rows rows = new Rows();

		txtName = new Textbox();
		txtName.setWidth("100%");
		addRow(rows, "Product Name *", txtName);

		// read only on purpose: the code is generated by the iDempiere M_Product
		// document sequence, the user must not type it
		txtCode = new Textbox();
		txtCode.setWidth("100%");
		txtCode.setReadonly(true);
		txtCode.setStyle("background-color: #f4f4f4;");
		addRow(rows, "Product Code", txtCode);

		txtDescription = new Textbox();
		txtDescription.setWidth("100%");
		// multi line is done with rows, not with type="textarea": zul.Textbox.setType
		// only accepts text/password/tel/email/url and rejects everything else
		txtDescription.setRows(3);
		addRow(rows, "Description", txtDescription);

		lblDefaults = new Label("");
		lblDefaults.setStyle("color: #555555; font-size: 11px; white-space: normal;");
		addRow(rows, "Defaults in use", lblDefaults);

		return frame("1. Basic Product Information", rows);
	}

	/** Product Classification segment. */
	private Component buildClassificationFrame() {
		Rows rows = new Rows();

		fldProductCategory = lookupEditor(COL_PRODUCT_CATEGORY);
		addRow(rows, "Product Category", fldProductCategory.getComponent());

		// the allowed values come from the generated model constants, never from
		// a hand written list of ids
		cmbProductType = new Combobox();
		cmbProductType.setWidth("100%");
		for (int i = 0; i < PRODUCT_TYPES.length; i++) {
			Comboitem item = cmbProductType.appendItem(PRODUCT_TYPES[i][1]);
			item.setValue(PRODUCT_TYPES[i][0]);
		}
		cmbProductType.setValue(productTypeName(X_M_Product.PRODUCTTYPE_Item));
		addRow(rows, "Product Type", cmbProductType);

		fldUOM = lookupEditor(COL_UOM);
		addRow(rows, "UOM", fldUOM.getComponent());

		fldTaxCategory = lookupEditor(COL_TAX_CATEGORY);
		addRow(rows, "Tax Category", fldTaxCategory.getComponent());

		return frame("2. Product Classification", rows);
	}

	/** Product Attributes segment. */
	private Component buildAttributesFrame() {
		Rows rows = new Rows();

		// a lookup is only offered when the column really exists on M_Product
		if (hasColumn(COL_GSM)) {
			fldGSM = lookupEditor(COL_GSM);
			addRow(rows, "GSM", fldGSM.getComponent());
		}
		if (hasColumn(COL_COLOUR)) {
			fldColour = lookupEditor(COL_COLOUR);
			addRow(rows, "Colour", fldColour.getComponent());
		}
		if (fldGSM == null && fldColour == null)
			addRow(rows, "Attributes", new Label("no custom attribute column on M_Product"));

		return frame("3. Product Attributes", rows);
	}

	/** Segment 4: the boolean business flags. */
	private Component buildSalesPurchaseFrame() {
		Rows rows = new Rows();

		chkSold = new Checkbox();
		chkSold.setChecked(true);
		addRow(rows, "Sales Product", chkSold);

		chkPurchased = new Checkbox();
		chkPurchased.setChecked(true);
		addRow(rows, "Purchase Product", chkPurchased);

		chkStocked = new Checkbox();
		chkStocked.setChecked(true);
		addRow(rows, "Stocked", chkStocked);

		chkActive = new Checkbox();
		chkActive.setChecked(true);
		addRow(rows, "Active", chkActive);

		return frame("4. Sales & Purchase Settings", rows);
	}

	/**
	 * Segment 5: values iDempiere fills in itself. They are shown read only and
	 * start collapsed, because they are not meant to be edited here.
	 */
	private Component buildSystemInformationFrame() {
		Rows rows = new Rows();

		txtSysId = readOnlyField("Product ID");
		addRow(rows, "Product ID", txtSysId);
		txtSysClient = readOnlyField("Client");
		addRow(rows, "Client", txtSysClient);
		txtSysOrg = readOnlyField("Organization");
		addRow(rows, "Organization", txtSysOrg);
		txtSysCreated = readOnlyField("Created");
		addRow(rows, "Created", txtSysCreated);
		txtSysCreatedBy = readOnlyField("Created By");
		addRow(rows, "Created By", txtSysCreatedBy);
		txtSysUpdated = readOnlyField("Updated");
		addRow(rows, "Updated", txtSysUpdated);
		txtSysUpdatedBy = readOnlyField("Updated By");
		addRow(rows, "Updated By", txtSysUpdatedBy);

		// starts collapsed, the "Show" toggle is created by frame(...)
		return frame("5. System Information", rows, true);
	}

	/** Segment 6: the common action area, outside every segment. */
	private Component buildActionPanel() {
		org.zkoss.zul.Hlayout actions = new org.zkoss.zul.Hlayout();
		actions.setSpacing("8px");
		actions.appendChild(btnSave);
		actions.appendChild(btnClear);

		org.zkoss.zul.Hlayout result = new org.zkoss.zul.Hlayout();
		result.setSpacing("8px");
		result.appendChild(lblResult);

		org.zkoss.zul.Vlayout panel = new org.zkoss.zul.Vlayout();
		panel.setStyle("margin-top: 12px;");
		panel.appendChild(actions);
		panel.appendChild(result);
		return panel;
	}

	/**
	 * Wraps section rows in a Groupbox with a Caption, which is the layout
	 * convention used by the iDempiere windows themselves.
	 *
	 * The rows are put into a Grid first: a ZK Rows component only accepts a Grid
	 * as its parent, appending it straight to the Groupbox fails with
	 * "Unsupported parent for rows".
	 */
	private Groupbox frame(String title, Rows rows) {
		return frame(title, rows, false);
	}

	/**
	 * @param collapsible when true the body starts hidden and a "Show" check box
	 *                    next to the caption toggles it
	 */
	private Groupbox frame(String title, Rows rows, boolean collapsible) {
		Groupbox box = new Groupbox();
		box.setStyle("margin-top: 10px;");

		Caption caption = new Caption(title);
		caption.setStyle("font-weight: bold;");
		box.appendChild(caption);

		Grid grid = new Grid();
		grid.setWidth("100%");
		Columns columns = new Columns();
		Column colLabel = new Column("Field");
		colLabel.setWidth("220px");
		columns.appendChild(colLabel);
		columns.appendChild(new Column("Value"));
		grid.appendChild(columns);
		grid.appendChild(rows);

		if (collapsible) {
			Div body = new Div();
			body.appendChild(grid);
			body.setVisible(false);
			box.appendChild(body);

			final Checkbox toggle = new Checkbox();
			toggle.setLabel("Show");
			toggle.setStyle("float: right; margin-top: -20px;");
			toggle.addEventListener(Events.ON_CHECK, e -> body.setVisible(toggle.isChecked()));
			box.appendChild(toggle);

			chkSystemToggle = toggle;
		} else {
			box.appendChild(grid);
		}
		return box;
	}

	/** Read only, greyed text box used by the system information segment. */
	private Textbox readOnlyField(String hint) {
		Textbox box = new Textbox();
		box.setWidth("100%");
		box.setReadonly(true);
		box.setStyle("background-color: #f4f4f4;");
		box.setPlaceholder(hint);
		return box;
	}

	/** Readable name of a M_Product.ProductType code. */
	private static String productTypeName(String code) {
		for (int i = 0; i < PRODUCT_TYPES.length; i++)
			if (PRODUCT_TYPES[i][0].equals(code))
				return PRODUCT_TYPES[i][1];
		return code;
	}

	/**
	 * Lists the products this form created, so the user does not need the
	 * standard Product window to find what they just made.
	 */
	private void appendCreatedList() {
		Label heading = new Label("Products created with this form");
		heading.setStyle("font-size: 13px; font-weight: bold; margin-top: 16px; margin-bottom: 4px;");
		scrollArea.appendChild(heading);

		lblListInfo = new Label("");
		lblListInfo.setStyle("color: #555555; font-size: 11px;");
		scrollArea.appendChild(lblListInfo);

		// toolbar above the list: select a row, then edit, update or delete it
		btnRefresh = new Button("REFRESH");
		btnRefresh.addEventListener(Events.ON_CLICK, this);
		btnEdit = new Button("EDIT");
		btnEdit.addEventListener(Events.ON_CLICK, this);
		btnUpdate = new Button("UPDATE");
		btnUpdate.addEventListener(Events.ON_CLICK, this);
		btnDelete = new Button("DELETE");
		btnDelete.addEventListener(Events.ON_CLICK, this);
		btnCancelEdit = new Button("CANCEL EDIT");
		btnCancelEdit.addEventListener(Events.ON_CLICK, this);
		btnCancelEdit.setVisible(false);

		org.zkoss.zul.Hlayout toolbar = new org.zkoss.zul.Hlayout();
		toolbar.setSpacing("6px");
		toolbar.setStyle("margin: 6px 0;");
		toolbar.appendChild(btnEdit);
		toolbar.appendChild(btnUpdate);
		toolbar.appendChild(btnDelete);
		toolbar.appendChild(btnCancelEdit);
		toolbar.appendChild(btnRefresh);
		toolbar.appendChild(new Label("select a row first, then EDIT / UPDATE / DELETE"));
		scrollArea.appendChild(toolbar);

		listCreated = new Listbox();
		listCreated.setWidth("100%");
		listCreated.setRows(8);
		listCreated.setStyle("margin-top: 4px;");
		listCreated.addEventListener(Events.ON_CLICK, this);
		listCreated.addEventListener(Events.ON_SELECT, this);

		org.zkoss.zul.Listhead head = new org.zkoss.zul.Listhead();
		head.appendChild(header("ID", "10%"));
		head.appendChild(header("Code", "20%"));
		head.appendChild(header("Name", "45%"));
		head.appendChild(header("Created", "25%"));
		listCreated.appendChild(head);

		// the iDempiere Listbox casts every row to org.adempiere.webui.component.ListItem,
		// so the renderer must produce those types and not the plain ZK ones
		listCreated.setItemRenderer(new QuickProductRenderer());
		scrollArea.appendChild(listCreated);

		refreshCreatedList();
	}

	/**
	 * Renderer for the created-products list. Has to return the iDempiere
	 * {@link org.adempiere.webui.component.ListItem}, otherwise the Listbox
	 * fails with a ClassCastException at render time.
	 */
	private class QuickProductRenderer
			implements org.zkoss.zul.ListitemRenderer<ProductDefaultService.QuickEntryProduct>,
			org.zkoss.zul.ListitemRendererExt {

		@Override
		public org.zkoss.zul.Listitem newListitem(org.zkoss.zul.Listbox box) {
			return new org.adempiere.webui.component.ListItem();
		}

		@Override
		public org.zkoss.zul.Listcell newListcell(org.zkoss.zul.Listitem item) {
			return new org.adempiere.webui.component.ListCell(item.getLabel());
		}

		@Override
		public int getControls() {
			return org.zkoss.zul.ListitemRendererExt.DETACH_ON_RENDER;
		}

		@Override
		public void render(org.zkoss.zul.Listitem item, ProductDefaultService.QuickEntryProduct data,
				int index) {
			item.appendChild(new org.adempiere.webui.component.ListCell(String.valueOf(data.getId())));
			item.appendChild(new org.adempiere.webui.component.ListCell(data.getValue()));
			item.appendChild(new org.adempiere.webui.component.ListCell(data.getName()));
			item.appendChild(new org.adempiere.webui.component.ListCell(
					data.getCreated() == null ? "" : data.getCreated()));
			item.setValue(data);
			// the click has to be registered on the row itself: a click on a row
			// reports the row as the event target, never the surrounding Listbox
			item.addEventListener(Events.ON_CLICK, WQuickProductForm.this);
		}
	}

	private org.zkoss.zul.Listheader header(String label, String width) {
		org.zkoss.zul.Listheader h = new org.zkoss.zul.Listheader(label);
		h.setWidth(width);
		h.setStyle("background: #f0f4f8; font-weight: bold;");
		return h;
	}

		/** Display name of a table reference record, or the plain id as fallback. */
	private String name(String table, int id) {
		if (id <= 0)
			return null;
		String value = DB.getSQLValueString(null, "SELECT Name FROM " + table + " WHERE " + table + "_ID=?",
				Integer.valueOf(id));
		return value == null ? String.valueOf(id) : value + " (" + id + ")";
	}

	private void refreshCreatedList() {
		List<ProductDefaultService.QuickEntryProduct> productsList = defaultsNow()
				.listQuickEntryProducts(50);
		listCreated.setModel(new org.zkoss.zul.ListModelList<ProductDefaultService.QuickEntryProduct>(
				productsList, true));
		if (lblListInfo != null)
			lblListInfo.setValue(productsList.isEmpty() ? "no product created with this form yet"
					: productsList.size() + " product(s) - newest first");
	}

	private Label titleLabel() {
		Label title = new Label("QUICK PRODUCT ENTRY");
		title.setStyle("font-size: 16px; font-weight: bold; margin-bottom: 8px;");
		return title;
	}

	/**
	 * Lays out one label/value row. The value cell stretches over the rest of the
	 * page, so the form uses the full width of the tab instead of a fixed block.
	 */
	private void addRow(Rows rows, String label, org.zkoss.zk.ui.Component value) {
		Row row = new Row();
		org.zkoss.zul.Cell labelCell = new org.zkoss.zul.Cell();
		Label lbl = new Label(label);
		lbl.setStyle("font-weight: bold;");
		labelCell.appendChild(lbl);
		labelCell.setWidth("220px");
		labelCell.setStyle("width: 220px; vertical-align: top; padding-top: 3px;");
		row.appendCellChild(labelCell);

		org.zkoss.zul.Cell valueCell = new org.zkoss.zul.Cell();
		valueCell.appendChild(value);
		valueCell.setWidth("100%");
		row.appendCellChild(valueCell);
		rows.appendChild(row);
	}

	/**
	 * Builds an autocomplete lookup for a M_Product reference column.
	 *
	 * @param columnName the column on M_Product, e.g. {@code C_UOM_ID}; its
	 *                   AD_Column_ID is read from the dictionary, never hard coded
	 * @return the editor, or null when this installation has no such column
	 */
	private WTableDirEditor lookupEditor(String columnName) {
		int adColumnId = adColumnId(columnName);
		if (adColumnId <= 0)
			return null;
		MLookup lookup = MLookupFactory.get(ctx(), m_WindowNo, 0, adColumnId, DisplayType.TableDir);
		WTableDirEditor editor = new WTableDirEditor(columnName, false, false, true, lookup, true);
		// let the lookup grow with the page instead of keeping a fixed width
		editor.getComponent().setWidth("100%");
		return editor;
	}

	/** AD_Column_ID of a column on M_Product, 0 when the column is unknown. */
	private int adColumnId(String columnName) {
		Integer cached = adColumnIds.get(columnName);
		if (cached != null)
			return cached.intValue();

		int id = DB.getSQLValue(null,
				"SELECT c.AD_Column_ID FROM AD_Column c"
						+ " INNER JOIN AD_Table t ON t.AD_Table_ID=c.AD_Table_ID"
						+ " WHERE UPPER(t.TableName)='M_PRODUCT'"
						+ " AND UPPER(c.ColumnName)=? AND c.IsActive='Y'",
				columnName.toUpperCase());
		adColumnIds.put(columnName, Integer.valueOf(id));
		return id;
	}

	private boolean hasColumn(String columnName) {
		return DB.getSQLValue(null,
				"SELECT COUNT(*) FROM AD_Column c INNER JOIN AD_Table t ON t.AD_Table_ID=c.AD_Table_ID "
						+ "WHERE UPPER(t.TableName)='M_PRODUCT' AND UPPER(c.ColumnName)=? AND c.IsActive='Y'",
				columnName.toUpperCase()) > 0;
	}

	/**
	 * Shows which defaults will be used when the user leaves a field empty.
	 * Reading them from the master data keeps the form honest about what will
	 * actually be saved.
	 */
	private void applyDefaults() {
		int uom = defaults.getDefaultUOM(null);
		int category = defaults.getDefaultProductCategory(null);
		int tax = defaults.getDefaultTaxCategory(null);

		String next = defaults.nextSequenceValue(null);

		lblDefaults.setValue("Product Code is generated automatically - next code will be "
				+ (next == null ? "unavailable" : next)
				+ ". Other empty fields use: Product Type " + X_M_Product.PRODUCTTYPE_Item
				+ ", UOM " + defaults.getUOMName(uom)
				+ ", Category " + defaults.getProductCategoryName(category)
				+ ", Tax Category " + defaults.getTaxCategoryName(tax));

		// preview the code that will be handed out, without consuming it
		if (txtCode != null && editId == 0)
			txtCode.setValue(next == null ? "" : next);
	}

	@Override
	public void onEvent(Event event) throws Exception {
		if (event.getTarget() == btnSave) {
			onSave();
		} else if (event.getTarget() == btnClear) {
			// clearing while editing would leave an empty form that still says
			// "UPDATE PRODUCT", so editing is ended as well
			leaveEditMode();
		} else if (event.getTarget() == btnRefresh) {
			refreshCreatedList();
		} else if (event.getTarget() == btnCancelEdit) {
			leaveEditMode();
		} else if (event.getTarget() == btnEdit) {
			onEditSelected();
		} else if (event.getTarget() == btnUpdate) {
			onUpdateSelected();
		} else if (event.getTarget() == btnDelete) {
			onDeleteSelected();
		} else {
			// a click on a list row arrives here with the row as target, so the
			// target is resolved by walking up instead of compared to the Listbox
			ProductDefaultService.QuickEntryProduct row = productFromEvent(event);
			if (row != null) {
				selectRow(row);
			}
		}
	}

	/** The product of the currently selected list row, or null when none is. */
	private ProductDefaultService.QuickEntryProduct selectedProduct() {
		if (listCreated == null)
			return null;
		org.zkoss.zul.Listitem item = listCreated.getSelectedItem();
		if (item != null && item.getValue() instanceof ProductDefaultService.QuickEntryProduct)
			return (ProductDefaultService.QuickEntryProduct) item.getValue();
		return null;
	}

	/**
	 * UPDATE on the selected list row. When the form is already editing that row
	 * the values on screen are written back; otherwise the record is loaded
	 * first, so the button works even without pressing EDIT before.
	 */
	private void onUpdateSelected() {
		ProductDefaultService.QuickEntryProduct row = selectedProduct();
		if (row == null) {
			Messagebox.show("Select a product from the list first.", "Update",
					Messagebox.OK, Messagebox.INFORMATION);
			return;
		}
		if (editId != row.getId()) {
			onEditSelected();
			if (editId == 0)
				return;
		}
		onSave();
	}

	/** DELETE on the selected list row, after the user confirmed. */
	private void onDeleteSelected() {
		final ProductDefaultService.QuickEntryProduct row = selectedProduct();
		if (row == null) {
			Messagebox.show("Select a product from the list first.", "Delete",
					Messagebox.OK, Messagebox.INFORMATION);
			return;
		}

		String question = "Delete product " + row.getValue() + " - " + row.getName() + " ?"
				+ "\n\nThis cannot be undone.";

		Dialog.ask("Delete", m_WindowNo, question, new Callback<Boolean>() {
			@Override
			public void onCallback(Boolean result) {
				if (Boolean.TRUE.equals(result))
					doDelete(row);
			}
		});
	}

	private void doDelete(ProductDefaultService.QuickEntryProduct row) {
		try {
			ProductCreationService liveProducts = new ProductCreationService(ctx(), defaultsNow());
			if (!liveProducts.delete(row.getId()))
				return;

			lblResult.setStyle("color: #1f6f3f;");
			lblResult.setValue("Deleted product " + row.getValue() + " (" + row.getName() + ").");
			if (editId == row.getId())
				leaveEditMode();
			refreshCreatedList();
			Messagebox.show("Product deleted successfully.", "Deleted",
					Messagebox.OK, Messagebox.INFORMATION);
		} catch (ProductCreationService.ProductException e) {
			Messagebox.show(e.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
		}
	}

	/** The product of the clicked list row, or null when the click was elsewhere. */
	private ProductDefaultService.QuickEntryProduct productFromEvent(Event event) {
		org.zkoss.zk.ui.Component target = event.getTarget();
		while (target != null) {
			if (target instanceof org.zkoss.zul.Listitem) {
				Object value = ((org.zkoss.zul.Listitem) target).getValue();
				if (value instanceof ProductDefaultService.QuickEntryProduct)
					return (ProductDefaultService.QuickEntryProduct) value;
			}
			if (target == listCreated)
				return null;
			target = target.getParent();
		}
		return null;
	}

	/** Keeps the list selection in sync with the row that was clicked. */
	private void selectRow(ProductDefaultService.QuickEntryProduct row) {
		int count = listCreated.getItemCount();
		for (int i = 0; i < count; i++) {
			org.zkoss.zul.Listitem item = listCreated.getItemAtIndex(i);
			if (item != null && item.getValue() == row) {
				listCreated.setSelectedIndex(i);
				return;
			}
		}
	}

	/**
	 * Loads the selected product into the input fields and switches the form into
	 * edit mode, so Save changes that record instead of creating a new one.
	 */
	private void onEditSelected() {
		ProductDefaultService.QuickEntryProduct row = selectedProduct();
		if (row == null) {
			Messagebox.show("Select a product from the list first.", "Edit",
					Messagebox.OK, Messagebox.INFORMATION);
			return;
		}

		MProduct p = new MProduct(ctx(), row.getId(), null);
		if (!p.load(null)) {
			Messagebox.show("Product " + row.getId() + " could not be read.", "Error",
					Messagebox.OK, Messagebox.ERROR);
			return;
		}

		editId = p.get_ID();
		txtName.setValue(p.getName() == null ? "" : p.getName());
		if (txtCode != null)
			txtCode.setValue(p.getValue() == null ? "" : p.getValue());
		txtDescription.setValue(p.getDescription() == null ? "" : p.getDescription());
		if (fldProductCategory != null)
			fldProductCategory.setValue(Integer.valueOf(p.getM_Product_Category_ID()));
		if (cmbProductType != null)
			cmbProductType.setValue(productTypeName(p.getProductType()));
		if (fldUOM != null)
			fldUOM.setValue(Integer.valueOf(p.getC_UOM_ID()));
		if (fldTaxCategory != null)
			fldTaxCategory.setValue(Integer.valueOf(p.getC_TaxCategory_ID()));
		if (fldGSM != null) {
			int gsm = p.get_ValueAsInt(COL_GSM);
			fldGSM.setValue(gsm > 0 ? Integer.valueOf(gsm) : null);
		}
		if (fldColour != null) {
			int colour = p.get_ValueAsInt(COL_COLOUR);
			fldColour.setValue(colour > 0 ? Integer.valueOf(colour) : null);
		}
		chkSold.setChecked(p.isSold());
		chkPurchased.setChecked(p.isPurchased());
		chkStocked.setChecked(p.isStocked());
		chkActive.setChecked(p.isActive());

		btnSave.setLabel("UPDATE PRODUCT");
		btnCancelEdit.setVisible(true);
		lblResult.setStyle("color: #8a5a00;");
		lblResult.setValue("Editing product " + editId + " - change the values and press UPDATE PRODUCT.");
		showSystemInformation(editId);
	}

	/** Leaves edit mode and goes back to creating a new product. */
	private void leaveEditMode() {
		editId = 0;
		btnSave.setLabel("SAVE PRODUCT");
		btnCancelEdit.setVisible(false);
		lblResult.setStyle("color: #1f6f3f;");
		lblResult.setValue("");
		refreshSystemInformation();
		onClear();
	}

	private void onSave() {
		String name = txtName.getValue();
		if (isBlank(name)) {
			Messagebox.show("Product Name is required.", "Error", Messagebox.OK, Messagebox.ERROR);
			txtName.setFocus(true);
			return;
		}

		// the context is read again here: this runs in the session of the user who
		// pressed the button, so it carries the tenant and organization they are
		// actually working in
		ProductDefaultService liveDefaults = defaultsNow();
		ProductCreationService liveProducts = new ProductCreationService(ctx(), liveDefaults);

		ProductCreationService.ProductInput input = new ProductCreationService.ProductInput();
		input.name = name;
		input.description = txtDescription.getValue();
		input.productCategoryId = asInteger(fldProductCategory == null ? null : fldProductCategory.getValue());
		input.uomId = asInteger(fldUOM == null ? null : fldUOM.getValue());
		input.taxCategoryId = asInteger(fldTaxCategory == null ? null : fldTaxCategory.getValue());
		input.gsmId = fldGSM == null ? null : asInteger(fldGSM.getValue());
		// an empty Product Type means "keep the iDempiere default", so the user
		// value is only sent when one was really chosen
		input.productType = selectedProductType();
		input.sold = Boolean.valueOf(chkSold.isChecked());
		input.purchased = Boolean.valueOf(chkPurchased.isChecked());
		input.stocked = Boolean.valueOf(chkStocked.isChecked());
		input.active = Boolean.valueOf(chkActive.isChecked());

		try {
			boolean wasEdit = editId > 0;
			ProductCreationService.ProductResult result = wasEdit
					? liveProducts.update(editId, input)
					: liveProducts.create(input);
			lblResult.setStyle("color: #1f6f3f; white-space: normal;");
			lblResult.setValue((wasEdit ? "Product updated - " : "Product created - ")
					+ "ID " + result.getId() + ", Code " + result.getValue() + ", Name " + result.getName());
			showSystemInformation(result.getId());
			applyDefaults();
			Messagebox.show((wasEdit ? "Product updated successfully." : "Product created successfully.")
					+ "\n\nID: " + result.getId() + "\nCode: " + result.getValue() + "\nName: " + result.getName(),
					"Saved", Messagebox.OK, Messagebox.INFORMATION);
			// the changed product has to show up in the list right away
			refreshCreatedList();
			leaveEditMode();
		} catch (ProductCreationService.ProductException e) {
			Messagebox.show(e.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
		}
	}

	/** Product Type chosen in the dropdown, or null when the user left it alone. */
	private String selectedProductType() {
		if (cmbProductType == null || cmbProductType.getValue() == null)
			return null;
		String shown = cmbProductType.getValue();
		for (int i = 0; i < PRODUCT_TYPES.length; i++)
			if (PRODUCT_TYPES[i][1].equals(shown))
				return PRODUCT_TYPES[i][0];
		return null;
	}

	/**
	 * Fills the read only System Information segment. Before a product exists the
	 * fields show what iDempiere will decide, never a value the user may change.
	 */
	private void refreshSystemInformation() {
		int clientId = Env.getAD_Client_ID(ctx());
		String orgId = orgLabel();
		String clientName = name("AD_Client", clientId);

		if (txtSysId != null)
			txtSysId.setValue(editId > 0 ? String.valueOf(editId) : "Auto Generated");
		if (txtSysClient != null)
			txtSysClient.setValue(clientId + (clientName == null ? "" : " - " + clientName));
		if (txtSysOrg != null)
			txtSysOrg.setValue(orgId == null || orgId.trim().length() == 0 ? "Current Organization"
					: orgId);
		if (txtSysCreated != null)
			txtSysCreated.setValue("Auto");
		if (txtSysCreatedBy != null)
			txtSysCreatedBy.setValue(String.valueOf(Env.getAD_User_ID(ctx())));
		if (txtSysUpdated != null)
			txtSysUpdated.setValue("Auto");
		if (txtSysUpdatedBy != null)
			txtSysUpdatedBy.setValue(String.valueOf(Env.getAD_User_ID(ctx())));
	}

	/** Shows the stored system values of an existing product. */
	private void showSystemInformation(int productId) {
		MProduct p = new MProduct(ctx(), productId, null);
		if (!p.load(null))
			return;
		if (txtSysId != null)
			txtSysId.setValue(String.valueOf(p.get_ID()));
		if (txtSysClient != null)
			txtSysClient.setValue(String.valueOf(p.getAD_Client_ID()));
		if (txtSysOrg != null)
			txtSysOrg.setValue(String.valueOf(p.getAD_Org_ID()));
		if (txtSysCreated != null)
			txtSysCreated.setValue(String.valueOf(p.getCreated()));
		if (txtSysCreatedBy != null)
			txtSysCreatedBy.setValue(String.valueOf(p.getCreatedBy()));
		if (txtSysUpdated != null)
			txtSysUpdated.setValue(String.valueOf(p.getUpdated()));
		if (txtSysUpdatedBy != null)
			txtSysUpdatedBy.setValue(String.valueOf(p.getUpdatedBy()));
	}

	/** Readable name of the organization of the session. */
	private String orgLabel() {
		int orgId = Env.getAD_Org_ID(ctx());
		if (orgId <= 0)
			return null;
		String value = DB.getSQLValueString(null, "SELECT Name FROM AD_Org WHERE AD_Org_ID=?",
				Integer.valueOf(orgId));
		return value == null ? String.valueOf(orgId) : value + " (" + orgId + ")";
	}

	/** Resets the form to a clean new record; database data is never touched. */
	private void onClear() {
		txtName.setValue("");
		txtDescription.setValue("");
		if (fldProductCategory != null)
			fldProductCategory.setValue(null);
		if (fldUOM != null)
			fldUOM.setValue(null);
		if (fldTaxCategory != null)
			fldTaxCategory.setValue(null);
		if (fldGSM != null)
			fldGSM.setValue(null);
		chkSold.setChecked(true);
		chkPurchased.setChecked(true);
		chkStocked.setChecked(true);
		chkActive.setChecked(true);
		applyDefaults();
		txtName.setFocus(true);
	}

	private static Integer asInteger(Object value) {
		if (value instanceof Integer)
			return (Integer) value;
		return null;
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().length() == 0;
	}
}
