package org.mycompany.requisition.approval.ui;

import java.util.List;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Column;
import org.adempiere.webui.component.Columns;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.component.Textbox;
import org.zkoss.zul.Caption;
import org.zkoss.zul.Cell;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Vlayout;

/**
 * Layout helpers shared by the approval window and its sections.
 * <p>
 * They exist so that every section is built the same way. Two ZK rules make this
 * worth centralising, and both have bitten this plugin before:
 *
 * <ul>
 * <li>a {@code Rows} component only accepts a {@code Grid} as its parent, so every
 * field grid is created through {@link #grid(int)} instead of by hand</li>
 * <li>the iDempiere {@code Combobox} appends by label, so the value is carried on
 * the {@code Comboitem} through {@link #combo(org.zkoss.zul.Component, String, Object, Object)}</li>
 * </ul>
 *
 * <p>
 * Labels are right aligned and of a fixed width so that a long section still
 * lines up in one column instead of drifting.
 */
public final class UiLayouts {

	/** Width every label column uses, so sections line up with each other. */
	private static final String LABEL_WIDTH = "150px";

	/** Style of a section title bar. */
	private static final String CAPTION_STYLE = "font-weight: bold;";

	/** The blue title bar of a standard iDempiere window. */
	private static final String TITLE_BAR_STYLE = "background: #d3e6f5; padding: 8px 12px; border: none;";

	/**
	 * Width of the label column of a compact two column frame.
	 * <p>
	 * Shorter than the value column on purpose. A compact frame has to hold four
	 * fields side by side without forcing a horizontal scrollbar, and the labels
	 * here are short words rather than sentences.
	 */
	private static final String COMPACT_LABEL_WIDTH = "110px";

	private UiLayouts() {
		// helper class
	}

	// ─────────────────────────────────────────────────────────────
	//  Frames
	// ─────────────────────────────────────────────────────────────

	/**
	 * A titled section.
	 *
	 * @param title  the caption
	 * @param open   whether the section starts expanded
	 * @param parent where to append it
	 * @return the frame, so the caller can add a note or a toolbar to it
	 */
	public static Groupbox frame(String title, boolean open, Vlayout parent) {
		Groupbox box = new Groupbox();
		box.setOpen(open);
		box.setStyle("margin: 4px; padding: 6px;");
		Caption caption = new Caption(title);
		caption.setStyle(CAPTION_STYLE);
		box.appendChild(caption);
		parent.appendChild(box);
		return box;
	}

	/**
	 * A collapsible section for information the user only occasionally needs, so
	 * the sections above it do not get pushed out of sight.
	 */
	public static Groupbox optionalFrame(String title, Vlayout parent) {
		Groupbox box = frame(title, false, parent);
		box.setStyle("margin: 4px; padding: 6px;");
		return box;
	}

	/**
	 * Puts content into the single scrolling area of the form.
	 * <p>
	 * A {@code Div} with {@code overflow-y: auto} rather than a ZK scroll box,
	 * because the frames, the buttons and the lists then scroll together as one
	 * continuous page instead of every section getting its own scrollbar.
	 *
	 * @param content the sections to make scrollable
	 * @return the area, already holding the content
	 */
	public static Div scrollAreaAnd(Vlayout content) {
		Div div = scrollArea();
		if (content != null)
			div.appendChild(content);
		return div;
	}

	/** A plain scrolling area, for a caller that fills it itself. */
	public static Div scrollArea() {
		Div div = new Div();
		div.setWidth("100%");
		div.setStyle("width: 100%; height: 100%; overflow-y: auto; overflow-x: hidden; padding: 4px;");
		return div;
	}

	// ─────────────────────────────────────────────────────────────
	//  Field grids
	// ─────────────────────────────────────────────────────────────

	/**
	 * A field grid together with the row container the caller fills.
	 * <p>
	 * The two cannot be created separately: ZK only accepts a {@code Grid} as the
	 * parent of a rows component, so the rows have to come from
	 * {@link Grid#newRows()} while the grid is being built. Handing both back in
	 * one object keeps that detail out of every section.
	 */
	public static final class FieldGrid {

		/** The grid to append to the section. */
		public final Grid grid;

		/** The rows to append fields to. */
		public final Rows rows;

		FieldGrid(Grid grid, Rows rows) {
			this.grid = grid;
			this.rows = rows;
		}
	}

	/**
	 * A field grid of the given column count.
	 *
	 * @param columns how many label and value pairs fit on one line
	 */
	public static FieldGrid grid(int columns) {
		Grid grid = new Grid();
		grid.setWidth("100%");
		grid.makeNoStrip();
		grid.setStyle("width: 100%;");

		Columns cols = new Columns();
		for (int i = 0; i < columns; i++)
			cols.appendChild(new Column());
		grid.appendChild(cols);

		return new FieldGrid(grid, grid.newRows());
	}

