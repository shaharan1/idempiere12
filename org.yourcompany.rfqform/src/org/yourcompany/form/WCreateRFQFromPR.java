package org.yourcompany.form;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.Checkbox;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Listbox;
import org.adempiere.webui.component.Listcell;
import org.adempiere.webui.component.Listhead;
import org.adempiere.webui.component.Listheader;
import org.adempiere.webui.component.Listitem;
import org.adempiere.webui.component.Panel;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.component.WDateEditor;
import org.adempiere.webui.component.WListbox;
import org.adempiere.webui.component.WTableDirEditor;
import org.adempiere.webui.editor.WEditor;
import org.adempiere.webui.factory.IFormController;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.panel.CustomForm;
import org.adempiere.webui.panel.IFormController;
import org.adempiere.webui.SessionManager;
import org.compiere.model.MAttachment;
import org.compiere.model.MBPartner;
import org.compiere.model.MCurrency;
import org.compiere.model.MOrg;
import org.compiere.model.MProduct;
import org.compiere.model.MRequisition;
import org.compiere.model.MRequisitionLine;
import org.compiere.model.MRfQ;
import org.compiere.model.MRfQLine;
import org.compiere.model.MRfQLineQty;
import org.compiere.model.MRfQTopic;
import org.compiere.model.MUOM;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.zkoss.util.media.Media;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.UploadEvent;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Hbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.North;
import org.zkoss.zul.Row;
import org.zkoss.zul.Rows;
import org.zkoss.zul.South;
import org.zkoss.zul.Vbox;

public class WCreateRFQFromPR extends CustomForm implements IFormController, EventListener<Event> {

	private static final long serialVersionUID = 1L;

	// Custom term table constants
	public static final String TERM_TABLE = "XX_RFQ_Term";
	public static final String TERM_ID = "XX_RFQ_Term_ID";
	public static final String TERM_NAME = "Name";
	public static final String TERM_TEXT = "TermsText";

	private Properties ctx = Env.getCtx();
	private int windowNo;

	// Parameter editors
	private WTableDirEditor orgEditor;
	private WTableDirEditor reqEditor;
	private WTableDirEditor topicEditor;
	private WDateEditor dateEditor;
	private WTableDirEditor termEditor;
	private Textbox termsBox;
	private Textbox subjectBox;
	private Textbox descBox;

	// Buttons
	private Button reloadBtn;
	private Button attachBtn;
	private Button addLineBtn;
	private Button removeLineBtn;
	private Button okBtn;
	private Button cancelBtn;

	// Grids
	private Listbox reqLineGrid;
	private Listbox rfqLineGrid;

	// Data models
	private List<ReqLineData> reqLines = new ArrayList<>();
	private List<RfqLineData> rfqLines = new ArrayList<>();

	// Attachment
	private Media attachmentMedia = null;

	// Inner data classes
	public static class ReqLineData {
		public boolean selected;
		public int M_RequisitionLine_ID;
		public int M_Product_ID;
		public String productName;
		public int C_UOM_ID;
		public String uomName;
		public BigDecimal qty;
		public String description;
	}

	public static class RfqLineData {
		public int M_RequisitionLine_ID;
		public int M_Product_ID;
		public String productName;
		public int C_UOM_ID;
		public String uomName;
		public BigDecimal qty;
		public String description;
	}

	public WCreateRFQFromPR() {
		super();
		init();
	}

