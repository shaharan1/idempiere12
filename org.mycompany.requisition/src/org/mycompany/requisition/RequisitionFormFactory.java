package org.mycompany.requisition;

import org.adempiere.webui.factory.IFormFactory;
import org.adempiere.webui.panel.ADForm;
import org.mycompany.requisition.approval.ui.ApprovalDashboard;
import org.mycompany.requisition.approval.ui.WPOApprovalForm;
import org.mycompany.requisition.rfq.WCreateRFQFromPR;
import org.mycompany.requisition.rfqapproval.WRFQApprovalForm;
import org.mycompany.requisition.rfqpo.WCreatePOFromCS;
import org.mycompany.requisition.rfqresponse.WRFQResponseEntry;
import org.mycompany.requisition.sales.WSalesOrderForm;
import org.mycompany.requisition.ui.WCrudForm;
import org.mycompany.requisition.ui.WEmpAdvanceForm;
import org.mycompany.requisition.ui.WGoodsReceiptForm;
import org.mycompany.requisition.ui.WQuickProductForm;
import org.mycompany.requisition.ui.WRequisitionAssignForm;
import org.mycompany.requisition.ui.WRequisitionEntryForm;

public class RequisitionFormFactory implements IFormFactory {

    @Override
    public ADForm newFormInstance(String className) {
        if (className == null)
            return null;

        // Support both new UI package name and legacy package name for backwards compatibility
        if ("org.mycompany.requisition.ui.WEmpAdvanceForm".equals(className)
                || "org.mycompany.requisition.WEmpAdvanceForm".equals(className))
            return new WEmpAdvanceForm();

        if ("org.mycompany.requisition.ui.WRequisitionEntryForm".equals(className)
                || "org.mycompany.requisition.WRequisitionEntryForm".equals(className))
            return new WRequisitionEntryForm();

        if ("org.mycompany.requisition.ui.WGoodsReceiptForm".equals(className)
                || "org.mycompany.requisition.WGoodsReceiptForm".equals(className))
            return new WGoodsReceiptForm();

        if ("org.mycompany.requisition.ui.WCrudForm".equals(className)
                || "org.mycompany.requisition.WCrudForm".equals(className))
            return new WCrudForm();

        if ("org.mycompany.requisition.ui.WQuickProductForm".equals(className)
                || "org.mycompany.requisition.WQuickProductForm".equals(className))
            return new WQuickProductForm();

        if ("org.mycompany.requisition.ui.WRequisitionAssignForm".equals(className)
                || "org.mycompany.requisition.WRequisitionAssignForm".equals(className))
            return new WRequisitionAssignForm();

        if ("org.mycompany.requisition.sales.WSalesOrderForm".equals(className))
            return new WSalesOrderForm();

        if ("org.mycompany.requisition.approval.ui.ApprovalDashboard".equals(className))
            return new ApprovalDashboard();

        if ("org.mycompany.requisition.approval.ui.WPOApprovalForm".equals(className))
            return new WPOApprovalForm();

        if ("org.mycompany.requisition.rfq.WCreateRFQFromPR".equals(className))
            return new WCreateRFQFromPR();

        if ("org.mycompany.requisition.rfqresponse.WRFQResponseEntry".equals(className))
            return new WRFQResponseEntry();

        if ("org.mycompany.requisition.rfqapproval.WRFQApprovalForm".equals(className))
            return new WRFQApprovalForm();

        if ("org.mycompany.requisition.rfqpo.WCreatePOFromCS".equals(className))
            return new WCreatePOFromCS();

        return null;
    }
}
