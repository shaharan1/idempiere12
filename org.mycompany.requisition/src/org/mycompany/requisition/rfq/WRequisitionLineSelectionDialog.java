package org.mycompany.requisition.rfq;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.compiere.model.GridTab;
import org.compiere.model.MRfQ;
import org.mycompany.requisition.rfq.WCreateRFQDialog.LineRow;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zul.Button;
import org.zkoss.zul.Hbox;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listhead;
import org.zkoss.zul.Listheader;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.Messagebox;
import org.zkoss.zul.Window;

/**
 * Two modes:
 *  1. RFQ window mode (old): {@link #showDialog(MRfQ, int, GridTab)} - ticked lines are added
 *     to an existing RFQ (used by CalloutRfQRequisition).
 *  2. Picker mode (new, "Create RFQ From PR" form): {@link #showPicker(List, Consumer)} - the
 *     given lines are listed (nothing ticked), the ticked ones are handed to the callback
 *     (the "Selected Lines" basket of WCreateRFQFromPR). No RFQ is touched.
 */
public class WRequisitionLineSelectionDialog extends Window implements EventListener<Event> {
	private static final long serialVersionUID = 1L;
	
	private MRfQ m_rfq;
	private int m_requisitionId;
	private GridTab m_parentTab;
	private Listbox listBox;
	private Button okButton;
	private Button cancelButton;
	private List<RequisitionLineDTO> requisitionLines;
	private boolean cancelled = true;

	/** picker mode only: receives the ticked lines */
	private Consumer<List<LineRow>> m_onAdd;
	
	/** Picker mode: lines of the chosen requisition(s); ticked ones go to onAdd. */
	public WRequisitionLineSelectionDialog(List<LineRow> lines, Consumer<List<LineRow>> onAdd) {
		super();
		this.m_onAdd = onAdd;

		setTitle("Select Lines");
		setBorder("normal");
		setWidth("850px");
		setClosable(true);
		setSizable(true);

		listBox = new Listbox();
		listBox.setMultiple(true);
		listBox.setCheckmark(true);   // row tick boxes + select-all in header
		listBox.setHeight("340px");
		appendChild(listBox);

		Listhead listhead = new Listhead();
		for (String h : new String[] { "Requisition", "BP Code", "Product Code", "Product Name", "UOM", "Qty",
				"Description" })
			listhead.appendChild(new Listheader(h));
		listBox.appendChild(listhead);

		for (LineRow r : lines) {
			Listitem item = new Listitem();
			item.setValue(r);
			item.appendChild(new Listcell(nvl(r.reqNo)));
			item.appendChild(new Listcell(nvl(r.bpCode)));
			item.appendChild(new Listcell(nvl(r.productValue)));
			item.appendChild(new Listcell(nvl(r.productName)));
			item.appendChild(new Listcell(nvl(r.uomName)));
			item.appendChild(new Listcell(r.qty == null ? "" : r.qty.stripTrailingZeros().toPlainString()));
			item.appendChild(new Listcell(nvl(r.desc)));
			listBox.appendChild(item);   // nothing ticked: the user picks the products
		}

		Hbox btnBox = new Hbox();
		btnBox.setStyle("padding: 10px; text-align: right;");
		okButton = new Button("Add Line");
		okButton.addEventListener("onClick", this);
		cancelButton = new Button("Cancel");
		cancelButton.addEventListener("onClick", this);
		btnBox.appendChild(okButton);
		btnBox.appendChild(cancelButton);
		appendChild(btnBox);
	}

	private static String nvl(String s) {
		return s == null ? "" : s;
	}

