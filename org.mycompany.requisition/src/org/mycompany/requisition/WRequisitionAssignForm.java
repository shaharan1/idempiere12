package org.mycompany.requisition;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.model.MActivity;
import org.compiere.model.MDocType;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MProduct;
import org.compiere.model.MRequisition;
import org.compiere.model.MRequisitionLine;
import org.compiere.model.MUOM;
import org.compiere.model.MWarehouse;
import org.compiere.print.ReportEngine;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Combobox;
import org.zkoss.zul.Comboitem;
import org.zkoss.zul.Div;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;

/**
 * Requisition Assignment Form.
 * <p>
 * Lists completed requisitions (filters on top). Click a requisition (or tick it) in the Requisition Assignment Line:
 * all its data is loaded into the "Requisition Assignment" master panel (header, delivery, products of the requisition,
 * who it is assigned to) and it can be assigned from there with the Assign button.
 * Not Assigned requisitions get the user chosen in "Assign To" (date = today); Assigned ones can be moved to another
 * user with "Re-Assign To". The ticked rows can still be saved together with OK (as before).
 * Print = requisition PDF, PO "View" = purchase order PDF, Close = closes the requisition.
 * Storage: M_Requisition.XX_AssignedTo_ID, XX_AssignedDate, XX_DeliveryDate, XX_ReassignedTo_ID, XX_ReassignedDate
 * (created by db/25_requisition_assignment.sql).
 */
