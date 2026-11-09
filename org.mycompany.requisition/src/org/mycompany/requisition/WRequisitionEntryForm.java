package org.mycompany.requisition;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.component.Window;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.model.MAttachment;
import org.compiere.model.MRequisition;
import org.compiere.model.MRequisitionLine;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.UploadEvent;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Caption;
import org.zkoss.zul.Center;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Groupbox;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.South;

/**
 * Requisition Form: header (Type, Location, Warehouse, Priority, dates, description,
 * attachment), line entry (Segment, Department, Parent Category, Sub-Category, Product,
 * Qty, Rate) and the list of lines added, then OK saves, Cancel closes.
 * <p>
 * It saves a standard Drafted M_Requisition with M_RequisitionLine rows (through the model
 * classes), so the approval inbox (trigger on M_Requisition), "Create RFQ From PR" and the
 * rest of this plugin work on it unchanged. The extra fields live in plain columns created by
 * db/16_requisition_entry_form.sql:
 * <pre>
 *   M_Requisition.XX_Location          (Location)
 *   M_RequisitionLine.XX_Segment       (Segment)
 *   M_RequisitionLine.XX_Department_ID (Department = C_Activity)
 *   M_RequisitionLine.XX_DateRequired  (Date Required (Line))
 * </pre>
 * Type = the document type (C_DocType, base type Purchase Requisition), so the approval
 * matrix, which is per document type, applies. Priority = the standard Priority list.
 * Specification goes to the line description.
 */
