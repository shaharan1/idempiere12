package org.idempiere.mms.factory;

import org.adempiere.base.IProcessFactory;
import org.compiere.process.SvrProcess;
import org.idempiere.mms.process.CreateOnlineMeetingProcess;
import org.idempiere.mms.process.MeetingReminderProcess;
import org.idempiere.mms.process.SendMeetingInvitationProcess;

/**
 * MMS Process Factory — maps process class names to instances.
 * Register each process class name in AD_Process.ClassName.
 */
public class MMSProcessFactory implements IProcessFactory {

    @Override
    public SvrProcess newProcessInstance(String className) {
        if (className == null) return null;
        return switch (className) {
            case "org.idempiere.mms.process.CreateOnlineMeetingProcess"  -> new CreateOnlineMeetingProcess();
            case "org.idempiere.mms.process.SendMeetingInvitationProcess" -> new SendMeetingInvitationProcess();
            case "org.idempiere.mms.process.MeetingReminderProcess"      -> new MeetingReminderProcess();
            default -> null;
        };
    }
}
