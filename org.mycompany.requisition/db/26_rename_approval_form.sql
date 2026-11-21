SET search_path TO adempiere, public;
-- Renames the approval form/menu "Purchase Order Approval" -> "Approval Center"
-- (it handles Requisition, PO, Sales Order and RFQ, not only purchase orders).
UPDATE AD_Form SET Name='Approval Center', Description='Approve or reject pending documents', Updated=now()
 WHERE Classname='org.mycompany.requisition.approval.ui.WPOApprovalForm';
UPDATE AD_Menu SET Name='Approval Center', Description='Approve or reject pending documents', Updated=now()
 WHERE AD_Form_ID IN (SELECT AD_Form_ID FROM AD_Form WHERE Classname='org.mycompany.requisition.approval.ui.WPOApprovalForm');
UPDATE AD_Form_Trl SET Name='Approval Center', Description='Approve or reject pending documents'
 WHERE AD_Form_ID IN (SELECT AD_Form_ID FROM AD_Form WHERE Classname='org.mycompany.requisition.approval.ui.WPOApprovalForm');
UPDATE AD_Menu_Trl SET Name='Approval Center', Description='Approve or reject pending documents'
 WHERE AD_Menu_ID IN (SELECT AD_Menu_ID FROM AD_Menu WHERE Name='Approval Center');
-- then: Cache Reset, log out and in again
