SET search_path TO adempiere, public;
-- =====================================================================
-- 17_requisition_master_data.sql
-- Requisition Form er master data SQL diye bosano: Department (C_Activity),
-- Segment ar Location (Reference List). PostgreSQL, idempotent (bar bar run korleo duplicate hoy na).
-- Run korar por: Cache Reset, tarpor form ta abar khulun.
--
-- 1. v_client ke apnar Client ID dik (Client info / AD_Client table theke dekhun).
-- 2. Nicher list e shob Department likhun (Value, Name).
-- =====================================================================

DO $$
DECLARE
    v_client NUMERIC := 11;      -- <== apnar AD_Client_ID (GardenWorld = 11). Bodle nin.
    r        RECORD;
    v_id     NUMERIC;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM AD_Client WHERE AD_Client_ID = v_client) THEN
        RAISE EXCEPTION 'AD_Client_ID % not found: set v_client at the top of this script', v_client;
    END IF;

    FOR r IN
        SELECT * FROM (VALUES
            ('HR',    'Human Resources'),
            ('ACC',   'Accounts'),
            ('PUR',   'Purchase'),
            ('PROD',  'Production'),
            ('STORE', 'Store'),
            ('SALES', 'Sales & Marketing'),
            ('IT',    'IT'),
            ('ADMIN', 'Administration'),
            ('MAINT', 'Maintenance'),
            ('QC',    'Quality Control')
        ) AS t(val, nm)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM C_Activity WHERE AD_Client_ID = v_client AND Value = r.val) THEN
            v_id := (SELECT COALESCE(MAX(C_Activity_ID), 999999) + 1 FROM C_Activity WHERE C_Activity_ID >= 1000000);
            INSERT INTO C_Activity (C_Activity_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                    Updated, UpdatedBy, Value, Name, IsSummary, C_Activity_UU)
            VALUES (v_id, v_client, 0, 'Y', now(), 100, now(), 100, r.val, r.nm, 'N', gen_random_uuid()::text);
        END IF;
    END LOOP;

    -- keep the ID sequence ahead of the rows we inserted (only if the table uses AD_Sequence)
    UPDATE AD_Sequence
       SET CurrentNext = GREATEST(CurrentNext, (SELECT MAX(C_Activity_ID) + 1 FROM C_Activity))
     WHERE Name = 'C_Activity' AND AD_Client_ID = 0;
END $$;

-- ---------------------------------------------------------------------
-- Notun Segment / Location (dropdown) jog korte: ekhane line jog korun
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_loc NUMERIC := (SELECT AD_Reference_ID FROM AD_Reference WHERE Name = 'XX Requisition Location');
    v_seg NUMERIC := (SELECT AD_Reference_ID FROM AD_Reference WHERE Name = 'XX Requisition Segment');
    r     RECORD;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            (v_loc, 'CTG',  'Chittagong Office'),     -- example Location
            (v_seg, 'PKG',  'Packaging')              -- example Segment
        ) AS t(ref, val, nm)
    LOOP
        IF r.ref IS NOT NULL AND NOT EXISTS (SELECT 1 FROM AD_Ref_List WHERE AD_Reference_ID = r.ref AND Value = r.val) THEN
            INSERT INTO AD_Ref_List (AD_Ref_List_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                     Updated, UpdatedBy, Value, Name, AD_Reference_ID, EntityType, AD_Ref_List_UU)
            VALUES ((SELECT COALESCE(MAX(AD_Ref_List_ID) FILTER (WHERE AD_Ref_List_ID >= 1000000), 999999) + 1
                       FROM AD_Ref_List),
                    0, 0, 'Y', now(), 100, now(), 100, r.val, r.nm, r.ref, 'U', gen_random_uuid()::text);
        END IF;
    END LOOP;
END $$;