public class WRequisitionAssignForm extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WRequisitionAssignForm.class);
	private static final String PDF_JRXML = "/org/mycompany/requisition/PurchaseRequisition.jrxml";
	private static final int MAX_ROWS = 500;

	private final int clientId = Env.getAD_Client_ID(Env.getCtx());
	private final int userRoleId = Env.getAD_Role_ID(Env.getCtx());

	// filters
	private Combobox cbOrg, cbUser, cbPriority, cbLocation, cbFAssigned, cbSegment, cbDocType, cbStatus, cbPo;
	private final Combobox txtReq = combo();
	private final Combobox txtCat = combo();
	private final Datebox dtFrom = new Datebox();
	private final Datebox dtTo = new Datebox();
	private final Button btnReload = new Button("Reload");
	private final Button btnOk = new Button("✔");
	private final Button btnCancel = new Button("✖");
	private final Listbox list = new Listbox();
	private final Label lblInfo = new Label();
	private final List<Row> rows = new ArrayList<Row>();
	private List<Object[]> userRows = new ArrayList<Object[]>();

	// master panel: the requisition that was clicked
	private Row current;
	private final Label lblSelected = new Label();
	private final Label mNo = mv(), mDate = mv(), mReq = mv(), mOrg = mv(), mType = mv(), mPrio = mv(), mLoc = mv(),
			mSeg = mv(), mDept = mv(), mUser = mv(), mWh = mv(), mCat = mv(), mStatus = mv(), mPo = mv(),
			mCurAssign = mv();
	private Combobox cbAssignTo;
	private Combobox cbReassign;
	private final Datebox dtDelivery = new Datebox();
	private final Button btnAssign = new Button("Assign");
	private final Button btnClear = new Button("Clear Selection");
	private final Listbox mLines = new Listbox();

	private static final class Row {
		int id;
		String docNo, org, user, cat, prio, status, assignedName, reName, loc, seg;
		Timestamp dateDoc, dateReq, assignedDate, deliveryDate;
		int assignedId, reId, deptId;
		Checkbox chk = new Checkbox();
		Button print = new Button("Print");
		Datebox delivery = new Datebox();
		Combobox reassign = new Combobox();
		Button view = new Button("View");
		Button close = new Button("Close");
	}

	@Override
	protected void initForm() {
		org.mycompany.requisition.ui.XxTheme.wrap(this);
		try {
			buildUi();
			loadCombos();
			Timestamp now = new Timestamp(System.currentTimeMillis());
			java.util.Calendar c = java.util.Calendar.getInstance();
			c.set(java.util.Calendar.MONTH, 0);
			c.set(java.util.Calendar.DAY_OF_MONTH, 1);
			dtFrom.setValue(c.getTime());
			dtTo.setValue(now);
			cbStatus.setSelectedIndex(2); // Not Assigned
			reload();
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			throw new AdempiereException(e);
		}
	}

	// ------------------------------------------------------------ UI

	private static Combobox combo() {
		Combobox cb = new Combobox();
		cb.setAutodrop(true);
		cb.setAutocomplete(true);
		cb.setButtonVisible(true);
		ZKUpdateUtil.setWidth(cb, "100%");
		return cb;
	}

	/** a value label of the master panel */
	private static Label mv() {
		Label l = new Label();
		l.setStyle("font-weight:600;color:#1F2937");
		return l;
	}

	private Grid newGrid(String sclass, org.zkoss.zul.Rows rs) {
		Grid g = new Grid();
		g.setSclass(sclass);
		org.zkoss.zul.Columns cols = new org.zkoss.zul.Columns();
		for (int i = 0; i < 6; i++)
			cols.appendChild(new org.zkoss.zul.Column());
		g.appendChild(cols);
		g.appendChild(rs);
		return g;
	}

	private void buildUi() {
		Div main = new Div();
		main.setStyle("height:100%;display:flex;flex-direction:column;box-sizing:border-box;overflow:auto;padding:6px 10px;");
		appendChild(main);
		ZKUpdateUtil.setWidth(this, "99%");
		ZKUpdateUtil.setHeight(this, "100%");

		cbOrg = combo();
		cbUser = combo();
		cbPriority = combo();
		cbLocation = combo();
		cbFAssigned = combo();
		cbSegment = combo();
		cbDocType = combo();
		cbStatus = combo();
		cbPo = combo();
		cbAssignTo = combo();
		cbReassign = combo();
		dtFrom.setFormat("dd/MM/yyyy");
		dtTo.setFormat("dd/MM/yyyy");
		dtDelivery.setFormat("dd/MM/yyyy");

		// ---- search / filter
		org.zkoss.zul.Rows fr = new org.zkoss.zul.Rows();
		Grid g = newGrid("xx-assign-filter", fr);
		Div dates = new Div();
		dates.setStyle("display:flex;gap:4px");
		dates.appendChild(dtFrom);
		dates.appendChild(dtTo);
		frow(fr, "Organization", cbOrg, "Priority", cbPriority, "Date Range", dates);
		frow(fr, "User", cbUser, "Location", cbLocation, "Status", cbStatus);
		frow(fr, "Requisition", txtReq, "Category", txtCat, "PO Created", cbPo);
		frow(fr, "Assigned To", cbFAssigned, "Segment", cbSegment, "Document Type", cbDocType);
		Div br = new Div();
		br.setStyle("text-align:right;padding:4px 0");
		btnReload.setSclass("btn btn-primary");
		br.appendChild(btnReload);

		main.appendChild(title("Search Requisition"));
		main.appendChild(g);
		main.appendChild(br);

		// ---- master: the requisition that was clicked in the line below
		main.appendChild(title("Requisition Assignment"));
		lblSelected.setStyle("display:block;padding:4px 8px;margin-bottom:4px;border-radius:6px;background:#E6EDF5;color:#27415F;font-weight:600");
		main.appendChild(lblSelected);
		org.zkoss.zul.Rows mr = new org.zkoss.zul.Rows();
		Grid mg = newGrid("xx-assign-master", mr);
		frow(mr, "Requisition No", mNo, "Requisition Date", mDate, "Date Required", mReq);
		frow(mr, "Organization", mOrg, "Requisition Type", mType, "Priority", mPrio);
		frow(mr, "Location", mLoc, "Segment", mSeg, "Department", mDept);
		frow(mr, "Requested By", mUser, "Warehouse", mWh, "Category", mCat);
		frow(mr, "Status", mStatus, "Purchase Order", mPo, "Currently Assigned", mCurAssign);
		ZKUpdateUtil.setWidth(dtDelivery, "100%");
		frow(mr, "Assign To", cbAssignTo, "Delivery Date", dtDelivery, "Re-Assign To", cbReassign);
		main.appendChild(mg);
		Div ab = new Div();
		ab.setStyle("text-align:right;padding:4px 0");
		btnAssign.setSclass("btn btn-success");
		btnClear.setSclass("btn btn-outline-primary");
		btnClear.setStyle("margin-left:6px");
		ab.appendChild(btnAssign);
		ab.appendChild(btnClear);
		main.appendChild(ab);

		// products of the requisition that was clicked
		Listhead mh = new Listhead();
		String[][] mc = { { "S/N", "50px" }, { "Item Code", "130px" }, { "Item Name", "300px" }, { "UOM", "70px" },
				{ "Qty", "90px" }, { "Specification", "300px" } };
		for (String[] x : mc) {
			Listheader h = new Listheader(x[0]);
			h.setHflex(x[1].replace("px", ""));
			mh.appendChild(h);
		}
		mLines.appendChild(mh);
		mLines.setEmptyMessage("Click a requisition below to see its products.");
		mLines.setStyle("max-height:200px");
		main.appendChild(mLines);

		main.appendChild(title("Requisition Assignment Line"));

		Listhead lh = new Listhead();
		lh.setSizable(true);
		String[][] h = { { "Select", "55px" }, { "Print", "75px" }, { "Requisition Date", "95px" },
				{ "Requisition No", "150px" }, { "Category", "150px" }, { "Organization", "140px" },
				{ "Date Required", "95px" }, { "User", "110px" }, { "SCM Assigned", "130px" },
				{ "Assigned Date", "105px" }, { "Delivery Date", "120px" }, { "Priority", "70px" },
				{ "Re-Assigned", "140px" }, { "PO No", "75px" }, { "Close", "75px" } };
		for (String[] x : h) {
			Listheader lhd = new Listheader(x[0]);
			lhd.setHflex(x[1].replace("px", ""));
			lhd.setStyle("white-space:nowrap");
			lh.appendChild(lhd);
		}
		list.appendChild(lh);
		list.setHflex("1");
		list.setStyle("min-height:260px;");
		main.appendChild(list);

		Div foot = new Div();
		foot.setStyle("display:flex;justify-content:space-between;align-items:center;padding:8px 0");
		foot.appendChild(lblInfo);
		Div r = new Div();
		btnOk.setSclass("btn btn-success");
		btnCancel.setSclass("btn btn-danger");
		r.appendChild(btnOk);
		r.appendChild(btnCancel);
		foot.appendChild(r);
		main.appendChild(foot);

		btnReload.addEventListener(Events.ON_CLICK, ev -> reload());
		txtReq.addEventListener(Events.ON_OK, ev -> reload());
		txtCat.addEventListener(Events.ON_OK, ev -> reload());
		txtReq.addEventListener(Events.ON_SELECT, ev -> reload());
		txtCat.addEventListener(Events.ON_SELECT, ev -> reload());
		btnOk.addEventListener(Events.ON_CLICK, ev -> save());
		btnAssign.addEventListener(Events.ON_CLICK, ev -> assignCurrent());
		btnClear.addEventListener(Events.ON_CLICK, ev -> clearMaster());
		btnCancel.addEventListener(Events.ON_CLICK, ev -> {
			try {
				SessionManager.getAppDesktop().closeActiveWindow();
			} catch (Exception e) {
				reload();
			}
		});
		clearMaster();
	}

	private static Label title(String t) {
		Label l = new Label(t);
		l.setStyle("display:block;font-weight:700;margin:8px 0 4px");
		return l;
	}

	private void frow(org.zkoss.zul.Rows rs, String l1, org.zkoss.zk.ui.Component c1, String l2,
			org.zkoss.zk.ui.Component c2, String l3, org.zkoss.zk.ui.Component c3) {
		org.zkoss.zul.Row r = new org.zkoss.zul.Row();
		r.appendChild(lbl(l1));
		r.appendChild(c1);
		r.appendChild(lbl(l2));
		r.appendChild(c2);
		r.appendChild(lbl(l3));
		r.appendChild(c3);
		rs.appendChild(r);
	}

	private static Label lbl(String t) {
		Label l = new Label(t);
		l.setStyle("font-weight:700");
		return l;
	}

	// ------------------------------------------------------------ master panel (read through the model)

	/** name of a list entry (the combo that was filled from the list: attribute "id" = value, label = name) */
	private static String labelOf(Combobox cb, String value) {
		if (value == null || value.isEmpty())
			return "";
		for (Object o : cb.getItems()) {
			Comboitem ci = (Comboitem) o;
			if (value.equals(String.valueOf(ci.getAttribute("id"))))
				return ci.getLabel();
		}
		return value;
	}

	/** nothing selected: the master panel is empty and its inputs are off */
	private void clearMaster() {
		current = null;
		lblSelected.setValue("No requisition selected. Click a requisition in the Requisition Assignment Line.");
		for (Label l : new Label[] { mNo, mDate, mReq, mOrg, mType, mPrio, mLoc, mSeg, mDept, mUser, mWh, mCat, mStatus,
				mPo, mCurAssign })
			l.setValue("");
		cbAssignTo.setValue("");
		cbReassign.setValue("");
		dtDelivery.setValue(null);
		cbAssignTo.setDisabled(true);
		cbReassign.setDisabled(true);
		dtDelivery.setDisabled(true);
		btnAssign.setDisabled(true);
		while (mLines.getItemCount() > 0)
			mLines.removeItemAt(0);
	}

	/** a requisition of the line was clicked / ticked: load all its data into the master panel */
	private void selectRow(Row r) {
		if (current == r)
			return;
		current = r;
		MRequisition req = new MRequisition(Env.getCtx(), r.id, null);
		boolean closed = "CL".equals(r.status);
		boolean assigned = r.assignedId > 0;

		lblSelected.setValue("Selected requisition: " + r.docNo
				+ (closed ? "   (closed: view only)" : assigned ? "   (assigned: use Re-Assign To to move it)"
						: "   (not assigned yet: choose Assign To)"));
		mNo.setValue(r.docNo);
		mDate.setValue(dmy(r.dateDoc));
		mReq.setValue(dmy(r.dateReq));
		mOrg.setValue(r.org == null ? "" : r.org);
		MDocType dt = req.getC_DocType_ID() > 0 ? MDocType.get(Env.getCtx(), req.getC_DocType_ID()) : null;
		mType.setValue(dt == null ? "" : dt.getName());
		mPrio.setValue(prioName(r.prio));
		mLoc.setValue(labelOf(cbLocation, r.loc));
		mSeg.setValue(labelOf(cbSegment, r.seg));
		mDept.setValue(r.deptId > 0 ? new MActivity(Env.getCtx(), r.deptId, null).getName() : "");
		mUser.setValue(r.user == null ? "" : r.user);
		MWarehouse wh = req.getM_Warehouse_ID() > 0 ? MWarehouse.get(Env.getCtx(), req.getM_Warehouse_ID()) : null;
		mWh.setValue(wh == null ? "" : wh.getName());
		mCat.setValue(r.cat == null ? "" : r.cat);
		mStatus.setValue(closed ? "Closed" : assigned ? "Assigned" : "Not Assigned");
		String cur = "";
		if (assigned)
			cur = r.assignedName + (r.assignedDate == null ? "" : "  (" + dmy(r.assignedDate) + ")");
		if (r.reName != null && !r.reName.isEmpty())
			cur += "  \u2192 re-assigned to " + r.reName;
		mCurAssign.setValue(cur);

		// products of the requisition and the purchase orders made from it
		while (mLines.getItemCount() > 0)
			mLines.removeItemAt(0);
		Set<String> poNos = new LinkedHashSet<String>();
		int n = 0;
		for (MRequisitionLine l : req.getLines()) {
			n++;
			MProduct prod = l.getM_Product_ID() > 0 ? MProduct.get(Env.getCtx(), l.getM_Product_ID()) : null;
			MUOM uom = prod != null && prod.getC_UOM_ID() > 0 ? MUOM.get(Env.getCtx(), prod.getC_UOM_ID()) : null;
			Listitem li = new Listitem();
			li.appendChild(new Listcell(String.valueOf(n)));
			li.appendChild(new Listcell(prod == null ? "" : prod.getValue()));
			li.appendChild(new Listcell(prod == null ? "" : prod.getName()));
			li.appendChild(new Listcell(uom == null ? "" : uom.getName()));
			li.appendChild(new Listcell(l.getQty() == null ? "" : l.getQty().stripTrailingZeros().toPlainString()));
			li.appendChild(new Listcell(l.getDescription() == null ? "" : l.getDescription()));
			mLines.appendChild(li);
			if (l.getC_OrderLine_ID() > 0) {
				MOrderLine ol = new MOrderLine(Env.getCtx(), l.getC_OrderLine_ID(), null);
				if (ol.getC_Order_ID() > 0)
					poNos.add(new MOrder(Env.getCtx(), ol.getC_Order_ID(), null).getDocumentNo());
			}
		}
		mPo.setValue(poNos.isEmpty() ? "Pending (no PO yet)" : String.join(", ", poNos));

		// inputs of the panel
		cbAssignTo.setValue("");
		cbReassign.setValue("");
		dtDelivery.setValue(r.deliveryDate);
		cbAssignTo.setDisabled(closed);
		cbReassign.setDisabled(closed || !assigned);
		dtDelivery.setDisabled(closed);
		btnAssign.setDisabled(closed);
	}

	/** the Assign button of the master panel: assigns (or re-assigns) the requisition that is loaded */
	private void assignCurrent() {
		if (current == null) {
			warn("Click a requisition in the Requisition Assignment Line first.");
			return;
		}
		if ("CL".equals(current.status)) {
			warn("Requisition " + current.docNo + " is closed.");
			return;
		}
		int to = intOf(cbAssignTo);
		int re = intOf(cbReassign);
		Timestamp dv = dtDelivery.getValue() == null ? null : new Timestamp(dtDelivery.getValue().getTime());
		String err;
		try {
			err = applyAssign(current, to, re, dv);
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			warn("Save failed:\n" + e.getMessage());
			return;
		}
		if (err != null) {
			warn("Not saved: " + err);
			return;
		}
		final int id = current.id;
		Messagebox.show("Requisition " + current.docNo + " saved.", "Requisition Assignment", Messagebox.OK,
				Messagebox.INFORMATION);
		reload();
		for (Row r : rows)
			if (r.id == id) {
				selectRow(r);
				break;
			}
	}

	// ------------------------------------------------------------ combos

	private void fill(Combobox cb, List<Object[]> items, boolean blank) {
		while (cb.getItemCount() > 0)
			cb.removeItemAt(0);
		cb.setValue("");
		if (blank) {
			Comboitem b = new Comboitem("");
			b.setAttribute("id", "");
			cb.appendChild(b);
		}
		for (Object[] r : items) {
			Comboitem ci = new Comboitem(String.valueOf(r[1]));
			ci.setAttribute("id", String.valueOf(r[0]));
			cb.appendChild(ci);
		}
	}

	private static String val(Combobox cb) {
		Comboitem ci = cb.getSelectedItem();
		if (ci == null) {
			// typed text: first entry starting with it
			String t = cb.getValue() == null ? "" : cb.getValue().trim().toLowerCase();
			if (t.length() == 0)
				return "";
			for (Object o : cb.getItems()) {
				Comboitem c = (Comboitem) o;
				if (c.getLabel().toLowerCase().startsWith(t))
					return String.valueOf(c.getAttribute("id"));
			}
			return "";
		}
		Object v = ci.getAttribute("id");
		return v == null ? "" : v.toString();
	}

	private static int intOf(Combobox cb) {
		try {
			return Integer.parseInt(val(cb));
		} catch (Exception e) {
			return 0;
		}
	}

	private List<Object[]> dbQuery(String sql, Object... p) {
		List<Object[]> out = new ArrayList<Object[]>();
		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			for (int i = 0; i < p.length; i++)
				ps.setObject(i + 1, p[i]);
			rs = ps.executeQuery();
			while (rs.next())
				out.add(new Object[] { rs.getObject(1), rs.getObject(2) });
		} catch (Exception e) {
			log.log(Level.SEVERE, sql, e);
		} finally {
			DB.close(rs, ps);
		}
		return out;
	}

	private void loadCombos() {
		List<Object[]> orgs = dbQuery("SELECT AD_Org_ID, Name FROM AD_Org WHERE IsActive='Y' AND IsSummary='N' AND AD_Org_ID>0 "
				+ "AND AD_Client_ID=? AND (EXISTS (SELECT 1 FROM AD_Role ro WHERE ro.AD_Role_ID=? AND ro.IsAccessAllOrgs='Y') "
				+ "OR AD_Org_ID IN (SELECT oa.AD_Org_ID FROM AD_Role_OrgAccess oa WHERE oa.AD_Role_ID=? AND oa.IsActive='Y')) ORDER BY Name",
				clientId, userRoleId, userRoleId);
		fill(cbOrg, orgs, true);
		int login = Env.getAD_Org_ID(Env.getCtx());
		for (Object o : cbOrg.getItems()) {
			Comboitem ci = (Comboitem) o;
			if (String.valueOf(login).equals(ci.getAttribute("id")))
				cbOrg.setSelectedItem(ci);
		}
		if (cbOrg.getSelectedItem() == null && orgs.size() == 1)
			cbOrg.setSelectedIndex(1);

		userRows = dbQuery("SELECT AD_User_ID, Name FROM AD_User WHERE IsActive='Y' AND AD_Client_ID=? ORDER BY Name", clientId);
		fill(txtReq, dbQuery("SELECT M_Requisition_ID, DocumentNo FROM M_Requisition WHERE AD_Client_ID=? AND IsActive='Y' "
				+ "AND DocStatus IN ('CO','CL') ORDER BY DateDoc DESC, M_Requisition_ID DESC LIMIT 2000", clientId), true);
		fill(txtCat, dbQuery("SELECT M_Product_Category_ID, Name FROM M_Product_Category WHERE AD_Client_ID IN (0,?) "
				+ "AND IsActive='Y' AND IsSummary='N' ORDER BY Name", clientId), true);
		fill(cbUser, userRows, true);
		fill(cbFAssigned, userRows, true);
		fill(cbAssignTo, userRows, true);
		fill(cbReassign, userRows, true);

		fill(cbPriority, dbQuery("SELECT Value, Name FROM AD_Ref_List WHERE AD_Reference_ID=154 AND IsActive='Y' ORDER BY Value"),
				true);
		fill(cbLocation, dbQuery("SELECT l.Value, l.Name FROM AD_Ref_List l JOIN AD_Reference r ON r.AD_Reference_ID=l.AD_Reference_ID "
				+ "WHERE r.Name='XX Requisition Location' AND l.IsActive='Y' ORDER BY l.Name"), true);
		fill(cbSegment, dbQuery("SELECT l.Value, l.Name FROM AD_Ref_List l JOIN AD_Reference r ON r.AD_Reference_ID=l.AD_Reference_ID "
				+ "WHERE r.Name='XX Requisition Segment' AND l.IsActive='Y' ORDER BY l.Name"), true);
		fill(cbDocType, dbQuery("SELECT C_DocType_ID, Name FROM C_DocType WHERE DocBaseType='POR' AND IsActive='Y' AND AD_Client_ID=? "
				+ "ORDER BY Name", clientId), true);
		List<Object[]> st = new ArrayList<Object[]>();
		st.add(new Object[] { "A", "Assigned" });
		st.add(new Object[] { "N", "Not Assigned" });
		fill(cbStatus, st, true);
		List<Object[]> po = new ArrayList<Object[]>();
		po.add(new Object[] { "Y", "PO Created" });
		po.add(new Object[] { "N", "Pending" });
		fill(cbPo, po, true);
	}

	// ------------------------------------------------------------ data

	private void reload() {
		list.getItems().clear();
		rows.clear();
		current = null;
		StringBuilder sql = new StringBuilder(
				"SELECT r.M_Requisition_ID, r.DocumentNo, r.DateDoc, r.DateRequired, r.PriorityRule, r.DocStatus, o.Name, u.Name, "
						+ "(SELECT pc.Name FROM M_RequisitionLine l JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID "
						+ " JOIN M_Product_Category pc ON pc.M_Product_Category_ID=p.M_Product_Category_ID "
						+ " WHERE l.M_Requisition_ID=r.M_Requisition_ID ORDER BY l.Line, l.M_RequisitionLine_ID LIMIT 1), "
						+ "r.XX_AssignedTo_ID, au.Name, r.XX_AssignedDate, r.XX_DeliveryDate, r.XX_ReassignedTo_ID, ru.Name, "
						// location, segment and department of the requisition: shown in the master panel
						+ "r.XX_Location, "
						+ "(SELECT l.XX_Segment FROM M_RequisitionLine l WHERE l.M_Requisition_ID=r.M_Requisition_ID "
						+ " ORDER BY l.Line, l.M_RequisitionLine_ID LIMIT 1), "
						+ "(SELECT l.XX_Department_ID FROM M_RequisitionLine l WHERE l.M_Requisition_ID=r.M_Requisition_ID "
						+ " ORDER BY l.Line, l.M_RequisitionLine_ID LIMIT 1) "
						+ "FROM M_Requisition r JOIN AD_Org o ON o.AD_Org_ID=r.AD_Org_ID "
						+ "LEFT JOIN AD_User u ON u.AD_User_ID=r.AD_User_ID "
						+ "LEFT JOIN AD_User au ON au.AD_User_ID=r.XX_AssignedTo_ID "
						+ "LEFT JOIN AD_User ru ON ru.AD_User_ID=r.XX_ReassignedTo_ID "
						+ "WHERE r.AD_Client_ID=? AND r.IsActive='Y' AND r.DocStatus IN ('CO','CL') ");
		List<Object> p = new ArrayList<Object>();
		p.add(clientId);
		int org = intOf(cbOrg);
		if (org > 0) {
			sql.append("AND r.AD_Org_ID=? ");
			p.add(org);
		}
		int u = intOf(cbUser);
		if (u > 0) {
			sql.append("AND r.AD_User_ID=? ");
			p.add(u);
		}
		String t = txtReq.getValue() == null ? "" : txtReq.getValue().trim();
		if (t.length() > 0) {
			sql.append("AND UPPER(r.DocumentNo) LIKE ? ");
			p.add("%" + t.toUpperCase() + "%");
		}
		t = txtCat.getValue() == null ? "" : txtCat.getValue().trim();
		if (t.length() > 0) {
			sql.append("AND EXISTS (SELECT 1 FROM M_RequisitionLine l JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID "
					+ "JOIN M_Product_Category pc ON pc.M_Product_Category_ID=p.M_Product_Category_ID "
					+ "WHERE l.M_Requisition_ID=r.M_Requisition_ID AND UPPER(pc.Name) LIKE ?) ");
			p.add("%" + t.toUpperCase() + "%");
		}
		String s = val(cbPriority);
		if (s.length() > 0) {
			sql.append("AND r.PriorityRule=? ");
			p.add(s);
		}
		s = val(cbLocation);
		if (s.length() > 0) {
			sql.append("AND r.XX_Location=? ");
			p.add(s);
		}
		s = val(cbSegment);
		if (s.length() > 0) {
			sql.append("AND EXISTS (SELECT 1 FROM M_RequisitionLine l WHERE l.M_Requisition_ID=r.M_Requisition_ID AND l.XX_Segment=?) ");
			p.add(s);
		}
		int dt = intOf(cbDocType);
		if (dt > 0) {
			sql.append("AND r.C_DocType_ID=? ");
			p.add(dt);
		}
		int at = intOf(cbFAssigned);
		if (at > 0) {
			sql.append("AND COALESCE(r.XX_ReassignedTo_ID, r.XX_AssignedTo_ID)=? ");
			p.add(at);
		}
		s = val(cbStatus);
		if ("A".equals(s))
			sql.append("AND r.XX_AssignedTo_ID IS NOT NULL AND r.XX_AssignedTo_ID>0 ");
		else if ("N".equals(s))
			sql.append("AND (r.XX_AssignedTo_ID IS NULL OR r.XX_AssignedTo_ID=0) ");
		s = val(cbPo);
		String hasPo = "EXISTS (SELECT 1 FROM M_RequisitionLine l WHERE l.M_Requisition_ID=r.M_Requisition_ID AND l.C_OrderLine_ID>0) ";
		if ("Y".equals(s))
			sql.append("AND ").append(hasPo);
		else if ("N".equals(s))
			sql.append("AND NOT ").append(hasPo);
		if (dtFrom.getValue() != null) {
			sql.append("AND r.DateDoc>=? ");
			p.add(new Timestamp(dtFrom.getValue().getTime()));
		}
		if (dtTo.getValue() != null) {
			sql.append("AND r.DateDoc<=? ");
			p.add(new Timestamp(dtTo.getValue().getTime()));
		}
		sql.append("ORDER BY r.DateDoc DESC, r.M_Requisition_ID DESC");

		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql.toString(), null);
			for (int i = 0; i < p.size(); i++)
				ps.setObject(i + 1, p.get(i));
			rs = ps.executeQuery();
			while (rs.next() && rows.size() < MAX_ROWS) {
				Row r = new Row();
				r.id = rs.getInt(1);
				r.docNo = rs.getString(2);
				r.dateDoc = rs.getTimestamp(3);
				r.dateReq = rs.getTimestamp(4);
				r.prio = rs.getString(5);
				r.status = rs.getString(6);
				r.org = rs.getString(7);
				r.user = rs.getString(8);
				r.cat = rs.getString(9);
				r.assignedId = rs.getInt(10);
				r.assignedName = rs.getString(11);
				r.assignedDate = rs.getTimestamp(12);
				r.deliveryDate = rs.getTimestamp(13);
				r.reId = rs.getInt(14);
				r.reName = rs.getString(15);
				r.loc = rs.getString(16);
				r.seg = rs.getString(17);
				r.deptId = rs.getInt(18);
				rows.add(r);
			}
		} catch (Exception e) {
			log.log(Level.SEVERE, sql.toString(), e);
			warn("Could not load the requisitions.\n" + e.getMessage()
					+ "\n(Did you run db/25_requisition_assignment.sql?)");
		} finally {
			DB.close(rs, ps);
		}
		for (int i = 0; i < rows.size(); i++)
			list.appendChild(buildItem(rows.get(i), i + 1));
		lblInfo.setValue(rows.size() + " requisition(s)");
		clearMaster();
	}

	private static String dmy(Timestamp t) {
		return t == null ? "" : new java.text.SimpleDateFormat("dd/MM/yyyy").format(t);
	}

	private static String prioName(String v) {
		if ("1".equals(v))
			return "Urgent";
		if ("3".equals(v))
			return "High";
		if ("5".equals(v))
			return "Medium";
		if ("7".equals(v))
			return "Low";
		if ("9".equals(v))
			return "Minor";
		return v == null ? "" : v;
	}

	private Listitem buildItem(final Row r, int sl) {
		Listitem li = new Listitem();
		boolean assigned = r.assignedId > 0;
		boolean closed = "CL".equals(r.status);

		li.appendChild(cell(r.chk));
		r.print.setDisabled(true);
		li.appendChild(cell(r.print));
		li.appendChild(new Listcell(dmy(r.dateDoc)));
		li.appendChild(new Listcell(r.docNo));
		li.appendChild(new Listcell(r.cat == null ? "" : r.cat));
		li.appendChild(new Listcell(r.org));
		li.appendChild(new Listcell(dmy(r.dateReq)));
		li.appendChild(new Listcell(r.user == null ? "" : r.user));
		li.appendChild(new Listcell(r.assignedName == null ? "" : r.assignedName));
		li.appendChild(new Listcell(dmy(r.assignedDate)));

		r.delivery.setFormat("dd/MM/yyyy");
		if (r.deliveryDate != null)
			r.delivery.setValue(r.deliveryDate);
		r.delivery.setDisabled(true);
		li.appendChild(cell(r.delivery));

		li.appendChild(new Listcell(prioName(r.prio)));

		r.reassign.setAutodrop(true);
		r.reassign.setButtonVisible(true);
		r.reassign.setDisabled(true);
		ZKUpdateUtil.setWidth(r.reassign, "100%");
		fill(r.reassign, userRows, true);
		if (r.reName != null)
			r.reassign.setValue(r.reName);
		li.appendChild(cell(r.reassign));

		r.view.setDisabled(true);
		li.appendChild(cell(r.view));
		r.close.setDisabled(true);
		r.close.setStyle("color:#dc3545;font-weight:600");
		li.appendChild(cell(r.close));

		final boolean canEdit = !closed;
		final boolean isAssigned = assigned;
		r.chk.addEventListener(Events.ON_CHECK, ev -> {
			boolean on = r.chk.isChecked();
			r.print.setDisabled(!on);
			r.view.setDisabled(!on);
			r.close.setDisabled(!on || !canEdit);
			r.delivery.setDisabled(!on || !canEdit);
			r.reassign.setDisabled(!on || !canEdit || !isAssigned);
			if (on)
				selectRow(r);
		});
		// a click on the line loads it into the Requisition Assignment panel above
		li.addEventListener(Events.ON_CLICK, ev -> selectRow(r));
		r.print.addEventListener(Events.ON_CLICK, ev -> printRequisition(r));
		r.view.addEventListener(Events.ON_CLICK, ev -> viewPo(r));
		r.close.addEventListener(Events.ON_CLICK, ev -> closeRequisition(r));
		return li;
	}

	private static Listcell cell(org.zkoss.zk.ui.Component c) {
		Listcell lc = new Listcell();
		lc.appendChild(c);
		return lc;
	}

	// ------------------------------------------------------------ actions

	private static void warn(String msg) {
		Messagebox.show(msg, "Requisition Assignment", Messagebox.OK, Messagebox.EXCLAMATION);
	}

	/**
	 * Writes the assignment of one requisition. Not assigned: "to" is required. Assigned: "re" moves it to another user.
	 * Returns a reason when nothing could be saved, null when it was saved.
	 * (XX_ columns are plain columns that are not in the Application Dictionary, so they are written here.)
	 */
	private String applyAssign(Row r, int to, int re, Timestamp dv) {
		int me = Env.getAD_User_ID(Env.getCtx());
		if (r.assignedId <= 0) {
			if (to <= 0)
				return r.docNo + " (choose Assign To)";
			DB.executeUpdateEx("UPDATE M_Requisition SET XX_AssignedTo_ID=?, XX_AssignedDate=?, XX_DeliveryDate=?, "
					+ "Updated=now(), UpdatedBy=? WHERE M_Requisition_ID=?",
					new Object[] { to, new Timestamp(System.currentTimeMillis()), dv, me, r.id }, null);
		} else if (re > 0 && re != r.reId) {
			DB.executeUpdateEx("UPDATE M_Requisition SET XX_ReassignedTo_ID=?, XX_ReassignedDate=?, "
					+ "XX_DeliveryDate=?, Updated=now(), UpdatedBy=? WHERE M_Requisition_ID=?",
					new Object[] { re, new Timestamp(System.currentTimeMillis()), dv, me, r.id }, null);
		} else {
			DB.executeUpdateEx("UPDATE M_Requisition SET XX_DeliveryDate=?, Updated=now(), UpdatedBy=? "
					+ "WHERE M_Requisition_ID=?", new Object[] { dv, me, r.id }, null);
		}
		return null;
	}

	/** OK: saves every ticked line (a Not Assigned one goes to the user chosen in the panel's Assign To) */
	private void save() {
		int to = intOf(cbAssignTo);
		int done = 0;
		StringBuilder skipped = new StringBuilder();
		try {
			for (Row r : rows) {
				if (!r.chk.isChecked() || "CL".equals(r.status))
					continue;
				Timestamp dv = r.delivery.getValue() == null ? null : new Timestamp(r.delivery.getValue().getTime());
				String err = applyAssign(r, to, intOf(r.reassign), dv);
				if (err != null)
					skipped.append(err).append(' ');
				else
					done++;
			}
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			warn("Save failed:\n" + e.getMessage());
			return;
		}
		if (done == 0 && skipped.length() == 0) {
			warn("Tick at least one requisition first.");
			return;
		}
		String msg = done + " requisition(s) saved.";
		if (skipped.length() > 0)
			msg += "\nNot saved: " + skipped;
		Messagebox.show(msg, "Requisition Assignment", Messagebox.OK, Messagebox.INFORMATION);
		reload();
	}

	private void closeRequisition(final Row r) {
		Messagebox.show("Close requisition " + r.docNo + "?", "Close", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION,
				ev -> {
					if (!Messagebox.ON_YES.equals(ev.getName()))
						return;
					try {
						MRequisition req = new MRequisition(Env.getCtx(), r.id, null);
						if (!req.processIt(DocAction.ACTION_Close))
							throw new AdempiereException(req.getProcessMsg());
						req.saveEx();
						reload();
					} catch (Exception e) {
						log.log(Level.SEVERE, e.getMessage(), e);
						warn("Could not close " + r.docNo + ":\n" + e.getMessage());
					}
				});
	}

	private String logoPath(String resource) {
		try (java.io.InputStream in = WRequisitionAssignForm.class.getResourceAsStream(resource)) {
			if (in == null)
				return "";
			java.io.File f = java.io.File.createTempFile("xxlogo", ".png");
			f.deleteOnExit();
			java.nio.file.Files.copy(in, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return f.getAbsolutePath();
		} catch (Exception e) {
			return "";
		}
	}

	private void printRequisition(Row r) {
		try (java.io.InputStream jr = WRequisitionAssignForm.class.getResourceAsStream(PDF_JRXML)) {
			if (jr == null)
				throw new AdempiereException(PDF_JRXML + " not found in the bundle");
			net.sf.jasperreports.engine.JasperReport report = net.sf.jasperreports.engine.JasperCompileManager
					.compileReport(jr);
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("RECORD_ID", Integer.valueOf(r.id));
			params.put("LOGO_LEFT", logoPath("/org/mycompany/requisition/rfqapproval/logo_left.png"));
			params.put("LOGO_RIGHT", logoPath("/org/mycompany/requisition/rfqapproval/logo_right.png"));
			java.sql.Connection conn = DB.getConnectionRO();
			byte[] pdf;
			try {
				pdf = net.sf.jasperreports.engine.JasperExportManager
						.exportReportToPdf(net.sf.jasperreports.engine.JasperFillManager.fillReport(report, params, conn));
			} finally {
				try {
					conn.close();
				} catch (Exception ignore) {
					// ignore
				}
			}
			showViewer("Requisition " + r.docNo, new org.zkoss.util.media.AMedia("Requisition_" + r.docNo + ".pdf", "pdf",
					"application/pdf", pdf));
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			warn("Could not create the PDF:\n" + e.getMessage());
		}
	}

	/** View PO: one PO opens directly, several PO give a small list to choose from (read through the model) */
	private void viewPo(Row r) {
		MRequisition req = new MRequisition(Env.getCtx(), r.id, null);
		final Map<Integer, String> pos = new java.util.LinkedHashMap<Integer, String>();
		for (MRequisitionLine l : req.getLines()) {
			if (l.getC_OrderLine_ID() <= 0)
				continue;
			MOrderLine ol = new MOrderLine(Env.getCtx(), l.getC_OrderLine_ID(), null);
			if (ol.getC_Order_ID() > 0 && !pos.containsKey(Integer.valueOf(ol.getC_Order_ID())))
				pos.put(Integer.valueOf(ol.getC_Order_ID()),
						new MOrder(Env.getCtx(), ol.getC_Order_ID(), null).getDocumentNo());
		}
		if (pos.isEmpty()) {
			warn("No purchase order has been created from " + r.docNo + " yet.");
			return;
		}
		if (pos.size() == 1) {
			Map.Entry<Integer, String> e = pos.entrySet().iterator().next();
			showPo(e.getKey().intValue(), e.getValue());
			return;
		}
		final org.adempiere.webui.component.Window win = new org.adempiere.webui.component.Window();
		win.setTitle("Purchase orders of " + r.docNo);
		win.setWidth("320px");
		win.setClosable(true);
		win.setAttribute(org.adempiere.webui.component.Window.MODE_KEY, org.adempiere.webui.component.Window.MODE_HIGHLIGHTED);
		Div body = new Div();
		body.setStyle("padding:10px");
		for (Map.Entry<Integer, String> po : pos.entrySet()) {
			final int id = po.getKey().intValue();
			final String no = po.getValue();
			Button b = new Button(no);
			b.setStyle("display:block;width:100%;margin-bottom:6px");
			b.addEventListener(Events.ON_CLICK, ev -> showPo(id, no));
			body.appendChild(b);
		}
		win.appendChild(body);
		org.adempiere.webui.apps.AEnv.showCenterScreen(win);
	}

	private void showPo(int orderId, String docNo) {
		try {
			ReportEngine re = ReportEngine.get(Env.getCtx(), ReportEngine.ORDER, orderId, null);
			if (re == null)
				throw new AdempiereException("No print format found for the purchase order");
			java.io.File f = re.getPDF();
			if (f == null)
				throw new AdempiereException("The PDF could not be created");
			byte[] pdf = java.nio.file.Files.readAllBytes(f.toPath());
			showViewer("PO " + docNo, new org.zkoss.util.media.AMedia("PO_" + docNo + ".pdf", "pdf", "application/pdf", pdf));
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			warn("Could not create the PO PDF:\n" + e.getMessage());
		}
	}

	private void showViewer(String title, final org.zkoss.util.media.Media media) {
		final org.adempiere.webui.component.Window win = new org.adempiere.webui.component.Window();
		win.setTitle(title);
		win.setWidth("900px");
		win.setHeight("92%");
		win.setClosable(true);
		win.setSizable(true);
		win.setAttribute(org.adempiere.webui.component.Window.MODE_KEY, org.adempiere.webui.component.Window.MODE_HIGHLIGHTED);
		Div body = new Div();
		body.setStyle("height:100%;display:flex;flex-direction:column;");
		org.zkoss.zul.Iframe frame = new org.zkoss.zul.Iframe();
		frame.setContent(media);
		frame.setStyle("flex:1 1 auto;width:100%;min-height:500px;border:1px solid #dee2e6;");
		ZKUpdateUtil.setHflex(frame, "1");
		ZKUpdateUtil.setVflex(frame, "1");
		Div bar = new Div();
		bar.setStyle("text-align:right;padding:8px 0 0;");
		Button dl = new Button("Download");
		dl.setSclass("btn btn-primary");
		dl.addEventListener(Events.ON_CLICK, ev -> org.zkoss.zul.Filedownload.save(media));
		Button cl = new Button("Close");
		cl.setSclass("btn btn-danger");
		cl.setStyle("margin-left:6px;");
		cl.addEventListener(Events.ON_CLICK, ev -> win.detach());
		bar.appendChild(dl);
		bar.appendChild(cl);
		body.appendChild(frame);
		body.appendChild(bar);
		win.appendChild(body);
		org.adempiere.webui.apps.AEnv.showCenterScreen(win);
	}
}