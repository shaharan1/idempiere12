package org.mycompany.requisition.model;

import java.sql.Timestamp;

/**
 * One entry of a document's approval history.
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

	public String getActionDateText() {
		return actionDate == null ? "-" : actionDate.toString().substring(0, 19);
	}
}
