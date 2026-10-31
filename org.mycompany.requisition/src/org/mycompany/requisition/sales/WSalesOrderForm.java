package org.mycompany.requisition.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.webui.component.AutoComplete;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Column;
import org.adempiere.webui.component.Columns;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.NumberBox;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.editor.WSearchEditor;
import org.adempiere.webui.event.ValueChangeEvent;
import org.adempiere.webui.event.ValueChangeListener;
import org.adempiere.webui.panel.ADForm;
import org.compiere.model.MBPartner;
import org.compiere.model.MBPartnerLocation;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MPriceList;
import org.compiere.model.MProduct;
import org.compiere.model.MProductPricing;
import org.compiere.model.MUOM;
import org.compiere.model.MWarehouse;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.InputEvent;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.South;
import org.zkoss.zul.Vlayout;

public class WSalesOrderForm extends ADForm implements ValueChangeListener, EventListener<Event> {
    private static final long serialVersionUID = 1L;

    /**
	 * Resolved on every use instead of being captured once, so the document is
	 * written with the tenant and organization of the request that triggered it.
	 */
	private Properties ctx() {
		return Env.getCtx();
	}

    // Header UI
    private WSearchEditor customerEditor;
    private Textbox txtCustomerName;
    private Textbox txtPhone;
    private Textbox txtEmail;
    private Textbox txtPriceList;

    private int m_C_BPartner_ID = 0;
    private int m_M_PriceList_ID = 0;
    private boolean isTaxIncluded = false;

    // Grid UI
    private Grid gridLines;
    private Rows rows;

    // Summary UI
    private Label lblTotalLines;
    private Label lblTotalQty;
    private Label lblSubtotal;
    private Label lblDiscount;
    private Label lblGrandTotal;

    private Button btnSaveDraft;
    private Button btnComplete;

    /** Minimum characters to type before suggestions are shown. */
    static final int MIN_SEARCH_CHARS = 1;
    /** Maximum number of suggestions offered at once. */
    static final int MAX_SUGGESTIONS = 20;

    /** Cached searchable product list (id + "Value_Name" + lower case search key). */
    private List<ProductItem> productItems;
    /** Cached client default sales price list. */
    private int productPriceList;

