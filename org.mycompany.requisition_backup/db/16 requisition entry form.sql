SET search_path TO adempiere, public;
-- =====================================================================
-- 16_requisition_entry_form.sql
-- Requisition Form (org.mycompany.requisition.WRequisitionEntryForm)
-- PostgreSQL, idempotent. Run once, then: rebuild the plugin, restart the server,
-- Cache Reset and log out / in (the menu tree is cached).
--
-- 1. Plain columns (like 01_ / 05_ / 09_, not in the Application Dictionary):
--      M_Requisition.XX_Location          Location (value of the XX Requisition Location list)
--      M_RequisitionLine.XX_Segment       Segment  (value of the XX Requisition Segment list)
--      M_RequisitionLine.XX_Department_ID Department (a C_Activity)
--      M_RequisitionLine.XX_DateRequired  Date Required (Line)
-- 2. Two drop-down lists (AD_Reference + AD_Ref_List) with the values of the screen.
--    Edit them later in the Reference window; the form reads them at every open.
-- 3. AD_Form + access + menu "Requisition Form", next to "Create RFQ From PR".
--
-- Not created here: the form's Type is the document type (C_DocType with base type
-- Purchase Requisition), so make one document type per Type you want to offer, for
-- example "Internal Requisition" and "Local Purchase Requisition", and add each to the
-- approval matrix. Priority is the standard Priority list.
-- =====================================================================

ALTER TABLE M_Requisition     ADD COLUMN IF NOT EXISTS XX_Location       VARCHAR(20);
ALTER TABLE M_RequisitionLine ADD COLUMN IF NOT EXISTS XX_Segment        VARCHAR(20);
ALTER TABLE M_RequisitionLine ADD COLUMN IF NOT EXISTS XX_Department_ID  NUMERIC(10);
ALTER TABLE M_RequisitionLine ADD COLUMN IF NOT EXISTS XX_DateRequired   TIMESTAMP;

-- ---------------------------------------------------------------------
-- 2. lists
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_ref NUMERIC;
    r     RECORD;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('XX Requisition Location', 'Location of a requisition (Requisition Form)'),
            ('XX Requisition Segment',  'Segment of a requisition line (Requisition Form)')
        ) AS t(name, descr)
    LOOP
        SELECT AD_Reference_ID INTO v_ref FROM AD_Reference WHERE Name = r.name;
        IF v_ref IS NULL THEN
            v_ref := (SELECT COALESCE(MAX(AD_Reference_ID) FILTER (WHERE AD_Reference_ID >= 1000000), 999999) + 1
                        FROM AD_Reference);
            INSERT INTO AD_Reference (AD_Reference_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                      Updated, UpdatedBy, Name, Description, ValidationType, EntityType,
                                      IsOrderByValue, AD_Reference_UU)
            VALUES (v_ref, 0, 0, 'Y', now(), 100, now(), 100, r.name, r.descr, 'L', 'U', 'N', gen_random_uuid()::text);
        END IF;
    END LOOP;
END $$;

DO $$
DECLARE
    v_loc NUMERIC := (SELECT AD_Reference_ID FROM AD_Reference WHERE Name = 'XX Requisition Location');
    v_seg NUMERIC := (SELECT AD_Reference_ID FROM AD_Reference WHERE Name = 'XX Requisition Segment');
    r     RECORD;
BEGIN
    -- the list order of Location is the order of these rows (AD_Ref_List_ID)
    FOR r IN
        SELECT * FROM (VALUES
            (v_loc, 'FAC',  'Factory'),
            (v_loc, 'SAL',  'Sales'),
            (v_loc, 'EPZ',  'Factory_EPZ'),
            (v_loc, 'HO',   'Head Office'),
            (v_loc, 'GAZ',  'Gazipur Factory'),
            (v_loc, 'KAC',  'Kachpur Factory'),
            (v_seg, 'BM',   'Building Materials'),
            (v_seg, 'COM',  'Common'),
            (v_seg, 'DS',   'Door & Sheet'),
            (v_seg, 'DUM',  'Dum'),
            (v_seg, 'GS',   'General/Stationary'),
            (v_seg, 'HH',   'Household'),
            (v_seg, 'MET',  'Metal'),
            (v_seg, 'PIPE', 'Pipe'),
            (v_seg, 'PRIM', 'Primitek Segment'),
            (v_seg, 'SVC',  'Service')
        ) AS t(ref, val, nm)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM AD_Ref_List WHERE AD_Reference_ID = r.ref AND Value = r.val) THEN
            INSERT INTO AD_Ref_List (AD_Ref_List_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                     Updated, UpdatedBy, Value, Name, AD_Reference_ID, EntityType, AD_Ref_List_UU)
            VALUES ((SELECT COALESCE(MAX(AD_Ref_List_ID) FILTER (WHERE AD_Ref_List_ID >= 1000000), 999999) + 1
                       FROM AD_Ref_List),
                    0, 0, 'Y', now(), 100, now(), 100, r.val, r.nm, r.ref, 'U', gen_random_uuid()::text);
        END IF;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 3. form + access + menu
