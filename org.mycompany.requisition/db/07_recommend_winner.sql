SET search_path TO adempiere, public;
-- =====================================================================
-- Recommend Winner (Document Approval Form)
-- PostgreSQL, idempotent. Chalanor por: Cache Reset + logout/login.
--
-- 1. UT_ApprovalMatrix.IsRecommendWinner : matrix window theke control
--    (Y = ei matrix row er approver Recommend Winner button pabe)
-- 2. C_RfQResponseLine.XX_WinnerComment  : per product/supplier comment
-- 3. XX_RfQ_WinnerLog                    : "History records" panel er data
-- =====================================================================

-- ---------- 1. matrix flag ----------
DO $$
DECLARE
    v_new boolean := false;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema()
                     AND LOWER(table_name) = 'ut_approvalmatrix'
                     AND LOWER(column_name) = 'isrecommendwinner') THEN
        ALTER TABLE UT_ApprovalMatrix ADD COLUMN IsRecommendWinner CHAR(1) NOT NULL DEFAULT 'N';
        v_new := true;
    END IF;
    -- Je row gulo age thekei ache segulor kaj jeno na bhange: ekbar 'Y' kore dei.
    -- Notun row te default 'N' (matrix window theke tick dile cholbe).
    IF v_new THEN
        UPDATE UT_ApprovalMatrix SET IsRecommendWinner = 'Y';
    END IF;
END $$;

-- ---------- 2. comment column ----------
ALTER TABLE C_RfQResponseLine ADD COLUMN IF NOT EXISTS XX_WinnerComment VARCHAR(255);

