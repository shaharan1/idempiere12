package org.mycompany.requisition;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.sql.Connection;
import java.io.InputStream;

import org.compiere.util.DB;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Combobox;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.editor.WSearchEditor;
import org.adempiere.webui.event.ValueChangeEvent;
import org.adempiere.webui.event.ValueChangeListener;
import org.adempiere.webui.panel.ADForm;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MOrg;
import org.compiere.model.MUser;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.*;
import org.zkoss.zul.Messagebox;
import org.zkoss.util.media.AMedia;
import org.zkoss.zul.Filedownload;

import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlLoader;

public class WRequisitionForm extends ADForm implements EventListener<Event> {

    // ── Color palette ────────────────────────────────────────
    private static final String C_PRIMARY      = "#1565c0";
    private static final String C_PRIMARY_LIGHT= "#e3f2fd";
    private static final String C_SECTION_BG   = "#ffffff";
    private static final String C_FORM_BG      = "#f0f4f8";
    private static final String C_BORDER       = "#90caf9";
    private static final String C_REQUIRED     = "#d32f2f";
    private static final String C_LABEL        = "#37474f";
    private static final String C_GREEN        = "#2e7d32";
    private static final String C_RED          = "#c62828";
    private static final String C_HEADER_TEXT  = "#ffffff";

    // ── Header fields ────────────────────────────────────────
    private Textbox  txtOrganization = new Textbox();
    private Label    lblUser         = new Label();
    private Combobox cbType          = new Combobox();
    private Combobox cbPriority      = new Combobox();
    private Combobox cbLocation      = new Combobox();
    private Datebox  dtDocDate       = new Datebox();
    private Datebox  dtDateRequired  = new Datebox();
    private WSearchEditor weWarehouse;

    // ── Line entry fields ────────────────────────────────────
    private Combobox cbSegment          = new Combobox();
    private Textbox  txtDepartment      = new Textbox();
    private Button   btnDeptSearch      = new Button("\uD83D\uDD0D"); // Department search icon
    private Textbox  txtSpecification   = new Textbox();
    private Datebox  dtDateRequiredLine = new Datebox();
    private Textbox  txtParentCategory  = new Textbox();
    private Button   btnParentCatSearch = new Button("\uD83D\uDD0D"); // Parent Category search icon
    private Textbox  txtSubCategory     = new Textbox();
    private Button   btnSubCatSearch    = new Button("\uD83D\uDD0D"); // Sub-Category search icon
    private WSearchEditor weProduct;
    private Label    lblUOM             = new Label("—");
    private Textbox  txtQty             = new Textbox();
    // hidden IDs for selected items
    private int selectedDeptId          = 0;
    private int selectedParentCatId     = 0;
    private int selectedSubCatId        = 0;

    // ── Buttons ──────────────────────────────────────────────
    private Button btnAddLine = new Button("\u2713");
    private Button btnClear   = new Button("\u2717");
    private Button btnSave    = new Button("\uD83D\uDCBE  Save");

    // ── Requisition Line list ────────────────────────────────
    private Listbox lineListbox = new Listbox();

    // ── In-memory lines ──────────────────────────────────────
    private static class LineData {
        int        lineNo;
        int        productId;
        String     productName;
        BigDecimal qty;
        String     specification;
        String     segment;
        String     department;
        String     parentCategory;
        String     subCategory;
        String     uom;
        String     dateRequired;
        int        warehouseId;
    }
    private List<LineData> lineDataList = new ArrayList<>();
    private int lineCounter = 10;

    // ── Constants ────────────────────────────────────────────
    private static final int COL_WAREHOUSE = 11474;
    private static final int COL_PRODUCT   = 11501;
    private static final int REF_PRIORITY  = 154;

    public WRequisitionForm() { super(); }