	/** A row of a field grid. */
	public static Row row() {
		return new Row();
	}

	/**
	 * A heading that spans the row, used for a group of fields inside a section.
	 * <p>
	 * The span is four because every section of this form uses the four column
	 * grid, and a narrower span would break the alignment of the fields under it.
	 */
	public static Cell headingCell(Row row, String text) {
		Cell cell = new Cell();
		cell.setColspan(4);
		Label label = new Label(text);
		label.setStyle("font-weight: bold; padding-top: 6px;");
		cell.appendChild(label);
		row.appendChild(cell);
		return cell;
	}

	/** A label cell, right aligned so the values line up. */
	public static Cell labelCell(Row row, String text) {
		return labelCell(row, text, LABEL_WIDTH);
	}

	/**
	 * A label cell of an explicit width.
	 * <p>
	 * The label is right aligned against the field, which is what makes a standard
	 * iDempiere window look like one: the eye follows the straight edge of the
	 * labels straight down the column instead of hunting for the field each time.
	 * A mandatory field is marked with an asterisk, as iDempiere does.
	 *
	 * @param width the label column width, for example a compact 110px
	 */
	public static Cell labelCell(Row row, String text, String width) {
		Cell cell = new Cell();
		cell.setWidth(width);
		cell.setStyle("text-align: right; padding-right: 8px; white-space: nowrap;");
		cell.appendChild(new Label(text));
		row.appendChild(cell);
		return cell;
	}

	/** A mandatory label cell, marked the way iDempiere marks a required field. */
	public static Cell requiredLabelCell(Row row, String text, String width) {
		Cell cell = labelCell(row, text, width);
		Label star = new Label("*");
		star.setStyle("color: #b00020; font-weight: bold;");
		cell.appendChild(star);
		return cell;
	}

	/**
	 * A value cell sized for a frame that holds two field pairs across the width.
	 * <p>
	 * The field is left with a little room on the right, which is where iDempiere
	 * puts the little zoom icon of a reference field. Nothing here is given a fixed
	 * pixel width, so a frame stays inside the window on a narrow screen instead of
	 * forcing the horizontal scrollbar the brief asks to avoid.
	 */
	public static Cell fieldCell(Row row, org.zkoss.zk.ui.Component component) {
		Cell cell = new Cell();
		cell.setStyle("padding: 2px 14px 2px 0;");
		if (component != null)
			cell.appendChild(component);
		row.appendChild(cell);
		return cell;
	}

	/**
	 * A numeric field, right aligned, the way a standard window shows an amount.
	 * <p>
	 * Uses the ZK decimal box because this iDempiere build has no
	 * {@code org.adempiere.webui.component.Decimalbox}; verified against the jars on
	 * the classpath rather than assumed. An empty field reads back as null, which
	 * is what the services treat as "not set" - a limit of zero and no limit are
	 * genuinely different and must not collapse into each other.
	 */
	public static org.zkoss.zul.Decimalbox decimal(String width) {
		org.zkoss.zul.Decimalbox box = new org.zkoss.zul.Decimalbox();
		box.setWidth(width);
		box.setScale(2);
		box.setStyle("text-align: right;");
		return box;
	}

	/** The value of a numeric field, or null when it is empty or not a number. */
	public static java.math.BigDecimal decimalOf(org.zkoss.zul.Decimalbox box) {
		if (box == null)
			return null;
		try {
			return box.getValue();
		} catch (RuntimeException e) {
			// ZK raises WrongValueException on a half typed number; the window then
			// reports it as a validation error rather than losing the value
			return null;
		}
	}

	/** The blue title bar of a window: title on the first line, hint under it. */
	public static Vlayout titleBar(String title, String hint) {
		Vlayout bar = new Vlayout();
		bar.setSpacing("2px");
		bar.setStyle(TITLE_BAR_STYLE);
		org.adempiere.webui.component.Label titleLabel = new org.adempiere.webui.component.Label(title);
		titleLabel.setStyle("font-size: 14px; font-weight: bold; color: #1a1a1a;");
		bar.appendChild(titleLabel);
		if (hint != null && hint.trim().length() > 0) {
			org.adempiere.webui.component.Label hintLabel = new org.adempiere.webui.component.Label(hint);
			hintLabel.setStyle("color: #444444; font-size: 11px;");
			bar.appendChild(hintLabel);
		}
		return bar;
	}

	/** A value cell holding a component. */
	public static Cell valueCell(Row row, org.zkoss.zk.ui.Component component) {
		Cell cell = new Cell();
		cell.setStyle("padding: 2px 8px 2px 2px;");
		if (component != null)
			cell.appendChild(component);
		row.appendChild(cell);
		return cell;
	}