-- ---------- 3. history table ----------
CREATE TABLE IF NOT EXISTS XX_RfQ_WinnerLog (
    XX_RfQ_WinnerLog_ID  SERIAL PRIMARY KEY,
    AD_Client_ID         NUMERIC(10) NOT NULL,
    C_RfQ_ID             NUMERIC(10) NOT NULL,
    C_RfQLine_ID         NUMERIC(10),
    C_RfQResponse_ID     NUMERIC(10),
    Created              TIMESTAMP NOT NULL DEFAULT now(),
    CreatedBy            NUMERIC(10) NOT NULL,
    BPartnerName         VARCHAR(120),
    ProductName          VARCHAR(255),
    QuotedQty            NUMERIC,
    QuotedPrice          NUMERIC,
    NegotiatePrice       NUMERIC,
    Rank                 NUMERIC(10),
    IsWinner             CHAR(1) NOT NULL DEFAULT 'N',
    Comment              VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS xx_rfq_winnerlog_rfq ON XX_RfQ_WinnerLog (C_RfQ_ID, Created DESC);

-- ---------- 4. matrix window e field (View er thik pore) ----------
DO $$
DECLARE
    v_table  NUMERIC;
    v_elem   NUMERIC;
    v_col    NUMERIC;
    v_tab    NUMERIC;
    v_view   NUMERIC;
    v_seq    NUMERIC;
    v_opt    TEXT;
BEGIN
    SELECT AD_Table_ID INTO v_table FROM AD_Table WHERE LOWER(TableName) = 'ut_approvalmatrix';
    IF v_table IS NULL THEN
        RAISE NOTICE 'AD_Table UT_ApprovalMatrix paoa jayni, window field add kora holo na';
        RETURN;
    END IF;

    SELECT AD_Element_ID INTO v_elem FROM AD_Element WHERE LOWER(ColumnName) = 'isrecommendwinner';
    IF v_elem IS NULL THEN
        v_elem := (SELECT COALESCE(MAX(AD_Element_ID), 999999) + 1 FROM AD_Element);
        INSERT INTO AD_Element (AD_Element_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                Updated, UpdatedBy, ColumnName, EntityType, Name, PrintName)
        VALUES (v_elem, 0, 0, 'Y', now(), 100, now(), 100, 'IsRecommendWinner', 'U',
                'Recommend Winner', 'Recommend Winner');
    END IF;

    SELECT AD_Column_ID INTO v_col FROM AD_Column
     WHERE AD_Table_ID = v_table AND LOWER(ColumnName) = 'isrecommendwinner';
    IF v_col IS NULL THEN
        v_col := (SELECT COALESCE(MAX(AD_Column_ID), 999999) + 1 FROM AD_Column);
        -- Shudhu je column gulo sob version e ache segulo diye insert kori.
        INSERT INTO AD_Column (AD_Column_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, Updated, CreatedBy, UpdatedBy,
            Name, AD_Table_ID, AD_Element_ID, AD_Reference_ID, ColumnName, FieldLength, DefaultValue,
            IsKey, IsParent, IsMandatory, IsUpdateable, IsIdentifier, SeqNo, IsTranslated, IsEncrypted,
            IsSelectionColumn, EntityType, IsSyncDatabase, IsAlwaysUpdateable)
        VALUES (v_col, 0, 0, 'Y', now(), now(), 100, 100,
            'Recommend Winner', v_table, v_elem, 20, 'IsRecommendWinner', 1, 'N',
            'N', 'N', 'Y', 'Y', 'N', 0, 'N', 'N',
            'N', 'U', 'N', 'N');
        -- Version bhede thaka optional column: thakle tobei set kori.
        FOR v_opt IN SELECT unnest(ARRAY['IsAutocomplete','IsAdvancedField','IsToolbarButton','IsSecure']) LOOP
            IF EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND LOWER(table_name) = 'ad_column'
                         AND LOWER(column_name) = LOWER(v_opt)) THEN
                EXECUTE format('UPDATE AD_Column SET %I = %L WHERE AD_Column_ID = %s', v_opt, 'N', v_col);
            END IF;
        END LOOP;
    END IF;

    -- tab: matrix table er prothom tab
    SELECT AD_Tab_ID INTO v_tab FROM AD_Tab WHERE AD_Table_ID = v_table ORDER BY SeqNo, AD_Tab_ID LIMIT 1;
    IF v_tab IS NULL THEN
        RAISE NOTICE 'UT_ApprovalMatrix er AD_Tab paoa jayni, field add kora holo na';
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM AD_Field WHERE AD_Tab_ID = v_tab AND AD_Column_ID = v_col) THEN
        RETURN;
    END IF;

    -- "View" field: column name ba field name e 'view' ache emon prothom field
    SELECT f.AD_Field_ID, f.SeqNo INTO v_view, v_seq
      FROM AD_Field f JOIN AD_Column c ON c.AD_Column_ID = f.AD_Column_ID
     WHERE f.AD_Tab_ID = v_tab
       AND (LOWER(f.Name) LIKE '%view%' OR LOWER(c.ColumnName) LIKE '%view%')
     ORDER BY f.SeqNo LIMIT 1;

    IF v_view IS NULL THEN
        v_seq := (SELECT COALESCE(MAX(SeqNo), 0) FROM AD_Field WHERE AD_Tab_ID = v_tab);
    ELSE
        UPDATE AD_Field SET SeqNo = SeqNo + 10 WHERE AD_Tab_ID = v_tab AND SeqNo > v_seq;
    END IF;

    INSERT INTO AD_Field (AD_Field_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                          Name, Description, AD_Tab_ID, AD_Column_ID, IsDisplayed, SeqNo, IsSameLine, IsHeading,
                          IsFieldOnly, IsReadOnly, EntityType)
    VALUES ((SELECT COALESCE(MAX(AD_Field_ID), 999999) + 1 FROM AD_Field), 0, 0, 'Y', now(), 100, now(), 100,
            'Recommend Winner', 'Tick = ei approver Document Approval Form e Recommend Winner button pabe',
            v_tab, v_col, 'Y', v_seq + 10, 'Y', 'N', 'N', 'N', 'U');
END $$;