	private void init() {
		this.setWidth("900px");
		this.setHeight("700px");

		Borderlayout layout = new Borderlayout();
		appendChild(layout);
		ZKUpdateUtil.setHeight(layout, "100%");
		ZKUpdateUtil.setWidth(layout, "100%");

		// North: parameters
		North north = new North();
		layout.appendChild(north);
		Panel northPanel = new Panel();
		north.appendChild(northPanel);
		Grid paramGrid = new Grid();
		northPanel.appendChild(paramGrid);
		Rows rows = new Rows();
		paramGrid.appendChild(rows);

		// Org (read-only)
		Row r1 = new Row();
		rows.appendChild(r1);
		r1.appendChild(new Label("Organization"));
		Label orgLabel = new Label(MOrg.get(ctx, Env.getAD_Org_ID(ctx)).getName());
		r1.appendChild(orgLabel);

		// Requisition
		Row r2 = new Row();
		rows.appendChild(r2);
		r2.appendChild(new Label("Requisition"));
		reqEditor = new WTableDirEditor();
		reqEditor.setTable("M_Requisition");
		reqEditor.setColumnName("M_Requisition_ID");
		r2.appendChild(reqEditor.getComponent());
		reqEditor.addValueChangeListener(e -> loadRequisitionLines());

		// RFQ Topic (mandatory)
		Row r3 = new Row();
		rows.appendChild(r3);
		r3.appendChild(new Label("RFQ Topic*"));
		topicEditor = new WTableDirEditor();
		topicEditor.setTable("C_RfQ_Topic");
		topicEditor.setColumnName("C_RfQ_Topic_ID");
		r3.appendChild(topicEditor.getComponent());

		// RFQ Date (mandatory)
		Row r4 = new Row();
		rows.appendChild(r4);
		r4.appendChild(new Label("RFQ Date*"));
		dateEditor = new WDateEditor();
		r4.appendChild(dateEditor.getComponent());

		// RFQ Term
		Row r5 = new Row();
		rows.appendChild(r5);
		r5.appendChild(new Label("RFQ Term"));
		termEditor = new WTableDirEditor();
		termEditor.setTable(TERM_TABLE);
		termEditor.setColumnName(TERM_ID);
		r5.appendChild(termEditor.getComponent());
		termEditor.addValueChangeListener(e -> loadTermText());

		// Terms and Conditions
		Row r6 = new Row();
		rows.appendChild(r6);
		r6.appendChild(new Label("Terms and Conditions"));
		termsBox = new Textbox();
		termsBox.setMultiline(true);
		termsBox.setRows(3);
		ZKUpdateUtil.setWidth(termsBox, "100%");
		r6.appendChild(termsBox);

		// Subject (mandatory)
		Row r7 = new Row();
		rows.appendChild(r7);
		r7.appendChild(new Label("Subject*"));
		subjectBox = new Textbox();
		ZKUpdateUtil.setWidth(subjectBox, "100%");
		r7.appendChild(subjectBox);

		// Description
		Row r8 = new Row();
		rows.appendChild(r8);
		r8.appendChild(new Label("Description"));
		descBox = new Textbox();
		descBox.setMultiline(true);
		descBox.setRows(2);
		ZKUpdateUtil.setWidth(descBox, "100%");
		r8.appendChild(descBox);

		// Buttons row
		Row r9 = new Row();
		rows.appendChild(r9);
		r9.appendChild(new Label(""));
		Hbox btnBox = new Hbox();
		reloadBtn = new Button("Reload Requisition");
		reloadBtn.addEventListener(Events.ON_CLICK, this);
		attachBtn = new Button("Attachment");
		attachBtn.addEventListener(Events.ON_CLICK, this);
		btnBox.appendChild(reloadBtn);
		btnBox.appendChild(attachBtn);
		r9.appendChild(btnBox);

		// Center: req lines grid
		Center center = new Center();
		layout.appendChild(center);
		Panel centerPanel = new Panel();
		center.appendChild(centerPanel);
		ZKUpdateUtil.setVflex(centerPanel, "1");
		ZKUpdateUtil.setHflex(centerPanel, "1");

		reqLineGrid = new Listbox();
		reqLineGrid.setCheckmark(true);
		reqLineGrid.setMultiple(true);
		reqLineGrid.setMold("paging");
		reqLineGrid.setPageSize(10);
		ZKUpdateUtil.setVflex(reqLineGrid, "1");
		ZKUpdateUtil.setHflex(reqLineGrid, "1");
		centerPanel.appendChild(reqLineGrid);

		Listhead lh1 = new Listhead();
		lh1.appendChild(new Listheader("Select"));
		lh1.appendChild(new Listheader("Product"));
		lh1.appendChild(new Listheader("UOM"));
		lh1.appendChild(new Listheader("Qty"));
		lh1.appendChild(new Listheader("Description"));
		reqLineGrid.appendChild(lh1);

		// Add/Remove buttons
		Hbox midBtnBox = new Hbox();
		addLineBtn = new Button("Add Line");
		addLineBtn.addEventListener(Events.ON_CLICK, this);
		removeLineBtn = new Button("Remove Line");
		removeLineBtn.addEventListener(Events.ON_CLICK, this);
		midBtnBox.appendChild(addLineBtn);
		midBtnBox.appendChild(removeLineBtn);
		centerPanel.appendChild(midBtnBox);

		// South: RFQ lines grid + footer
		South south = new South();
		layout.appendChild(south);
		Panel southPanel = new Panel();
		south.appendChild(southPanel);

		rfqLineGrid = new Listbox();
		rfqLineGrid.setMold("paging");
		rfqLineGrid.setPageSize(10);
		ZKUpdateUtil.setHeight(rfqLineGrid, "150px");
		ZKUpdateUtil.setHflex(rfqLineGrid, "1");
		southPanel.appendChild(rfqLineGrid);

		Listhead lh2 = new Listhead();
		lh2.appendChild(new Listheader("Product"));
		lh2.appendChild(new Listheader("UOM"));
		lh2.appendChild(new Listheader("Qty"));
		lh2.appendChild(new Listheader("Description"));
		rfqLineGrid.appendChild(lh2);

		// Footer
		Hbox footerBox = new Hbox();
		southPanel.appendChild(footerBox);
		footerBox.setPack("end");
		okBtn = new Button("OK");
		okBtn.addEventListener(Events.ON_CLICK, this);
		cancelBtn = new Button("Cancel");
		cancelBtn.addEventListener(Events.ON_CLICK, this);
		footerBox.appendChild(okBtn);
		footerBox.appendChild(cancelBtn);
	}

