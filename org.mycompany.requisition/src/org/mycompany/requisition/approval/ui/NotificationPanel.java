package org.mycompany.requisition.approval.ui;

import java.util.ArrayList;
import java.util.List;

import org.mycompany.requisition.approval.config.NotificationSetting;
import org.mycompany.requisition.approval.config.NotificationSetting.Channel;
import org.mycompany.requisition.approval.config.NotificationSetting.Event;
import org.mycompany.requisition.approval.config.NotificationSetting.SendTo;
import org.mycompany.requisition.approval.service.NotificationService;
import org.zkoss.zul.Combobox;

/**
 * Section 09, who is told what.
 * <p>
 * One row per event and recipient. The engine reads these rows when it raises an
 * event, so nothing about the recipients is written into the engine: a tenant
 * configures its own process and this file never changes.
 *
 * <h3>Subject and body placeholders</h3>
 * Both accept the same placeholders the default texts use: {@code {DocumentNo}},
 * {@code {Amount}}, {@code {Requester}}, {@code {Status}}, {@code {Action}},
 * {@code {LevelNo}}, {@code {LevelName}}, {@code {DueDate}} and {@code {Route}}.
 * Leave a field empty to use the default text of the event.
 */
public class NotificationPanel extends ConfigListPanel {
	private static final long serialVersionUID = 1L;

	private final WProcessApprovalWindow window;
	private List<NotificationSetting> settings = new ArrayList<NotificationSetting>();
	private NotificationService service;

	private Combobox cmbEvent;
	private Combobox cmbSendTo;
	private Combobox cmbChannel;
	private org.adempiere.webui.component.Textbox txtSubject;
	private org.adempiere.webui.component.Textbox txtBody;
	private org.adempiere.webui.component.Textbox txtDescription;

	public NotificationPanel(WProcessApprovalWindow window) {
		super(window, "No notifications configured. The engine stays silent for this process.");
		this.window = window;
	}

	private NotificationService serviceOrNull() {
		if (service == null) {
			try {
				service = new NotificationService();
			} catch (Exception e) {
				window.reportError("Notifications are not available: " + e.getMessage());
			}
		}
		return service;
	}

	// ─────────────────────────────────────────────────────────────
	//  data
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void reload() {
		settings = new ArrayList<NotificationSetting>();
		NotificationService svc = serviceOrNull();
		if (svc != null && window.getSelectedVersionId() > 0)
			settings.addAll(svc.listSettings(window.getSelectedVersionId()));
	}

	@Override
	protected List<Integer> ids() {
		List<Integer> result = new ArrayList<Integer>();
		for (NotificationSetting s : settings)
			result.add(Integer.valueOf(s.getId()));
		return result;
	}

	@Override
	protected String labelOf(int index) {
		NotificationSetting s = settings.get(index);
		StringBuilder sb = new StringBuilder();
		sb.append(s.getEvent().getLabel());
		sb.append(" to ").append(s.getSendTo().getLabel());
		sb.append("  [").append(s.getChannel().getLabel()).append(']');
		if (s.getSubject() != null && s.getSubject().trim().length() > 0)
			sb.append("  \"").append(s.getSubject()).append('"');
		return sb.toString();
	}

	private NotificationSetting settingOf(int id) {
		for (NotificationSetting s : settings)
			if (s.getId() == id)
				return s;
		return null;
	}

	// ─────────────────────────────────────────────────────────────
	//  editor
	// ─────────────────────────────────────────────────────────────

