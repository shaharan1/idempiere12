package org.mycompany.requisition;

import org.adempiere.webui.factory.IFormFactory;
import org.adempiere.webui.panel.ADForm;

public class RequisitionFormFactory implements IFormFactory {

    @Override
    public ADForm newFormInstance(String className) {
        if (className == null)
            return null;
        if ("org.mycompany.requisition.WEmpAdvanceForm".equals(className))
            return new WEmpAdvanceForm();
        return null;
    }
}