public class WRequisitionEntryForm extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WRequisitionEntryForm.class);

	/** AD_Reference names seeded by db/16_requisition_entry_form.sql */
	static final String REF_LOCATION = "XX Requisition Location";
	static final String REF_SEGMENT = "XX Requisition Segment";
	/** standard "_PriorityRule" list */
	private static final int REF_PRIORITY = 154;

	private static final String BORDER = "#9fb4cc";

	private final int clientId = Env.getAD_Client_ID(Env.getCtx());
	private final int orgId = Env.getAD_Org_ID(Env.getCtx());
	private final int userId = Env.getAD_User_ID(Env.getCtx());

	// ---- header
	private final Label lblOrg = new Label();
	private final Label lblUser = new Label();
	private Listbox cbType;
	private Listbox cbLocation;
	private Listbox cbPriority;
	private Picker pWarehouse;
	private final Datebox dtDoc = new Datebox();
	private final Datebox dtRequired = new Datebox();
	private final Textbox txtDescription = new Textbox();
	private final Button btnAttach = new Button("Attachment");
	private final Label lblAttach = new Label();
	private final List<Media> attachments = new ArrayList<Media>();

	// ---- line entry
	private Listbox cbSegment;
	private Picker pDept;
	private Picker pParent;
	private Picker pSub;
	private Picker pProduct;
	private final Textbox txtSpec = new Textbox();
	private final Datebox dtLine = new Datebox();
	private final Label lblUom = new Label();
	private final Label lblAvail = new Label("0");
	private final Decimalbox decQty = new Decimalbox();
	private final Decimalbox decRate = new Decimalbox();
	private final Button btnAdd = new Button("+  Add Product");

	// ---- lines
	private final Listbox lineList = new Listbox();
	private final List<Line> lines = new ArrayList<Line>();

	private final Button btnOk = new Button("\u2714");
	private final Button btnCancel = new Button("\u2716");

	/** one product line waiting to be saved */
	private static final class Line {
		String segVal = "";
		String segName = "";
		int deptId;
		String deptName = "";
		int productId;
		String code = "";
		String name = "";
		String uom = "";
		String spec = "";
		Timestamp dateReq;
		BigDecimal avail = BigDecimal.ZERO;
		BigDecimal qty = BigDecimal.ZERO;
		BigDecimal rate = BigDecimal.ZERO;
	}

	// =====================================================================
	// init
	// =====================================================================

	@Override
	protected void initForm() {
		org.mycompany.requisition.ui.XxTheme.wrap(this);
		try {
			buildUi();
			loadData();
			if (!columnExists("M_Requisition", "XX_Location") || !columnExists("M_RequisitionLine", "XX_Segment")
					|| !columnExists("M_RequisitionLine", "XX_Department_ID")
					|| !columnExists("M_RequisitionLine", "XX_DateRequired"))
				Messagebox.show("The extra requisition columns are missing.\nRun db/16_requisition_entry_form.sql, "
						+ "then reopen this form.", "Setup needed", Messagebox.OK, Messagebox.EXCLAMATION);
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			throw new AdempiereException(e);
		}
	}

	// =====================================================================
	// UI
	// =====================================================================

	private void buildUi() {
		Borderlayout main = new Borderlayout();
		appendChild(main);
		ZKUpdateUtil.setWidth(this, "99%");
		ZKUpdateUtil.setHeight(this, "100%");
		ZKUpdateUtil.setWidth(main, "100%");
		ZKUpdateUtil.setHeight(main, "100%");

		cbType = newSelect();
		cbLocation = newSelect();
		cbPriority = newSelect();
		cbSegment = newSelect();

		pWarehouse = new Picker("Warehouse",
				"SELECT M_Warehouse_ID, Value, Name FROM M_Warehouse WHERE IsActive='Y' AND AD_Client_ID=" + clientId,
				"Name", false, null);
		pDept = new Picker("Department",
				"SELECT C_Activity_ID, Value, Name FROM C_Activity WHERE IsActive='Y' AND IsSummary='N' "
						+ "AND AD_Client_ID IN (0," + clientId + ")",
				"Name", false, null);
		pParent = new Picker("Parent Category",
				"SELECT M_Product_Category_ID, Value, Name FROM M_Product_Category WHERE IsActive='Y' "
						+ "AND AD_Client_ID IN (0," + clientId + ")",
				"Name", false,
				p -> " AND (M_Product_Category_Parent_ID IS NULL OR M_Product_Category_Parent_ID=M_Product_Category_ID)");
		pSub = new Picker("Sub-Category",
				"SELECT M_Product_Category_ID, Value, Name FROM M_Product_Category WHERE IsActive='Y' "
						+ "AND AD_Client_ID IN (0," + clientId + ")",
				"Name", false, p -> {
					if (pParent.id > 0) {
						p.add(Integer.valueOf(pParent.id));
						return " AND M_Product_Category_Parent_ID=? AND M_Product_Category_ID<>M_Product_Category_Parent_ID";
					}
					return " AND M_Product_Category_Parent_ID IS NOT NULL "
							+ "AND M_Product_Category_Parent_ID<>M_Product_Category_ID";
				});
		pProduct = new Picker("Product",
				"SELECT M_Product_ID, Value, Name FROM M_Product WHERE IsActive='Y' AND IsSummary='N' "
						+ "AND AD_Client_ID IN (0," + clientId + ")",
				"Name", true, p -> {
					if (pSub.id > 0) {
						p.add(Integer.valueOf(pSub.id));
						return " AND M_Product_Category_ID=?";
					}
					if (pParent.id > 0) {
						p.add(Integer.valueOf(pParent.id));
						return " AND M_Product_Category_ID IN (WITH RECURSIVE t AS ("
								+ "SELECT M_Product_Category_ID FROM M_Product_Category WHERE M_Product_Category_ID=? "
								+ "UNION SELECT c.M_Product_Category_ID FROM M_Product_Category c "
								+ "JOIN t ON c.M_Product_Category_Parent_ID=t.M_Product_Category_ID) "
								+ "SELECT M_Product_Category_ID FROM t)";
					}
					return "";
				});

		North north = new North();
		ZKUpdateUtil.setVflex(north, "min");
		main.appendChild(north);
		Div top = new Div();
		top.appendChild(buildHeader());
		top.appendChild(buildLineEntry());
		north.appendChild(top);

		Center center = new Center();
		main.appendChild(center);
		Groupbox gb = new Groupbox();
		gb.setStyle("margin:6px 8px;border:1px solid " + BORDER + ";background:#e3edf7;");
		Caption cap = new Caption("Requisition Line");
		cap.setStyle("font-weight:600;");
		gb.appendChild(cap);
		buildLineList();
		gb.appendChild(lineList);
		ZKUpdateUtil.setVflex(gb, "1");
		ZKUpdateUtil.setHflex(gb, "1");
		center.appendChild(gb);

		South south = new South();
		ZKUpdateUtil.setVflex(south, "min");
		main.appendChild(south);
		Div foot = new Div();
		foot.setStyle("text-align:right;padding:6px 14px;");
		btnOk.setStyle("width:58px;height:32px;background:#fff;color:#2e7d32;font-weight:700;"
				+ "border:1px solid #b9c7d6;border-radius:3px;margin-right:6px;");
		btnCancel.setStyle("width:58px;height:32px;background:#fff;color:#c62828;font-weight:700;"
				+ "border:1px solid #b9c7d6;border-radius:3px;");
		foot.appendChild(btnOk);
		foot.appendChild(btnCancel);
		south.appendChild(foot);

		wireEvents();
	}

	/** header panel: 4 label/field pairs per row, same order as the screen */
	private Groupbox buildHeader() {
		Groupbox gb = new Groupbox();
		gb.setStyle("margin:6px 8px 0;border:1px solid " + BORDER + ";background:#e3edf7;");
		Caption cap = new Caption("Requisition");
		cap.setStyle("font-weight:600;");
		gb.appendChild(cap);

		Div g = gridDiv("8% 17% 8% 17% 8% 17% 8% 17%");
		// row 0
		g.appendChild(lbl("Organization", true, 0, 1));
		lblOrg.setStyle("font-size:12px;");
		g.appendChild(cell(boxed(lblOrg, "#e9edf1"), 0, 1));
		g.appendChild(lbl("Type", true, 0, 1));
		g.appendChild(cell(cbType, 0, 1));
		g.appendChild(lbl("Location", true, 0, 1));
		g.appendChild(cell(cbLocation, 0, 1));
		g.appendChild(lbl("Warehouse", false, 0, 1));
		g.appendChild(cell(pWarehouse.box, 0, 1));
		// row 1
		g.appendChild(lbl("User", false, 1, 1));
		lblUser.setStyle("font-size:12px;");
		g.appendChild(cell(lblUser, 1, 1));
		g.appendChild(lbl("Priority", true, 1, 1));
		g.appendChild(cell(cbPriority, 1, 1));
		g.appendChild(lbl("Document Date", true, 1, 1));
		dtDoc.setWidth("140px");
		g.appendChild(cell(dtDoc, 1, 1));
		g.appendChild(lbl("Date Required", true, 1, 1));
		dtRequired.setWidth("140px");
		g.appendChild(cell(dtRequired, 1, 1));
		// row 2: description + attachment
		g.appendChild(lbl("Description", false, 2, 1));
		txtDescription.setMultiline(true);
		txtDescription.setRows(1);
		txtDescription.setStyle("resize:vertical;");
		ZKUpdateUtil.setHflex(txtDescription, "1");
		g.appendChild(cell(txtDescription, 2, 6));
		btnAttach.setUpload("true,multiple=true");
		btnAttach.setStyle("width:100%;height:28px;color:#1d4ed8;font-weight:600;background:#fff;"
				+ "border:1px solid #b9c7d6;");
		lblAttach.setStyle("font-size:11px;color:#475569;");
		Div att = new Div();
		att.setStyle("width:100%;");
		att.appendChild(btnAttach);
		att.appendChild(lblAttach);
		g.appendChild(cell(att, 2, 1));
		gb.appendChild(g);
		return gb;
	}

	/** line entry panel (Segment ... Add Product) */
	private Div buildLineEntry() {
		Div box = new Div();
		box.setStyle("margin:6px 8px 0;border:1px solid " + BORDER + ";background:#e3edf7;");
		Div g = gridDiv("6% 11% 7% 14% 8% 28% 11% 15%");
		// row 0
		g.appendChild(lbl("Segment", true, 0, 1));
		g.appendChild(cell(cbSegment, 0, 1));
		g.appendChild(lbl("Department", true, 0, 1));
		g.appendChild(cell(pDept.box, 0, 1));
		g.appendChild(lbl("Specification", false, 0, 1));
		txtSpec.setMultiline(true);
		txtSpec.setRows(1);
		txtSpec.setStyle("resize:vertical;");
		ZKUpdateUtil.setHflex(txtSpec, "1");
		g.appendChild(cell(txtSpec, 0, 1));
		g.appendChild(lbl("Date Required (Line)", false, 0, 1));
		dtLine.setWidth("140px");
		g.appendChild(cell(dtLine, 0, 1));
		// row 1
		g.appendChild(lbl("Parent Category*", true, 1, 1));
		g.appendChild(cell(pParent.box, 1, 1));
		g.appendChild(lbl("Sub-Category", false, 1, 1));
		g.appendChild(cell(pSub.box, 1, 1));
		g.appendChild(lbl("Product", true, 1, 1));
		g.appendChild(cell(pProduct.box, 1, 1));
		g.appendChild(lbl("UOM", false, 1, 1));
		lblUom.setStyle("font-size:12px;");
		g.appendChild(cell(lblUom, 1, 1));
		// row 2
		g.appendChild(lbl("Available Qty", false, 2, 1));
		lblAvail.setStyle("font-size:12px;");
		g.appendChild(cell(lblAvail, 2, 1));
		g.appendChild(lbl("Qty", true, 2, 1));
		decQty.setWidth("110px");
		g.appendChild(cell(decQty, 2, 1));
		g.appendChild(lbl("Rate", false, 2, 1));
		decRate.setWidth("110px");
		g.appendChild(cell(decRate, 2, 1));
		g.appendChild(cell(new Label(), 2, 1));
		btnAdd.setStyle("height:28px;background:#fff;color:#1d4ed8;font-weight:600;border:1px solid #b9c7d6;");
		Div addBox = new Div();
		addBox.setStyle("text-align:right;width:100%;");
		addBox.appendChild(btnAdd);
		g.appendChild(cell(addBox, 2, 1));
		box.appendChild(g);
		return box;
	}

	private void buildLineList() {
		Listhead head = new Listhead();
		String[][] cols = { { "Line", "55px" }, { "Segment", "130px" }, { "Department", "140px" },
				{ "Product Code", "120px" }, { "Product Name", null }, { "UOM", "60px" },
				{ "Specification", "170px" }, { "Date Required", "100px" }, { "Available", "80px" },
				{ "Qty", "80px" }, { "Rate", "80px" }, { "Amount", "95px" }, { "", "50px" } };
		for (String[] c : cols) {
			Listheader h = new Listheader(c[0]);
			if (c[1] != null)
				h.setWidth(c[1]);
			else
				h.setHflex("1");
			head.appendChild(h);
		}
		lineList.appendChild(head);
		ZKUpdateUtil.setHflex(lineList, "1");
		ZKUpdateUtil.setVflex(lineList, "1");
	}

	// ---- small layout helpers

	private Div gridDiv(String columns) {
		Div g = new Div();
		g.setStyle("display:grid;grid-template-columns:" + columns + ";width:100%;");
		return g;
	}

	private String rowBg(int row) {
		return row % 2 == 0 ? "#eaf2fb" : "#dbe7f3";
	}

	private Div lbl(String text, boolean required, int row, int span) {
		Label l = new Label(text);
		l.setStyle("font-size:12px;font-weight:600;color:" + (required ? "#d32f2f" : "#1f2937") + ";");
		return cell(l, row, span);
	}

	private Div cell(Component c, int row, int span) {
		Div d = new Div();
		d.setStyle("display:flex;align-items:center;box-sizing:border-box;min-height:30px;padding:3px 6px;"
				+ "background:" + rowBg(row) + ";" + (span > 1 ? "grid-column:span " + span + ";" : ""));
		d.appendChild(c);
		return d;
	}

	/** a grey read-only box that holds a label */
	private Div boxed(Label l, String bg) {
		Div d = new Div();
		d.setStyle("width:100%;box-sizing:border-box;padding:3px 6px;border:1px solid #c5ced8;background:" + bg
				+ ";");
		d.appendChild(l);
		return d;
	}

	/** native drop-down, first entry empty */
	private Listbox newSelect() {
		Listbox lb = new Listbox();
		lb.setMold("select");
		lb.appendItem("", "");
		lb.setSelectedIndex(0);
		ZKUpdateUtil.setHflex(lb, "1");
		return lb;
	}

	// =====================================================================
	// search picker: read-only box + magnifier, opens a search popup
	// =====================================================================

	private interface Filter {
		/** extra " AND ..." for the query; add its ? values to params */
		String where(List<Object> params);
	}

	private final class Picker {
		final Div box = new Div();
		private final Label text = new Label();
		private final Button btn = new Button("\uD83D\uDD0D");
		private final String title;
		private final String select;
		private final String order;
		private final boolean showCode;
		private final Filter filter;
		int id;
		String code = "";
		String name = "";
		Runnable onChange;

		Picker(String title, String select, String order, boolean showCode, Filter filter) {
			this.title = title;
			this.select = select;
			this.order = order;
			this.showCode = showCode;
			this.filter = filter;
			box.setStyle("display:flex;width:100%;height:26px;box-sizing:border-box;border:1px solid #b6c3d1;"
					+ "background:#fff;");
			text.setStyle("flex:1;min-width:0;padding:4px 6px;font-size:12px;overflow:hidden;"
					+ "white-space:nowrap;text-overflow:ellipsis;");
			btn.setStyle("width:30px;height:24px;border:none;border-left:1px solid #b6c3d1;background:#f3f6fa;"
					+ "cursor:pointer;");
			box.appendChild(text);
			box.appendChild(btn);
			btn.addEventListener(Events.ON_CLICK, e -> open());
			text.addEventListener(Events.ON_CLICK, e -> open());
		}

		void set(int newId, String newCode, String newName, boolean fire) {
			id = newId;
			code = newCode == null ? "" : newCode;
			name = newName == null ? "" : newName;
			text.setValue(id <= 0 ? "" : (showCode ? code + " - " + name : name));
			if (fire && onChange != null)
				onChange.run();
		}

		void clear(boolean fire) {
			set(0, "", "", fire);
		}

		void open() {
			final Window win = new Window();
			win.setTitle(title);
			win.setWidth("560px");
			win.setHeight("440px");
			win.setClosable(true);
			win.setAttribute(Window.MODE_KEY, Window.MODE_HIGHLIGHTED);

			Div body = new Div();
			body.setStyle("padding:10px;");
			final Textbox q = new Textbox();
			q.setWidth("300px");
			q.setPlaceholder("Type code or name, press Enter");
			Button go = new Button("Search");
			Button clr = new Button("Clear selection");
			final Listbox lb = new Listbox();
			lb.setHeight("320px");
			Listhead lh = new Listhead();
			Listheader h1 = new Listheader("Code");
			h1.setWidth("150px");
			lh.appendChild(h1);
			Listheader h2 = new Listheader("Name");
			h2.setHflex("1");
			lh.appendChild(h2);
			lb.appendChild(lh);

			Div bar = new Div();
			bar.setStyle("margin-bottom:8px;");
			bar.appendChild(q);
			bar.appendChild(go);
			bar.appendChild(clr);
			body.appendChild(bar);
			body.appendChild(lb);
			win.appendChild(body);

			Runnable load = () -> {
				while (lb.getItemCount() > 0)
					lb.removeItemAt(0);
				List<Object> params = new ArrayList<Object>();
				String extra = filter == null ? "" : filter.where(params);
				String pat = "%" + (q.getValue() == null ? "" : q.getValue().trim().toUpperCase()) + "%";
				params.add(pat);
				params.add(pat);
				String sql = select + extra + " AND (UPPER(Value) LIKE ? OR UPPER(Name) LIKE ?) ORDER BY " + order
						+ " LIMIT 200";
				try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
					for (int i = 0; i < params.size(); i++) {
						Object o = params.get(i);
						if (o instanceof Integer)
							ps.setInt(i + 1, ((Integer) o).intValue());
						else
							ps.setString(i + 1, String.valueOf(o));
					}
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							Listitem li = new Listitem();
							li.setValue(new Object[] { Integer.valueOf(rs.getInt(1)), rs.getString(2), rs.getString(3) });
							li.appendChild(new Listcell(rs.getString(2)));
							li.appendChild(new Listcell(rs.getString(3)));
							lb.appendChild(li);
						}
					}
				} catch (Exception ex) {
					log.log(Level.SEVERE, sql, ex);
				}
			};
			q.addEventListener(Events.ON_OK, e -> load.run());
			go.addEventListener(Events.ON_CLICK, e -> load.run());
			clr.addEventListener(Events.ON_CLICK, e -> {
				clear(true);
				win.detach();
			});
			lb.addEventListener(Events.ON_SELECT, e -> {
				Listitem sel = lb.getSelectedItem();
				if (sel == null)
					return;
				Object[] v = (Object[]) sel.getValue();
				set(((Integer) v[0]).intValue(), (String) v[1], (String) v[2], true);
				win.detach();
			});
			AEnv.showCenterScreen(win);
			load.run();
		}
	}

	// =====================================================================
	// data
	// =====================================================================

	private void loadData() {
		lblOrg.setValue(orgId > 0 ? str("SELECT Name FROM AD_Org WHERE AD_Org_ID=" + orgId) : "*");
		lblUser.setValue(str("SELECT Name FROM AD_User WHERE AD_User_ID=" + userId));

		fill(cbType, "SELECT C_DocType_ID, Name FROM C_DocType WHERE DocBaseType='POR' AND IsActive='Y' "
				+ "AND AD_Client_ID=" + clientId + " ORDER BY Name");
		fill(cbLocation, "SELECT l.Value, l.Name FROM AD_Ref_List l JOIN AD_Reference r "
				+ "ON r.AD_Reference_ID=l.AD_Reference_ID WHERE r.Name='" + REF_LOCATION
				+ "' AND l.IsActive='Y' ORDER BY l.AD_Ref_List_ID");
		fill(cbSegment, "SELECT l.Value, l.Name FROM AD_Ref_List l JOIN AD_Reference r "
				+ "ON r.AD_Reference_ID=l.AD_Reference_ID WHERE r.Name='" + REF_SEGMENT
				+ "' AND l.IsActive='Y' ORDER BY l.Name");
		fill(cbPriority, "SELECT Value, Name FROM AD_Ref_List WHERE AD_Reference_ID=" + REF_PRIORITY
				+ " AND IsActive='Y' ORDER BY CASE Value WHEN '3' THEN 1 WHEN '5' THEN 2 WHEN '7' THEN 3 "
				+ "WHEN '9' THEN 4 WHEN '1' THEN 5 ELSE 6 END");

		Date today = new Date();
		dtDoc.setValue(today);
		// a document type that is the only one is the obvious choice
		if (cbType.getItemCount() == 2)
			cbType.setSelectedIndex(1);
	}

	private void fill(Listbox lb, String sql) {
		try (PreparedStatement ps = DB.prepareStatement(sql, null); ResultSet rs = ps.executeQuery()) {
			while (rs.next())
				lb.appendItem(rs.getString(2), rs.getString(1));
		} catch (Exception e) {
			log.log(Level.SEVERE, sql, e);
		}
	}

	private String str(String sql) {
		String s = DB.getSQLValueString(null, sql);
		return s == null ? "" : s;
	}

	private boolean columnExists(String table, String column) {
		return DB.getSQLValue(null, "SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)", table, column) > 0;
	}

	private BigDecimal availableQty(int productId, int warehouseId) {
		String sql = "SELECT COALESCE(SUM(s.QtyOnHand),0) FROM M_StorageOnHand s "
				+ "JOIN M_Locator l ON l.M_Locator_ID=s.M_Locator_ID WHERE s.M_Product_ID=?"
				+ (warehouseId > 0 ? " AND l.M_Warehouse_ID=?" : "");
		BigDecimal v = warehouseId > 0 ? DB.getSQLValueBD(null, sql, productId, warehouseId)
				: DB.getSQLValueBD(null, sql, productId);
		return v == null ? BigDecimal.ZERO : v;
	}

	// =====================================================================
	// events
	// =====================================================================

	private void wireEvents() {
		pParent.onChange = () -> {
			pSub.clear(false);
			pProduct.clear(false);
			productChanged();
		};
		pSub.onChange = () -> {
			pProduct.clear(false);
			productChanged();
		};
		pProduct.onChange = () -> {
			productChanged();
			fillCategoryFromProduct();
		};
		pWarehouse.onChange = this::productChanged;

		btnAdd.addEventListener(Events.ON_CLICK, e -> addLine());
		btnOk.addEventListener(Events.ON_CLICK, e -> save());
		btnCancel.addEventListener(Events.ON_CLICK, e -> close());
		btnAttach.addEventListener(Events.ON_UPLOAD, e -> {
			if (e instanceof UploadEvent) {
				Media[] ms = ((UploadEvent) e).getMedias();
				if (ms != null)
					for (Media m : ms)
						if (m != null)
							attachments.add(m);
				lblAttach.setValue(attachments.isEmpty() ? "" : attachments.size() + " file(s) attached");
			}
		});
	}

	/** UOM and Available Qty follow the product (and the warehouse) */
	private void productChanged() {
		if (pProduct.id <= 0) {
			lblUom.setValue("");
			lblAvail.setValue("0");
			return;
		}
		lblUom.setValue(str("SELECT u.Name FROM M_Product p JOIN C_UOM u ON u.C_UOM_ID=p.C_UOM_ID "
				+ "WHERE p.M_Product_ID=" + pProduct.id));
		lblAvail.setValue(availableQty(pProduct.id, pWarehouse.id).stripTrailingZeros().toPlainString());
	}

	/** a product picked first fills its categories */
	private void fillCategoryFromProduct() {
		if (pProduct.id <= 0 || pParent.id > 0)
			return;
		String sql = "SELECT c.M_Product_Category_ID, c.Value, c.Name, p.M_Product_Category_ID, p.Value, p.Name "
				+ "FROM M_Product pr JOIN M_Product_Category c ON c.M_Product_Category_ID=pr.M_Product_Category_ID "
				+ "LEFT JOIN M_Product_Category p ON p.M_Product_Category_ID=c.M_Product_Category_Parent_ID "
				+ "AND p.M_Product_Category_ID<>c.M_Product_Category_ID WHERE pr.M_Product_ID=?";
		try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
			ps.setInt(1, pProduct.id);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					if (rs.getInt(4) > 0) {
						pParent.set(rs.getInt(4), rs.getString(5), rs.getString(6), false);
						pSub.set(rs.getInt(1), rs.getString(2), rs.getString(3), false);
					} else {
						pParent.set(rs.getInt(1), rs.getString(2), rs.getString(3), false);
					}
				}
			}
		} catch (Exception e) {
			log.log(Level.WARNING, e.getMessage(), e);
		}
	}

	private void close() {
		try {
			SessionManager.getAppDesktop().closeActiveWindow();
		} catch (Exception e) {
			resetForm();
		}
	}

	// =====================================================================
	// lines
	// =====================================================================

	private String selValue(Listbox lb) {
		Listitem it = lb.getSelectedItem();
		return it == null ? "" : String.valueOf(it.getValue());
	}

	private String selName(Listbox lb) {
		Listitem it = lb.getSelectedItem();
		return it == null ? "" : it.getLabel();
	}

	private Timestamp ts(Datebox d) {
		Date v = d.getValue();
		return v == null ? null : new Timestamp(v.getTime());
	}

	private void warn(String msg) {
		Messagebox.show(msg, "Requisition", Messagebox.OK, Messagebox.EXCLAMATION);
	}

	private void addLine() {
		if (selValue(cbSegment).length() == 0) {
			warn("Segment is required.");
			return;
		}
		if (pDept.id <= 0) {
			warn("Department is required.");
			return;
		}
		if (pParent.id <= 0) {
			warn("Parent Category is required.");
			return;
		}
		if (pProduct.id <= 0) {
			warn("Product is required.");
			return;
		}
		BigDecimal qty = decQty.getValue();
		if (qty == null || qty.signum() <= 0) {
			warn("Qty must be greater than 0.");
			return;
		}
		Line l = new Line();
		l.segVal = selValue(cbSegment);
		l.segName = selName(cbSegment);
		l.deptId = pDept.id;
		l.deptName = pDept.name;
		l.productId = pProduct.id;
		l.code = pProduct.code;
		l.name = pProduct.name;
		l.uom = lblUom.getValue();
		l.spec = txtSpec.getValue() == null ? "" : txtSpec.getValue().trim();
		l.dateReq = ts(dtLine);
		try {
			l.avail = new BigDecimal(lblAvail.getValue());
		} catch (Exception ignore) {
			l.avail = BigDecimal.ZERO;
		}
		l.qty = qty;
		BigDecimal r = decRate.getValue();
		l.rate = r == null ? BigDecimal.ZERO : r;
		lines.add(l);
		refreshLines();

		// next product: keep Segment, Department and categories, clear the rest
		pProduct.clear(false);
		txtSpec.setValue("");
		decQty.setValue((BigDecimal) null);
		decRate.setValue((BigDecimal) null);
		productChanged();
	}

	private void refreshLines() {
		while (lineList.getItemCount() > 0)
			lineList.removeItemAt(0);
		int n = 0;
		for (final Line l : lines) {
			n++;
			Listitem li = new Listitem();
			li.appendChild(new Listcell(String.valueOf(n * 10)));
			li.appendChild(new Listcell(l.segName));
			li.appendChild(new Listcell(l.deptName));
			li.appendChild(new Listcell(l.code));
			li.appendChild(new Listcell(l.name));
			li.appendChild(new Listcell(l.uom));
			li.appendChild(new Listcell(l.spec));
			li.appendChild(new Listcell(l.dateReq == null ? "" : new java.text.SimpleDateFormat("dd/MM/yyyy").format(l.dateReq)));
			li.appendChild(new Listcell(l.avail.stripTrailingZeros().toPlainString()));
			li.appendChild(new Listcell(l.qty.stripTrailingZeros().toPlainString()));
			li.appendChild(new Listcell(l.rate.signum() == 0 ? "" : l.rate.toPlainString()));
			li.appendChild(new Listcell(l.rate.signum() == 0 ? "" : l.rate.multiply(l.qty).toPlainString()));
			Listcell rm = new Listcell();
			Button x = new Button("\u2716");
			x.setStyle("color:#c62828;background:#fff;border:1px solid #d0d7de;width:30px;height:22px;");
			x.addEventListener(Events.ON_CLICK, e -> {
				lines.remove(l);
				refreshLines();
			});
			rm.appendChild(x);
			li.appendChild(rm);
			lineList.appendChild(li);
		}
	}

	// =====================================================================
	// save
	// =====================================================================

	private void save() {
		if (orgId <= 0) {
			warn("Log in with a specific organization (not *) to create a requisition.");
			return;
		}
		if (selValue(cbType).length() == 0) {
			warn("Type is required.");
			return;
		}
		if (selValue(cbLocation).length() == 0) {
			warn("Location is required.");
			return;
		}
		if (selValue(cbPriority).length() == 0) {
			warn("Priority is required.");
			return;
		}
		Timestamp docDate = ts(dtDoc);
		Timestamp reqDate = ts(dtRequired);
		if (docDate == null) {
			warn("Document Date is required.");
			return;
		}
		if (reqDate == null) {
			warn("Date Required is required.");
			return;
		}
		if (lines.isEmpty()) {
			warn("Add at least one product (+ Add Product).");
			return;
		}
		if (!columnExists("M_Requisition", "XX_Location") || !columnExists("M_RequisitionLine", "XX_Segment")
				|| !columnExists("M_RequisitionLine", "XX_Department_ID")
				|| !columnExists("M_RequisitionLine", "XX_DateRequired")) {
			warn("Run db/16_requisition_entry_form.sql first (extra requisition columns are missing).");
			return;
		}

		int warehouseId = pWarehouse.id;
		if (warehouseId <= 0)
			warehouseId = DB.getSQLValue(null, "SELECT M_Warehouse_ID FROM M_Warehouse WHERE IsActive='Y' "
					+ "AND AD_Client_ID=? ORDER BY (AD_Org_ID=?) DESC, M_Warehouse_ID LIMIT 1", clientId, orgId);
		int priceListId = DB.getSQLValue(null, "SELECT M_PriceList_ID FROM M_PriceList WHERE IsActive='Y' "
				+ "AND AD_Client_ID=? AND IsSOPriceList='N' ORDER BY IsDefault DESC, M_PriceList_ID LIMIT 1",
				clientId);
		if (warehouseId <= 0 || priceListId <= 0) {
			warn("No warehouse or purchase price list found for this client.");
			return;
		}

		String trxName = Trx.createTrxName("ReqEntry");
		Trx trx = Trx.get(trxName, true);
		try {
			MRequisition req = new MRequisition(Env.getCtx(), 0, trxName);
			req.setAD_Org_ID(orgId);
			req.setAD_User_ID(userId);
			req.setC_DocType_ID(Integer.parseInt(selValue(cbType)));
			req.setDateDoc(docDate);
			req.setDateRequired(reqDate);
			req.setPriorityRule(selValue(cbPriority));
			req.setM_Warehouse_ID(warehouseId);
			req.setM_PriceList_ID(priceListId);
			String desc = txtDescription.getValue() == null ? "" : txtDescription.getValue().trim();
			if (desc.length() > 0)
				req.setDescription(desc);
			req.saveEx();
			DB.executeUpdateEx("UPDATE M_Requisition SET XX_Location=? WHERE M_Requisition_ID=?",
					new Object[] { selValue(cbLocation), Integer.valueOf(req.get_ID()) }, trxName);

			int n = 0;
			for (Line l : lines) {
				n++;
				MRequisitionLine rl = new MRequisitionLine(req);
				rl.setLine(n * 10);
				rl.setM_Product_ID(l.productId);
				rl.setQty(l.qty);
				rl.setPriceActual(l.rate);
				if (l.spec.length() > 0)
					rl.setDescription(l.spec.length() > 255 ? l.spec.substring(0, 255) : l.spec);
				rl.saveEx();
				DB.executeUpdateEx("UPDATE M_RequisitionLine SET XX_Segment=?, XX_Department_ID=?, "
						+ "XX_DateRequired=? WHERE M_RequisitionLine_ID=?",
						new Object[] { l.segVal, Integer.valueOf(l.deptId), l.dateReq != null ? l.dateReq : reqDate,
								Integer.valueOf(rl.get_ID()) },
						trxName);
			}

			if (!attachments.isEmpty()) {
				MAttachment att = new MAttachment(Env.getCtx(), MRequisition.Table_ID, req.get_ID(), trxName);
				for (Media m : attachments) {
					byte[] bytes = m.isBinary() ? m.getByteData() : m.getStringData().getBytes("UTF-8");
					att.addEntry(m.getName(), bytes);
				}
				att.saveEx();
			}

			trx.commit(true);
			final String docNo = req.getDocumentNo();
			resetForm();
			Messagebox.show("Requisition " + docNo + " has been saved.", "Requisition", Messagebox.OK,
					Messagebox.INFORMATION);
		} catch (Exception ex) {
			trx.rollback();
			log.log(Level.SEVERE, ex.getMessage(), ex);
			Messagebox.show("Could not save the requisition:\n" + ex.getMessage(), "Error", Messagebox.OK,
					Messagebox.ERROR);
		} finally {
			trx.close();
		}
	}

	private void resetForm() {
		lines.clear();
		refreshLines();
		attachments.clear();
		lblAttach.setValue("");
		cbType.setSelectedIndex(cbType.getItemCount() == 2 ? 1 : 0);
		cbLocation.setSelectedIndex(0);
		cbPriority.setSelectedIndex(0);
		cbSegment.setSelectedIndex(0);
		pWarehouse.clear(false);
		pDept.clear(false);
		pParent.clear(false);
		pSub.clear(false);
		pProduct.clear(false);
		dtDoc.setValue(new Date());
		dtRequired.setValue((Date) null);
		dtLine.setValue((Date) null);
		txtDescription.setValue("");
		txtSpec.setValue("");
		decQty.setValue((BigDecimal) null);
		decRate.setValue((BigDecimal) null);
		productChanged();
	}
}