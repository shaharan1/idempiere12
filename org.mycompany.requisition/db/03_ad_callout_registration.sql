-- Register CalloutRfQRequisition on M_RfQ.M_Requisition_ID column

UPDATE AD_Column 
SET Callout = 'org.mycompany.requisition.callout.CalloutRfQRequisition.start'
WHERE AD_Table_ID = (SELECT AD_Table_ID FROM AD_Table WHERE TableName='C_RfQ')
AND ColumnName = 'M_Requisition_ID';

-- Verify
SELECT ColumnName, Callout FROM AD_Column 
WHERE AD_Table_ID = (SELECT AD_Table_ID FROM AD_Table WHERE TableName='C_RfQ')
AND ColumnName = 'M_Requisition_ID';