	@Override
	protected void buildEditor() {
		NotificationSetting setting = editingId() > 0 ? settingOf(editingId()) : null;
		UiLayouts.FieldGrid fields = UiLayouts.grid(4);

		org.adempiere.webui.component.Row row = UiLayouts.row();
		UiLayouts.labelCell(row, "Event");
		cmbEvent = UiLayouts.comboBox();
		for (Event event : Event.values())
			UiLayouts.combo(cmbEvent, event.getLabel(), event.name(),
					setting != null ? event == setting.getEvent() : event == Event.APPROVAL_PENDING);
		UiLayouts.valueCell(row, cmbEvent);

		UiLayouts.labelCell(row, "Channel");
		cmbChannel = UiLayouts.comboBox();
		for (Channel channel : Channel.values())
			UiLayouts.combo(cmbChannel, channel.getLabel(), channel.name(),
					setting != null ? channel == setting.getChannel() : channel == Channel.NOTICE);
		UiLayouts.valueCell(row, cmbChannel);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Send To");
		cmbSendTo = UiLayouts.comboBox();
		for (SendTo sendTo : SendTo.values())
			UiLayouts.combo(cmbSendTo, sendTo.getLabel(), sendTo.name(),
					setting != null ? sendTo == setting.getSendTo() : sendTo == SendTo.APPROVER);
		UiLayouts.valueCell(row, cmbSendTo);

		UiLayouts.labelCell(row, "Description");
		txtDescription = UiLayouts.text();
		txtDescription.setValue(setting == null ? "" : nullSafe(setting.getDescription()));
		UiLayouts.valueCell(row, txtDescription);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Subject");
		txtSubject = UiLayouts.text();
		txtSubject.setValue(setting == null ? "" : nullSafe(setting.getSubject()));
		UiLayouts.wideValueCell(row, txtSubject, 3);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.labelCell(row, "Message");
		txtBody = UiLayouts.text();
		txtBody.setValue(setting == null ? "" : nullSafe(setting.getMessageBody()));
		txtBody.setRows(4);
		UiLayouts.wideValueCell(row, txtBody, 3);
		fields.rows.appendChild(row);

		row = UiLayouts.row();
		UiLayouts.hintCell(row, "Leave the subject and message empty to use the default text of the "
				+ "event. Placeholders: {DocumentNo} {Amount} {Requester} {Status} {Action} {LevelNo} "
				+ "{LevelName} {DueDate} {Route}");
		fields.rows.appendChild(row);

		editor().appendChild(fields.grid);
		hint("");
	}

	// ─────────────────────────────────────────────────────────────
	//  actions
	// ─────────────────────────────────────────────────────────────

	@Override
	protected String firstActionLabel() {
		return null;
	}

	@Override
	protected String secondActionLabel() {
		return null;
	}

	@Override
	protected void createRow() {
		run("Adding a notification", () -> {
			NotificationSetting setting = fromEditor();
			NotificationService svc = requireService();
			svc.create(setting);
			window.reportStatus(setting.getEvent().getLabel() + " notification to "
					+ setting.getSendTo().getLabel() + " saved.");
			refresh();
		});
	}

	@Override
	protected void updateRow(int id) {
		run("Saving the notification", () -> {
			NotificationSetting setting = fromEditor();
			setting.setId(id);
			requireService().update(setting);
			window.reportStatus("Notification updated.");
			refresh();
		});
	}

	@Override
	protected void deleteRow(int id) {
		run("Deleting the notification", () -> {
			requireService().delete(id);
			window.reportStatus("Notification deactivated.");
			refresh();
		});
	}

	private NotificationSetting fromEditor() throws Exception {
		NotificationSetting setting = new NotificationSetting();
		setting.setProcessVersionId(window.getSelectedVersionId());

		setting.setEvent(eventOf());
		setting.setSendTo(sendToOf());
		setting.setChannel(channelOf());
		setting.setSubject(textOf(txtSubject));
		setting.setMessageBody(textOf(txtBody));
		setting.setDescription(textOf(txtDescription));
		return setting;
	}

	private Event eventOf() {
		Object value = UiLayouts.comboValue(cmbEvent);
		return Event.of(value == null ? null : value.toString());
	}

	private SendTo sendToOf() {
		Object value = UiLayouts.comboValue(cmbSendTo);
		return SendTo.of(value == null ? null : value.toString());
	}

	private Channel channelOf() {
		Object value = UiLayouts.comboValue(cmbChannel);
		return Channel.of(value == null ? null : value.toString());
	}

	private NotificationService requireService() throws Exception {
		NotificationService svc = serviceOrNull();
		if (svc == null)
			throw new IllegalStateException("The notification table is not available.");
		return svc;
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}
}