    @Override
    protected void initForm() {
        Borderlayout mainLayout = new Borderlayout();
        mainLayout.setWidth("100%");
        mainLayout.setHeight("100%");
        this.appendChild(mainLayout);

        // --- Header section ---
        North north = new North();
        north.setStyle("border: none; background: #f9f9f9; padding: 15px;");
        Vlayout headerLayout = new Vlayout();
        headerLayout.setSpacing("10px");
        headerLayout.setSclass("sales-header");

        Label title = new Label("SALES ORDER MANAGEMENT");
        title.setStyle("font-size: 16px; font-weight: bold; margin-bottom: 10px;");
        headerLayout.appendChild(title);

        Hlayout customerSearchLayout = new Hlayout();
        customerSearchLayout.setSpacing("15px");
        customerSearchLayout.setValign("middle");
        Label lblCustomer = new Label("Customer: ");
        lblCustomer.setStyle("font-weight: bold; width: 80px; display: inline-block;");
        customerSearchLayout.appendChild(lblCustomer);
        MLookup bpLookup = MLookupFactory.get(ctx(), m_WindowNo, 0, 2762, DisplayType.Search);
        customerEditor = new WSearchEditor("C_BPartner_ID", false, false, true, bpLookup);
        customerEditor.addValueChangeListener(this);
        customerEditor.getComponent().addEventListener(Events.ON_BLUR, this);
        customerSearchLayout.appendChild(customerEditor.getComponent());
        headerLayout.appendChild(customerSearchLayout);

        Hlayout customerInfoLayout = new Hlayout();
        customerInfoLayout.setSpacing("10px");
        customerInfoLayout.setStyle("margin-bottom: 10px;");
        txtCustomerName = new Textbox(); txtCustomerName.setReadonly(true); txtCustomerName.setWidth("150px");
        txtPhone = new Textbox(); txtPhone.setReadonly(true); txtPhone.setWidth("120px");
        txtEmail = new Textbox(); txtEmail.setReadonly(true); txtEmail.setWidth("150px");
        txtPriceList = new Textbox(); txtPriceList.setReadonly(true); txtPriceList.setWidth("150px");

        customerInfoLayout.appendChild(new Label("Name: ")); customerInfoLayout.appendChild(txtCustomerName);
        customerInfoLayout.appendChild(new Label("Phone: ")); customerInfoLayout.appendChild(txtPhone);
        customerInfoLayout.appendChild(new Label("Email: ")); customerInfoLayout.appendChild(txtEmail);
        customerInfoLayout.appendChild(new Label("Price List: ")); customerInfoLayout.appendChild(txtPriceList);
        headerLayout.appendChild(customerInfoLayout);

        north.appendChild(headerLayout);
        mainLayout.appendChild(north);

        // --- Grid section ---
        Center center = new Center();
        center.setAutoscroll(true);
        gridLines = new Grid();
        gridLines.setWidth("100%");

        Columns columns = new Columns();
        Column colProd = new Column("Product Search"); colProd.setWidth("25%"); columns.appendChild(colProd);
        Column colCode = new Column("Code"); colCode.setWidth("15%"); columns.appendChild(colCode);
        Column colUOM = new Column("UOM"); colUOM.setWidth("10%"); columns.appendChild(colUOM);
        Column colQty = new Column("Qty"); colQty.setWidth("10%"); columns.appendChild(colQty);
        Column colPrice = new Column("Unit Price"); colPrice.setWidth("10%"); columns.appendChild(colPrice);
        Column colDiscount = new Column("Discount %"); colDiscount.setWidth("10%"); columns.appendChild(colDiscount);
        Column colAmount = new Column("Amount"); colAmount.setWidth("15%"); columns.appendChild(colAmount);
        Column colAction = new Column("Action"); colAction.setWidth("5%"); colAction.setStyle("text-align: center;"); columns.appendChild(colAction);
        gridLines.appendChild(columns);

        rows = new Rows();
        gridLines.appendChild(rows);
        center.appendChild(gridLines);
        mainLayout.appendChild(center);

        // --- Summary section ---
        South south = new South();
        Vlayout footerLayout = new Vlayout();

        Hlayout summaryLayout = new Hlayout();
        lblTotalLines = new Label("Total Lines: 0");
        lblTotalQty = new Label("Total Qty: 0");
        lblSubtotal = new Label("Subtotal: 0.00");
        lblDiscount = new Label("Discount: 0.00");
        lblGrandTotal = new Label("GRAND TOTAL: 0.00");
        lblGrandTotal.setStyle("font-weight: bold; font-size: 14px;");

        summaryLayout.appendChild(lblTotalLines); summaryLayout.appendChild(new Label(" | "));
        summaryLayout.appendChild(lblTotalQty); summaryLayout.appendChild(new Label(" | "));
        summaryLayout.appendChild(lblSubtotal); summaryLayout.appendChild(new Label(" | "));
        summaryLayout.appendChild(lblDiscount); summaryLayout.appendChild(new Label(" | "));
        summaryLayout.appendChild(lblGrandTotal);
        footerLayout.appendChild(summaryLayout);

        Hlayout actionLayout = new Hlayout();
        btnSaveDraft = new Button("Save Draft");
        btnSaveDraft.addEventListener(Events.ON_CLICK, this);
        btnComplete = new Button("Complete Order");
        btnComplete.addEventListener(Events.ON_CLICK, this);
        actionLayout.appendChild(btnSaveDraft);
        actionLayout.appendChild(btnComplete);
        footerLayout.appendChild(actionLayout);

        south.appendChild(footerLayout);
        mainLayout.appendChild(south);

        // Start with exactly one empty row
        addNewEmptyRow();
    }

    @Override
    public void valueChange(ValueChangeEvent evt) {
        if (evt.getSource() == customerEditor) {
            loadCustomerInfo();
        }
    }

    private void loadCustomerInfo() {
        Object val = customerEditor.getValue();
        if (val == null) {
            m_C_BPartner_ID = 0;
            txtCustomerName.setValue("");
            txtPhone.setValue("");
            txtEmail.setValue("");
            txtPriceList.setValue("");
            m_M_PriceList_ID = 0;
            isTaxIncluded = false;
            repriceLines();
            return;
        }

        m_C_BPartner_ID = (Integer) val;
        MBPartner bp = new MBPartner(ctx(), m_C_BPartner_ID, null);
        txtCustomerName.setValue(bp.getName());

        MBPartnerLocation[] locs = bp.getLocations(false);
        if (locs.length > 0) {
            txtPhone.setValue(locs[0].getPhone() != null ? locs[0].getPhone() : "");
        }

        m_M_PriceList_ID = bp.getM_PriceList_ID();
        if (m_M_PriceList_ID <= 0)
            m_M_PriceList_ID = getDefaultSOPriceList();

        if (m_M_PriceList_ID > 0) {
            MPriceList pl = MPriceList.get(ctx(), m_M_PriceList_ID, null);
            txtPriceList.setValue(pl.getName());
            isTaxIncluded = pl.isTaxIncluded();
        } else {
            txtPriceList.setValue("No Price List");
            isTaxIncluded = false;
        }

        repriceLines();
    }