-- ---------------------------------------------------------------------
DO $$
DECLARE
    c_class  CONSTANT text := 'org.mycompany.requisition.WRequisitionEntryForm';   -- 47 chars (max 60)
    v_form   NUMERIC;
    v_menu   NUMERIC;
    v_ref    NUMERIC;
    v_parent NUMERIC;
    v_seq    NUMERIC;
BEGIN
    SELECT AD_Form_ID INTO v_form FROM AD_Form WHERE Classname = c_class;
    IF v_form IS NULL THEN
        v_form := (SELECT COALESCE(MAX(AD_Form_ID) FILTER (WHERE AD_Form_ID >= 1000000), 999999) + 1 FROM AD_Form);
        INSERT INTO AD_Form (AD_Form_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                             Name, Description, Classname, AccessLevel, EntityType, IsBetaFunctionality)
        VALUES (v_form, 0, 0, 'Y', now(), 100, now(), 100,
                'Requisition Form', 'Create a purchase requisition (header, lines, attachment)',
                c_class, '3', 'U', 'N');
    END IF;

    -- same roles that can open the Create RFQ From PR form
    INSERT INTO AD_Form_Access (AD_Form_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                Updated, UpdatedBy, IsReadWrite)
    SELECT v_form, fa.AD_Role_ID, fa.AD_Client_ID, fa.AD_Org_ID, 'Y', now(), 100, now(), 100, 'Y'
    FROM AD_Form_Access fa JOIN AD_Form f ON f.AD_Form_ID = fa.AD_Form_ID
    WHERE f.Classname LIKE '%rfq.WCreateRFQFromPR%'
      AND NOT EXISTS (SELECT 1 FROM AD_Form_Access x WHERE x.AD_Form_ID = v_form AND x.AD_Role_ID = fa.AD_Role_ID);

    IF NOT EXISTS (SELECT 1 FROM AD_Form_Access WHERE AD_Form_ID = v_form) THEN
        INSERT INTO AD_Form_Access (AD_Form_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                    Updated, UpdatedBy, IsReadWrite)
        VALUES (v_form, 0, 0, 0, 'Y', now(), 100, now(), 100, 'Y');
    END IF;

    SELECT AD_Menu_ID INTO v_menu FROM AD_Menu WHERE AD_Form_ID = v_form;
    IF v_menu IS NULL THEN
        v_menu := (SELECT COALESCE(MAX(AD_Menu_ID) FILTER (WHERE AD_Menu_ID >= 1000000), 999999) + 1 FROM AD_Menu);
        INSERT INTO AD_Menu (AD_Menu_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                             Name, Description, IsSummary, IsSOTrx, IsReadOnly, Action, AD_Form_ID, EntityType)
        VALUES (v_menu, 0, 0, 'Y', now(), 100, now(), 100, 'Requisition Form', 'Requisition Form', 'N', 'N', 'N', 'X', v_form, 'U');
    END IF;

    SELECT m.AD_Menu_ID INTO v_ref FROM AD_Menu m JOIN AD_Form f ON f.AD_Form_ID = m.AD_Form_ID
    WHERE f.Classname LIKE '%rfq.WCreateRFQFromPR%' LIMIT 1;
    IF v_ref IS NOT NULL THEN
        SELECT Parent_ID, COALESCE(SeqNo, 0) + 1 INTO v_parent, v_seq
        FROM AD_TreeNodeMM WHERE AD_Tree_ID = 10 AND Node_ID = v_ref;
    END IF;
    IF v_parent IS NULL THEN
        SELECT 0, COALESCE(MAX(SeqNo), 0) + 1 INTO v_parent, v_seq
        FROM AD_TreeNodeMM WHERE AD_Tree_ID = 10 AND Parent_ID = 0;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID = 10 AND Node_ID = v_menu) THEN
        INSERT INTO AD_TreeNodeMM (AD_Tree_ID, Node_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                   Updated, UpdatedBy, Parent_ID, SeqNo)
        VALUES (10, v_menu, 0, 0, 'Y', now(), 100, now(), 100, v_parent, v_seq);
    END IF;
