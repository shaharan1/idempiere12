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
import java.util.List;
import java.util.Map;
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
import org.zkoss.zul.Window;

/**
 * Second step of "Create RFQ From PR".
 *
 * WCreateRFQFromPR (the list form) opens this window with the requisition lines of the
 * requisition(s) the user selected. Here the user:
 *  - picks RFQ Date, RFQ Topic (mandatory), optional Attachment
 *  - unticks lines they do not want / changes the Qty
 *  - picks an RFQ Term (fills Terms and Conditions)
 *  - clicks OK -> one C_RfQ is created (same save logic as the old single-page form).
 *
 * Rules are unchanged from the old form:
 *  - C_RfQLine.XX_RequisitionLine_ID is set
 *  - M_RequisitionLine.XX_RfQ_ID is set after save (void/delete releases it via RfQVoidEventHandler)
 *  - same product (+ UOM + attribute set) -> ONE RFQ line with the summed qty
 *  - Organization: login org, otherwise the requisition's org
 */
public class WCreateRFQDialog extends Window implements EventListener<Event> {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WCreateRFQDialog.class);

	/** Column names tried (in order) for the term text on the term table */
	private static final String[] TERM_TEXT_CANDIDATES = {
			"TermsText", "DocumentNote", "TermsAndConditions", "TermsConditions", "TermsCondition", "Terms",
			"Description", "Help", "Comments", "Text" };

	/** One requisition line (shared with the list form) */
	public static class LineRow {
		public int reqId;
		public String reqNo;
		public int reqLineId;
		public int lineNo;
		public int productId;
		public String productValue;
		public String productName;
		public int uomId;
		public String uomName;
		public int asiId;
		public BigDecimal qty;
		public String desc;
		public int bpId;
		public String bpCode;
		public String bpName;
		Decimalbox qtyBox; // editable qty in this dialog
	}

	private final int windowNo;
	private final List<LineRow> sourceLines;
	private final Runnable onCreated;

	private WTableDirEditor topicEditor;
	private Datebox dateBox = new Datebox();
	private Listbox termCombo = new Listbox();
	private Textbox termsField = new Textbox();
	private Listbox rfqList;

	private Button attachBtn = new Button("Attachment");
	private Button okBtn = new Button("OK");
	private Button cancelBtn = new Button("Cancel");

	// RFQ Term source (detected from the dictionary)
	private String termColName;
	private String termTable;
	private String termIdCol;
	private String termTextCol;

	private Media attachMedia;
	private boolean reqLineHasRfqCol;

	/**
	 * @param windowNo   window number of the parent form (used by the Topic lookup)
	 * @param lines      requisition lines to show (all ticked by default)
	 * @param onCreated  called after the RFQ is saved (the list form refreshes itself)
	 */
	public WCreateRFQDialog(int windowNo, List<LineRow> lines, Runnable onCreated) {
		super();
		this.windowNo = windowNo;
		this.sourceLines = lines;
		this.onCreated = onCreated;
		try {
			reqLineHasRfqCol = columnExists("M_RequisitionLine", "XX_RfQ_ID");
			initComponents();
			layout();
			fillLines();
		} catch (Exception e) {
			log.log(Level.SEVERE, e.getMessage(), e);
			throw new AdempiereException(e);
		}
	}

	// ------------------------------------------------------------------ init

	private void initComponents() {
		setTitle("Create RFQ");
		setBorder("normal");
		setClosable(true);
		setSizable(true);
		setWidth("92%");
		setHeight("88%");

		// RFQ Topic dropdown (mandatory)
		int topicCol = requireColumn("C_RfQ", "C_RfQ_Topic_ID");
		MLookup topicLookup = MLookupFactory.get(Env.getCtx(), windowNo, 0, topicCol, DisplayType.TableDir);
		topicEditor = new WTableDirEditor("C_RfQ_Topic_ID", true, false, true, topicLookup);

		termsField.setMultiline(true);
		termsField.setRows(5);
		ZKUpdateUtil.setHflex(termsField, "1");
		initTerms();

		rfqList = new Listbox();
		rfqList.setCheckmark(true);   // row checkboxes + select-all in header
		rfqList.setMultiple(true);
		Listhead head = new Listhead();
		head.appendChild(new Listheader("Serial No"));
		head.appendChild(new Listheader("Requisition"));
		head.appendChild(new Listheader("BP Code"));
		head.appendChild(new Listheader("Product ID"));
		head.appendChild(new Listheader("Product Name"));
		head.appendChild(new Listheader("UOM"));
		head.appendChild(new Listheader("Qty"));
		rfqList.appendChild(head);
		ZKUpdateUtil.setHflex(rfqList, "1");
		ZKUpdateUtil.setVflex(rfqList, "1");

		okBtn.addEventListener(Events.ON_CLICK, this);
		cancelBtn.addEventListener(Events.ON_CLICK, this);
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
			log.log(Level.WARNING, "RFQ Term read failed: " + sql, e);
		} finally {
			DB.close(rs2, ps2);
		}
		termCombo.setSelectedIndex(0);
		termCombo.addEventListener(Events.ON_SELECT, this);
	}

	// ---------------------------------------------------------------- layout

	private void layout() {
		Borderlayout main = new Borderlayout();
		ZKUpdateUtil.setWidth(main, "100%");
		ZKUpdateUtil.setHeight(main, "100%");
		appendChild(main);

		// ---- North: RFQ Date | RFQ Topic, then Attachment ----
		North north = new North();
		ZKUpdateUtil.setVflex(north, "min");
		main.appendChild(north);

		Grid grid = new Grid();
		Columns cols = new Columns();
		String[] widths = { "100px", "1", "100px", "1" };
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

		Row r1 = new Row();
		rows.appendChild(r1);
		r1.appendChild(new Label("RFQ Date"));
		r1.appendChild(dateBox);
		r1.appendChild(new Label("RFQ Topic *"));
		r1.appendChild(topicEditor.getComponent());

		Row r2 = new Row();
		rows.appendChild(r2);
		r2.appendChild(new Label(""));
		r2.appendChild(attachBtn);
		r2.appendChild(new Label(""));
		r2.appendChild(new Label(""));

		// ---- Center: lines table ----
		Center center = new Center();
		center.setAutoscroll(true);
		main.appendChild(center);
		center.appendChild(rfqList);

		// ---- South: OK / Cancel, then RFQ Term + Terms and Conditions ----
		South south = new South();
		ZKUpdateUtil.setVflex(south, "min");
		main.appendChild(south);

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

	/** Puts the lines passed by the list form into the table (all ticked, qty editable). */
	private void fillLines() {
		int serial = 0;
		for (LineRow s : sourceLines) {
			LineRow r = new LineRow();
			r.reqId = s.reqId;
			r.reqNo = s.reqNo;
			r.reqLineId = s.reqLineId;
			r.lineNo = s.lineNo;
			r.productId = s.productId;
			r.productValue = s.productValue;
			r.productName = s.productName;
			r.uomId = s.uomId;
			r.uomName = s.uomName;
			r.asiId = s.asiId;
			r.qty = s.qty;
			r.desc = s.desc;
			r.bpId = s.bpId;
			r.bpCode = s.bpCode;
			r.bpName = s.bpName;
			r.qtyBox = new Decimalbox(s.qty);
			r.qtyBox.setWidth("90px");

			serial++;
			Listitem item = new Listitem();
			item.setValue(r);
			item.appendChild(new Listcell(String.valueOf(serial)));
			item.appendChild(new Listcell(nvl(r.reqNo)));
			item.appendChild(new Listcell(nvl(r.bpCode)));
			item.appendChild(new Listcell(String.valueOf(r.productId)));
			item.appendChild(new Listcell(nvl(r.productName)));
			item.appendChild(new Listcell(nvl(r.uomName)));
			Listcell qtyCell = new Listcell();
			qtyCell.appendChild(r.qtyBox);
			item.appendChild(qtyCell);
			rfqList.appendChild(item);
			item.setSelected(true);
		}
	}

	// ----------------------------------------------------------------- events

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
			if (c == termCombo) {
				Listitem ti = termCombo.getSelectedItem();
				onTermSelected(ti == null ? 0 : toInt(ti.getValue()));
			} else if (c == okBtn) {
				int rfqId = saveRfq();
				detach();
				if (onCreated != null)
					onCreated.run();
				try {
					AEnv.zoom(MRfQ.Table_ID, rfqId); // opens RfQ window with Line/Quantity tabs
				} catch (Exception zx) {
					log.log(Level.WARNING, "Zoom failed", zx);
				}
			} else if (c == cancelBtn) {
				detach();
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
			throw new AdempiereException("No line available");
		if (rfqList.getSelectedCount() == 0)
			throw new AdempiereException("Select at least one line in the lines table");

		// Empty date -> today
		Date d = dateBox.getValue();
		if (d == null)
			d = new Date();
		Timestamp rfqDate = new Timestamp(d.getTime());

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

				lineNo += 1;

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
}