	/**
	 * A value cell that fills the rest of the row, for a description or a note
	 * rather than a short value.
	 * <p>
	 * The span goes on the cell and not on the control: a ZK component cannot span
	 * grid columns by itself.
	 */
	public static Cell wideValueCell(Row row, org.zkoss.zk.ui.Component component, int columns) {
		Cell cell = new Cell();
		cell.setColspan(columns);
		cell.setStyle("padding: 2px 8px 2px 2px;");
		if (component != null)
			cell.appendChild(component);
		row.appendChild(cell);
		return cell;
	}

	/** A value cell holding a read only, muted component. */
	public static Cell readOnlyCell(Row row, org.zkoss.zk.ui.Component component) {
		Cell cell = valueCell(row, component);
		cell.setStyle("padding: 2px 8px 2px 2px; color: #555555;");
		if (component instanceof Textbox)
			((Textbox) component).setReadonly(true);
		return cell;
	}

	/** A hint cell that spans the row, for warnings and explanations. */
	public static Cell hintCell(Row row, String text) {
		Cell cell = new Cell();
		cell.setColspan(4);
		Label label = new Label(text);
		label.setStyle("color: #a06000; padding: 2px 0 4px 0;");
		cell.appendChild(label);
		row.appendChild(cell);
		return cell;
	}

	// ─────────────────────────────────────────────────────────────
	//  Controls
	// ─────────────────────────────────────────────────────────────

	/** A text box for a section. */
	public static Textbox text() {
		Textbox box = new Textbox();
		box.setWidth("100%");
		return box;
	}

	/** A compact text box, for codes and dates. */
	public static Textbox smallText() {
		Textbox box = new Textbox();
		box.setWidth("140px");
		return box;
	}

	/**
	 * Adds one option to a dropdown.
	 * <p>
	 * The iDempiere combobox appends by label, so the value travels on the item.
	 * A value is always set, even when it is null: the iDempiere {@code setValue}
	 * walks the items and compares their values, and an item without one would
	 * break that walk.
	 *
	 * @param combo where to add it
	 * @param label what the user reads
	 * @param value what the code stores, may be null
	 * @param selected whether this option starts selected
	 * @return the item, so a caller can select it afterwards
	 */
	public static Comboitem combo(Combobox combo, String label, Object value, boolean selected) {
		Comboitem item = combo.appendItem(label);
		item.setValue(value);
		if (selected)
			combo.setSelectedItem(item);
		return item;
	}

	/** A dropdown that fills the available width. */
	public static Combobox comboBox() {
		Combobox combo = new Combobox();
		combo.setWidth("100%");
		return combo;
	}

	/**
	 * Reads the value of the selected option.
	 * <p>
	 * The selected item is asked directly rather than {@code combo.getValue()},
	 * which on this combobox returns the selected <em>label</em> as a string and
	 * would therefore give the code back as text.
	 *
	 * @return the stored value, or null when nothing is selected
	 */
	public static Object comboValue(Combobox combo) {
		if (combo == null)
			return null;
		Comboitem item = combo.getSelectedItem();
		return item == null ? null : item.getValue();
	}