    /**
     * Re-reads the unit price of every product line already in the grid, so
     * picking a customer also refreshes prices of rows added beforehand.
     */
    private void repriceLines() {
        if (rows == null) return;
        for (Object child : rows.getChildren()) {
            if (child instanceof OrderLineRow) {
                OrderLineRow row = (OrderLineRow) child;
                if (row.isValidLine())
                    row.calculateRow();
            }
        }
        updateTotals();
    }

    /**
     * Fallback used when the business partner (and its BP group) has no sales
     * price list assigned. Without this the product prices would never be read
     * and every line would end up with a zero unit price.
     */
    private int getDefaultSOPriceList() {
        return DB.getSQLValue(null,
                "SELECT M_PriceList_ID FROM M_PriceList "
                        + "WHERE AD_Client_ID=? AND IsSOPriceList='Y' AND IsActive='Y' "
                        + "ORDER BY IsDefault DESC, M_PriceList_ID",
                Env.getAD_Client_ID(ctx()));
    }

    // ==============================================================
    //  CONTINUOUS EMPTY ROW ALGORITHM
    // ==============================================================
    // Rule: at any moment there must be exactly 1 empty row at the bottom.

    /**
     * Called ONLY when we need to append a brand-new empty row.
     * This is called once during initForm, and once each time
     * a row transitions from empty to valid.
     */
    private void addNewEmptyRow() {
        OrderLineRow emptyRow = new OrderLineRow(this);
        rows.appendChild(emptyRow);
    }

    /**
     * Called by OrderLineRow when a product is selected and the row
     * becomes valid. We simply append one new empty row.
     * Guard: only do this if the last child is NOT already empty.
     */
    protected void onRowBecameValid() {
        // Check if the very last row is already empty
        List<?> children = rows.getChildren();
        if (!children.isEmpty()) {
            Object last = children.get(children.size() - 1);
            if (last instanceof OrderLineRow && !((OrderLineRow) last).isValidLine()) {
                // Already have an empty row at bottom - do nothing
                return;
            }
        }
        addNewEmptyRow();
        updateTotals();
    }

    /**
     * Called by OrderLineRow when a product is cleared,
     * turning a valid row back to empty. If there are now
     * two empty rows we must remove the extra one.
     */
    protected void onRowBecameEmpty(OrderLineRow row) {
        // Count empty rows
        int emptyCount = 0;
        OrderLineRow firstExtra = null;
        List<?> children = new ArrayList<>(rows.getChildren());
        for (Object child : children) {
            if (child instanceof OrderLineRow) {
                OrderLineRow r = (OrderLineRow) child;
                if (!r.isValidLine()) {
                    emptyCount++;
                    if (emptyCount > 1 && firstExtra == null) {
                        firstExtra = r;
                    }
                }
            }
        }
        // If there are 2+ empty rows, remove the extra (the one that just became empty)
        if (emptyCount > 1 && firstExtra != null) {
            rows.removeChild(row); // remove the one that was cleared, keep the bottom one
        }
        updateTotals();
    }

    protected void updateTotals() {
        int lines = 0;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (Object child : rows.getChildren()) {
            if (child instanceof OrderLineRow) {
                OrderLineRow row = (OrderLineRow) child;
                if (row.isValidLine()) {
                    lines++;
                    totalQty = totalQty.add(row.getQty());
                    subtotal = subtotal.add(row.getGrossAmount());
                    totalDiscount = totalDiscount.add(row.getDiscountAmount());
                    grandTotal = grandTotal.add(row.getNetAmount());
                }
            }
        }

        lblTotalLines.setValue("Total Lines: " + lines);
        lblTotalQty.setValue("Total Qty: " + totalQty.toString());
        lblSubtotal.setValue("Subtotal: " + subtotal.toString());
        lblDiscount.setValue("Discount: " + totalDiscount.toString());
        lblGrandTotal.setValue("GRAND TOTAL: " + grandTotal.toString());
    }

    @Override
    public void onEvent(Event event) throws Exception {
        if (event.getTarget() == btnSaveDraft) {
            saveOrder("Save");
        } else if (event.getTarget() == btnComplete) {
            saveOrder("Complete");
        } else if (Events.ON_BLUR.equalsIgnoreCase(event.getName())) {
            // Commit a customer that was only typed and never picked from the
            // lookup, otherwise every product line would be priced without a
            // business partner and therefore show a zero unit price.
            resolveCustomerFromText();
        }
    }

