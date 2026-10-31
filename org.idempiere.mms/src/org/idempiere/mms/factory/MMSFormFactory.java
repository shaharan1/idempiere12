package org.idempiere.mms.factory;

import org.adempiere.webui.factory.IFormFactory;
import org.adempiere.webui.panel.ADForm;
import org.idempiere.mms.form.WMeetingDashboard;

/**
 * MMS Form Factory — maps ZK form class names to instances.
 * The class name must match AD_Form.ClassName in Application Dictionary.
 */
public class MMSFormFactory implements IFormFactory {

    @Override
    public ADForm newFormInstance(String className) {
        if (className == null) return null;
        return switch (className) {
            case "org.idempiere.mms.form.WMeetingDashboard" -> new WMeetingDashboard();
            default -> null;
        };
    }
}
