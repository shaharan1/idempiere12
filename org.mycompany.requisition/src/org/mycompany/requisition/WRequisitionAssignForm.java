package org.mycompany.requisition;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.model.MRequisition;
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
 * Lists completed requisitions (filters on top). Tick a row to enable Print, Delivery Date, Re-Assigned and Close.
 * Print = requisition PDF, PO "View" = purchase order PDF, Close = closes the requisition.
 * OK saves: Not Assigned rows get the user chosen in "Assign To" (date = today); Assigned rows
 * can be moved to another user with the Re-Assigned box.
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

	private Combobox cbOrg, cbUser, cbPriority, cbLocation, cbAssignTo, cbSegment, cbDocType, cbStatus, cbPo;
	private final org.adempiere.webui.component.Textbox txtReq = new org.adempiere.webui.component.Textbox();
	private final org.adempiere.webui.component.Textbox txtCat = new org.adempiere.webui.component.Textbox();
	private final Datebox dtFrom = new Datebox();
	private final Datebox dtTo = new Datebox();
	private final Button btnReload = new Button("Reload");
	private final Button btnOk = new Button("✔");
	private final Button btnCancel = new Button("✖");
	private final Listbox list = new Listbox();
	private final Label lblInfo = new Label();
	private final List<Row> rows = new ArrayList<Row>();
	private List<Object[]> userRows = new ArrayList<Object[]>();

	private static final class Row {
		int id;
		String docNo, org, user, cat, prio, status, assignedName, reName;
		Timestamp dateDoc, dateReq, assignedDate, deliveryDate;
		int assignedId, reId;
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

	private void buildUi() {
		Div main = new Div();
		main.setStyle("height:100%;overflow:auto;padding:6px 10px;");
		appendChild(main);
		ZKUpdateUtil.setWidth(this, "99%");
		ZKUpdateUtil.setHeight(this, "100%");

		cbOrg = combo();
		cbUser = combo();
		cbPriority = combo();
		cbLocation = combo();
		cbAssignTo = combo();
		cbSegment = combo();
		cbDocType = combo();
		cbStatus = combo();
		cbPo = combo();
		dtFrom.setFormat("dd/MM/yyyy");
		dtTo.setFormat("dd/MM/yyyy");

		Grid g = new Grid();
		g.setSclass("xx-assign-filter");
		org.zkoss.zul.Columns cols = new org.zkoss.zul.Columns();
		for (int i = 0; i < 6; i++)
			cols.appendChild(new org.zkoss.zul.Column());
		g.appendChild(cols);
		org.zkoss.zul.Rows rs = new org.zkoss.zul.Rows();
		g.appendChild(rs);

		Div dates = new Div();
		dates.setStyle("display:flex;gap:4px");
		dates.appendChild(dtFrom);
		dates.appendChild(dtTo);

		frow(rs, "Organization", cbOrg, "Priority", cbPriority, "Date Range", dates);
		frow(rs, "User", cbUser, "Location", cbLocation, "Status", cbStatus);
		frow(rs, "Requisition", txtReq, "Category", txtCat, "PO Created", cbPo);
		frow(rs, "Assign To", cbAssignTo, "Segment", cbSegment, "Document Type", cbDocType);
		Div br = new Div();
		br.setStyle("text-align:right;padding:4px 0");
		btnReload.setSclass("btn btn-primary");
		br.appendChild(btnReload);

		main.appendChild(title("Requisition Assignment"));
		main.appendChild(g);
		main.appendChild(br);
		main.appendChild(title("Requisition Assignment Line"));

		Listhead lh = new Listhead();
		lh.setSizable(true);
		String[][] h = { { "Select", "55px" }, { "SL No.", "50px" }, { "Print", "75px" }, { "Requisition Date", "95px" },
				{ "Requisition No", "150px" }, { "Category", "150px" }, { "Organization", "140px" },
				{ "Date Required", "95px" }, { "User", "110px" }, { "SCM Assigned", "130px" },
				{ "Assigned Date", "105px" }, { "Delivery Date", "120px" }, { "Priority", "70px" },
				{ "Re-Assigned", "140px" }, { "PO No", "75px" }, { "Close", "75px" } };
		for (String[] x : h) {
			Listheader lhd = new Listheader(x[0]);
			lhd.setWidth(x[1]);
			lh.appendChild(lhd);
		}
		list.appendChild(lh);
		list.setHeight("430px");
		list.setSizedByContent(false);
		list.setSpan(false);
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
		btnOk.addEventListener(Events.ON_CLICK, ev -> save());
		btnCancel.addEventListener(Events.ON_CLICK, ev -> {
			try {
				SessionManager.getAppDesktop().closeActiveWindow();
			} catch (Exception e) {
				reload();
			}
		});
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
		fill(cbUser, userRows, true);
		fill(cbAssignTo, userRows, true);

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
		StringBuilder sql = new StringBuilder(
				"SELECT r.M_Requisition_ID, r.DocumentNo, r.DateDoc, r.DateRequired, r.PriorityRule, r.DocStatus, o.Name, u.Name, "
						+ "(SELECT pc.Name FROM M_RequisitionLine l JOIN M_Product p ON p.M_Product_ID=l.M_Product_ID "
						+ " JOIN M_Product_Category pc ON pc.M_Product_Category_ID=p.M_Product_Category_ID "
						+ " WHERE l.M_Requisition_ID=r.M_Requisition_ID ORDER BY l.Line, l.M_RequisitionLine_ID LIMIT 1), "
						+ "r.XX_AssignedTo_ID, au.Name, r.XX_AssignedDate, r.XX_DeliveryDate, r.XX_ReassignedTo_ID, ru.Name "
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
		int at = intOf(cbAssignTo);
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
		li.appendChild(new Listcell(String.valueOf(sl)));
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
		});
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

	private void save() {
		int to = intOf(cbAssignTo);
		int done = 0;
		StringBuilder skipped = new StringBuilder();
		try {
			for (Row r : rows) {
				if (!r.chk.isChecked() || "CL".equals(r.status))
					continue;
				Timestamp dv = r.delivery.getValue() == null ? null : new Timestamp(r.delivery.getValue().getTime());
				if (r.assignedId <= 0) {
					if (to <= 0) {
						skipped.append(r.docNo).append(" (choose Assign To) ");
						continue;
					}
					DB.executeUpdateEx("UPDATE M_Requisition SET XX_AssignedTo_ID=?, XX_AssignedDate=?, XX_DeliveryDate=?, "
							+ "Updated=now(), UpdatedBy=? WHERE M_Requisition_ID=?",
							new Object[] { to, new Timestamp(System.currentTimeMillis()), dv, Env.getAD_User_ID(Env.getCtx()), r.id },
							null);
					done++;
				} else {
					int re = intOf(r.reassign);
					if (re > 0 && re != r.reId) {
						DB.executeUpdateEx("UPDATE M_Requisition SET XX_ReassignedTo_ID=?, XX_ReassignedDate=?, "
								+ "XX_DeliveryDate=?, Updated=now(), UpdatedBy=? WHERE M_Requisition_ID=?",
								new Object[] { re, new Timestamp(System.currentTimeMillis()), dv,
										Env.getAD_User_ID(Env.getCtx()), r.id },
								null);
					} else {
						DB.executeUpdateEx("UPDATE M_Requisition SET XX_DeliveryDate=?, Updated=now(), UpdatedBy=? "
								+ "WHERE M_Requisition_ID=?", new Object[] { dv, Env.getAD_User_ID(Env.getCtx()), r.id }, null);
					}
					done++;
				}
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

	/** View PO: one PO opens directly, several PO give a small list to choose from */
	private void viewPo(Row r) {
		List<Object[]> pos = dbQuery("SELECT DISTINCT o.C_Order_ID, o.DocumentNo FROM M_RequisitionLine l "
				+ "JOIN C_OrderLine ol ON ol.C_OrderLine_ID=l.C_OrderLine_ID JOIN C_Order o ON o.C_Order_ID=ol.C_Order_ID "
				+ "WHERE l.M_Requisition_ID=? ORDER BY o.DocumentNo", r.id);
		if (pos.isEmpty()) {
			warn("No purchase order has been created from " + r.docNo + " yet.");
			return;
		}
		if (pos.size() == 1) {
			showPo(((Number) pos.get(0)[0]).intValue(), String.valueOf(pos.get(0)[1]));
			return;
		}
		final org.adempiere.webui.component.Window win = new org.adempiere.webui.component.Window();
		win.setTitle("Purchase orders of " + r.docNo);
		win.setWidth("320px");
		win.setClosable(true);
		win.setAttribute(org.adempiere.webui.component.Window.MODE_KEY, org.adempiere.webui.component.Window.MODE_HIGHLIGHTED);
		Div body = new Div();
		body.setStyle("padding:10px");
		for (Object[] po : pos) {
			final int id = ((Number) po[0]).intValue();
			final String no = String.valueOf(po[1]);
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