SET search_path TO adempiere, public;

-- =====================================================================
-- 20_add_m_inout_challan_columns.sql
-- Add Challan, Pack, and Cost columns to M_InOut and M_InOutLine
-- and register them in Application Dictionary (AD_Element and AD_Column).
--
-- Header (M_InOut):
--   XX_VAT_Challan_No         VARCHAR(60)
--   XX_VAT_Challan_Date       TIMESTAMP
--   XX_Delivery_Challan_No    VARCHAR(60)
--   XX_Delivery_Challan_Date  TIMESTAMP
--
-- Line (M_InOutLine):
--   XX_Pack_Size              VARCHAR(60)
--   XX_No_Of_Pack             NUMERIC
--   XX_Challan_Qty            NUMERIC
--   XX_Damaged_Qty            NUMERIC
--   XX_Eff_Cost               NUMERIC
-- =====================================================================

-- 1. Database Columns
ALTER TABLE M_InOut ADD COLUMN IF NOT EXISTS XX_VAT_Challan_No         VARCHAR(60);
ALTER TABLE M_InOut ADD COLUMN IF NOT EXISTS XX_VAT_Challan_Date       TIMESTAMP;
ALTER TABLE M_InOut ADD COLUMN IF NOT EXISTS XX_Delivery_Challan_No    VARCHAR(60);
ALTER TABLE M_InOut ADD COLUMN IF NOT EXISTS XX_Delivery_Challan_Date  TIMESTAMP;

ALTER TABLE M_InOutLine ADD COLUMN IF NOT EXISTS XX_Pack_Size          VARCHAR(60);
ALTER TABLE M_InOutLine ADD COLUMN IF NOT EXISTS XX_No_Of_Pack         NUMERIC;
ALTER TABLE M_InOutLine ADD COLUMN IF NOT EXISTS XX_Challan_Qty        NUMERIC;
ALTER TABLE M_InOutLine ADD COLUMN IF NOT EXISTS XX_Damaged_Qty        NUMERIC;
ALTER TABLE M_InOutLine ADD COLUMN IF NOT EXISTS XX_Eff_Cost           NUMERIC;

-- 2. AD_Element + AD_Column Registration
-- References: 10=String, 15=Date, 16=DateTime, 22=Number, 29=Quantity
DO $$
DECLARE
    r          RECORD;
    v_table_id NUMERIC;
    v_elem_id  NUMERIC;
    v_col_id   NUMERIC;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('M_InOut',     'XX_VAT_Challan_No',        'VAT Challan No',        10, 60,  NULL::text),
            ('M_InOut',     'XX_VAT_Challan_Date',      'VAT Challan Date',      15, 15,  NULL),
            ('M_InOut',     'XX_Delivery_Challan_No',   'Delivery Challan No',   10, 60,  NULL),
            ('M_InOut',     'XX_Delivery_Challan_Date', 'Delivery Challan Date', 15, 15,  NULL),
            ('M_InOutLine', 'XX_Pack_Size',             'Pack Size',             10, 60,  NULL),
            ('M_InOutLine', 'XX_No_Of_Pack',            'No of Pack',            22, 22,  NULL),
            ('M_InOutLine', 'XX_Challan_Qty',           'Challan Qty',           29, 29,  NULL),
            ('M_InOutLine', 'XX_Damaged_Qty',           'Damaged Qty',           29, 29,  NULL),
            ('M_InOutLine', 'XX_Eff_Cost',              'Effective Cost',        22, 22,  NULL)
        ) AS t(tbl, col, nm, ref, len, def)
    LOOP
        SELECT AD_Table_ID INTO v_table_id FROM AD_Table WHERE TableName = r.tbl;
        IF v_table_id IS NULL THEN
            RAISE EXCEPTION 'AD_Table not found: %', r.tbl;
        END IF;

        SELECT AD_Element_ID INTO v_elem_id FROM AD_Element WHERE LOWER(ColumnName) = LOWER(r.col);
        IF v_elem_id IS NULL THEN
            v_elem_id := (SELECT COALESCE(MAX(AD_Element_ID), 999999) + 1 FROM AD_Element);
            INSERT INTO AD_Element (AD_Element_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                    Updated, UpdatedBy, ColumnName, EntityType, Name, PrintName, AD_Element_UU)
            VALUES (v_elem_id, 0, 0, 'Y', now(), 100, now(), 100, r.col, 'U', r.nm, r.nm, gen_random_uuid()::text);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM AD_Column WHERE AD_Table_ID = v_table_id AND LOWER(ColumnName) = LOWER(r.col)) THEN
            v_col_id := (SELECT COALESCE(MAX(AD_Column_ID), 999999) + 1 FROM AD_Column);
            INSERT INTO AD_Column (AD_Column_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, Updated, CreatedBy, UpdatedBy,
                Name, AD_Table_ID, AD_Element_ID, AD_Reference_ID, ColumnName, FieldLength, DefaultValue,
                IsKey, IsParent, IsMandatory, IsUpdateable, IsIdentifier, SeqNo, IsTranslated, IsEncrypted,
                IsSelectionColumn, EntityType, IsSyncDatabase, IsAlwaysUpdateable, Version, AD_Column_UU)
            VALUES (v_col_id, 0, 0, 'Y', now(), now(), 100, 100,
                r.nm, v_table_id, v_elem_id, r.ref, r.col, r.len, r.def,
                'N', 'N', 'N', 'Y', 'N', 0, 'N', 'N',
                'N', 'U', 'N', 'N', 0, gen_random_uuid()::text);
        END IF;
    END LOOP;
END $$;