	public WRequisitionLineSelectionDialog(MRfQ rfq, int requisitionId, GridTab parentTab) {
		super();
		this.m_rfq = rfq;
		this.m_requisitionId = requisitionId;
		this.m_parentTab = parentTab;
		
		setTitle("Select Requisition Lines for RFQ");
		setBorder("normal");
		setWidth("750px");
		setHeight("480px");
		setClosable(true);
		
		listBox = new Listbox();
		listBox.setMultiple(true);
		listBox.setCheckmark(true);
		listBox.setMold("paging");
		listBox.setPageSize(15);
		listBox.setHeight("340px");
		appendChild(listBox);
		
		Listhead listhead = new Listhead();
		listhead.appendChild(new Listheader("Requisition"));
		listhead.appendChild(new Listheader("Line"));
		listhead.appendChild(new Listheader("Product"));
		listhead.appendChild(new Listheader("Qty"));
		listBox.appendChild(listhead);
		
		requisitionLines = RequisitionRFQService.getEligibleRequisitionLines(
			m_rfq.getCtx(), m_requisitionId, m_rfq.get_ID(), m_rfq.get_TrxName()
		);
		
		if (requisitionLines.isEmpty()) {
			Label emptyLbl = new Label("No eligible lines found for this Requisition.");
			emptyLbl.setStyle("color: red; font-style: italic; padding: 10px;");
			appendChild(emptyLbl);
		} else {
			for (RequisitionLineDTO dto : requisitionLines) {
				Listitem item = new Listitem();
				item.setValue(dto);
				item.appendChild(new Listcell(dto.getRequisitionDocumentNo()));
				item.appendChild(new Listcell(String.valueOf(dto.getLineNo())));
				item.appendChild(new Listcell(dto.getProductName() != null ? dto.getProductName() : ""));
				item.appendChild(new Listcell(dto.getQty() != null ? dto.getQty().toString() : "0"));
				listBox.appendChild(item);
			}
		}
		
		Hbox btnBox = new Hbox();
		btnBox.setStyle("padding: 10px; text-align: right;");
		okButton = new Button("Add Selected Lines");
		okButton.addEventListener("onClick", this);
		cancelButton = new Button("Cancel");
		cancelButton.addEventListener("onClick", this);
		
		btnBox.appendChild(okButton);
		btnBox.appendChild(cancelButton);
		appendChild(btnBox);
	}
	
	@Override
	public void onEvent(Event event) throws Exception {
		if (m_onAdd != null) {   // picker mode
			if (event.getTarget() == okButton) {
				List<LineRow> picked = new ArrayList<LineRow>();
				for (Listitem item : listBox.getSelectedItems())
					if (item.getValue() instanceof LineRow)
						picked.add((LineRow) item.getValue());
				if (picked.isEmpty()) {
					Messagebox.show("Tick at least one line.", "Warning", Messagebox.OK, Messagebox.EXCLAMATION);
					return;
				}
				cancelled = false;
				detach();
				m_onAdd.accept(picked);
			} else if (event.getTarget() == cancelButton) {
				cancelled = true;
				detach();
			}
			return;
		}
		if (event.getTarget() == okButton) {
			List<Integer> selectedIds = new ArrayList<Integer>();
			for (Listitem item : listBox.getSelectedItems()) {
				Object value = item.getValue();
				if (value instanceof RequisitionLineDTO) {
					RequisitionLineDTO dto = (RequisitionLineDTO) value;
					selectedIds.add(dto.getM_RequisitionLine_ID());
				}
			}
			System.out.println("Selected IDs: " + selectedIds);
			
			if (selectedIds.isEmpty()) {
				Messagebox.show("Please select at least one line.", "Warning", Messagebox.OK, Messagebox.EXCLAMATION);
				return;
			}
			
			try {
				RequisitionRFQService.createRFQLinesFromRequisitionLines(m_rfq, selectedIds);
				cancelled = false;
				Messagebox.show("Added " + selectedIds.size() + " line(s) to RFQ Details.", "Success", Messagebox.OK, Messagebox.INFORMATION);
				
				// Refresh parent UI tab so new RFQ Lines appear immediately
				if (m_parentTab != null) {
					m_parentTab.dataRefresh();
				}
			} catch (Exception e) {
				System.out.println("Error adding lines: " + e.getMessage());
				e.printStackTrace();
				Messagebox.show("Error: " + e.getMessage(), "Error", Messagebox.OK, Messagebox.ERROR);
			}
			detach();
		} else if (event.getTarget() == cancelButton) {
			cancelled = true;
			detach();
		}
	}
	
	public boolean isCancelled() {
		return cancelled;
	}
	
	public static void showDialog(MRfQ rfq, int requisitionId, GridTab parentTab) {
		WRequisitionLineSelectionDialog dialog = new WRequisitionLineSelectionDialog(rfq, requisitionId, parentTab);
		org.zkoss.zk.ui.Desktop desktop = org.zkoss.zk.ui.Executions.getCurrent().getDesktop();
		if (desktop != null && desktop.getFirstPage() != null) {
			dialog.setPage(desktop.getFirstPage());
			org.mycompany.requisition.ui.XxTheme.apply(dialog);
			dialog.doOverlapped();
		} else {
			System.out.println("No desktop/page available");
		}
	}
}