package org.mycompany.requisition.rfqpo;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.Window;
import org.adempiere.webui.panel.ADForm;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.mycompany.requisition.rfqpo.RfqPoService.CsLine;
import org.mycompany.requisition.rfqpo.RfqPoService.Line;
import org.mycompany.requisition.rfqpo.RfqPoService.Result;
import org.mycompany.requisition.rfqpo.RfqPoService.Row;
import org.mycompany.requisition.rfqpo.WinnerChangeService.ChangeRequest;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Checkbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listgroup;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Vlayout;

/**
 * Create PO from CS with Fast-Track Winner Change Request & Approval.
 */
public class WCreatePOFromCS extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WCreatePOFromCS.class);
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
	private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy");

	private final RfqPoService service = new RfqPoService();

	private Checkbox pendingOnly;
	private Label countLabel;
	private Listbox grid;

	@Override
	protected void initForm() {
		org.mycompany.requisition.ui.XxTheme.wrap(this);
		setTitle("Create PO From CS");
		setWidth("100%");
		setHeight("100%");

		WinnerChangeService.ensureTable();

		// Root fills entire form vertically
		Vlayout root = new Vlayout();
		root.setWidth("100%");
		root.setVflex("1");
		root.setStyle("padding:0;margin:0;display:flex;flex-direction:column;height:100%;box-sizing:border-box;");
		appendChild(root);

		// ---- top bar
		Div topBar = new Div();
		topBar.setStyle(
			"display:flex;align-items:center;gap:12px;padding:8px 14px;" +
			"background:linear-gradient(90deg,#1e3a8a 0%,#2563eb 100%);" +
			"border-radius:6px 6px 0 0;flex-shrink:0;");
		root.appendChild(topBar);

		Label org = new Label("Organization: " + Env.getContext(Env.getCtx(), "#AD_Org_Name"));
		org.setStyle("font-weight:700;color:#ffffff;font-size:13px;");

		Label sep = new Label("|");
		sep.setStyle("color:#93c5fd;font-weight:300;");

		pendingOnly = new Checkbox("Pending PO only");
		pendingOnly.setChecked(true);
		pendingOnly.setStyle("color:#ffffff;font-size:13px;font-weight:500;");
		pendingOnly.addEventListener(Events.ON_CHECK, e -> reload());

		Button reload = new Button("⟳ Reload RFQ");
		reload.setStyle(
			"background:#0d9488;color:#fff;border:none;border-radius:4px;" +
			"padding:4px 14px;font-size:12px;font-weight:600;cursor:pointer;");
		reload.addEventListener(Events.ON_CLICK, e -> reload());

		// HOD Approvals button
		Button hodBtn = new Button("📋 Winner Change Requests");
		hodBtn.setStyle(
			"background:#f59e0b;color:#1e3a8a;border:none;border-radius:4px;" +
			"padding:4px 14px;font-size:12px;font-weight:700;cursor:pointer;margin-left:6px;");
		hodBtn.addEventListener(Events.ON_CLICK, e -> showHodApprovalDialog());

		countLabel = new Label();
		countLabel.setStyle("color:#bfdbfe;font-size:12px;margin-left:auto;");

		topBar.appendChild(org);
		topBar.appendChild(sep);
		topBar.appendChild(pendingOnly);
		topBar.appendChild(reload);
		topBar.appendChild(hodBtn);
		topBar.appendChild(countLabel);

		// ---- Table wrapper fills remaining vertical space
		Div tableWrap = new Div();
		tableWrap.setWidth("100%");
		tableWrap.setVflex("1");
		tableWrap.setStyle("flex:1 1 auto;overflow:hidden;");
		root.appendChild(tableWrap);

		// ---- Grid / Listbox
		grid = new Listbox();
		grid.setWidth("100%");
		grid.setVflex("1");
		grid.setStyle("border:none;border-radius:0 0 6px 6px;");

		Listhead head = new Listhead();
		head.setSizable(true);
		String[] names  = { "SL No.", "BP Code", "Business Partner", "Address",
				"PO Creation", "Product Code", "Product Name", "Product Rank",
				"Price", "Recommended Winner", "Select Winner", "Comments" };
		String[] hflexs = { "1", "2", "4", "5",
				"3", "2", "4", "2",
				"2", "3", "3", "4" };
		for (int i = 0; i < names.length; i++) {
			Listheader h = new Listheader(names[i]);
			h.setHflex(hflexs[i]);
			h.setStyle("text-align:center;white-space:normal;");
			head.appendChild(h);
		}
		grid.appendChild(head);
		tableWrap.appendChild(grid);

		reload();
	}

	private void reload() {
		grid.getItems().clear();

		Map<Integer, Row> csHeads = new LinkedHashMap<Integer, Row>();
		for (Row r : service.listRows(pendingOnly.isChecked()))
			csHeads.putIfAbsent(Integer.valueOf(r.rfqId), r);
		Map<String, Row> winnerRows = new HashMap<String, Row>();
		for (Row r : service.listRows(false))
			winnerRows.put(r.rfqId + ":" + r.respId, r);

		for (Row head : csHeads.values()) {
			// CS Header banner row spanning across all 12 columns
			Listitem banner = new Listitem();
			banner.setStyle("background:linear-gradient(90deg, #1e3a8a 0%, #2563eb 100%);color:#ffffff;");
			Listcell bannerCell = new Listcell("📁 CS " + head.docNo + " — " + head.name + " (" + (head.date == null ? "" : DATE.format(head.date)) + ")");
			bannerCell.setSpan(12);
			bannerCell.setStyle("font-weight:700;font-size:13px;color:#ffffff;padding:8px 14px;letter-spacing:0.3px;");
			banner.appendChild(bannerCell);
			grid.appendChild(banner);

			Map<String, Line> left = new HashMap<String, Line>();
			for (Row wr : winnerRows.values())
				if (wr.rfqId == head.rfqId)
					for (Line l : service.loadLines(wr.rfqId, wr.respId))
						left.put(wr.respId + ":" + l.rfqLineId, l);

			// Map: respId -> list of CsLine
			Map<Integer, List<CsLine>> vendorLines = new LinkedHashMap<Integer, List<CsLine>>();
			for (CsLine c : service.listCsLines(head.rfqId)) {
				vendorLines.computeIfAbsent(Integer.valueOf(c.respId), k -> new java.util.ArrayList<CsLine>()).add(c);
			}

			int sl = 0;
			for (Map.Entry<Integer, List<CsLine>> entry : vendorLines.entrySet()) {
				int respId = entry.getKey().intValue();
				List<CsLine> lines = entry.getValue();
				sl++;

				final Row wr = winnerRows.get(head.rfqId + ":" + respId);

				// Count winning lines for this vendor that have remaining qty and are approved without pending change
				int winnerLinesCount = 0;
				for (CsLine c : lines) {
					Line wl = left.get(respId + ":" + c.rfqLineId);
					ChangeRequest pendingReq = WinnerChangeService.getPendingRequest(c.rfqLineId);
					boolean hasPendingChange = (pendingReq != null);
					if (c.winner && wl != null && wl.remaining().signum() > 0 && !hasPendingChange) {
						winnerLinesCount++;
					}
				}

				boolean canCreatePo = (wr != null && wr.csApproved() && winnerLinesCount > 0);

				for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
					final CsLine c = lines.get(lineIdx);
					boolean first = (lineIdx == 0);

					Listitem li = new Listitem();
					li.setStyle((sl % 2 == 0) ? "background:#f8faff;" : "background:#ffffff;");

					li.appendChild(first ? center(String.valueOf(sl)) : new Listcell());
					li.appendChild(new Listcell(first ? c.bpCode : ""));
					li.appendChild(new Listcell(first ? c.bpName : ""));
					li.appendChild(new Listcell(first ? c.address : ""));

					// PO Creation Column: Only on the first row of each vendor!
					Listcell action = new Listcell();
					action.setStyle("text-align:center;");
					if (first) {
						if (canCreatePo) {
							final Button create = new Button("Create PO (" + winnerLinesCount + ")");
							create.setStyle("background:#16a34a;color:#fff;border:none;border-radius:4px;padding:4px 10px;font-size:11px;font-weight:600;cursor:pointer;");
							create.addEventListener(Events.ON_CLICK, e -> createVendorPo(wr, create));
							action.appendChild(create);
						} else {
							// Invisible / hidden if no winner line exists for this vendor
							// We leave the cell empty or blank as requested
						}
					}
					li.appendChild(action);

					li.appendChild(new Listcell(c.productCode));
					li.appendChild(new Listcell(c.productName));
					li.appendChild(center(String.valueOf(c.rank)));

					Listcell price = new Listcell(MONEY.format(c.price));
					price.setStyle("text-align:right;");
					li.appendChild(price);

					// Recommended Winner — read-only
					li.appendChild(check(c.winner));

					// ---- Select Winner cell with Fast-Track Deviation Request ----
					final int rfqLineId_f = c.rfqLineId;
					final int respId_f    = c.respId;
					final int rfqId_f     = c.rfqId;
					final String bpName_f = c.bpName;
					final String pName_f  = c.productName;

					ChangeRequest pendingReq = WinnerChangeService.getPendingRequest(c.rfqLineId);
					boolean hasPendingChange = (pendingReq != null);

					Listcell selCell = new Listcell();
					selCell.setStyle("text-align:center;");

					if (hasPendingChange && pendingReq.newRespId == c.respId) {
						Label pendingBadge = new Label("⏳ Pending HOD");
						pendingBadge.setStyle("color:#b45309;background:#fef3c7;padding:2px 6px;border-radius:4px;font-size:11px;font-weight:600;");
						pendingBadge.setTooltiptext("Change requested: " + pendingReq.justification);
						selCell.appendChild(pendingBadge);
					} else {
						Checkbox selCb = new Checkbox();
						selCb.setChecked(c.winner);
						selCb.setStyle("cursor:pointer;");
						selCb.addEventListener(Events.ON_CHECK, evt -> {
							boolean newVal = selCb.isChecked();
							if (newVal) {
								showWinnerChangeDialog(rfqId_f, rfqLineId_f, respId_f, bpName_f, pName_f, selCb);
							} else {
								selCb.setChecked(true);
								Messagebox.show("To change winner, please check the alternative vendor you want to request.",
										"Info", Messagebox.OK, Messagebox.INFORMATION);
							}
						});
						selCell.appendChild(selCb);
					}
					li.appendChild(selCell);

					// ---- Comments ----
					Listcell cm = new Listcell();
					Textbox tb = new Textbox(c.comment == null ? "" : c.comment);
					tb.setReadonly(!c.winner);
					tb.setWidth("97%");
					tb.setStyle(c.winner
							? "border:1px solid #60a5fa;border-radius:3px;font-size:12px;"
							: "border:1px solid #e5e7eb;border-radius:3px;font-size:12px;background:#f9fafb;");
					cm.appendChild(tb);
					li.appendChild(cm);

					grid.appendChild(li);
				}
			}
		}
		countLabel.setValue("Total CS: " + csHeads.size());
	}

	/** Fast-Track Winner Change Request Modal Dialog */
	private void showWinnerChangeDialog(int rfqId, int rfqLineId, int newRespId, String bpName, String prodName, Checkbox selCb) {
		Window dlg = new Window();
		dlg.setTitle("Request Winner Change (Deviation Request)");
		dlg.setAttribute(Window.MODE_KEY, Window.MODE_HIGHLIGHTED);
		dlg.setWidth("480px");

		Vlayout body = new Vlayout();
		body.setStyle("padding:16px;gap:12px;");
		dlg.appendChild(body);

		Label title = new Label("Product: " + prodName + "\nProposed New Winner: " + bpName);
		title.setStyle("font-weight:700;color:#1e3a8a;font-size:13px;white-space:pre-line;");
		body.appendChild(title);

		Label sub = new Label("CS was already approved. Changing winner requires HOD approval. Please provide official justification (e.g. Previous vendor unable to supply/stock out):");
		sub.setStyle("font-size:12px;color:#4b5563;");
		body.appendChild(sub);

		Textbox reasonBox = new Textbox();
		reasonBox.setRows(3);
		reasonBox.setWidth("100%");
		reasonBox.setPlaceholder("Enter justification for HOD review…");
		reasonBox.setStyle("font-size:12px;border:1px solid #93c5fd;border-radius:4px;");
		body.appendChild(reasonBox);

		Label errLabel = new Label("⚠ Justification is mandatory.");
		errLabel.setStyle("color:#dc2626;font-size:12px;display:none;");
		body.appendChild(errLabel);

		Hlayout buttons = new Hlayout();
		buttons.setStyle("justify-content:flex-end;gap:8px;margin-top:8px;");

		Button submitBtn = new Button("✔ Submit to HOD");
		submitBtn.setStyle("background:#2563eb;color:#fff;border:none;border-radius:4px;padding:5px 16px;font-weight:600;cursor:pointer;");

		Button cancelBtn = new Button("✘ Cancel");
		cancelBtn.setStyle("background:#6b7280;color:#fff;border:none;border-radius:4px;padding:5px 14px;cursor:pointer;");

		buttons.appendChild(submitBtn);
		buttons.appendChild(cancelBtn);
		body.appendChild(buttons);

		submitBtn.addEventListener(Events.ON_CLICK, e -> {
			String reason = reasonBox.getValue().trim();
			if (reason.isEmpty()) {
				errLabel.setStyle("color:#dc2626;font-size:12px;display:block;");
				return;
			}
			try {
				WinnerChangeService.submitRequest(rfqId, rfqLineId, newRespId, reason);
				Messagebox.show("Winner change request submitted for HOD approval.", "Success", Messagebox.OK, Messagebox.INFORMATION);
				dlg.detach();
				reload();
			} catch (Exception ex) {
				Messagebox.show("Error: " + ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
			}
		});

		cancelBtn.addEventListener(Events.ON_CLICK, e -> {
			selCb.setChecked(false);
			dlg.detach();
			reload();
		});

		dlg.setClosable(true);
		AEnv.showCenterScreen(dlg);
	}

	/** HOD Fast-Track Review & Approval Dialog */
	private void showHodApprovalDialog() {
		List<ChangeRequest> pendingList = WinnerChangeService.listPendingRequests();

		Window dlg = new Window();
		dlg.setTitle("HOD Winner Change Approvals (" + pendingList.size() + " Pending)");
		dlg.setAttribute(Window.MODE_KEY, Window.MODE_HIGHLIGHTED);
		dlg.setWidth("750px");
		dlg.setHeight("480px");

		Vlayout body = new Vlayout();
		body.setStyle("padding:14px;gap:10px;height:100%;box-sizing:border-box;");
		dlg.appendChild(body);

		if (pendingList.isEmpty()) {
			Label empty = new Label("No pending winner change requests.");
			empty.setStyle("font-size:13px;color:#6b7280;font-weight:500;padding:20px;text-align:center;display:block;");
			body.appendChild(empty);
		} else {
			Listbox reqGrid = new Listbox();
			reqGrid.setVflex("1");
			reqGrid.setWidth("100%");

			Listhead lh = new Listhead();
			String[] hNames = {"CS Doc No", "Product", "Previous Winner", "Requested Winner", "Justification", "Action"};
			String[] hWidths = {"90px", "120px", "110px", "110px", "170px", "120px"};
			for (int i=0; i<hNames.length; i++) {
				Listheader th = new Listheader(hNames[i]);
				th.setWidth(hWidths[i]);
				lh.appendChild(th);
			}
			reqGrid.appendChild(lh);

			for (ChangeRequest cr : pendingList) {
				Listitem item = new Listitem();
				item.appendChild(new Listcell(cr.rfqDocNo));
				item.appendChild(new Listcell(cr.productName));
				item.appendChild(new Listcell(cr.oldBpName.isEmpty() ? "-" : cr.oldBpName));
				item.appendChild(new Listcell(cr.newBpName));

				Listcell jc = new Listcell(cr.justification);
				jc.setTooltiptext(cr.justification);
				item.appendChild(jc);

				Listcell actCell = new Listcell();
				Hlayout actLayout = new Hlayout();
				actLayout.setStyle("gap:4px;");

				Button appBtn = new Button("✔");
				appBtn.setTooltiptext("Approve Change");
				appBtn.setStyle("background:#16a34a;color:#fff;border:none;border-radius:3px;padding:3px 8px;font-weight:bold;cursor:pointer;");
				appBtn.addEventListener(Events.ON_CLICK, ev -> {
					try {
						WinnerChangeService.approveRequest(cr.changeId, "Approved by HOD");
						Messagebox.show("Request Approved! New winner is set.", "Success", Messagebox.OK, Messagebox.INFORMATION);
						dlg.detach();
						reload();
					} catch (Exception ex) {
						Messagebox.show("Error: " + ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
					}
				});

				Button rejBtn = new Button("✘");
				rejBtn.setTooltiptext("Reject Change");
				rejBtn.setStyle("background:#dc2626;color:#fff;border:none;border-radius:3px;padding:3px 8px;font-weight:bold;cursor:pointer;");
				rejBtn.addEventListener(Events.ON_CLICK, ev -> {
					try {
						WinnerChangeService.rejectRequest(cr.changeId, "Rejected by HOD");
						Messagebox.show("Request Rejected.", "Info", Messagebox.OK, Messagebox.INFORMATION);
						dlg.detach();
						reload();
					} catch (Exception ex) {
						Messagebox.show("Error: " + ex.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
					}
				});

				actLayout.appendChild(appBtn);
				actLayout.appendChild(rejBtn);
				actCell.appendChild(actLayout);
				item.appendChild(actCell);

				reqGrid.appendChild(item);
			}
			body.appendChild(reqGrid);
		}

		Button closeBtn = new Button("Close");
		closeBtn.setStyle("background:#6b7280;color:#fff;border:none;border-radius:4px;padding:4px 14px;cursor:pointer;align-self:flex-end;");
		closeBtn.addEventListener(Events.ON_CLICK, e -> dlg.detach());
		body.appendChild(closeBtn);

		dlg.setClosable(true);
		AEnv.showCenterScreen(dlg);
	}

	/** Creates the PO for ALL winning product lines of this vendor and Completes it in ONE PO. */
	private void createVendorPo(Row r, Button button) {
		if (!RfqPoService.columnsReady()) {
			Messagebox.show("Run db/09_create_po_from_cs.sql first.",
				"Create PO From CS", Messagebox.OK, Messagebox.ERROR);
			return;
		}
		button.setDisabled(true);
		try {
			// createCompletedPo(r, 0) takes ALL winner lines with remaining qty for this vendor
			Result res = service.createCompletedPo(r, 0);
			Messagebox.show("Vendor PO " + res.docNo + " created successfully for " + r.bpName + ".\n" + res.approvalMessage,
				"Create PO From CS", Messagebox.OK, Messagebox.INFORMATION);
		} catch (Exception ex) {
			log.warning("createVendorPo: " + ex.getMessage());
			Messagebox.show(ex.getMessage() == null ? ex.toString() : ex.getMessage(),
				"Create PO From CS", Messagebox.OK, Messagebox.ERROR);
		}
		reload();
	}

	private static Listcell check(boolean on) {
		Listcell c = new Listcell();
		Checkbox cb = new Checkbox();
		cb.setChecked(on);
		cb.setDisabled(true);
		c.appendChild(cb);
		c.setStyle("text-align:center;");
		return c;
	}

	private static Listcell center(String text) {
		Listcell c = new Listcell(text);
		c.setStyle("text-align:center;");
		return c;
	}
}
