package org.mycompany.requisition.rfq;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Grid;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Row;
import org.adempiere.webui.component.Rows;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.editor.WTableDirEditor;
import org.adempiere.webui.event.ValueChangeEvent;
import org.adempiere.webui.event.ValueChangeListener;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.model.MAttachment;
import org.compiere.model.MColumn;
import org.compiere.model.MLookup;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MRfQ;
import org.compiere.model.MRfQLine;
import org.compiere.model.MRfQLineQty;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.compiere.util.Util;
import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.UploadEvent;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Column;
import org.zkoss.zul.Columns;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.North;
import org.zkoss.zul.South;
import org.zkoss.zul.Vlayout;
import org.zkoss.zul.Window;

/**
 * "Create RFQ From PR" form.
 *
 * Flow:
 *  1. Choose RFQ Date, RFQ Topic and Requisition (all three on one line).
 *  2. Selecting a requisition opens a popup with its eligible lines.
 *  3. Tick lines in the popup and click "Add Line" -> they appear in the lines table
 *     (the table grows with the number of lines).
 *  4. Choose an RFQ Term -> its text is filled into Terms and Conditions.
 *  5. OK creates the RFQ.
 *
 * Rules (same as RequisitionRFQService):
 *  - Only requisition lines not used in any RFQ/PO
 *    (M_RequisitionLine.XX_RfQ_ID null, C_OrderLine_ID null)
 *  - C_RfQLine.XX_RequisitionLine_ID is set
 *  - After save, M_RequisitionLine.XX_RfQ_ID is set
 *    (void/delete releases it via RfQVoidEventHandler)
 *  - Organization is not on the form: login org is used, otherwise the requisition's org.
 *
 * RFQ Term source: the term column of C_RfQ (the one the RfQ window uses) is detected
 * automatically from the Application Dictionary, so the dropdown shows the same terms.
 */
