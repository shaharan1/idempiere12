package org.mycompany.requisition.approval.config;

/**
 * Which notification is sent on which event, to whom and through which channel.
 * <p>
 * Maps to {@code XX_NOTIFICATION_SETTING}. Nothing here is hard coded in the
 * engine: the event, the recipient role and the channel are all stored per
 * process version, so the same framework notifies differently for a purchase
 * requisition and for a leave request without a code change.
 * <p>
 * Rows are additive. A process with no notification rows simply sends nothing,
 * which is a valid configuration rather than an error.
 */
public class NotificationSetting {

	/** Notification events the engine understands. */
	public enum Event {
		/** A level becomes pending and the approver must act. */
		APPROVAL_PENDING("Approval Pending"),
		/** The configured warning window has been reached. */
		APPROVAL_REMINDER("Approval Reminder"),
		/** The due date has passed without an action. */
		APPROVAL_OVERDUE("Approval Overdue"),
		/** The step has been escalated to its fallback. */
		ESCALATION("Escalation"),
		/** The whole chain is approved. */
		APPROVAL_COMPLETED("Approval Completed"),
		/** A level rejected the document. */
		APPROVAL_REJECTED("Approval Rejected"),
		/** The document was returned to the requester. */
		APPROVAL_RETURNED("Approval Returned"),
		/** A delegation was recorded. */
		DELEGATION_RECORDED("Delegation Recorded");

		private final String label;

		private Event(String label) {
			this.label = label;
		}

		public String getLabel() {
			return label;
		}

		/** Resolves a stored code, defaulting to the pending event. */
		public static Event of(String code) {
			if (code != null)
				for (Event e : values())
					if (e.name().equalsIgnoreCase(code.trim()))
						return e;
			return APPROVAL_PENDING;
		}
	}

	/** Who receives the notification. */
	public enum SendTo {
		/** The approver of the pending level. */
		APPROVER("Approver"),
		/** The user who submitted the document. */
		REQUESTER("Requester"),
		/** The supervisor of the requester. */
		MANAGER("Manager"),
		/** The escalation target of the level. */
		ESCALATION_USER("Escalation User"),
		/** Everyone with the administrator role of the process. */
		ADMINISTRATOR("Administrator");

		private final String label;

		private SendTo(String label) {
			this.label = label;
		}

		public String getLabel() {
			return label;
		}

		/** Resolves a stored code, defaulting to the approver. */
		public static SendTo of(String code) {
			if (code != null)
				for (SendTo s : values())
					if (s.name().equalsIgnoreCase(code.trim()))
						return s;
			return APPROVER;
		}
	}

	/** Delivery channel. */
	public enum Channel {
		/** In application notice, handled by iDempiere. */
		NOTICE("System Notice"),
		/** Mail, handled by the iDempiere mail pipeline. */
		MAIL("EMail"),
		/** Both. */
		NOTICE_AND_MAIL("Notice and EMail");

		private final String label;

		private Channel(String label) {
			this.label = label;
		}

		public String getLabel() {
			return label;
		}

		/** Resolves a stored code, defaulting to the in application notice. */
		public static Channel of(String code) {
			if (code != null)
				for (Channel c : values())
					if (c.name().equalsIgnoreCase(code.trim()))
						return c;
			return NOTICE;
		}
	}

	private int id;
	private int processVersionId;
	private Event event = Event.APPROVAL_PENDING;
	private SendTo sendTo = SendTo.APPROVER;
	private Channel channel = Channel.NOTICE;
	private String subject;
	private String messageBody;
	private String description;

	/** XX_NOTIFICATION_SETTING_ID, 0 when not persisted. */
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	/** XX_PROCESS_VERSION_ID this setting belongs to. */
	public int getProcessVersionId() {
		return processVersionId;
	}

	public void setProcessVersionId(int processVersionId) {
		this.processVersionId = processVersionId;
	}

	public Event getEvent() {
		return event;
	}

	public void setEvent(Event event) {
		this.event = event == null ? Event.APPROVAL_PENDING : event;
	}

	public SendTo getSendTo() {
		return sendTo;
	}

	public void setSendTo(SendTo sendTo) {
		this.sendTo = sendTo == null ? SendTo.APPROVER : sendTo;
	}

	public Channel getChannel() {
		return channel;
	}

	public void setChannel(Channel channel) {
		this.channel = channel == null ? Channel.NOTICE : channel;
	}

	/** Subject line, placeholders are replaced by the engine. */
	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	/** Message body, placeholders are replaced by the engine. */
	public String getMessageBody() {
		return messageBody;
	}

	public void setMessageBody(String messageBody) {
		this.messageBody = messageBody;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@Override
	public String toString() {
		return event.getLabel() + " -> " + sendTo.getLabel() + " (" + channel.getLabel() + ")";
	}
}
