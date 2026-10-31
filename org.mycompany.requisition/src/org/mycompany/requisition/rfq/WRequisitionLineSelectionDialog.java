package org.mycompany.requisition.rfq;

import java.util.ArrayList;
import java.util.List;

import org.compiere.model.GridTab;
import org.compiere.model.MRfQ;
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
			dialog.doOverlapped();
		} else {
			System.out.println("No desktop/page available");
		}
	}
}