    /**
     * Resolves the text typed into the customer search box to a business partner.
     * Only an exact code/name match is accepted, so the wrong customer can never
     * be attached silently. Does nothing once a customer is already set.
     */
    private void resolveCustomerFromText() {
        if (m_C_BPartner_ID > 0 || customerEditor == null)
            return;

        String text = customerEditor.getDisplay();
        if (text == null || text.trim().length() == 0)
            return;

        // the lookup shows "Value_Name", so also try the part before the separator
        String code = text.trim();
        int sep = code.indexOf('_');
        if (sep > 0)
            code = code.substring(0, sep);

        int bpId = findCustomerByExactMatch(text.trim());
        if (bpId <= 0 && !code.equalsIgnoreCase(text.trim()))
            bpId = findCustomerByExactMatch(code);
        if (bpId <= 0)
            return;

        customerEditor.setValue(Integer.valueOf(bpId));
        loadCustomerInfo();
    }

    private int findCustomerByExactMatch(String text) {
        String upper = text.toUpperCase();
        return DB.getSQLValue(null,
                "SELECT C_BPartner_ID FROM C_BPartner "
                        + "WHERE AD_Client_ID=? AND IsActive='Y' AND IsCustomer='Y' "
                        + "AND (UPPER(Value)=? OR UPPER(Name)=?) ORDER BY C_BPartner_ID",
                Env.getAD_Client_ID(ctx()), upper, upper);
    }

