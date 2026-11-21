-- 10_rfq_docstatus.sql
-- Shows "Document Status" (read-only) in the RFQ window.
--
--   Drafted     (DR) : not completed yet, or the CS was rejected
--   In Progress (IP) : "Complete RFQ" was done and the CS is going through the approval levels
--   Completed   (CO) : the CS got its FINAL approval
--
-- It is a VIRTUAL column (AD_Column.ColumnSQL): nothing is added to the C_RfQ table and the value
-- can never get out of step with UT_ApprovalHistory. It is the same rule the Java code uses
-- (RfQApprovalService.completeSql()), which drives:
--   * RFQ Response Entry : only Drafted RFQs are listed
--   * Create PO from CS  : only Completed (finally approved) RFQs are listed
--
-- Safe to run more than once. After running: System Admin > Cache Reset, then log in again.

-- iDempiere tables live in the "adempiere" schema
SET search_path TO adempiere, public;

DO $$
DECLARE
    v_table  numeric;
    v_elem   numeric;
    v_col    numeric;
    v_docno  text;
    v_sql    text;
    v_match  text;
    v_tab    record;
BEGIN
    SELECT AD_Table_ID INTO v_table FROM AD_Table WHERE TableName = 'C_RfQ';
    IF v_table IS NULL THEN
        RAISE EXCEPTION 'AD_Table C_RfQ not found';
    END IF;

    -- C_RfQ.DocumentNo is optional in this installation (same check as RfQApprovalService.docNoExpr)
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE lower(table_name) = 'c_rfq' AND lower(column_name) = 'documentno') THEN
        v_docno := 'COALESCE(C_RfQ.DocumentNo, C_RfQ.Name)';
    ELSE
        v_docno := 'C_RfQ.Name';
    END IF;

    -- NOTE: no ORDER BY / LIMIT in here on purpose. MRole.addAccessSQL cuts the statement at the last
    -- " ORDER BY " it finds, which breaks AccessSqlParser (StringIndexOutOfBoundsException).
    -- IP : the CS has a pending approval level (any P row)      -> In Progress
    -- CO : no pending level and the latest action is Approved   -> Completed (final approval done)
    -- DR : no history, or the latest action is Rejected          -> Drafted
    v_match := 'h.AD_Client_ID=C_RfQ.AD_Client_ID AND h.DocumentNo=' || v_docno
            || ' AND h.C_DocType_ID=(SELECT rq.C_DocType_ID FROM M_Requisition rq'
            || ' WHERE rq.M_Requisition_ID=C_RfQ.M_Requisition_ID)';

    v_sql := '(CASE WHEN EXISTS (SELECT 1 FROM UT_ApprovalHistory h WHERE ' || v_match
          || ' AND h.Status=''P'') THEN ''IP'''
          || ' WHEN EXISTS (SELECT 1 FROM UT_ApprovalHistory h WHERE ' || v_match
          || ' AND h.Status=''A'' AND h.Updated=(SELECT MAX(h2.Updated) FROM UT_ApprovalHistory h2'
          || ' WHERE h2.AD_Client_ID=h.AD_Client_ID AND h2.DocumentNo=h.DocumentNo'
          || ' AND h2.C_DocType_ID=h.C_DocType_ID)) THEN ''CO'' ELSE ''DR'' END)';

    SELECT AD_Element_ID INTO v_elem FROM AD_Element WHERE ColumnName = 'DocStatus' ORDER BY AD_Element_ID LIMIT 1;
    IF v_elem IS NULL THEN
        RAISE EXCEPTION 'AD_Element DocStatus not found';
    END IF;

    SELECT AD_Column_ID INTO v_col FROM AD_Column WHERE AD_Table_ID = v_table AND ColumnName = 'DocStatus';

    IF v_col IS NULL THEN
        SELECT COALESCE(MAX(AD_Column_ID), 0) + 1 INTO v_col FROM AD_Column;
        -- only columns that exist in every iDempiere version; the rest take their table defaults
        INSERT INTO AD_Column (AD_Column_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, Updated, CreatedBy, UpdatedBy,
            Name, Description, Help,
            AD_Table_ID, AD_Element_ID, AD_Reference_ID, AD_Reference_Value_ID, ColumnName, FieldLength, DefaultValue,
            IsKey, IsParent, IsMandatory, IsUpdateable, IsIdentifier, SeqNo, IsTranslated, IsEncrypted,
            IsSelectionColumn, EntityType, IsSyncDatabase, IsAlwaysUpdateable, Version, ColumnSQL)
        VALUES (v_col, 0, 0, 'Y', now(), now(), 100, 100,
            'Document Status', 'The current status of the document',
            'Completed once Complete RFQ is done and its CS is pending approval or approved.',
            v_table, v_elem, 17, 131, 'DocStatus', 2, NULL,
            'N', 'N', 'N', 'N', 'N', 0, 'N', 'N',
            'N', 'U', 'N', 'N', 0, v_sql);
    ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE lower(table_name) = 'c_rfq' AND lower(column_name) = 'docstatus') THEN
        -- C_RfQ already has a real DocStatus column: leave it alone
        RAISE NOTICE 'C_RfQ already has a physical DocStatus column; AD_Column not changed.';
    ELSE
        UPDATE AD_Column SET ColumnSQL = v_sql, IsUpdateable = 'N', IsActive = 'Y', Updated = now()
         WHERE AD_Column_ID = v_col;
    END IF;

    -- show it (read-only) in every header tab of C_RfQ that does not have it yet
    FOR v_tab IN SELECT AD_Tab_ID FROM AD_Tab
                  WHERE AD_Table_ID = v_table AND TabLevel = 0 AND IsActive = 'Y' LOOP
        IF NOT EXISTS (SELECT 1 FROM AD_Field WHERE AD_Tab_ID = v_tab.AD_Tab_ID AND AD_Column_ID = v_col) THEN
            INSERT INTO AD_Field (AD_Field_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                Name, Description, AD_Tab_ID, AD_Column_ID, IsCentrallyMaintained, IsDisplayed, DisplayLength,
                IsReadOnly, SeqNo, IsSameLine, IsHeading, IsFieldOnly, IsEncrypted, EntityType)
            VALUES ((SELECT COALESCE(MAX(AD_Field_ID), 0) + 1 FROM AD_Field), 0, 0, 'Y', now(), 100, now(), 100,
                'Document Status', 'The current status of the document', v_tab.AD_Tab_ID, v_col, 'N', 'Y', 2,
                'Y', (SELECT COALESCE(MAX(SeqNo), 0) + 10 FROM AD_Field WHERE AD_Tab_ID = v_tab.AD_Tab_ID),
                'N', 'N', 'N', 'N', 'U');
        END IF;
    END LOOP;
END $$;