	private void loadRequisitionLines() {
		reqLines.clear();
		reqLineGrid.getItems().clear();

		Object reqIdObj = reqEditor.getValue();
		int reqId = 0;
		if (reqIdObj instanceof Number) {
			reqId = ((Number) reqIdObj).intValue();
		} else if (reqIdObj != null) {
			try { reqId = Integer.parseInt(reqIdObj.toString()); } catch (Exception e) { }
		}

		if (reqId <= 0) return;

		String sql = "SELECT rl.M_RequisitionLine_ID, rl.M_Product_ID, p.Name, rl.C_UOM_ID, u.Name, rl.Qty, rl.Description " +
			"FROM M_RequisitionLine rl " +
			"LEFT JOIN M_Product p ON p.M_Product_ID = rl.M_Product_ID " +
			"LEFT JOIN C_UOM u ON u.C_UOM_ID = rl.C_UOM_ID " +
			"WHERE rl.M_Requisition_ID = ? AND rl.IsActive = 'Y'";

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, reqId);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ReqLineData d = new ReqLineData();
				d.selected = false;
				d.M_RequisitionLine_ID = rs.getInt("M_RequisitionLine_ID");
				d.M_Product_ID = rs.getInt("M_Product_ID");
				d.productName = rs.getString("Name");
				d.C_UOM_ID = rs.getInt("C_UOM_ID");
				d.uomName = rs.getString("u.Name") != null ? rs.getString("u.Name") : rs.getString("Name");
				d.qty = rs.getBigDecimal("Qty");
				d.description = rs.getString("Description");
				reqLines.add(d);