    private void saveOrder(String docAction) {
        if (m_C_BPartner_ID <= 0) {
            Messagebox.show("Please select a customer.", "Error", Messagebox.OK, Messagebox.ERROR);
            return;
        }

        List<OrderLineRow> validRows = new ArrayList<>();
        for (Object child : rows.getChildren()) {
            if (child instanceof OrderLineRow) {
                OrderLineRow row = (OrderLineRow) child;
                if (row.isValidLine()) {
                    if (row.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                        Messagebox.show("Quantity must be greater than zero.", "Error", Messagebox.OK, Messagebox.ERROR);
                        return;
                    }
                    validRows.add(row);
                }
            }
        }

        if (validRows.isEmpty()) {
            Messagebox.show("Please add at least one valid product line.", "Error", Messagebox.OK, Messagebox.ERROR);
            return;
        }

        Trx trx = Trx.get(Trx.createTrxName("WSalesOrder"), true);
        try {
            MBPartner bp = new MBPartner(ctx(), m_C_BPartner_ID, trx.getTrxName());
            MBPartnerLocation[] locs = bp.getLocations(false);
            int C_BPartner_Location_ID = locs.length > 0 ? locs[0].getC_BPartner_Location_ID() : 0;

            MOrder order = new MOrder(ctx(), 0, trx.getTrxName());
            order.setIsSOTrx(true);
            order.setC_DocTypeTarget_ID();
            order.setC_BPartner_ID(m_C_BPartner_ID);
            if (C_BPartner_Location_ID > 0) order.setC_BPartner_Location_ID(C_BPartner_Location_ID);
            if (m_M_PriceList_ID > 0) order.setM_PriceList_ID(m_M_PriceList_ID);
            order.setDateOrdered(new Timestamp(System.currentTimeMillis()));
            order.setSalesRep_ID(Env.getAD_User_ID(ctx()));
            order.setIsTaxIncluded(isTaxIncluded);

            int M_Warehouse_ID = Env.getContextAsInt(ctx(), Env.M_WAREHOUSE_ID);
            if (M_Warehouse_ID <= 0)
                M_Warehouse_ID = getDefaultWarehouse();
            if (M_Warehouse_ID <= 0) {
                trx.rollback();
                Messagebox.show("No warehouse available. Please define an active warehouse for this organization.",
                        "Error", Messagebox.OK, Messagebox.ERROR);
                return;
            }
            order.setM_Warehouse_ID(M_Warehouse_ID);

            // Keep the order in the warehouse's organization, otherwise iDempiere
            // reports WarehouseOrgConflict and cannot determine the correct taxes.
            MWarehouse wh = MWarehouse.get(ctx(), M_Warehouse_ID, trx.getTrxName());
            if (wh != null && wh.getAD_Org_ID() > 0 && wh.getAD_Org_ID() != order.getAD_Org_ID())
                order.setAD_Org_ID(wh.getAD_Org_ID());

            if (!order.save()) {
                String reason = CLogger.retrieveErrorString(null);
                trx.rollback();
                Messagebox.show("Sales Order could not be saved"
                        + (reason != null && reason.trim().length() > 0 ? ": " + reason : "."),
                        "Error", Messagebox.OK, Messagebox.ERROR);
                return;
            }

            int lineNo = 10;
            for (OrderLineRow row : validRows) {
                MOrderLine line = new MOrderLine(order);
                line.setLine(lineNo);
                line.setM_Product_ID(row.getM_Product_ID());
                line.setQty(row.getQty());
                line.setPrice(row.getPriceActual());
                line.setPriceList(row.getPriceList());
                line.setC_UOM_ID(row.getC_UOM_ID());
                line.setDiscount(row.getDiscountPercent());
                if (!line.save()) {
                    String reason = CLogger.retrieveErrorString(null);
                    trx.rollback();
                    Messagebox.show("Failed to save order line for product " + row.getM_Product_ID()
                                    + (reason != null && reason.trim().length() > 0 ? ": " + reason : ""),
                            "Error", Messagebox.OK, Messagebox.ERROR);
                    return;
                }
                lineNo += 10;
            }

            if ("Complete".equals(docAction)) {
                order.setDocAction(org.compiere.process.DocAction.ACTION_Complete);
                if (!order.processIt(org.compiere.process.DocAction.ACTION_Complete)) {
                    trx.rollback();
                    Messagebox.show("Failed to complete order: " + order.getProcessMsg(), "Error", Messagebox.OK, Messagebox.ERROR);
                    return;
                }
                order.saveEx();
            }

            trx.commit();
            Messagebox.show("Sales Order " + order.getDocumentNo() + " was saved successfully.", "Success", Messagebox.OK, Messagebox.INFORMATION);

            // Reset the form
            customerEditor.setValue(null);
            for (Object child : new ArrayList<>(rows.getChildren())) {
                if (child instanceof OrderLineRow)
                    rows.removeChild((OrderLineRow) child);
            }
            loadCustomerInfo();
            addNewEmptyRow();
            updateTotals();

        } catch (Exception e) {
            trx.rollback();
            Messagebox.show("Error: " + e.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
        } finally {
            trx.close();
        }
    }

    public int getM_PriceList_ID() {
        return m_M_PriceList_ID;
    }

    public int getC_BPartner_ID() {
        return m_C_BPartner_ID;
    }

    /**
     * Price list that lines are priced with. Falls back to the client default
     * sales price list so a product always gets a price, even while the customer
     * has not been committed in the search box yet.
     */
    int getEffectivePriceListID() {
        if (m_M_PriceList_ID > 0)
            return m_M_PriceList_ID;
        if (productPriceList == 0)
            productPriceList = getDefaultSOPriceList();
        return productPriceList;
    }

    /**
     * All active products, loaded once and searched in memory.
     * <p>
     * The standard {@link MLookup} cannot be used here: for
     * {@link DisplayType#Search} lookups {@code refresh()} intentionally skips
     * preloading ({@code MLookup.refresh()} calls {@code removeAllElements()} and
     * returns 0), so the item list would always be empty. Each entry keeps a
     * lower cased "Value_Name" key so suggestions match with a plain contains().
     */
    List<ProductItem> getProductItems() {
        if (productItems == null) {
            productItems = new ArrayList<>();
            String sql = "SELECT M_Product_ID, Value, Name FROM M_Product "
                    + "WHERE AD_Client_ID=? AND IsActive='Y' ORDER BY Value";
            PreparedStatement pstmt = DB.prepareStatement(sql, null);
            ResultSet rs = null;
            try {
                pstmt.setInt(1, Env.getAD_Client_ID(ctx()));
                rs = pstmt.executeQuery();
                while (rs.next()) {
                    String value = rs.getString(2);
                    String name = rs.getString(3);
                    String label = buildLabel(value, name);
                    if (label.length() > 0)
                        productItems.add(new ProductItem(rs.getInt(1), label));
                }
            } catch (SQLException e) {
                CLogger.getCLogger(WSalesOrderForm.class)
                        .log(Level.SEVERE, "Cannot load products for the sales order search", e);
            } finally {
                DB.close(rs, pstmt);
            }
            if (productItems.isEmpty())
                CLogger.getCLogger(WSalesOrderForm.class)
                        .log(Level.WARNING, "No products loaded for the sales order product search");
        }
        return productItems;
    }

    /** Same "Value_Name" layout iDempiere uses for a product search display. */
    private static String buildLabel(String value, String name) {
        boolean hasValue = value != null && value.trim().length() > 0;
        boolean hasName = name != null && name.trim().length() > 0;
        if (hasValue && hasName)
            return value.trim() + "_" + name.trim();
        if (hasValue)
            return value.trim();
        if (hasName)
            return name.trim();
        return "";
    }

    /**
     * Warehouse used for the order. The session context of a custom form does
     * not always carry #M_Warehouse_ID, and C_Order.M_Warehouse_ID is mandatory,
     * so fall back to the first warehouse of the current organization. Not every
     * organization owns a warehouse (e.g. "Stores" has none), in that case the
     * first warehouse of the tenant is used and the order is moved to its
     * organization.
     */
    private int getDefaultWarehouse() {
        int warehouseId = DB.getSQLValue(null,
                "SELECT M_Warehouse_ID FROM M_Warehouse "
                        + "WHERE AD_Client_ID=? AND AD_Org_ID IN (0,?) "
                        + "AND IsActive='Y' AND IsInTransit='N' "
                        + "ORDER BY AD_Org_ID DESC, M_Warehouse_ID",
                Env.getAD_Client_ID(ctx()), Env.getAD_Org_ID(ctx()));

        if (warehouseId <= 0) {
            warehouseId = DB.getSQLValue(null,
                    "SELECT M_Warehouse_ID FROM M_Warehouse "
                            + "WHERE AD_Client_ID=? AND IsActive='Y' AND IsInTransit='N' "
                            + "ORDER BY AD_Org_ID, M_Warehouse_ID",
                    Env.getAD_Client_ID(ctx()));
            if (warehouseId > 0)
                CLogger.getCLogger(WSalesOrderForm.class).log(Level.WARNING,
                        "Organization " + Env.getAD_Org_ID(ctx())
                                + " has no warehouse, using warehouse " + warehouseId);
        }
        return warehouseId;
    }

    /** Value/Name pair of a product, used as search key. */
    static class ProductItem {
        final int id;
        final String label;
        final String searchKey;

        ProductItem(int id, String label) {
            this.id = id;
            this.label = label;
            this.searchKey = label.toLowerCase();
        }
    }

    public boolean isTaxIncluded() {
        return isTaxIncluded;
    }

    public void removeRow(OrderLineRow row) {
        rows.removeChild(row);
        // After removing, ensure exactly 1 empty row remains
        int emptyCount = 0;
        for (Object child : rows.getChildren()) {
            if (child instanceof OrderLineRow && !((OrderLineRow) child).isValidLine()) {
                emptyCount++;
            }
        }
        if (emptyCount == 0) {
            addNewEmptyRow();
        }
        updateTotals();
    }

    /**
     * Product search field with Google like suggestions: as soon as the user
     * types, every product whose code or name contains the typed text is offered.
     * <p>
     * Extends the standard iDempiere {@link AutoComplete} combobox but replaces
     * its client side filtering with a substring match that already works from
     * the first character.
     * <p>
     * NOTE: this class has to stay <b>public</b>. ZK dispatches the
     * {@code onChanging} handler by reflection and a handler declared in a
     * package private class fails the access check, so the suggestions would
     * silently never be built.
     */
    public static class ProductSearchEditor extends AutoComplete implements EventListener<Event> {
        private static final long serialVersionUID = 1L;

        private final OrderLineRow row;
        private final WSalesOrderForm parentForm;
        private Integer m_M_Product_ID;

        ProductSearchEditor(OrderLineRow row, WSalesOrderForm parentForm) {
            this.row = row;
            this.parentForm = parentForm;

            setAutocomplete(true);
            setAutodrop(true);
            setInstantSelect(false);
            setWidth("90%");

            // Registered explicitly instead of relying on ZK's naming convention,
            // which dispatches "onChanging" by reflection.
            addEventListener(Events.ON_CHANGING, this);
            addEventListener(Events.ON_CHANGE, this);
            addEventListener(Events.ON_SELECT, this);
            addEventListener(Events.ON_BLUR, this);
        }

        /**
         * Intentionally left empty: the base class calls it from setValue(),
         * which ZK triggers while applying a selection. Rebuilding the items
         * there would throw the just selected entry away.
         */
        @Override
        public void refresh(String val) {
        }

        /**
         * Builds the suggestion list for the given search text. Every product
         * whose "Value_Name" contains the text is offered, so a single character
         * already lists the matching products.
         */
        private void updateSuggestions(String text) {
            clearSuggestions();

            String compare = text == null ? "" : text.trim().toLowerCase();
            if (compare.length() < MIN_SEARCH_CHARS)
                return;

            List<ProductItem> products = parentForm.getProductItems();
            int shown = 0;
            for (ProductItem item : products) {
                if (item.searchKey.indexOf(compare) < 0)
                    continue;
                Comboitem comboitem = new Comboitem(item.label);
                comboitem.setValue(Integer.valueOf(item.id));
                appendChild(comboitem);
                if (++shown >= MAX_SUGGESTIONS)
                    break;
            }

            if (shown == 0)
                CLogger.getCLogger(ProductSearchEditor.class).log(Level.WARNING,
                        "product search '" + compare + "' matched none of the " + products.size() + " products");

            if (shown > 0)
                setOpen(true);
        }

        private void clearSuggestions() {
            while (getItemCount() > 0)
                removeItemAt(0);
        }

        @Override
        public void onEvent(Event event) throws Exception {
            if (event.getTarget() != this)
                return;

            if (Events.ON_CHANGING.equalsIgnoreCase(event.getName())) {
                InputEvent evt = (InputEvent) event;
                if (!evt.isChangingBySelectBack())
                    updateSuggestions(evt.getValue());
                return;
            }

            if (Events.ON_CHANGE.equalsIgnoreCase(event.getName())
                    && getSelectedItem() == null
                    && getValue() != null
                    && getValue().trim().length() >= MIN_SEARCH_CHARS) {
                // safety net: rebuilds the list when the change event is sent
                // without a preceding onChanging (e.g. Enter in the field)
                updateSuggestions(getValue());
                return;
            }

            if (Events.ON_SELECT.equalsIgnoreCase(event.getName())) {
                Comboitem selected = getSelectedItem();
                Integer newId = selected != null && selected.getValue() instanceof Integer
                        ? (Integer) selected.getValue()
                        : null;
                if (!eq(newId, m_M_Product_ID)) {
                    m_M_Product_ID = newId;
                    clearSuggestions();
                    row.onProductChanged();
                }
            } else if (Events.ON_BLUR.equalsIgnoreCase(event.getName())) {
                // Text that was never matched by a selection is discarded, so the
                // row never shows a product different from the one in the database.
                String typed = getValue() == null ? "" : getValue().trim();
                if (typed.length() == 0) {
                    if (m_M_Product_ID != null) {
                        m_M_Product_ID = null;
                        clearSuggestions();
                        row.onProductChanged();
                    }
                } else if (getSelectedItem() == null) {
                    restoreSelectedLabel();
                }
            }
        }

        private static boolean eq(Integer a, Integer b) {
            return a == null ? b == null : a.equals(b);
        }

        private void restoreSelectedLabel() {
            String label = null;
            if (m_M_Product_ID != null) {
                for (ProductItem item : parentForm.getProductItems()) {
                    if (item.id == m_M_Product_ID.intValue()) {
                        label = item.label;
                        break;
                    }
                }
            }
            setText(label == null ? "" : label);
            clearSuggestions();
        }

        Integer getProductId() {
            return m_M_Product_ID;
        }
    }
}

class OrderLineRow extends Row implements EventListener<Event> {
    private static final long serialVersionUID = 1L;

