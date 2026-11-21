package org.mycompany.requisition.docapproval;

import java.sql.Timestamp;

/**
 * One entry of a document's approval history.
 *
 * @author Shaharan Hossain
 */
public class ApprovalHistoryLine {

	private String action;
	private Timestamp actionDate;
	private String comment;
	private String userName;

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public Timestamp getActionDate() {
		return actionDate;
	}

	public void setActionDate(Timestamp actionDate) {
		this.actionDate = actionDate;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	/** "2026-05-04 11:20:00, or an em dash when the date is unknown. */
	public String getActionDateText() {
		if (actionDate == null)
			return "—";
		return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(actionDate);
	}

	@Override
	public String toString() {
		return (action == null ? "" : action) + " " + getActionDateText();
	}
}