				Listitem item = new Listitem();
				item.setValue(d);
				Checkbox cb = new Checkbox();
				item.appendChild(new Listcell());
				item.getChildren().get(0).appendChild(cb);
				item.appendChild(new Listcell(d.productName != null ? d.productName : ""));
				item.appendChild(new Listcell(d.uomName != null ? d.uomName : ""));
				item.appendChild(new Listcell(d.qty != null ? d.qty.toString() : "0"));
				item.appendChild(new Listcell(d.description != null ? d.description : ""));
				reqLineGrid.appendChild(item);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			DB.close(rs, pstmt);
		}
	}

	private void loadTermText() {
		Object termIdObj = termEditor.getValue();
		int termId = 0;
		if (termIdObj instanceof Number) {
			termId = ((Number) termIdObj).intValue();
		} else if (termIdObj != null) {
			try { termId = Integer.parseInt(termIdObj.toString()); } catch (Exception e) { }
		}

		if (termId <= 0) return;

		String sql = "SELECT " + TERM_TEXT + " FROM " + TERM_TABLE + " WHERE " + TERM_ID + " = ?";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, termId);
			rs = pstmt.executeQuery();
			if (rs.next()) {
				termsBox.setValue(rs.getString(TERM_TEXT));
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			DB.close(rs, pstmt);
		}
	}

	@Override
	public void onEvent(Event event) throws Exception {
		if (event.getTarget() == reloadBtn) {
			loadRequisitionLines();
		} else if (event.getTarget() == attachBtn) {
			// Attachment handled via upload
		} else if (event.getTarget() == addLineBtn) {
			addLines();
		} else if (event.getTarget() == removeLineBtn) {
			removeLines();
		} else if (event.getTarget() == okBtn) {
			onOK();
		} else if (event.getTarget() == cancelBtn) {
			onCancel();
		}
	}

	private void addLines() {
		for (Listitem item : reqLineGrid.getItems()) {
			Object val = item.getValue();
			if (!(val instanceof ReqLineData)) continue;
			ReqLineData rd = (ReqLineData) val;
			// Check if checkbox is checked
			// In ZK checkmark, selected items
			if (!item.isSelected()) continue;

			// Check duplicate
			boolean dup = false;
			for (RfqLineData rld : rfqLines) {
				if (rld.M_RequisitionLine_ID == rd.M_RequisitionLine_ID) {
					dup = true;
					break;
				}
			}
			if (dup) continue;

			RfqLineData rld = new RfqLineData();
			rld.M_RequisitionLine_ID = rd.M_RequisitionLine_ID;
			rld.M_Product_ID = rd.M_Product_ID;
			rld.productName = rd.productName;
			rld.C_UOM_ID = rd.C_UOM_ID;
			rld.uomName = rd.uomName;
			rld.qty = rd.qty;
			rld.description = rd.description;
			rfqLines.add(rld);

			Listitem li = new Listitem();
			li.setValue(rld);
			li.appendChild(new Listcell(rld.productName != null ? rld.productName : ""));
			li.appendChild(new Listcell(rld.uomName != null ? rld.uomName : ""));
			// Qty editable - use textbox
			org.zkoss.zul.Textbox qtyBox = new org.zkoss.zul.Textbox();
			qtyBox.setValue(rld.qty != null ? rld.qty.toString() : "0");
			qtyBox.addEventListener(Events.ON_CHANGE, e -> {
				try { rld.qty = new BigDecimal(qtyBox.getValue()); } catch (Exception ex) { }
			});
			Listcell qtyCell = new Listcell();
			qtyCell.appendChild(qtyBox);
			li.appendChild(qtyCell);
			li.appendChild(new Listcell(rld.description != null ? rld.description : ""));
			rfqLineGrid.appendChild(li);
		}
	}

	private void removeLines() {
		List<Listitem> toRemove = new ArrayList<>();
		for (Listitem item : rfqLineGrid.getItems()) {
			if (item.isSelected()) {
				toRemove.add(item);
				RfqLineData rld = (RfqLineData) item.getValue();
				rfqLines.remove(rld);
			}
		}
		for (Listitem item : toRemove) {
			rfqLineGrid.removeChild(item);
		}
	}

	private void onOK() {
		// Validate
		Object topicObj = topicEditor.getValue();
		int topicId = 0;
		if (topicObj instanceof Number) topicId = ((Number) topicObj).intValue();
		else if (topicObj != null) try { topicId = Integer.parseInt(topicObj.toString()); } catch (Exception e) { }

		if (topicId <= 0) {
			org.adempiere.webui.window.FDialog.error(0, this, "RFQ Topic is mandatory");
			return;
		}

		Date rfqDate = dateEditor.getValue();
		if (rfqDate == null) {
			org.adempiere.webui.window.FDialog.error(0, this, "RFQ Date is mandatory");
			return;
		}

		String subject = subjectBox.getValue();
		if (subject == null || subject.trim().isEmpty()) {
			org.adempiere.webui.window.FDialog.error(0, this, "Subject is mandatory");
			return;
		}

		if (rfqLines.isEmpty()) {
			org.adempiere.webui.window.FDialog.error(0, this, "At least one RFQ line is required");
			return;
		}

		// Create RFQ in transaction
		String trxName = org.compiere.util.Trx.createTrxName("RFQ");
		org.compiere.util.Trx trx = org.compiere.util.Trx.get(trxName, true);
		try {
			trx.start();

			MRfQ rfq = new MRfQ(ctx, 0, trxName);
			rfq.setC_RfQ_Topic_ID(topicId);
			rfq.setName(subject);
			rfq.setDescription(descBox.getValue());
			rfq.setHelp(termsBox.getValue());
			rfq.setDateResponse(new Timestamp(rfqDate.getTime()));
			rfq.setDateWorkStart(new Timestamp(rfqDate.getTime()));
			rfq.setC_Currency_ID(Env.getContextAsInt(ctx, 0, "C_Currency_ID"));
			rfq.setSalesRep_ID(Env.getAD_User_ID(ctx));

			// M_Requisition_ID if column exists
			int reqId = 0;
			Object reqIdObj = reqEditor.getValue();
			if (reqIdObj instanceof Number) reqId = ((Number) reqIdObj).intValue();
			else if (reqIdObj != null) try { reqId = Integer.parseInt(reqIdObj.toString()); } catch (Exception e) { }

			if (reqId > 0 && rfq.get_ColumnIndex("M_Requisition_ID") >= 0) {
				rfq.set_ValueOfColumn("M_Requisition_ID", reqId);
			}

			rfq.saveEx(trxName);

			for (RfqLineData rld : rfqLines) {
				MRfQLine rfqLine = new MRfQLine(rfq);
				rfqLine.setLine(rfqLines.indexOf(rld) * 10 + 10);
				rfqLine.setM_Product_ID(rld.M_Product_ID);
				rfqLine.setDescription(rld.description);
				rfqLine.saveEx(trxName);

				MRfQLineQty rfqLineQty = new MRfQLineQty(rfqLine);
				rfqLineQty.setC_UOM_ID(rld.C_UOM_ID);
				rfqLineQty.setQty(rld.qty != null ? rld.qty : BigDecimal.ONE);
				rfqLineQty.setIsRfQQty(true);
				rfqLineQty.setIsPurchaseQty(true);
				rfqLineQty.saveEx(trxName);
			}

			trx.commit();

			// Attachment
			if (attachmentMedia != null) {
				MAttachment attach = MAttachment.get(ctx, MRfQ.Table_ID, rfq.getC_RfQ_ID());
				if (attach == null) {
					attach = new MAttachment(ctx, MRfQ.Table_ID, rfq.getC_RfQ_ID(), trxName);
				}
				attach.addEntry(attachmentMedia.getName(), attachmentMedia.getStreamData());
				attach.saveEx(trxName);
			}

			// Close form and zoom
			SessionManager.getAppDesktop().closeActiveWindow();
			org.adempiere.webui.apps.AEnv.zoom(MRfQ.Table_ID, rfq.getC_RfQ_ID());

		} catch (Exception e) {
			trx.rollback();
			org.adempiere.webui.window.FDialog.error(0, this, e.getMessage());
		} finally {
			trx.close();
		}
	}

	private void onCancel() {
		SessionManager.getAppDesktop().closeActiveWindow();
	}

	@Override
	public ADForm getForm() {
		return this;
	}
}
