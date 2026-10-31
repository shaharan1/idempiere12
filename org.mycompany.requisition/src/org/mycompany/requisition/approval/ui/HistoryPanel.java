package org.mycompany.requisition.approval.ui;

import java.util.List;

import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.ListItem;
import org.adempiere.webui.component.Listbox;
import org.mycompany.requisition.approval.model.ApprovalHistoryEntry;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.zkoss.zul.Vlayout;

/**
 * Section 11, the approval history of the document selected in section 10.
 * <p>
 * Append only and read only. There is no edit and no delete here, and there is
 * none in the service either, because the history of an approval is the one thing
 * that must stay true: if it could be edited, "approved on the third of March by
 * the finance manager" would no longer mean anything.
 */
public class HistoryPanel extends Vlayout {
	private static final long serialVersionUID = 1L;

	private Listbox lstHistory;
	private Label lblCaption;

	public HistoryPanel() {
		setSpacing("2px");
		setStyle("width: 100%;");

		lblCaption = new Label("No document selected in section 10.");
		lblCaption.setStyle("color: #555555;");
		appendChild(lblCaption);

		lstHistory = new Listbox();
		lstHistory.setWidth("100%");
		lstHistory.setRows(6);
		UiLayouts.clear(lstHistory);
		appendChild(lstHistory);
	}

	/** Redraws for the document currently selected in section 10. */
	public void show(ApprovalTransaction chain) {
		UiLayouts.clear(lstHistory);
		if (chain == null) {
			lblCaption.setValue("No document selected in section 10.");
			lblCaption.setStyle("color: #555555;");
			return;
		}

		List<ApprovalHistoryEntry> entries = chain.getHistory();
		if (entries.isEmpty()) {
			lblCaption.setValue("Document " + chain.getDisplayName() + " has no history yet.");
			lblCaption.setStyle("color: #a06000;");
			return;
		}

		lblCaption.setValue("History of " + chain.getDisplayName() + " - " + entries.size()
				+ " entry(ies), oldest first. This list cannot be edited.");
		lblCaption.setStyle("color: #333333; font-weight: bold;");

		for (ApprovalHistoryEntry entry : entries) {
			ListItem item = lstHistory.appendItem(rowOf(entry), entry);
			if (item != null)
				item.setStyle(styleOf(entry));
		}
	}

	private String rowOf(ApprovalHistoryEntry entry) {
		StringBuilder sb = new StringBuilder();
		if (entry.getActionDate() != null)
			sb.append(entry.getActionDate()).append("  ");
		sb.append(entry.getAction() == null ? "?" : entry.getAction().getLabel());
		if (entry.getLevelNo() > 0)
			sb.append("  level ").append(entry.getLevelNo());
		if (entry.getLevelName() != null)
			sb.append(" (").append(entry.getLevelName()).append(')');
		if (entry.getUserId() > 0)
			sb.append("  by ").append(userName(entry.getUserId()));
		if (entry.getComment() != null && entry.getComment().trim().length() > 0)
			sb.append("  -  ").append(entry.getComment().trim());
		if (entry.getDuration() != null)
			sb.append("  [").append(entry.getDuration()).append(" min]");
		return sb.toString();
	}

	/** A rejected or returned entry is coloured, and says so in its own action text. */
	private String styleOf(ApprovalHistoryEntry entry) {
		if (entry.getAction() == null)
			return "";
		switch (entry.getAction().name()) {
		case "REJECT":
			return "color: #b00020;";
		case "RETURN":
			return "color: #e07b00;";
		case "APPROVE":
			return "color: #2e7d32;";
		default:
			return "";
		}
	}

	private String userName(int userId) {
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_User WHERE AD_User_ID=?", Integer.valueOf(userId));
		return name == null ? ("user " + userId) : name;
	}
}