END $$;SET search_path TO adempiere, public;
-- =====================================================================
-- 16_requisition_entry_form.sql
-- Requisition Form (org.mycompany.requisition.WRequisitionEntryForm)
-- PostgreSQL, idempotent. Run once, then: rebuild the plugin, restart the server,
-- Cache Reset and log out / in (the menu tree is cached).
--
-- 1. Plain columns (like 01_ / 05_ / 09_, not in the Application Dictionary):
--      M_Requisition.XX_Location          Location (value of the XX Requisition Location list)
--      M_RequisitionLine.XX_Segment       Segment  (value of the XX Requisition Segment list)
--      M_RequisitionLine.XX_Department_ID Department (a C_Activity)
--      M_RequisitionLine.XX_DateRequired  Date Required (Line)
-- 2. Two drop-down lists (AD_Reference + AD_Ref_List) with the values of the screen.
--    Edit them later in the Reference window; the form reads them at every open.
-- 3. AD_Form + access + menu "Requisition Form", next to "Create RFQ From PR".
--
-- Not created here: the form's Type is the document type (C_DocType with base type
-- Purchase Requisition), so make one document type per Type you want to offer, for
-- example "Internal Requisition" and "Local Purchase Requisition", and add each to the
-- approval matrix. Priority is the standard Priority list.
-- =====================================================================

ALTER TABLE M_Requisition     ADD COLUMN IF NOT EXISTS XX_Location       VARCHAR(20);
ALTER TABLE M_RequisitionLine ADD COLUMN IF NOT EXISTS XX_Segment        VARCHAR(20);
ALTER TABLE M_RequisitionLine ADD COLUMN IF NOT EXISTS XX_Department_ID  NUMERIC(10);
ALTER TABLE M_RequisitionLine ADD COLUMN IF NOT EXISTS XX_DateRequired   TIMESTAMP;

-- ---------------------------------------------------------------------
-- 2. lists
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_ref NUMERIC;
    r     RECORD;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('XX Requisition Location', 'Location of a requisition (Requisition Form)'),
            ('XX Requisition Segment',  'Segment of a requisition line (Requisition Form)')
        ) AS t(name, descr)
    LOOP
        SELECT AD_Reference_ID INTO v_ref FROM AD_Reference WHERE Name = r.name;
        IF v_ref IS NULL THEN
            v_ref := (SELECT COALESCE(MAX(AD_Reference_ID) FILTER (WHERE AD_Reference_ID >= 1000000), 999999) + 1
                        FROM AD_Reference);
            INSERT INTO AD_Reference (AD_Reference_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                      Updated, UpdatedBy, Name, Description, ValidationType, EntityType,
                                      IsOrderByValue, AD_Reference_UU)
            VALUES (v_ref, 0, 0, 'Y', now(), 100, now(), 100, r.name, r.descr, 'L', 'U', 'N', gen_random_uuid()::text);
        END IF;
    END LOOP;
END $$;

DO $$
DECLARE
    v_loc NUMERIC := (SELECT AD_Reference_ID FROM AD_Reference WHERE Name = 'XX Requisition Location');
    v_seg NUMERIC := (SELECT AD_Reference_ID FROM AD_Reference WHERE Name = 'XX Requisition Segment');
    r     RECORD;
BEGIN
    -- the list order of Location is the order of these rows (AD_Ref_List_ID)
    FOR r IN
        SELECT * FROM (VALUES
            (v_loc, 'FAC',  'Factory'),
            (v_loc, 'SAL',  'Sales'),
            (v_loc, 'EPZ',  'Factory_EPZ'),
            (v_loc, 'HO',   'Head Office'),
            (v_loc, 'GAZ',  'Gazipur Factory'),
            (v_loc, 'KAC',  'Kachpur Factory'),
            (v_seg, 'BM',   'Building Materials'),
            (v_seg, 'COM',  'Common'),
            (v_seg, 'DS',   'Door & Sheet'),
            (v_seg, 'DUM',  'Dum'),
            (v_seg, 'GS',   'General/Stationary'),
            (v_seg, 'HH',   'Household'),
            (v_seg, 'MET',  'Metal'),
            (v_seg, 'PIPE', 'Pipe'),
            (v_seg, 'PRIM', 'Primitek Segment'),
            (v_seg, 'SVC',  'Service')
        ) AS t(ref, val, nm)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM AD_Ref_List WHERE AD_Reference_ID = r.ref AND Value = r.val) THEN
            INSERT INTO AD_Ref_List (AD_Ref_List_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                     Updated, UpdatedBy, Value, Name, AD_Reference_ID, EntityType, AD_Ref_List_UU)
            VALUES ((SELECT COALESCE(MAX(AD_Ref_List_ID) FILTER (WHERE AD_Ref_List_ID >= 1000000), 999999) + 1
                       FROM AD_Ref_List),
                    0, 0, 'Y', now(), 100, now(), 100, r.val, r.nm, r.ref, 'U', gen_random_uuid()::text);
        END IF;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 3. form + access + menu
