-- AD Registration for Requisition-RFQ custom columns

-- Create AD_Elements if not exist
INSERT INTO AD_Element (AD_Element_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, ColumnName, EntityType, Name, PrintName)
SELECT (SELECT MAX(AD_Element_ID)+1 FROM AD_Element), 0, 0, 'Y', now(), 100, now(), 100, 'XX_RfQ_ID', 'U', 'RFQ', 'RFQ'
WHERE NOT EXISTS (SELECT 1 FROM AD_Element WHERE ColumnName='XX_RfQ_ID');

INSERT INTO AD_Element (AD_Element_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, ColumnName, EntityType, Name, PrintName)
SELECT (SELECT MAX(AD_Element_ID)+1 FROM AD_Element), 0, 0, 'Y', now(), 100, now(), 100, 'XX_RequisitionLine_ID', 'U', 'Requisition Line', 'Requisition Line'
WHERE NOT EXISTS (SELECT 1 FROM AD_Element WHERE ColumnName='XX_RequisitionLine_ID');

-- Add Column XX_RfQ_ID to M_RequisitionLine
INSERT INTO AD_Column (AD_Column_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, Updated, CreatedBy, UpdatedBy, Name, Description, Help, 
AD_Table_ID, AD_Element_ID, AD_Reference_ID, AD_Reference_Value_ID, ColumnName, FieldLength, DefaultValue, IsKey, IsParent, IsMandatory, 
IsUpdateable, IsIdentifier, SeqNo, IsTranslated, IsEncrypted, IsSelectionColumn, EntityType, IsSyncDatabase, IsAlwaysUpdateable, IsAutocomplete, 
IsAllowLogging, FormatPattern, IsAdvancedField, IsAllowCopy, IsToolbarButton, IsSecure)
SELECT (SELECT MAX(AD_Column_ID)+1 FROM AD_Column), 0, 0, 'Y', now(), now(), 100, 100, 
'RFQ', 'Linked RFQ', 'RFQ created from this requisition line', 
(SELECT AD_Table_ID FROM AD_Table WHERE TableName='M_RequisitionLine'),
(SELECT AD_Element_ID FROM AD_Element WHERE ColumnName='XX_RfQ_ID'),
19, NULL, 'XX_RfQ_ID', 10, NULL, 'N', 'N', 'N', 
'Y', 'N', 0, 'N', 'N', 'N', 'U', 'N', 'N', 'N',
'Y', NULL, 'N', 'Y', 'N', 'N'
WHERE NOT EXISTS (SELECT 1 FROM AD_Column WHERE AD_Table_ID=(SELECT AD_Table_ID FROM AD_Table WHERE TableName='M_RequisitionLine') AND ColumnName='XX_RfQ_ID');

-- Add Column XX_RequisitionLine_ID to M_RfQLine
INSERT INTO AD_Column (AD_Column_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, Updated, CreatedBy, UpdatedBy, Name, Description, Help, 
AD_Table_ID, AD_Element_ID, AD_Reference_ID, AD_Reference_Value_ID, ColumnName, FieldLength, DefaultValue, IsKey, IsParent, IsMandatory, 
IsUpdateable, IsIdentifier, SeqNo, IsTranslated, IsEncrypted, IsSelectionColumn, EntityType, IsSyncDatabase, IsAlwaysUpdateable, IsAutocomplete, 
IsAllowLogging, FormatPattern, IsAdvancedField, IsAllowCopy, IsToolbarButton, IsSecure)
SELECT (SELECT MAX(AD_Column_ID)+1 FROM AD_Column), 0, 0, 'Y', now(), now(), 100, 100, 
'Requisition Line', 'Source Requisition Line', 'Requisition line used to create this RFQ line', 
(SELECT AD_Table_ID FROM AD_Table WHERE TableName='C_RfQLine'),
(SELECT AD_Element_ID FROM AD_Element WHERE ColumnName='XX_RequisitionLine_ID'),
19, NULL, 'XX_RequisitionLine_ID', 10, NULL, 'N', 'N', 'N', 
'Y', 'N', 0, 'N', 'N', 'N', 'U', 'N', 'N', 'N',
'Y', NULL, 'N', 'Y', 'N', 'N'
WHERE NOT EXISTS (SELECT 1 FROM AD_Column WHERE AD_Table_ID=(SELECT AD_Table_ID FROM AD_Table WHERE TableName='C_RfQLine') AND ColumnName='XX_RequisitionLine_ID');