	/**
	 * Reads the value of the selected option as an id.
	 *
	 * @return the id, or 0 when nothing meaningful is selected
	 */
	public static int comboId(Combobox combo) {
		Object value = comboValue(combo);
		if (value == null)
			return 0;
		try {
			return Integer.parseInt(value.toString().trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Selects the option that carries this value.
	 * <p>
	 * Done by walking the items instead of through {@code setValue}, because the
	 * iDempiere overload compares values of mixed types and would need the caller
	 * to hand it exactly the type the item was created with.
	 *
	 * @return true when an option was found and selected
	 */
	public static boolean selectValue(Combobox combo, Object value) {
		if (combo == null)
			return false;
		if (value == null) {
			combo.setSelectedItem(null);
			return false;
		}
		for (Comboitem item : combo.getItems()) {
			Object candidate = item.getValue();
			if (candidate == null)
				continue;
			if (candidate.toString().trim().equalsIgnoreCase(value.toString().trim())) {
				combo.setSelectedItem(item);
				return true;
			}
		}
		combo.setSelectedItem(null);
		return false;
	}

	/**
	 * Fills a dropdown from {@code id|label} rows.
	 * <p>
	 * Lives here rather than on one panel because three panels need it and the
	 * row format is the same everywhere: the label goes in as text and the id
	 * travels on the item.
	 *
	 * @param combo      where to fill
	 * @param rows       the {@code id|label} rows
	 * @param withEmpty whether to offer a "not set" option first
	 */
	public static void fill(Combobox combo, List<String> rows, boolean withEmpty) {
		clearItems(combo);
		if (withEmpty)
			combo(combo, "- not set -", Integer.valueOf(0), true);
		for (String row : rows) {
			int bar = row.indexOf('|');
			String label = bar < 0 ? row : row.substring(bar + 1);
			combo(combo, label, Integer.valueOf(idOf(row)), false);
		}
	}

	/**
	 * The id of an {@code id|label} row.
	 *
	 * @return the id, or 0 when the row is empty or malformed
	 */
	public static int idOf(String row) {
		if (row == null)
			return 0;
		int bar = row.indexOf('|');
		if (bar <= 0)
			return 0;
		try {
			return Integer.parseInt(row.substring(0, bar).trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * Empties a dropdown.
	 * <p>
	 * Uses the combobox's own {@code removeItemAt} rather than the generic child
	 * removal. A combobox keeps state of its own per item, so emptying it through
	 * the plain component model can leave that state behind, and the dropdown then
	 * shows fewer items than it has while still reporting the old count.
	 *
	 * @param combo the dropdown to empty, may be null
	 */
	public static void clearItems(Combobox combo) {
		if (combo == null)
			return;
		for (int i = combo.getItemCount() - 1; i >= 0; i--)
			combo.removeItemAt(i);
	}

	/**
	 * Empties a container safely.
	 * <p>
	 * The children are removed one at a time through {@code removeChild} rather
	 * than by clearing the list the getter hands out. Clearing that list drops the
	 * children without telling ZK about it, and they keep pointing at a container
	 * that no longer knows them - which surfaces much later as a component that will
	 * not render, or a grid that rejects a child it never had.
	 *
	 * @param container the component to empty, may be null
	 */
	public static void clear(org.zkoss.zk.ui.Component container) {
		if (container == null)
			return;
		List<org.zkoss.zk.ui.Component> children =
				new java.util.ArrayList<org.zkoss.zk.ui.Component>(container.getChildren());
		for (org.zkoss.zk.ui.Component child : children)
			container.removeChild(child);
	}

	/** Reads an integer from a text box, or null when it is empty or not a number. */
	public static Integer integerOf(org.adempiere.webui.component.Textbox box) {
		if (box == null)
			return null;
		String value = box.getValue();
		if (value == null || value.trim().length() == 0)
			return null;
		try {
			return Integer.valueOf(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Reads a trimmed text box, or null when it is empty. */
	public static String textOf(org.adempiere.webui.component.Textbox box) {
		if (box == null)
			return null;
		String value = box.getValue();
		if (value == null || value.trim().length() == 0)
			return null;
		return value.trim();
	}

	/**
	 * Reads a date box as a database timestamp.
	 * <p>
	 * The ZK date box stores a {@link java.util.Date}, not a timestamp, so a plain
	 * cast would fail on exactly the value this form puts there. Anything that is
	 * not a usable date becomes null, which the services treat as "not set".
	 *
	 * @return the timestamp, or null when the box is empty
	 */
	public static java.sql.Timestamp dateOf(org.adempiere.webui.component.Datebox box) {
		if (box == null)
			return null;
		Object value;
		try {
			value = box.getValue();
		} catch (RuntimeException e) {
			// ZK raises WrongValueException on a malformed entry rather than
			// returning null, and a half typed date must not break the form
			return null;
		}
		if (value == null)
			return null;
		if (value instanceof java.sql.Timestamp)
			return (java.sql.Timestamp) value;
		if (value instanceof java.util.Date)
			return new java.sql.Timestamp(((java.util.Date) value).getTime());
		return null;
	}

	// ─────────────────────────────────────────────────────────────
	//  Rows of buttons and status
	// ─────────────────────────────────────────────────────────────

	/** A horizontal strip of buttons. */
	public static Hlayout toolbar() {
		Hlayout layout = new Hlayout();
		layout.setSpacing("6px");
		layout.setStyle("padding: 6px 4px;");
		return layout;
	}

	/** A button of a toolbar. */
	public static Button button(String text) {
		return new Button(text);
	}

	/**
	 * Sets a control on or off, so an action that does not apply to the current
	 * selection cannot be used by mistake.
	 */
	public static void enable(org.zkoss.zk.ui.Component component, boolean enabled) {
		if (component == null)
			return;
		if (component instanceof Button)
			((Button) component).setDisabled(!enabled);
		else if (component instanceof org.zkoss.zul.Combobox)
			((org.zkoss.zul.Combobox) component).setDisabled(!enabled);
		else if (component instanceof org.zkoss.zul.Textbox)
			((org.zkoss.zul.Textbox) component).setDisabled(!enabled);
	}
}