    private WSalesOrderForm parentForm;
    private WSalesOrderForm.ProductSearchEditor productEditor;
    private Textbox txtCode;
    private Textbox txtUOM;
    private NumberBox numQty;
    private NumberBox numPrice;
    private NumberBox numDiscount;
    private NumberBox numAmount;
    private Button btnRemove;

    private int m_M_Product_ID = 0;
    private int m_C_UOM_ID = 0;
    private BigDecimal priceList = BigDecimal.ZERO;
    private BigDecimal priceActual = BigDecimal.ZERO;
    private boolean validLine = false;

    /**
     * Recalculates price and amount. Used as the change listener of the quantity
     * and the discount field: {@link NumberBox} forwards the listener to its
     * inner text box, so the event target is never the NumberBox itself and
     * cannot be compared here.
     */
    private final EventListener<Event> recalcListener = new EventListener<Event>() {
        @Override
        public void onEvent(Event event) {
            calculateRow();
            parentForm.updateTotals();
        }
    };

    public OrderLineRow(WSalesOrderForm parentForm) {
        this.parentForm = parentForm;

        productEditor = new WSalesOrderForm.ProductSearchEditor(this, parentForm);
        this.appendChild(productEditor);

        txtCode = new Textbox(); txtCode.setReadonly(true); txtCode.setWidth("90%");
        this.appendChild(txtCode);

        txtUOM = new Textbox(); txtUOM.setReadonly(true); txtUOM.setWidth("90%");
        this.appendChild(txtUOM);

        numQty = new NumberBox(false);
        numQty.addEventListener(Events.ON_CHANGE, recalcListener);
        this.appendChild(numQty);

        numPrice = new NumberBox(false); numPrice.setEnabled(false);
        this.appendChild(numPrice);

        numDiscount = new NumberBox(false);
        numDiscount.addEventListener(Events.ON_CHANGE, recalcListener);
        this.appendChild(numDiscount);

        numAmount = new NumberBox(false); numAmount.setEnabled(false);
        this.appendChild(numAmount);

        btnRemove = new Button("X");
        btnRemove.addEventListener(Events.ON_CLICK, this);
        this.appendChild(btnRemove);
    }