    @Override
    protected void initForm() {
        try {
            buildUI();
            loadDropdownData();
            wireEvents();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ═══════════════════════════════════════════════════════
    //  UI BUILD
    // ═══════════════════════════════════════════════════════
    private void buildUI() {
        // Root layout
        Borderlayout root = new Borderlayout();
        root.setWidth("100%");
        root.setHeight("100%");
        root.setStyle("background:" + C_FORM_BG + ";");
        this.appendChild(root);

        // NORTH — header section + line entry + add/clear buttons
        North north = new North();
        north.setStyle("overflow:auto; border:none; background:" + C_FORM_BG + ";");
        north.setAutoscroll(true);
        root.appendChild(north);

        Vlayout northVl = new Vlayout();
        northVl.setWidth("100%");
        northVl.setStyle("padding:10px; gap:10px;");
        northVl.appendChild(buildRequisitionHeader());
        northVl.appendChild(buildLineEntry());
        northVl.appendChild(buildAddClearButtons());
        north.appendChild(northVl);

        // CENTER — Requisition Line list
        Center center = new Center();
        center.setStyle("overflow:auto; border:none; background:" + C_FORM_BG + ";");
        center.setAutoscroll(true);
        root.appendChild(center);

        Vlayout centerVl = new Vlayout();
        centerVl.setWidth("100%");
        centerVl.setStyle("padding:0 10px 10px 10px;");
        centerVl.appendChild(buildLineSection());
        center.appendChild(centerVl);

        // SOUTH — Save button
        South south = new South();
        south.setStyle(
            "border-top:1px solid " + C_BORDER + "; "
            + "background:" + C_SECTION_BG + "; padding:8px 14px;");
        south.setHeight("50px");
        root.appendChild(south);

        btnSave.setStyle(
            "background:" + C_PRIMARY + "; color:#fff; "
            + "border:none; padding:8px 30px; border-radius:6px; "
            + "font-size:14px; font-weight:600; cursor:pointer; "
            + "box-shadow:0 2px 4px rgba(21,101,192,0.4);");

        Hbox saveBox = new Hbox();
        saveBox.setStyle("width:100%; align-items:center;");
        saveBox.appendChild(btnSave);
        south.appendChild(saveBox);
    }

    // ── Requisition header groupbox ──────────────────────────
    private Div buildRequisitionHeader() {
        Div wrapper = new Div();
        wrapper.setWidth("100%");
        wrapper.setStyle(
            "background:" + C_SECTION_BG + "; "
            + "border-radius:8px; "
            + "box-shadow:0 1px 4px rgba(0,0,0,0.12); "
            + "overflow:hidden;");

        // Colored title bar
        Div titleBar = new Div();
        titleBar.setStyle(
            "background:linear-gradient(135deg," + C_PRIMARY + " 0%,#1976d2 100%); "
            + "color:" + C_HEADER_TEXT + "; "
            + "padding:8px 16px; font-size:14px; font-weight:700; "
            + "letter-spacing:0.5px;");
        titleBar.appendChild(new org.zkoss.zul.Label("\uD83D\uDCCB  Requisition"));
        wrapper.appendChild(titleBar);

        weWarehouse = makeSearch(COL_WAREHOUSE, "M_Warehouse_ID");

        // Organization → readonly textbox
        txtOrganization.setReadonly(true);
        txtOrganization.setStyle(
            "background:#f5f5f5; border:1px solid #ccc; border-radius:3px; "
            + "padding:3px 8px; color:#333; font-size:13px; width:180px; "
            + "cursor:default;");

        // User → plain label, lighter color
        lblUser.setStyle(
            "color:#546e7a; font-size:13px; padding:3px 0;");

        // Style comboboxes
        styleInput(cbType);
        styleInput(cbPriority);
        styleInput(cbLocation);
        styleInput(dtDocDate);
        styleInput(dtDateRequired);

        Grid g = new Grid();
        g.setWidth("100%");
        g.setStyle("border:none; padding:12px;");
        Columns cols = new Columns();
        // label | value | label | value | label | value | label | value
        int[] widths = {100, 180, 80, 160, 80, 190, 90, 0};
        for (int w : widths) {
            Column c = new Column();
            if (w > 0) c.setWidth(w + "px");
            cols.appendChild(c);
        }
        g.appendChild(cols);
        Rows rows = new Rows();
        g.appendChild(rows);

        // Row 1: Organization | Type* | Location* | Warehouse
        Row r1 = new Row();
        r1.setStyle("height:36px;");
        r1.appendChild(hdrLabel("Organization", false));
        r1.appendChild(txtOrganization);
        r1.appendChild(hdrLabel("Type", true));
        r1.appendChild(cbType);
        r1.appendChild(hdrLabel("Location", true));
        r1.appendChild(cbLocation);
        r1.appendChild(hdrLabel("Warehouse", false));
        r1.appendChild(weWarehouse.getComponent());
        rows.appendChild(r1);

        // Row 2: User | Priority* | Document Date | Date Required*
        Row r2 = new Row();
        r2.setStyle("height:36px;");
        r2.appendChild(hdrLabel("User", false));
        r2.appendChild(lblUser);
        r2.appendChild(hdrLabel("Priority", true));
        r2.appendChild(cbPriority);
        r2.appendChild(hdrLabel("Document Date", false));
        r2.appendChild(dtDocDate);
        r2.appendChild(hdrLabel("Date Required", true));
        r2.appendChild(dtDateRequired);
        rows.appendChild(r2);

        wrapper.appendChild(g);
        return wrapper;
    }

    // ── Line entry section ───────────────────────────────────
    private Div buildLineEntry() {
        Div wrapper = new Div();
        wrapper.setWidth("100%");
        wrapper.setStyle(
            "background:" + C_SECTION_BG + "; "
            + "border-radius:8px; "
            + "border-left:4px solid #42a5f5; "
            + "box-shadow:0 1px 4px rgba(0,0,0,0.10); "
            + "overflow:hidden;");

        Div titleBar = new Div();
        titleBar.setStyle(
            "background:" + C_PRIMARY_LIGHT + "; "
            + "border-bottom:1px solid " + C_BORDER + "; "
            + "padding:6px 14px; font-size:13px; font-weight:700; "
            + "color:" + C_PRIMARY + ";");
        titleBar.appendChild(new org.zkoss.zul.Label("\u2795  Add Line"));
        wrapper.appendChild(titleBar);

        weProduct = makeSearch(COL_PRODUCT, "M_Product_ID");

        // Style simple inputs
        styleInput(cbSegment);
        styleInput(txtSpecification);
        styleInput(dtDateRequiredLine);
        styleInput(txtQty);

        // Department textbox with search icon
        txtDepartment.setPlaceholder("Type department or click search");
        txtDepartment.setStyle(
            "border:1px solid " + C_BORDER + "; border-right:none; "
            + "border-radius:4px 0 0 4px; padding:3px 6px; "
            + "background:#fafcff; font-size:13px; width:110px;");
        styleSearchBtn(btnDeptSearch);

        // Parent Category textbox with search icon
        txtParentCategory.setPlaceholder("Category search");
        txtParentCategory.setStyle(
            "border:1px solid " + C_BORDER + "; border-right:none; "
            + "border-radius:4px 0 0 4px; padding:3px 6px; "
            + "background:#fafcff; font-size:13px; width:110px;");
        styleSearchBtn(btnParentCatSearch);

        // Sub-Category textbox with search icon
        txtSubCategory.setPlaceholder("Sub-Category search");
        txtSubCategory.setStyle(
            "border:1px solid " + C_BORDER + "; border-right:none; "
            + "border-radius:4px 0 0 4px; padding:3px 6px; "
            + "background:#fafcff; font-size:13px; width:110px;");
        styleSearchBtn(btnSubCatSearch);

        txtSpecification.setMultiline(true);
        txtSpecification.setRows(2);
        txtSpecification.setWidth("100%");
        txtSpecification.setPlaceholder("Enter details...");
        txtQty.setValue("1");
        txtQty.setWidth("80px");

        lblUOM.setStyle(
            "background:#fff9c4; padding:3px 10px; border-radius:4px; "
            + "color:#f57f17; font-weight:600; border:1px solid #f9a825; "
            + "min-width:80px; display:inline-block;");

        Grid g = new Grid();
        g.setWidth("100%");
        g.setStyle("border:none; padding:12px;");
        Rows rows = new Rows();
        g.appendChild(rows);

        // Row 1: Segment | Department (textbox+search) | Specification | Date Required
        Row r1 = new Row();
        r1.setStyle("height:44px;");
        r1.appendChild(lineLabel("Segment",              true));
        r1.appendChild(cbSegment);
        r1.appendChild(lineLabel("Department",           true));
        r1.appendChild(makeSearchField(txtDepartment, btnDeptSearch));
        r1.appendChild(lineLabel("Specification",        false));
        r1.appendChild(txtSpecification);
        r1.appendChild(lineLabel("Date Required (Line)", false));
        r1.appendChild(dtDateRequiredLine);
        rows.appendChild(r1);

        // Row 2: Parent Category (textbox+search) | Sub-Category (textbox+search) | Product | UOM
        Row r2 = new Row();
        r2.setStyle("height:44px;");
        r2.appendChild(lineLabel("Parent Category*", false));
        r2.appendChild(makeSearchField(txtParentCategory, btnParentCatSearch));
        r2.appendChild(lineLabel("Sub-Category",     false));
        r2.appendChild(makeSearchField(txtSubCategory, btnSubCatSearch));
        r2.appendChild(lineLabel("Product",          true));
        r2.appendChild(weProduct.getComponent());
        r2.appendChild(lineLabel("UOM",              false));
        r2.appendChild(lblUOM);
        rows.appendChild(r2);

        // Row 3: Qty
        Row r3 = new Row();
        r3.setStyle("height:36px;");
        r3.appendChild(lineLabel("Qty*", false));
        r3.appendChild(txtQty);
        rows.appendChild(r3);

        wrapper.appendChild(g);
        return wrapper;
    }

    /** Textbox + search button combined in one Hbox */
    private Hbox makeSearchField(Textbox txt, Button btn) {
        Hbox box = new Hbox();
        box.setStyle("display:flex; align-items:center;");
        box.setSpacing("0");
        box.appendChild(txt);
        box.appendChild(btn);
        return box;
    }

    /** Search button common style */
    private void styleSearchBtn(Button btn) {
        btn.setStyle(
            "background:" + C_PRIMARY + "; color:#fff; "
            + "border:1px solid " + C_PRIMARY + "; "
            + "border-radius:0 4px 4px 0; "
            + "padding:3px 8px; font-size:13px; cursor:pointer; "
            + "height:28px; line-height:1;");
    }

    // ── Add / Clear buttons ───────────────────────────────────
    private Hbox buildAddClearButtons() {
        btnAddLine.setStyle(
            "background:" + C_GREEN + "; color:#fff; "
            + "border:none; width:40px; height:34px; "
            + "font-size:18px; border-radius:6px; cursor:pointer; "
            + "box-shadow:0 2px 4px rgba(46,125,50,0.4);");
        btnClear.setStyle(
            "background:" + C_RED + "; color:#fff; "
            + "border:none; width:40px; height:34px; "
            + "font-size:18px; border-radius:6px; cursor:pointer; "
            + "box-shadow:0 2px 4px rgba(198,40,40,0.4);");

        Hbox box = new Hbox();
        box.setWidth("100%");
        box.setPack("end");
        box.setSpacing("6px");
        box.setStyle("padding:4px 6px;");
        box.appendChild(btnAddLine);
        box.appendChild(btnClear);
        return box;
    }

    // ── Requisition Line list ────────────────────────────────
    private Div buildLineSection() {
        Div wrapper = new Div();
        wrapper.setWidth("100%");
        wrapper.setStyle(
            "background:" + C_SECTION_BG + "; "
            + "border-radius:8px; "
            + "box-shadow:0 1px 4px rgba(0,0,0,0.12); "
            + "overflow:hidden;");

        // Title bar
        Div titleBar = new Div();
        titleBar.setStyle(
            "background:linear-gradient(135deg,#0d47a1 0%,#1565c0 100%); "
            + "color:#fff; padding:8px 16px; "
            + "font-size:13px; font-weight:700;");
        titleBar.appendChild(new org.zkoss.zul.Label("\uD83D\uDCC4  Requisition Line"));
        wrapper.appendChild(titleBar);

        lineListbox.setWidth("100%");
        lineListbox.setStyle("border:none;");
        lineListbox.setCheckmark(true);
        lineListbox.setMultiple(false);
        lineListbox.setEmptyMessage("No lines added yet — click the check button");

        Listhead head = new Listhead();
        head.setStyle(
            "background:" + C_PRIMARY_LIGHT + "; "
            + "color:" + C_PRIMARY + "; font-weight:700;");
        head.setSizable(true);

        String[][] cols = {
            {"Line#","55"},{"Product","200"},{"Qty","65"},
            {"Department","120"},{"Specification","220"},
            {"Category","120"},{"UOM","70"},
            {"Date Required","120"},{"Action","55"}
        };
        for (String[] c : cols) {
            Listheader lh = new Listheader(c[0]);
            lh.setWidth(c[1] + "px");
            lh.setStyle("font-weight:700; color:" + C_PRIMARY + ";");
            head.appendChild(lh);
        }
        lineListbox.appendChild(head);
        wrapper.appendChild(lineListbox);
        return wrapper;
    }

    // ═══════════════════════════════════════════════════════
    //  LOAD DROPDOWN DATA
    // ═══════════════════════════════════════════════════════
    private void loadDropdownData() {
        // Organization → readonly textbox
        MOrg  org  = MOrg.get(Env.getCtx(), Env.getAD_Org_ID(Env.getCtx()));
        MUser user = MUser.get(Env.getCtx(), Env.getAD_User_ID(Env.getCtx()));
        txtOrganization.setValue(org  != null ? org.getName()  : "");
        // User → plain label
        lblUser.setValue(user != null ? user.getName() : "");

        // Priority
        loadRefList(cbPriority, REF_PRIORITY);
        if (cbPriority.getItemCount() > 0) {
            // "Medium" default
            for (int i = 0; i < cbPriority.getItemCount(); i++) {
                if ("M".equals(cbPriority.getItemAtIndex(i).getValue())) {
                    cbPriority.setSelectedIndex(i); break;
                }
            }
        }

        // Type
        addComboItems(cbType,
            new String[]{"Purchase","Service","Asset","Repair"},
            new String[]{"PO",      "SV",     "AS",   "RP"});
        cbType.setSelectedIndex(0);

        // Location from C_Location
        loadLocations();

        // Segment
        addComboItems(cbSegment,
            new String[]{"Raw Material","Packaging","Consumable","Spare Parts","IT","Others"},
            new String[]{"RM",          "PK",       "CO",        "SP",         "IT","OT"});

        // Parent Category & Sub-Category → selected via search popup (search button)

        // Document Date
        dtDocDate.setValue(new Timestamp(System.currentTimeMillis()));
    }

    /** Load M_Product_Category into combobox.
     *  parentId=0  → root categories (M_Product_Category_Parent_ID IS NULL)
     *  parentId=-1 → all categories
     *  parentId>0  → children of that parent
     */
    private void loadProductCategories(Combobox cb, int parentId) {
        cb.getItems().clear();
        String sql;
        if (parentId == 0) {
            sql = "SELECT M_Product_Category_ID, Name "
                + "FROM M_Product_Category "
                + "WHERE IsActive='Y' "
                + "  AND (M_Product_Category_Parent_ID IS NULL "
                + "       OR M_Product_Category_Parent_ID=M_Product_Category_ID) "
                + "ORDER BY Name";
        } else if (parentId < 0) {
            sql = "SELECT M_Product_Category_ID, Name "
                + "FROM M_Product_Category WHERE IsActive='Y' ORDER BY Name";
        } else {
            sql = "SELECT M_Product_Category_ID, Name "
                + "FROM M_Product_Category "
                + "WHERE IsActive='Y' AND M_Product_Category_Parent_ID=" + parentId
                + " ORDER BY Name";
        }
        try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Comboitem item = cb.appendItem(rs.getString("Name"));
                item.setValue(rs.getInt("M_Product_Category_ID"));
            }
            if (cb.getItemCount() == 0) {
                Comboitem ph = cb.appendItem("(No categories found)");
                ph.setValue(0);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void loadRefList(Combobox cb, int refId) {
        cb.getItems().clear();
        String sql = "SELECT Value, Name FROM AD_Ref_List "
                   + "WHERE AD_Reference_ID=? AND IsActive='Y' ORDER BY Name";
        try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
            ps.setInt(1, refId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Comboitem item = cb.appendItem(rs.getString("Name"));
                item.setValue(rs.getString("Value"));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void loadLocations() {
        cbLocation.getItems().clear();
        String sql = "SELECT C_Location_ID, "
                   + "COALESCE(Address1||', '||City, Address1, City, "
                   + "'Location-'||C_Location_ID) AS DisplayName "
                   + "FROM C_Location WHERE IsActive='Y' ORDER BY DisplayName LIMIT 100";
        try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Comboitem item = cbLocation.appendItem(rs.getString("DisplayName"));
                item.setValue(rs.getInt("C_Location_ID"));
            }
            if (cbLocation.getItemCount() == 0) {
                Comboitem ph = cbLocation.appendItem("(No location found)");
                ph.setValue(0);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void addComboItems(Combobox cb, String[] labels, String[] values) {
        cb.getItems().clear();
        for (int i = 0; i < labels.length; i++) {
            Comboitem item = cb.appendItem(labels[i]);
            item.setValue(values[i]);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  EVENTS
    // ═══════════════════════════════════════════════════════
    private void wireEvents() {
        btnAddLine.addEventListener(Events.ON_CLICK, this);
        btnClear  .addEventListener(Events.ON_CLICK, this);
        btnSave   .addEventListener(Events.ON_CLICK, this);

        // Department search — search from AD_User (system users as department reps)
        btnDeptSearch.addEventListener(Events.ON_CLICK, e ->
            openSearchPopup("Department / User Search",
                "SELECT AD_User_ID AS id, Name AS name "
                + "FROM AD_User WHERE IsActive='Y' "
                + "AND (Name ILIKE '%'||?||'%' OR Name ILIKE '%'||?||'%') "
                + "ORDER BY Name LIMIT 50",
                txtDepartment.getValue().trim(),
                (id, name) -> {
                    txtDepartment.setValue(name);
                    selectedDeptId = id;
                }));

        // Parent Category search — search from M_Product_Category (root)
        btnParentCatSearch.addEventListener(Events.ON_CLICK, e ->
            openSearchPopup("Parent Category Search",
                "SELECT M_Product_Category_ID AS id, Name AS name "
                + "FROM M_Product_Category WHERE IsActive='Y' "
                + "AND Name ILIKE '%'||?||'%' "
                + "ORDER BY Name LIMIT 50",
                txtParentCategory.getValue().trim(),
                (id, name) -> {
                    txtParentCategory.setValue(name);
                    selectedParentCatId = id;
                    // Filter sub-category by selected parent
                    openSubCatForParent(id);
                }));

        // Sub-Category search — search from M_Product_Category (filtered by parent)
        btnSubCatSearch.addEventListener(Events.ON_CLICK, e -> {
            String parentFilter = selectedParentCatId > 0
                ? "AND M_Product_Category_Parent_ID = " + selectedParentCatId + " " : "";
            openSearchPopup("Sub-Category Search",
                "SELECT M_Product_Category_ID AS id, Name AS name "
                + "FROM M_Product_Category WHERE IsActive='Y' "
                + parentFilter
                + "AND Name ILIKE '%'||?||'%' "
                + "ORDER BY Name LIMIT 50",
                txtSubCategory.getValue().trim(),
                (id, name) -> {
                    txtSubCategory.setValue(name);
                    selectedSubCatId = id;
                });
        });

        // Product selected → auto-fill UOM (single listener via ValueChangeListener)
        try {
            weProduct.addValueChangeListener(new ValueChangeListener() {
                @Override
                public void valueChange(ValueChangeEvent evt) {
                    int pid = toInt(evt.getNewValue());
                    lblUOM.setValue(pid > 0 ? getUOMForProduct(pid) : "—");
                }
            });
        } catch (Exception ignore) {}
    }

    @Override
    public void onEvent(Event e) throws Exception {
        if      (e.getTarget() == btnAddLine) addLineToList();
        else if (e.getTarget() == btnClear)   clearLineFields();
        else if (e.getTarget() == btnSave)    handleSave();
    }

    // ── Generic Search Popup ─────────────────────────────────
    @FunctionalInterface
    interface SearchCallback { void onSelect(int id, String name); }

    /**
     * Shows a popup window where the user can type to search.
     * @param title     popup title
     * @param sql       SELECT ... AS id, ... AS name FROM ... WHERE ... ILIKE '%'||?||'%'
     * @param initValue current value of the textbox (initial filter)
     * @param callback  action to run when an item is selected
     */
    private void openSearchPopup(String title, String sql,
                                 String initValue, SearchCallback callback) {
        Window win = new Window();
        win.setTitle(title);
        win.setBorder("normal");
        win.setWidth("420px");
        win.setClosable(true);
        win.setSizable(false);
        win.setStyle(
            "border-radius:8px; box-shadow:0 4px 20px rgba(0,0,0,0.25);");

        Vlayout vl = new Vlayout();
        vl.setStyle("padding:10px; gap:6px;");

        // Search input row
        Textbox searchBox = new Textbox(initValue);
        searchBox.setStyle(
            "border:1px solid " + C_BORDER + "; border-right:none; "
            + "border-radius:4px 0 0 4px; padding:5px 8px; "
            + "font-size:13px; width:280px;");
        searchBox.setPlaceholder("Search...");

        Button btnSearch = new Button("\uD83D\uDD0D Search");
        btnSearch.setStyle(
            "background:" + C_PRIMARY + "; color:#fff; border:none; "
            + "border-radius:0 4px 4px 0; padding:5px 12px; cursor:pointer;");

        Hbox searchRow = new Hbox();
        searchRow.setSpacing("0");
        searchRow.appendChild(searchBox);
        searchRow.appendChild(btnSearch);
        vl.appendChild(searchRow);

        // Results listbox
        Listbox resultList = new Listbox();
        resultList.setWidth("100%");
        resultList.setHeight("250px");
        resultList.setStyle("border:1px solid " + C_BORDER + "; border-radius:4px;");
        resultList.setEmptyMessage("Type a keyword to search...");

        Listhead rHead = new Listhead();
        Listheader lhName = new Listheader("Name");
        lhName.setStyle("background:" + C_PRIMARY_LIGHT + "; color:" + C_PRIMARY + "; font-weight:700;");
        rHead.appendChild(lhName);
        resultList.appendChild(rHead);
        vl.appendChild(resultList);

        // Helper: run query and fill resultList
        Runnable runSearch = () -> {
            resultList.getItems().clear();
            String keyword = searchBox.getValue().trim();
            String qSql = sql.replace("ILIKE '%'||?||'%'",
                    "ILIKE '%" + keyword.replace("'","''") + "%'")
                .replace("AND (Name ILIKE '%'||?||'%' OR Name ILIKE '%'||?||'%')",
                    "AND Name ILIKE '%" + keyword.replace("'","''") + "%'");
            try (PreparedStatement ps = DB.prepareStatement(qSql, null)) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    int id     = rs.getInt("id");
                    String nm  = rs.getString("name");
                    Listitem li = new Listitem(nm);
                    li.setValue(new int[]{id});
                    li.setAttribute("itemName", nm);
                    resultList.appendChild(li);
                }
            } catch (Exception ex) { ex.printStackTrace(); }
        };

        // Search button click
        btnSearch.addEventListener(Events.ON_CLICK, ev -> runSearch.run());
        // Enter key in search box
        searchBox.addEventListener(Events.ON_OK, ev -> runSearch.run());

        // Selection
        resultList.addEventListener(Events.ON_SELECT, ev -> {
            Listitem sel = resultList.getSelectedItem();
            if (sel != null) {
                int selId   = ((int[]) sel.getValue())[0];
                String selNm = (String) sel.getAttribute("itemName");
                callback.onSelect(selId, selNm);
                win.detach();
            }
        });

        win.appendChild(vl);
        win.setPage(this.getPage());
        win.doModal();

        // Auto-search on open if there's initial text
        if (!initValue.isEmpty()) runSearch.run();
    }

    /** When parent category is selected, update sub-category field hint */
    private void openSubCatForParent(int parentId) {
        txtSubCategory.setValue("");
        txtSubCategory.setPlaceholder("Sub-Category (parent selected)");
        selectedSubCatId = 0;
    }

    // ═══════════════════════════════════════════════════════
    //  ADD LINE TO LIST
    // ═══════════════════════════════════════════════════════
    private void addLineToList() {
        // Validate
        int productId = toInt(weProduct.getValue());
        if (productId <= 0) {
            showError("Product is required — please select one from the Product field");
            return;
        }

        BigDecimal qty;
        try {
            qty = new BigDecimal(txtQty.getValue().trim());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) throw new Exception();
        } catch (Exception ex) {
            showError("Qty must be a number greater than 0");
            return;
        }

        LineData ld      = new LineData();
        ld.lineNo        = lineCounter;
        ld.productId     = productId;
        ld.productName   = getProductName(productId);
        ld.qty           = qty;
        ld.specification = txtSpecification.getValue().trim();
        ld.segment       = cbSegment.getSelectedItem() != null
                            ? cbSegment.getSelectedItem().getLabel() : "";
        ld.department    = txtDepartment.getValue().trim();
        ld.parentCategory= txtParentCategory.getValue().trim();
        ld.subCategory   = txtSubCategory.getValue().trim();
        ld.uom           = lblUOM.getValue().equals("—") ? "" : lblUOM.getValue();
        ld.dateRequired  = dtDateRequiredLine.getValue() != null
                            ? dtDateRequiredLine.getValue().toString().substring(0, 10) : "";
        ld.warehouseId   = toInt(weWarehouse.getValue());

        lineDataList.add(ld);
        lineCounter += 10;

        // Add row with alternating stripe
        Listitem li = new Listitem();
        li.setValue(ld);
        boolean isEven = (lineListbox.getItemCount() % 2 == 0);
        li.setStyle(isEven
            ? "background:#f5f9ff;"
            : "background:#ffffff;");

        li.appendChild(styledCell(String.valueOf(ld.lineNo), "center", C_PRIMARY, true));
        li.appendChild(styledCell(ld.productName, "left", "#1a237e", true));
        li.appendChild(styledCell(qty.toPlainString(), "center", "#2e7d32", true));
        li.appendChild(styledCell(ld.department, "left", C_LABEL, false));
        li.appendChild(styledCell(ld.specification, "left", C_LABEL, false));
        li.appendChild(styledCell(
            (ld.parentCategory + (ld.subCategory.isEmpty() ? "" : " \u203A " + ld.subCategory)),
            "left", "#6a1b9a", false));
        li.appendChild(styledCell(ld.uom, "center", "#e65100", false));
        li.appendChild(styledCell(ld.dateRequired, "center", C_LABEL, false));

        // Delete button
        Button btnDel = new Button("\uD83D\uDDD1");
        btnDel.setStyle(
            "background:none; border:none; color:" + C_RED + "; "
            + "cursor:pointer; font-size:15px; padding:2px;");
        final LineData ldRef = ld;
        final Listitem liRef = li;
        btnDel.addEventListener(Events.ON_CLICK, ev -> {
            lineDataList.remove(ldRef);
            lineListbox.removeChild(liRef);
        });
        Listcell actCell = new Listcell();
        actCell.setStyle("text-align:center;");
        actCell.appendChild(btnDel);
        li.appendChild(actCell);

        lineListbox.appendChild(li);
        clearLineFields();
    }

    // ═══════════════════════════════════════════════════════
    //  CLEAR LINE FIELDS
    // ═══════════════════════════════════════════════════════
    private void clearLineFields() {
        cbSegment.setSelectedItem(null);
        txtDepartment.setValue("");
        txtSpecification.setValue("");
        dtDateRequiredLine.setValue(null);
        txtParentCategory.setValue("");
        txtSubCategory.setValue("");
        selectedDeptId      = 0;
        selectedParentCatId = 0;
        selectedSubCatId    = 0;
        txtQty.setValue("1");
        lblUOM.setValue("—");
        try { weProduct.setValue(null); } catch (Exception ignore) {}
    }

    // ═══════════════════════════════════════════════════════
    //  SAVE BUTTON
    // ═══════════════════════════════════════════════════════
    private void handleSave() {
        int warehouseId = toInt(weWarehouse.getValue());
        if (warehouseId <= 0) {
            showError("Warehouse is required");
            return;
        }

        List<LineData> toSave = new ArrayList<>();
        Listitem selectedItem = lineListbox.getSelectedItem();

        if (selectedItem != null) {
            toSave.add((LineData) selectedItem.getValue());
        } else {
            if (lineDataList.isEmpty()) {
                showError("Please add at least one line (check button)\nor select a line from the list.");
                return;
            }
            toSave.addAll(lineDataList);
        }

        int requisitionId = performSave(warehouseId, toSave);
        if (requisitionId <= 0) return;

        String saved = selectedItem != null
            ? "The selected line" : "All " + toSave.size() + " line(s)";

        final int reqId = requisitionId;
        Messagebox.show(
            "\u2705  " + saved + " saved successfully!\n\n"
            + "\uD83D\uDCC4  Would you like to view it as a PDF?",
            "Save Successful",
            Messagebox.YES | Messagebox.NO,
            Messagebox.QUESTION,
            evt -> {
                if (Messagebox.ON_YES.equals(evt.getName())) {
                    generateAndDownloadPDF(reqId);
                }
            }
        );

        if (selectedItem != null) {
            lineDataList.remove((LineData) selectedItem.getValue());
            lineListbox.removeChild(selectedItem);
        } else {
            lineDataList.clear();
            lineCounter = 10;
            lineListbox.getItems().clear();
            clearLineFields();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  DB SAVE
    // ═══════════════════════════════════════════════════════
    private int performSave(int warehouseId, List<LineData> lines) {
        int    adClientId = Env.getAD_Client_ID(Env.getCtx());
        int    adOrgId    = Env.getAD_Org_ID(Env.getCtx());
        int    userId     = Env.getAD_User_ID(Env.getCtx());
        Timestamp now     = new Timestamp(System.currentTimeMillis());
        String docNo      = "REQ-" + System.currentTimeMillis();

        int locationId = 0;
        if (cbLocation.getSelectedItem() != null)
            locationId = toInt(cbLocation.getSelectedItem().getValue());

        int requisitionId = getNextId("XX_Requisition", "XX_Requisition_ID");
        if (requisitionId <= 0) {
            showError("Failed to generate Requisition ID");
            return -1;
        }

        String hSql =
            "INSERT INTO XX_Requisition "
            + "(XX_Requisition_ID,AD_Client_ID,AD_Org_ID,IsActive,"
            + " Created,CreatedBy,Updated,UpdatedBy,"
            + " DocumentNo,RequisitionDate,M_Warehouse_ID,"
            + " C_Location_ID,Description,PurchaseType,DateRequired,DocStatus) "
            + "VALUES (?,?,?,'Y',?,?,?,?,?,?,?,?,?,?,?,'DR')";

        try (PreparedStatement ps = DB.prepareStatement(hSql, null)) {
            ps.setInt(1, requisitionId);
            ps.setInt(2, adClientId);
            ps.setInt(3, adOrgId);
            ps.setTimestamp(4, now);
            ps.setInt(5, userId);
            ps.setTimestamp(6, now);
            ps.setInt(7, userId);
            ps.setString(8, docNo);
            Timestamp docDate = dtDocDate.getValue() != null
                    ? new Timestamp(dtDocDate.getValue().getTime()) : now;
            ps.setTimestamp(9, docDate);
            ps.setInt(10, warehouseId);
            if (locationId > 0) ps.setInt(11, locationId);
            else                ps.setNull(11, java.sql.Types.NUMERIC);
            ps.setString(12, "");                 // Description / Note — left blank on save
            ps.setString(13, buildDesc());         // Purchase Type — actual selected Type label
            if (dtDateRequired.getValue() != null)
                ps.setTimestamp(14, new Timestamp(dtDateRequired.getValue().getTime()));
            else
                ps.setNull(14, java.sql.Types.TIMESTAMP);
            if (ps.executeUpdate() == 0) throw new Exception("0 rows");
        } catch (Exception ex) {
            ex.printStackTrace();
            showError("Failed to save header: " + ex.getMessage());
            return -1;
        }

        String lSql =
            "INSERT INTO XX_Requisition_Line "
            + "(XX_Requisition_Line_ID,AD_Client_ID,AD_Org_ID,IsActive,"
            + " Created,CreatedBy,Updated,UpdatedBy,"
            + " XX_Requisition_ID,Line,M_Product_ID,M_Warehouse_ID,"
            + " Qty,Description) "
            + "VALUES (?,?,?,'Y',?,?,?,?,?,?,?,?,?,?)";

        for (LineData ld : lines) {
            int lineId = getNextId("XX_Requisition_Line","XX_Requisition_Line_ID");
            int wh     = ld.warehouseId > 0 ? ld.warehouseId : warehouseId;
            try (PreparedStatement ps = DB.prepareStatement(lSql, null)) {
                ps.setInt(1, lineId);
                ps.setInt(2, adClientId);
                ps.setInt(3, adOrgId);
                ps.setTimestamp(4, now);
                ps.setInt(5, userId);
                ps.setTimestamp(6, now);
                ps.setInt(7, userId);
                ps.setInt(8, requisitionId);
                ps.setInt(9, ld.lineNo);
                ps.setInt(10, ld.productId);
                ps.setInt(11, wh);
                ps.setBigDecimal(12, ld.qty);
                ps.setString(13, ld.specification);
                if (ps.executeUpdate() == 0) throw new Exception("0 rows");
            } catch (Exception ex) {
                ex.printStackTrace();
                DB.executeUpdate("DELETE FROM XX_Requisition_Line WHERE XX_Requisition_ID="
                        + requisitionId, null);
                DB.executeUpdate("DELETE FROM XX_Requisition WHERE XX_Requisition_ID="
                        + requisitionId, null);
                showError("Failed to save line " + ld.lineNo + ": " + ex.getMessage());
                return -1;
            }
        }
        return requisitionId;
    }

    // ═══════════════════════════════════════════════════════
    //  PDF GENERATION
    // ═══════════════════════════════════════════════════════
    private void generateAndDownloadPDF(int requisitionId) {
        InputStream jrxmlStream = null;
        try {
            jrxmlStream = getClass().getResourceAsStream(
                    "/org/mycompany/requisition/report/RequisitionForm.jrxml");
            if (jrxmlStream == null) {
                showError("RequisitionForm.jrxml not found!\n"
                        + "Place the file under: src/org/mycompany/requisition/report/");
                return;
            }
            JasperDesign design  = JRXmlLoader.load(jrxmlStream);
            net.sf.jasperreports.engine.JasperReport report =
                    JasperCompileManager.compileReport(design);

            Map<String, Object> params = new HashMap<>();
            params.put("RECORD_ID", requisitionId);

            Connection conn    = DB.getConnectionRO();
            JasperPrint print  = JasperFillManager.fillReport(report, params, conn);
            byte[] pdfBytes    = JasperExportManager.exportReportToPdf(print);

            String fileName = "Requisition_" + requisitionId + ".pdf";
            Filedownload.save(new AMedia(fileName,"pdf","application/pdf",pdfBytes));

        } catch (Exception e) {
            e.printStackTrace();
            showError("Failed to generate PDF: " + e.getMessage());
        } finally {
            if (jrxmlStream != null)
                try { jrxmlStream.close(); } catch (Exception ignore) {}
        }
    }

    // ═══════════════════════════════════════════════════════
    //  STYLING HELPERS
    // ═══════════════════════════════════════════════════════
    private void styleInput(Object comp) {
        String base =
            "border:1px solid " + C_BORDER + "; border-radius:4px; "
            + "padding:3px 6px; background:#fafcff; "
            + "font-size:13px; color:" + C_LABEL + ";";
        if (comp instanceof Combobox) ((Combobox)comp).setStyle(base + " min-width:140px;");
        else if (comp instanceof Textbox)  ((Textbox)comp) .setStyle(base);
        else if (comp instanceof Datebox)  ((Datebox)comp) .setStyle(base);
    }

    private Label hdrLabel(String text, boolean required) {
        Label l = new Label(text);
        l.setStyle(
            "font-size:12px; font-weight:600; "
            + "color:" + (required ? C_REQUIRED : C_LABEL) + ";");
        return l;
    }

    private Label lineLabel(String text, boolean required) {
        Label l = new Label(text);
        l.setStyle(
            "font-size:12px; font-weight:600; "
            + "color:" + (required ? C_REQUIRED : "#455a64") + ";");
        return l;
    }

    private Listcell styledCell(String text, String align,
                                String color, boolean bold) {
        Listcell lc = new Listcell(text);
        lc.setStyle(
            "text-align:" + align + "; color:" + color + "; "
            + (bold ? "font-weight:600;" : "") + " font-size:13px;");
        return lc;
    }

    private void showError(String msg) {
        try {
            Messagebox.show(msg, "Validation", Messagebox.OK, Messagebox.EXCLAMATION);
        } catch (Exception ignore) {}
    }

    // ═══════════════════════════════════════════════════════
    //  DB HELPERS
    // ═══════════════════════════════════════════════════════
    private WSearchEditor makeSearch(int colId, String colName) {
        MLookup lkp = MLookupFactory.get(Env.getCtx(), 0, 0, colId, DisplayType.Search);
        return new WSearchEditor(colName, true, false, true, lkp);
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Integer) return (Integer) val;
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    private String getProductName(int productId) {
        String sql = "SELECT Name FROM M_Product WHERE M_Product_ID=?";
        try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
            ps.setInt(1, productId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("Name");
        } catch (Exception e) { e.printStackTrace(); }
        return "Product#" + productId;
    }

    /** Auto-fill UOM when a product is selected */
    private String getUOMForProduct(int productId) {
        String sql =
            "SELECT u.Name FROM M_Product p "
            + "JOIN C_UOM u ON u.C_UOM_ID = p.C_UOM_ID "
            + "WHERE p.M_Product_ID = ?";
        try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
            ps.setInt(1, productId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("Name");
        } catch (Exception e) { e.printStackTrace(); }
        return "";
    }

    private int getNextId(String table, String idCol) {
        String sql = "SELECT COALESCE(MAX(" + idCol + "),0)+1 FROM " + table;
        try (PreparedStatement ps = DB.prepareStatement(sql, null)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) { e.printStackTrace(); }
        return -1;
    }

    private String buildDesc() {
        return cbType.getSelectedItem() != null
                ? cbType.getSelectedItem().getLabel() : "";
    }
}