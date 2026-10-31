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
        if ("org.mycompany.requisition.WCrudForm".equals(className))
            return new WCrudForm();
        if ("org.mycompany.requisition.WQuickProductForm".equals(className))
            return new WQuickProductForm();
        if ("org.mycompany.requisition.sales.WSalesOrderForm".equals(className))
            return new org.mycompany.requisition.sales.WSalesOrderForm();
//        if ("org.mycompany.requisition.approval.ui.WProcessApprovalWindow".equals(className))
//            return new org.mycompany.requisition.approval.ui.WProcessApprovalWindow();
        if ("org.mycompany.requisition.approval.ui.ApprovalDashboard".equals(className))
            return new org.mycompany.requisition.approval.ui.ApprovalDashboard();
//        if ("org.mycompany.requisition.docapproval.DocumentApprovalForm".equals(className))
//            return new org.mycompany.requisition.docapproval.DocumentApprovalForm();
//        if ("com.xx.approval.form.POApprovalForm".equals(className))
//            return new com.xx.approval.form.POApprovalForm();
       
        if ("org.mycompany.requisition.approval.ui.WPOApprovalForm".equals(className)) {
            return new org.mycompany.requisition.approval.ui.WPOApprovalForm();
        }
              
        if ("org.mycompany.requisition.rfq.WCreateRFQFromPR".equals(className))
            return new org.mycompany.requisition.rfq.WCreateRFQFromPR();
        
        if ("org.mycompany.requisition.rfqresponse.WRFQResponseEntry".equals(className))
            return new org.mycompany.requisition.rfqresponse.WRFQResponseEntry();

        if ("org.mycompany.requisition.rfqapproval.WRFQApprovalForm".equals(className))
            return new org.mycompany.requisition.rfqapproval.WRFQApprovalForm();

        return null;
    }
    
    
}