    @Override
    public void onEvent(Event event) throws Exception {
        if (event.getTarget() == btnRemove) {
            parentForm.removeRow(this);
        }
    }

    private void handleProductSelection() {
        Integer productId = productEditor.getProductId();
        if (productId == null || productId.intValue() <= 0) {
            // Row was cleared - was it previously valid?
            boolean wasValid = validLine;
            validLine = false;
            m_M_Product_ID = 0;
            txtCode.setValue("");
            txtUOM.setValue("");
            numQty.setValue(BigDecimal.ZERO);
            numPrice.setValue(BigDecimal.ZERO);
            numDiscount.setValue(BigDecimal.ZERO);
            numAmount.setValue(BigDecimal.ZERO);
            if (wasValid) {
                parentForm.onRowBecameEmpty(this);
            }
            return;
        }

        // A valid product was selected
        boolean wasEmpty = !validLine;

        m_M_Product_ID = productId.intValue();
        MProduct product = MProduct.get(Env.getCtx(), m_M_Product_ID);
        txtCode.setValue(product.getValue());

        m_C_UOM_ID = product.getC_UOM_ID();
        MUOM uom = MUOM.get(Env.getCtx(), m_C_UOM_ID);
        txtUOM.setValue(uom.getUOMSymbol());

        numQty.setValue(BigDecimal.ONE);
        numDiscount.setValue(BigDecimal.ZERO);

        validLine = true;
        calculateRow();

        // If this row WAS the empty row, tell parent to create a new empty row
        if (wasEmpty) {
            parentForm.onRowBecameValid();
        }
    }