-- ---------------------------------------------------------------------
DO $$
DECLARE
    c_class  CONSTANT text := 'org.mycompany.requisition.WRequisitionEntryForm';   -- 47 chars (max 60)
    v_form   NUMERIC;
    v_menu   NUMERIC;
    v_ref    NUMERIC;
    v_parent NUMERIC;
    v_seq    NUMERIC;
BEGIN
    SELECT AD_Form_ID INTO v_form FROM AD_Form WHERE Classname = c_class;
    IF v_form IS NULL THEN
        v_form := (SELECT COALESCE(MAX(AD_Form_ID) FILTER (WHERE AD_Form_ID >= 1000000), 999999) + 1 FROM AD_Form);
        INSERT INTO AD_Form (AD_Form_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                             Name, Description, Classname, AccessLevel, EntityType, IsBetaFunctionality)
        VALUES (v_form, 0, 0, 'Y', now(), 100, now(), 100,
                'Requisition Form', 'Create a purchase requisition (header, lines, attachment)',
                c_class, '3', 'U', 'N');
    END IF;

    -- same roles that can open the Create RFQ From PR form
    INSERT INTO AD_Form_Access (AD_Form_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                Updated, UpdatedBy, IsReadWrite)
    SELECT v_form, fa.AD_Role_ID, fa.AD_Client_ID, fa.AD_Org_ID, 'Y', now(), 100, now(), 100, 'Y'
    FROM AD_Form_Access fa JOIN AD_Form f ON f.AD_Form_ID = fa.AD_Form_ID
    WHERE f.Classname LIKE '%rfq.WCreateRFQFromPR%'
      AND NOT EXISTS (SELECT 1 FROM AD_Form_Access x WHERE x.AD_Form_ID = v_form AND x.AD_Role_ID = fa.AD_Role_ID);

    IF NOT EXISTS (SELECT 1 FROM AD_Form_Access WHERE AD_Form_ID = v_form) THEN
        INSERT INTO AD_Form_Access (AD_Form_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                    Updated, UpdatedBy, IsReadWrite)
        VALUES (v_form, 0, 0, 0, 'Y', now(), 100, now(), 100, 'Y');
    END IF;

    SELECT AD_Menu_ID INTO v_menu FROM AD_Menu WHERE AD_Form_ID = v_form;
    IF v_menu IS NULL THEN
        v_menu := (SELECT COALESCE(MAX(AD_Menu_ID) FILTER (WHERE AD_Menu_ID >= 1000000), 999999) + 1 FROM AD_Menu);
        INSERT INTO AD_Menu (AD_Menu_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                             Name, Description, IsSummary, IsSOTrx, IsReadOnly, Action, AD_Form_ID, EntityType)
        VALUES (v_menu, 0, 0, 'Y', now(), 100, now(), 100, 'Requisition Form', 'Requisition Form', 'N', 'N', 'N', 'X', v_form, 'U');
    END IF;

    SELECT m.AD_Menu_ID INTO v_ref FROM AD_Menu m JOIN AD_Form f ON f.AD_Form_ID = m.AD_Form_ID
    WHERE f.Classname LIKE '%rfq.WCreateRFQFromPR%' LIMIT 1;
    IF v_ref IS NOT NULL THEN
        SELECT Parent_ID, COALESCE(SeqNo, 0) + 1 INTO v_parent, v_seq
        FROM AD_TreeNodeMM WHERE AD_Tree_ID = 10 AND Node_ID = v_ref;
    END IF;
    IF v_parent IS NULL THEN
        SELECT 0, COALESCE(MAX(SeqNo), 0) + 1 INTO v_parent, v_seq
        FROM AD_TreeNodeMM WHERE AD_Tree_ID = 10 AND Parent_ID = 0;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID = 10 AND Node_ID = v_menu) THEN
        INSERT INTO AD_TreeNodeMM (AD_Tree_ID, Node_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                   Updated, UpdatedBy, Parent_ID, SeqNo)
        VALUES (10, v_menu, 0, 0, 'Y', now(), 100, now(), 100, v_parent, v_seq);
    END IF;
END $$;