package org.mycompany.requisition.approval.ui;

import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Row;
import org.mycompany.requisition.approval.model.ApprovalStatus;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.zkoss.zul.Vlayout;

/**
 * Section 12, who did what to this approval, and when.
 * <p>
 * Read only, and deliberately separate from the history. The history says what
 * happened level by level; this says when the record was created, when it last
 * changed, and who released, rejected or returned it. An auditor asking "who
 * released this document" should not have to read a list of events to find out.
 *
 * <p>
 * The values come from the transaction header, which iDempiere fills itself through
 * the standard audit columns, so nothing here is written by this framework.
 */
public class AuditPanel extends Vlayout {
	private static final long serialVersionUID = 1L;

	private UiLayouts.FieldGrid fields;

	public AuditPanel() {
		setSpacing("2px");
		setStyle("width: 100%;");

		fields = UiLayouts.grid(4);
		appendChild(fields.grid);
		show(null);
	}

	/** Redraws for the document currently selected in section 10. */
	public void show(ApprovalTransaction chain) {
		UiLayouts.clear(fields.rows);

		if (chain == null) {
			Row row = UiLayouts.row();
			UiLayouts.hintCell(row, "No document selected in section 10.");
			fields.rows.appendChild(row);
			return;
		}

		Row row = UiLayouts.row();
		addReadOnly(row, "Document", chain.getDisplayName());
		addReadOnly(row, "Status", chain.getStatus().getLabel());
		addReadOnly(row, "Process Version", chain.getProcessVersionId() > 0
				? "version " + chain.getProcessVersionId() : "not recorded");
		addReadOnly(row, "Target Record", chain.getTableId() + " / " + chain.getRecordId());
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		addReadOnly(row, "Requested By", chain.getRequestedBy() > 0 ? userName(chain.getRequestedBy()) : "");
		addReadOnly(row, "Requested On", text(chain.getRequestDate()));
		addReadOnly(row, "Amount", chain.getAmount() == null ? "" : chain.getAmount().toPlainString());
		addReadOnly(row, "Current Level", chain.getCurrentLevel() > 0 ? String.valueOf(chain.getCurrentLevel()) : "-");
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		addReadOnly(row, "Approved By", chain.getApprovedBy() > 0 ? userName(chain.getApprovedBy()) : "");
		addReadOnly(row, "Approved On", text(chain.getApprovedDate()));
		addReadOnly(row, "Rejected By", chain.getRejectedBy() > 0 ? userName(chain.getRejectedBy()) : "");
		addReadOnly(row, "Rejected On", text(chain.getRejectedDate()));
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		org.adempiere.webui.component.Textbox lastAction = UiLayouts.text();
		lastAction.setReadonly(true);
		lastAction.setValue(chain.getLastAction() == null ? "" : chain.getLastAction().getLabel());
		UiLayouts.labelCell(row, "Last Action");
		UiLayouts.valueCell(row, lastAction);

		org.adempiere.webui.component.Textbox lastActionOn = UiLayouts.text();
		lastActionOn.setReadonly(true);
		lastActionOn.setValue(text(chain.getLastActionDate()));
		UiLayouts.labelCell(row, "Last Action On");
		UiLayouts.valueCell(row, lastActionOn);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		ApprovalStatus status = chain.getStatus();
		UiLayouts.hintCell(row, status.isFinal()
				? "This approval is closed and its history cannot be changed."
				: "This approval is still running. Who released it is recorded once it is closed.");
		fields.rows.appendChild(row);
	}

	private void addReadOnly(Row row, String label, String value) {
		org.adempiere.webui.component.Textbox box = UiLayouts.text();
		box.setReadonly(true);
		box.setValue(value == null ? "" : value);
		UiLayouts.labelCell(row, label);
		UiLayouts.valueCell(row, box);
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private String userName(int userId) {
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_User WHERE AD_User_ID=?", Integer.valueOf(userId));
		return name == null ? ("user " + userId) : name;
	}
}