    /**
     * Reads the price of the selected product from the customer's sales price
     * list (falling back to the client default one) and stores it as unit
     * price. Uses the current qty so volume breaks are taken into account.
     * <p>
     * A missing business partner only means that no partner discount can be
     * applied - it must not zero the price, so the client default price list is
     * used in that case as well.
     */
    private void loadUnitPrice() {
        int m_PriceList_ID = parentForm.getEffectivePriceListID();
        int bpId = parentForm.getC_BPartner_ID();

        if (m_PriceList_ID <= 0) {
            priceList = BigDecimal.ZERO;
            priceActual = BigDecimal.ZERO;
            numPrice.setValue(priceActual);
            CLogger.getCLogger(OrderLineRow.class).log(Level.WARNING,
                    "No sales price list available - product " + m_M_Product_ID + " has no unit price");
            return;
        }

        BigDecimal qty = numQty.getValue();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0)
            qty = BigDecimal.ONE;

        MProductPricing pp = new MProductPricing(m_M_Product_ID, bpId, qty, true, null);
        pp.setM_PriceList_ID(m_PriceList_ID);
        pp.setPriceDate(new Timestamp(System.currentTimeMillis()));
        pp.calculatePrice();
        priceList = pp.getPriceList();
        priceActual = pp.getPriceStd();

        numPrice.setValue(priceActual);
    }

    public void calculateRow() {
        if (!validLine) return;

        BigDecimal qty = numQty.getValue();
        if (qty == null) qty = BigDecimal.ZERO;

        BigDecimal discount = numDiscount.getValue();
        if (discount == null) discount = BigDecimal.ZERO;

        loadUnitPrice();

        BigDecimal grossAmt = qty.multiply(priceActual);
        BigDecimal discountAmt = grossAmt.multiply(discount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
        BigDecimal netAmt = grossAmt.subtract(discountAmt);

        numAmount.setValue(netAmt);
    }

    public boolean isValidLine() { return validLine; }
    public int getM_Product_ID() { return m_M_Product_ID; }
    public int getC_UOM_ID() { return m_C_UOM_ID; }
    public BigDecimal getQty() { return numQty.getValue() != null ? numQty.getValue() : BigDecimal.ZERO; }
    public BigDecimal getPriceList() { return priceList; }
    public BigDecimal getPriceActual() { return priceActual; }
    public BigDecimal getDiscountPercent() { return numDiscount.getValue() != null ? numDiscount.getValue() : BigDecimal.ZERO; }

    public BigDecimal getGrossAmount() {
        return getQty().multiply(priceActual);
    }

    public BigDecimal getDiscountAmount() {
        return getGrossAmount().multiply(getDiscountPercent()).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal getNetAmount() {
        return numAmount.getValue() != null ? numAmount.getValue() : BigDecimal.ZERO;
    }

    /** Called by {@link ProductSearchEditor} whenever the selected product changes. */
    void onProductChanged() {
        handleProductSelection();
    }
}