public class WCreateRFQFromPR extends ADForm implements ValueChangeListener, EventListener<Event> {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WCreateRFQFromPR.class);

	/** Column names tried (in order) for the term text on the term table */
	private static final String[] TERM_TEXT_CANDIDATES = {
			"TermsText", "DocumentNote", "TermsAndConditions", "TermsConditions", "TermsCondition", "Terms",
			"Description", "Help", "Comments", "Text" };

	/** One line's data (popup list and lines table share it) */
	private static class LineRow {
		int reqId;
		int reqLineId;
		int productId;
		String productName;
		int uomId;
		String uomName;
		int asiId;
		BigDecimal qty;
		String desc;
		Decimalbox qtyBox; // editable qty in the lines table
	}

	private Borderlayout mainLayout = new Borderlayout();

	private WTableDirEditor reqEditor;
	private WTableDirEditor topicEditor;
	private Listbox termCombo = new Listbox();
	private Datebox dateBox = new Datebox();
	private Textbox termsField = new Textbox();

	// RFQ Term source (detected from the dictionary)
	private String termColName;     // column on C_RfQ, e.g. XX_RfQTerm_ID
	private String termTable;       // table holding the terms
	private String termIdCol;       // key column of the term table
	private String termTextCol;     // column holding the term text

	private Button reloadBtn = new Button("Reload Requisition");
	private Button attachBtn = new Button("Attachment");
	private Button okBtn = new Button("OK");
	private Button cancelBtn = new Button("Cancel");

	private Listbox rfqList;     // lines table (Serial No, Requisition ID, Product ID, Product Name, Qty)

	// popup
	private Window popupWin;
	private Listbox popupList;
	private Button popupAddBtn = new Button("Add Line");

	private Media attachMedia;

	private boolean reqLineHasRfqCol;

	// ------------------------------------------------------------------ init

	@Override
	protected void initForm() {
		try {
			reqLineHasRfqCol = columnExists("M_RequisitionLine", "XX_RfQ_ID");
			initComponents();
			layout();
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			throw new AdempiereException(e);
		}
	}

	private void initComponents() {
		// Requisition dropdown
		int reqCol = requireColumn("M_RequisitionLine", "M_Requisition_ID");
		MLookup reqLookup = MLookupFactory.get(Env.getCtx(), m_WindowNo, 0, reqCol, DisplayType.TableDir);
		reqEditor = new WTableDirEditor("M_Requisition_ID", false, false, true, reqLookup);
		reqEditor.addValueChangeListener(this);

		// RFQ Topic dropdown (mandatory)
		int topicCol = requireColumn("C_RfQ", "C_RfQ_Topic_ID");
		MLookup topicLookup = MLookupFactory.get(Env.getCtx(), m_WindowNo, 0, topicCol, DisplayType.TableDir);
		topicEditor = new WTableDirEditor("C_RfQ_Topic_ID", true, false, true, topicLookup);

		// Terms
		termsField.setMultiline(true);
		termsField.setRows(5);
		ZKUpdateUtil.setHflex(termsField, "1");
		initTerms();

		// Lines table (no checkbox, no remove, grows with its content)
		rfqList = newTable("Serial No", "Requisition ID", "Product ID", "Product Name", "Qty");

		// Buttons
		reloadBtn.addEventListener(Events.ON_CLICK, this);
		okBtn.addEventListener(Events.ON_CLICK, this);
		cancelBtn.addEventListener(Events.ON_CLICK, this);
		popupAddBtn.addEventListener(Events.ON_CLICK, this);
		attachBtn.setUpload("true");
		attachBtn.addEventListener(Events.ON_UPLOAD, this);
	}

	/**
	 * RFQ Term dropdown. Source table: the one behind the RFQ Term column of C_RfQ (if the
	 * dictionary has one), otherwise XX_RFQ_Term. Selecting a term fills Terms and Conditions.
	 */
	private void initTerms() {
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		int refId = 0;
		int refValueId = 0;
		try {
			pstmt = DB.prepareStatement(
					"SELECT ColumnName, AD_Reference_ID, AD_Reference_Value_ID "
					+ "FROM AD_Column WHERE AD_Table_ID=(SELECT AD_Table_ID FROM AD_Table WHERE TableName='C_RfQ') "
					+ "AND IsActive='Y' AND LOWER(ColumnName) LIKE '%term%' AND LOWER(ColumnName) LIKE '%_id' "
					+ "ORDER BY AD_Column_ID", null);
			rs = pstmt.executeQuery();
			if (rs.next()) {
				termColName = rs.getString(1);
				refId = rs.getInt(2);
				refValueId = rs.getInt(3);
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "RFQ Term column lookup failed", e);
		} finally {
			DB.close(rs, pstmt);
		}

		if (termColName != null) {
			if (refId == DisplayType.Table && refValueId > 0) {
				termTable = DB.getSQLValueString(null,
						"SELECT t.TableName FROM AD_Ref_Table rt JOIN AD_Table t ON t.AD_Table_ID=rt.AD_Table_ID "
						+ "WHERE rt.AD_Reference_ID=?", refValueId);
				termIdCol = DB.getSQLValueString(null,
						"SELECT k.ColumnName FROM AD_Ref_Table rt JOIN AD_Column k ON k.AD_Column_ID=rt.AD_Key "
						+ "WHERE rt.AD_Reference_ID=?", refValueId);
			} else if (termColName.toLowerCase().endsWith("_id")) {
				termTable = termColName.substring(0, termColName.length() - 3);
				termIdCol = termColName;
			}
		}
		if (termTable == null || termIdCol == null || !columnExists(termTable, termIdCol)) {
			// no term column on C_RfQ: use the XX_RFQ_Term table (Name + TermsText)
			termTable = "XX_RFQ_Term";
			termIdCol = "XX_RFQ_Term_ID";
		}

		for (String cand : TERM_TEXT_CANDIDATES) {
			if (columnExists(termTable, cand)) {
				termTextCol = cand;
				break;
			}
		}
		if (termTextCol == null)
			termTextCol = DB.getSQLValueString(null,
					"SELECT column_name FROM information_schema.columns "
					+ "WHERE LOWER(table_name)=LOWER(?) AND (data_type='text' OR character_maximum_length>=255) "
					+ "ORDER BY ordinal_position LIMIT 1", termTable);
		log.info("RFQ Term source: column=" + termColName + ", table=" + termTable
				+ ", key=" + termIdCol + ", text=" + termTextCol);

		termCombo.setMold("select");
		termCombo.appendChild(new Listitem("", Integer.valueOf(0)));
		boolean hasClient = columnExists(termTable, "AD_Client_ID");
		String sql = "SELECT " + termIdCol + ", Name FROM " + termTable
				+ " WHERE IsActive='Y'" + (hasClient ? " AND AD_Client_ID IN (0,?)" : "") + " ORDER BY Name";
		PreparedStatement ps2 = null;
		ResultSet rs2 = null;
		try {
			ps2 = DB.prepareStatement(sql, null);
			if (hasClient)
				ps2.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			rs2 = ps2.executeQuery();
			while (rs2.next())
				termCombo.appendChild(new Listitem(rs2.getString(2), Integer.valueOf(rs2.getInt(1))));
		} catch (SQLException e) {
			// form still works if the term table is missing; dropdown stays empty
			log.log(Level.WARNING, "RFQ Term read failed: " + sql, e);
		} finally {
			DB.close(rs2, ps2);
		}
		termCombo.setSelectedIndex(0);
		termCombo.addEventListener(Events.ON_SELECT, this);
	}

	private Listbox newTable(String... headers) {
		Listbox lb = new Listbox();
		lb.setCheckmark(true);   // row checkboxes + select-all checkbox in the header
		lb.setMultiple(true);
		Listhead head = new Listhead();
		for (String h : headers)
			head.appendChild(new Listheader(h));
		lb.appendChild(head);
		ZKUpdateUtil.setHflex(lb, "1"); // no fixed height: grows with the number of lines
		return lb;
	}

	// ---------------------------------------------------------------- layout

	private void layout() {
		appendChild(mainLayout);
		ZKUpdateUtil.setWidth(this, "99%");
		ZKUpdateUtil.setHeight(this, "100%");
		ZKUpdateUtil.setWidth(mainLayout, "100%");
		ZKUpdateUtil.setHeight(mainLayout, "100%");

		// ---- North: header (Date, Topic, Requisition on ONE line, then buttons) ----
		North north = new North();
		ZKUpdateUtil.setVflex(north, "min");
		mainLayout.appendChild(north);

		Grid grid = new Grid();

		// 6 columns: label | field | label | field | label | field
		Columns cols = new Columns();
		String[] widths = { "100px", "1", "100px", "1", "100px", "1" };
		for (String w : widths) {
			Column col = new Column();
			if (w.endsWith("px"))
				col.setWidth(w);
			else
				col.setHflex(w);
			cols.appendChild(col);
		}
		grid.appendChild(cols);

		Rows rows = new Rows();
		grid.appendChild(rows);
		north.appendChild(grid);

		// Row 1: RFQ Date | RFQ Topic | Requisition  (all on one line)
		Row r1 = new Row();
		rows.appendChild(r1);
		r1.appendChild(new Label("RFQ Date"));
		r1.appendChild(dateBox);
		r1.appendChild(new Label("RFQ Topic *"));
		r1.appendChild(topicEditor.getComponent());
		r1.appendChild(new Label("Requisition"));
		r1.appendChild(reqEditor.getComponent());

		// Row 2: Attachment / Reload buttons (6 cells to keep columns aligned)
		Row r2 = new Row();
		rows.appendChild(r2);
		r2.appendChild(new Label(""));
		r2.appendChild(attachBtn);
		r2.appendChild(new Label(""));
		r2.appendChild(reloadBtn);
		r2.appendChild(new Label(""));
		r2.appendChild(new Label(""));

		// ---- Center: lines table (grows with lines; scrolls when taller than the form) ----
		Center center = new Center();
		center.setAutoscroll(true);
		mainLayout.appendChild(center);
		Vlayout v = new Vlayout();
		ZKUpdateUtil.setHflex(v, "1");
		center.appendChild(v);
		v.appendChild(rfqList);

		// ---- South: RFQ Term + Terms and Conditions, then OK / Cancel ----
		South south = new South();
		ZKUpdateUtil.setVflex(south, "min");
		mainLayout.appendChild(south);

		Div southBox = new Div();
		south.appendChild(southBox);

		Div footer = new Div();
		footer.setStyle("text-align:right; padding:6px;");
		footer.appendChild(okBtn);
		footer.appendChild(cancelBtn);
		southBox.appendChild(footer);

		Grid termGrid = new Grid();
		Rows termRows = new Rows();
		termGrid.appendChild(termRows);
		Row t1 = new Row();
		termRows.appendChild(t1);
		t1.appendChild(new Label("RFQ Term"));
		t1.appendChild(termCombo);
		t1.appendChild(new Label("Terms and Conditions"));
		t1.appendChild(termsField);
		southBox.appendChild(termGrid);
	}

	// ----------------------------------------------------------------- popup

	/** Opens a modal popup listing the eligible lines of the requisition. */
	private void openRequisitionPopup(int requisitionId) {
		if (requisitionId <= 0)
			return;
		closePopup();

		// lines already in the table are not offered again
		Set<Integer> existing = new HashSet<Integer>();
		for (Listitem it : rfqList.getItems())
			existing.add(Integer.valueOf(((LineRow) it.getValue()).reqLineId));

		List<LineRow> rowsData = loadRequisitionLines(requisitionId, existing);
		if (rowsData.isEmpty()) {
			warn("No available lines in this requisition (already used in RFQ/PO or already added).");
			return;
		}

		popupList = new Listbox();
		popupList.setCheckmark(true);
		popupList.setMultiple(true);
		popupList.setHeight("300px");
		ZKUpdateUtil.setHflex(popupList, "1");
		Listhead head = new Listhead();
		head.appendChild(new Listheader("Product"));
		head.appendChild(new Listheader("UOM"));
		head.appendChild(new Listheader("Qty"));
		head.appendChild(new Listheader("Description"));
		popupList.appendChild(head);

		for (LineRow r : rowsData) {
			Listitem item = new Listitem();
			item.setValue(r);
			item.appendChild(new Listcell(nvl(r.productName)));
			item.appendChild(new Listcell(nvl(r.uomName)));
			item.appendChild(new Listcell(r.qty.stripTrailingZeros().toPlainString()));
			item.appendChild(new Listcell(nvl(r.desc)));
			popupList.appendChild(item);
		}

		popupWin = new Window();
		popupWin.setTitle("Select Requisition Lines");
		popupWin.setClosable(true);
		popupWin.setBorder("normal");
		popupWin.setWidth("700px");

		Vlayout box = new Vlayout();
		ZKUpdateUtil.setHflex(box, "1");
		box.appendChild(popupList);

		Div bar = new Div();
		bar.setStyle("text-align:right; padding:6px;");
		bar.appendChild(popupAddBtn);
		box.appendChild(bar);

		popupWin.appendChild(box);
		appendChild(popupWin);
		popupWin.doModal();
	}

	private void closePopup() {
		if (popupWin != null) {
			popupWin.detach();
			popupWin = null;
		}
		popupList = null;
	}

	/** Follows the RequisitionRFQService eligibility rule */
	private List<LineRow> loadRequisitionLines(int requisitionId, Set<Integer> skipLineIds) {
		List<LineRow> result = new ArrayList<LineRow>();

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT rl.M_RequisitionLine_ID, rl.M_Product_ID, p.Name, rl.C_UOM_ID, u.Name, ");
		sql.append("rl.Qty, rl.Description, rl.M_AttributeSetInstance_ID, rl.M_Requisition_ID ");
		sql.append("FROM M_RequisitionLine rl ");
		sql.append("JOIN M_Product p ON p.M_Product_ID = rl.M_Product_ID ");
		sql.append("LEFT JOIN C_UOM u ON u.C_UOM_ID = rl.C_UOM_ID ");
		sql.append("WHERE rl.M_Requisition_ID=? AND rl.IsActive='Y' AND rl.C_OrderLine_ID IS NULL ");
		if (reqLineHasRfqCol)
			sql.append("AND (rl.XX_RfQ_ID IS NULL OR rl.XX_RfQ_ID=0) ");
		sql.append("ORDER BY rl.Line");

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), null);
			pstmt.setInt(1, requisitionId);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				int lineId = rs.getInt(1);
				if (skipLineIds.contains(Integer.valueOf(lineId)))
					continue;
				LineRow r = new LineRow();
				r.reqLineId = lineId;
				r.productId = rs.getInt(2);
				r.productName = rs.getString(3);
				r.uomId = rs.getInt(4);
				r.uomName = rs.getString(5);
				r.qty = rs.getBigDecimal(6) != null ? rs.getBigDecimal(6) : BigDecimal.ONE;
				r.desc = rs.getString(7);
				r.asiId = rs.getInt(8);
				r.reqId = rs.getInt(9);
				result.add(r);
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, sql.toString(), e);
			throw new AdempiereException(e);
		} finally {
			DB.close(rs, pstmt);
		}
		return result;
	}

	/** Popup "Add Line": moves ticked lines into the lines table. */
	private void addSelectedLines() {
		if (popupList == null)
			return;
		if (popupList.getSelectedCount() == 0) {
			warn("Select at least one line.");
			return;
		}

		Set<Integer> existing = new HashSet<Integer>();
		for (Listitem it : rfqList.getItems())
			existing.add(Integer.valueOf(((LineRow) it.getValue()).reqLineId));

		for (Listitem src : new ArrayList<Listitem>(popupList.getSelectedItems())) {
			LineRow s = (LineRow) src.getValue();
			if (existing.contains(Integer.valueOf(s.reqLineId)))
				continue;

			LineRow r = new LineRow();
			r.reqId = s.reqId;
			r.reqLineId = s.reqLineId;
			r.productId = s.productId;
			r.productName = s.productName;
			r.uomId = s.uomId;
			r.uomName = s.uomName;
			r.asiId = s.asiId;
			r.qty = s.qty;
			r.desc = s.desc;
			r.qtyBox = new Decimalbox(s.qty);
			r.qtyBox.setWidth("90px");

			int serial = rfqList.getItemCount() + 1;

			Listitem item = new Listitem();
			item.setValue(r);
			item.appendChild(new Listcell(String.valueOf(serial)));
			item.appendChild(new Listcell(String.valueOf(r.reqId)));
			item.appendChild(new Listcell(String.valueOf(r.productId)));
			item.appendChild(new Listcell(nvl(r.productName)));
			Listcell qtyCell = new Listcell();
			qtyCell.appendChild(r.qtyBox);
			item.appendChild(qtyCell);
			rfqList.appendChild(item);
			item.setSelected(true);   // newly added lines are ticked by default
		}
		closePopup();
	}

	// ----------------------------------------------------------------- events

	@Override
	public void valueChange(ValueChangeEvent evt) {
		String name = evt.getPropertyName();
		if ("M_Requisition_ID".equals(name))
			openRequisitionPopup(toInt(evt.getNewValue()));
	}

	@Override
	public void onEvent(Event e) {
		try {
			if (e instanceof UploadEvent) {
				Media m = ((UploadEvent) e).getMedia();
				if (m != null) {
					attachMedia = m;
					attachBtn.setLabel("Attachment: " + m.getName());
				}
				return;
			}

			Object c = e.getTarget();
			if (c == reloadBtn) {
				int reqId = toInt(reqEditor.getValue());
				if (reqId <= 0)
					warn("Select a requisition first.");
				else
					openRequisitionPopup(reqId);
			} else if (c == termCombo) {
				Listitem ti = termCombo.getSelectedItem();
				onTermSelected(ti == null ? 0 : toInt(ti.getValue()));
			} else if (c == popupAddBtn) {
				addSelectedLines();
			} else if (c == okBtn) {
				int rfqId = saveRfq();
				SessionManager.getAppDesktop().closeActiveWindow();
				try {
					AEnv.zoom(MRfQ.Table_ID, rfqId); // opens RfQ window with Line/Quantity tabs
				} catch (Exception zx) {
					log.log(Level.WARNING, "Zoom failed", zx);
				}
			} else if (c == cancelBtn) {
				SessionManager.getAppDesktop().closeActiveWindow();
			}
		} catch (Exception ex) {
			log.log(Level.SEVERE, ex.getMessage(), ex);
			Messagebox.show(ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
		}
	}

	/** Fills Terms and Conditions with the text of the selected RFQ Term. */
	private void onTermSelected(int termId) {
		if (termId <= 0) {
			termsField.setValue("");
			return;
		}
		if (termTable == null || termIdCol == null || termTextCol == null)
			return;
		String text = DB.getSQLValueString(null,
				"SELECT " + termTextCol + " FROM " + termTable + " WHERE " + termIdCol + "=?", termId);
		termsField.setValue(text == null ? "" : text);
	}

	// ------------------------------------------------------------------- save

	/** @return created C_RfQ_ID */
	private int saveRfq() throws Exception {
		int topicId = toInt(topicEditor.getValue());

		if (topicId <= 0)
			throw new AdempiereException("RFQ Topic mandatory");
		if (rfqList.getItemCount() == 0)
			throw new AdempiereException("No line added (select a requisition and click Add Line)");

		// Empty date -> today (avoids restricted Datebox.setValue)
		Date d = dateBox.getValue();
		if (d == null)
			d = new Date();
		Timestamp rfqDate = new Timestamp(d.getTime());

		if (rfqList.getSelectedCount() == 0)
			throw new AdempiereException("Select at least one line in the lines table");

		// read ticked lines only + validate qty
		List<LineRow> lines = new ArrayList<LineRow>();
		for (Listitem it : rfqList.getItems()) {
			if (!it.isSelected())
				continue;
			LineRow r = (LineRow) it.getValue();
			BigDecimal q = r.qtyBox != null ? r.qtyBox.getValue() : r.qty;
			if (q == null || q.signum() <= 0)
				throw new AdempiereException("Qty must be greater than 0: " + r.productName);
			r.qty = q;
			lines.add(r);
		}
		int reqId = lines.get(0).reqId; // requisition of the first line

		String trxName = Trx.createTrxName("CreateRFQ");
		Trx trx = Trx.get(trxName, true);
		try {
			// ---------- Organization (login org, else requisition's org) ----------
			int orgId = Env.getAD_Org_ID(Env.getCtx());
			if (orgId <= 0 && reqId > 0)
				orgId = DB.getSQLValue(trxName,
						"SELECT AD_Org_ID FROM M_Requisition WHERE M_Requisition_ID=?", reqId);
			if (orgId <= 0)
				throw new AdempiereException(
						"Organization not found. Login with a specific organization or use a requisition with an organization.");

			// ---------- Header: C_RfQ ----------
			MRfQ rfq = new MRfQ(Env.getCtx(), 0, trxName);
			rfq.setAD_Org_ID(orgId);
			rfq.setC_RfQ_Topic_ID(topicId);
			rfq.setName("RFQ-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
			rfq.setHelp(termsField.getValue());   // Comment/Help = Terms and Conditions
			rfq.setDateResponse(rfqDate);
			rfq.setDateWorkStart(rfqDate);
			rfq.setSalesRep_ID(Env.getAD_User_ID(Env.getCtx()));

			// selected RFQ Term (so the RfQ window shows it too)
			Listitem termItem = termCombo.getSelectedItem();
			int termId = (termItem == null) ? 0 : toInt(termItem.getValue());
			if (termId > 0 && termColName != null && rfq.get_ColumnIndex(termColName) >= 0)
				rfq.set_ValueOfColumn(termColName, termId);

			int curId = Env.getContextAsInt(Env.getCtx(), "$C_Currency_ID");
			if (curId <= 0)
				curId = DB.getSQLValue(trxName,
						"SELECT C_Currency_ID FROM C_AcctSchema WHERE C_AcctSchema_ID="
						+ "(SELECT C_AcctSchema1_ID FROM AD_ClientInfo WHERE AD_Client_ID=?)",
						Env.getAD_Client_ID(Env.getCtx()));
			rfq.setC_Currency_ID(curId);

			if (reqId > 0 && rfq.get_ColumnIndex("M_Requisition_ID") >= 0)
				rfq.set_ValueOfColumn("M_Requisition_ID", reqId);

			rfq.saveEx();
			int rfqId = rfq.getC_RfQ_ID();

			// ---------- Lines ----------
			// Same product (+ same UOM / attribute set) -> ONE RFQ line with the summed qty.
			// All requisition lines of the group are still locked to this RFQ.
			Map<String, List<LineRow>> groups = new LinkedHashMap<String, List<LineRow>>();
			for (LineRow r : lines) {
				String key = r.productId + "_" + r.uomId + "_" + r.asiId;
				List<LineRow> g = groups.get(key);
				if (g == null) {
					g = new ArrayList<LineRow>();
					groups.put(key, g);
				}
				g.add(r);
			}

			int lineNo = 0;
			for (List<LineRow> g : groups.values()) {
				LineRow first = g.get(0);
				BigDecimal totalQty = BigDecimal.ZERO;
				for (LineRow r : g)
					totalQty = totalQty.add(r.qty);

				lineNo += 1;   // Line = Serial No (1, 2, 3...) so the RfQ window matches this form

				MRfQLine line = new MRfQLine(rfq);
				line.setLine(lineNo);
				line.setM_Product_ID(first.productId);
				line.setM_AttributeSetInstance_ID(first.asiId);
				line.setDescription(!Util.isEmpty(first.desc, true) ? first.desc : first.productName);
				if (line.get_ColumnIndex("XX_RequisitionLine_ID") >= 0)
					line.set_ValueOfColumn("XX_RequisitionLine_ID", first.reqLineId);
				if (line.get_ColumnIndex("Qty") >= 0)
					line.set_ValueOfColumn("Qty", totalQty);
				line.saveEx();

				MRfQLineQty lq = new MRfQLineQty(line);
				if (first.uomId > 0)
					lq.setC_UOM_ID(first.uomId);
				lq.setQty(totalQty);
				lq.setIsRfQQty(true);
				lq.setIsPurchaseQty(true);
				lq.saveEx();

				// lock every requisition line of this group to this RFQ
				if (reqLineHasRfqCol) {
					for (LineRow r : g) {
						int n = DB.executeUpdateEx(
								"UPDATE M_RequisitionLine SET XX_RfQ_ID=? WHERE M_RequisitionLine_ID=? "
								+ "AND (XX_RfQ_ID IS NULL OR XX_RfQ_ID=0)",
								new Object[] { rfqId, r.reqLineId }, trxName);
						if (n != 1)
							throw new AdempiereException(
									"Requisition line already used in another RFQ: " + r.productName);
					}
				}
			}

			// ---------- Attachment ----------
			if (attachMedia != null) {
				MAttachment att = new MAttachment(Env.getCtx(), MRfQ.Table_ID, rfqId, trxName);
				byte[] bytes = attachMedia.isBinary()
						? attachMedia.getByteData()
						: attachMedia.getStringData().getBytes("UTF-8");
				att.addEntry(attachMedia.getName(), bytes);
				att.saveEx();
			}

			trx.commit();
			return rfqId;
		} catch (Exception ex) {
			trx.rollback();
			if (ex instanceof AdempiereException)
				throw ex;
			throw new AdempiereException(ex);
		} finally {
			trx.close();
		}
	}

	// ---------------------------------------------------------------- helpers

	private static boolean columnExists(String table, String column) {
		return DB.getSQLValue(null,
				"SELECT COUNT(*) FROM information_schema.columns "
				+ "WHERE LOWER(table_name)=LOWER(?) AND LOWER(column_name)=LOWER(?)",
				table, column) > 0;
	}

	private static int requireColumn(String table, String column) {
		int id = MColumn.getColumn_ID(table, column);
		if (id <= 0)
			throw new AdempiereException("AD_Column not found: " + table + "." + column);
		return id;
	}

	private static int toInt(Object o) {
		if (o instanceof Number)
			return ((Number) o).intValue();
		return 0;
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	private void warn(String msg) {
		Messagebox.show(msg, "Warning", Messagebox.OK, Messagebox.EXCLAMATION);
	}
}