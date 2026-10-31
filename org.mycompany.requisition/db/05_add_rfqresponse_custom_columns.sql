SET search_path TO adempiere, public;
-- =====================================================================
-- RFQ Response Entry form er custom column (PostgreSQL)
-- Header  : C_RfQResponse
-- Row     : C_RfQResponseLineQty  (Image 3 er prottek row = ek ta LineQty)
-- =====================================================================

-- ---------- 1. DB column ----------
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_QuotationRef      VARCHAR(60);
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_Warranty          VARCHAR(120);
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_DeliveryPlace     VARCHAR(120);
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_PaymentTerm_ID    NUMERIC(10);
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_PaymentRule       VARCHAR(1);
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_TransportMode     VARCHAR(60);
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_TransportCost     NUMERIC;
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_OthersCost        NUMERIC;
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_IsGrossDiscountPct CHAR(1) DEFAULT 'N';
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_GrossDiscount     NUMERIC;
ALTER TABLE C_RfQResponse ADD COLUMN IF NOT EXISTS XX_GrandTotal        NUMERIC;

ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_Specification  VARCHAR(255);
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_Manufacturer   VARCHAR(60);
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_Brand          VARCHAR(60);
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_Origin         VARCHAR(60);
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_QuotationQty   NUMERIC;
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_NegotiatePrice NUMERIC;
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_Tax_ID         NUMERIC(10);
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_IsRebate       CHAR(1) DEFAULT 'N';
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_IsTariff       CHAR(1) DEFAULT 'N';
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_VATAmt         NUMERIC;
ALTER TABLE C_RfQResponseLineQty ADD COLUMN IF NOT EXISTS XX_LineTotal      NUMERIC;

-- ---------- 2. AD_Element + AD_Column registration ----------
-- (02_ad_registration_rfqlines.sql er moto, kintu ek loop e shob column)
-- Reference: 10=String, 11=Integer, 20=YesNo, 22=Number
DO $$
DECLARE
    r          RECORD;
    v_table_id NUMERIC;
    v_elem_id  NUMERIC;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('C_RfQResponse',        'XX_QuotationRef',       'Quotation Ref',        10, 60,  NULL::text),
            ('C_RfQResponse',        'XX_Warranty',           'Warranty',             10, 120, NULL),
            ('C_RfQResponse',        'XX_DeliveryPlace',      'Delivery Place',       10, 120, NULL),
            ('C_RfQResponse',        'XX_PaymentTerm_ID',     'Payment Term',         11, 10,  NULL),
            ('C_RfQResponse',        'XX_PaymentRule',        'Payment Rule',         10, 1,   NULL),
            ('C_RfQResponse',        'XX_TransportMode',      'Transportation Mode',  10, 60,  NULL),
            ('C_RfQResponse',        'XX_TransportCost',      'Transport Cost',       22, 22,  NULL),
            ('C_RfQResponse',        'XX_OthersCost',         'Others Cost',          22, 22,  NULL),
            ('C_RfQResponse',        'XX_IsGrossDiscountPct', 'Gross Discount %',     20, 1,   'N'),
            ('C_RfQResponse',        'XX_GrossDiscount',      'Gross Discount',       22, 22,  NULL),
            ('C_RfQResponse',        'XX_GrandTotal',         'Grand Total',          22, 22,  NULL),
            ('C_RfQResponseLineQty', 'XX_Specification',      'Specification',        10, 255, NULL),
            ('C_RfQResponseLineQty', 'XX_Manufacturer',       'Manufacturer',         10, 60,  NULL),
            ('C_RfQResponseLineQty', 'XX_Brand',              'Brand',                10, 60,  NULL),
            ('C_RfQResponseLineQty', 'XX_Origin',             'Origin',               10, 60,  NULL),
            ('C_RfQResponseLineQty', 'XX_QuotationQty',       'Quotation Qty',        22, 22,  NULL),
            ('C_RfQResponseLineQty', 'XX_NegotiatePrice',     'Negotiate Price',      22, 22,  NULL),
            ('C_RfQResponseLineQty', 'XX_Tax_ID',             'VAT Rate',             11, 10,  NULL),
            ('C_RfQResponseLineQty', 'XX_IsRebate',           'Rebate',               20, 1,   'N'),
            ('C_RfQResponseLineQty', 'XX_IsTariff',           'Tariff Rate',          20, 1,   'N'),
            ('C_RfQResponseLineQty', 'XX_VATAmt',             'VAT Amt',              22, 22,  NULL),
            ('C_RfQResponseLineQty', 'XX_LineTotal',          'Line Total',           22, 22,  NULL)
        ) AS t(tbl, col, nm, ref, len, def)
    LOOP
        SELECT AD_Table_ID INTO v_table_id FROM AD_Table WHERE TableName = r.tbl;
        IF v_table_id IS NULL THEN
            RAISE EXCEPTION 'AD_Table not found: %', r.tbl;
        END IF;

        SELECT AD_Element_ID INTO v_elem_id FROM AD_Element WHERE ColumnName = r.col;
        IF v_elem_id IS NULL THEN
            v_elem_id := (SELECT COALESCE(MAX(AD_Element_ID), 999999) + 1 FROM AD_Element);
            INSERT INTO AD_Element (AD_Element_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                    Updated, UpdatedBy, ColumnName, EntityType, Name, PrintName)
            VALUES (v_elem_id, 0, 0, 'Y', now(), 100, now(), 100, r.col, 'U', r.nm, r.nm);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM AD_Column WHERE AD_Table_ID = v_table_id AND ColumnName = r.col) THEN
            INSERT INTO AD_Column (AD_Column_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, Updated, CreatedBy, UpdatedBy,
                Name, Description, Help, AD_Table_ID, AD_Element_ID, AD_Reference_ID, AD_Reference_Value_ID,
                ColumnName, FieldLength, DefaultValue, IsKey, IsParent, IsMandatory,
                IsUpdateable, IsIdentifier, SeqNo, IsTranslated, IsEncrypted, IsSelectionColumn, EntityType,
                IsSyncDatabase, IsAlwaysUpdateable, IsAutocomplete, IsAllowLogging, FormatPattern,
                IsAdvancedField, IsAllowCopy, IsToolbarButton, IsSecure)
            VALUES ((SELECT COALESCE(MAX(AD_Column_ID), 999999) + 1 FROM AD_Column), 0, 0, 'Y', now(), now(), 100, 100,
                r.nm, NULL, NULL, v_table_id, v_elem_id, r.ref, NULL,
                r.col, r.len, r.def, 'N', 'N', 'N',
                'Y', 'N', 0, 'N', 'N', 'N', 'U',
                'N', 'N', 'N', 'Y', NULL,
                'N', 'Y', 'N', 'N');
        END IF;
    END LOOP;
END $$;

-- Optional: ID sequence thik rakhte (iDempiere er DB utility)
-- SELECT update_sequences();
