SET search_path TO adempiere, public;
-- =====================================================================
-- RFQ Approval form (Recommend Winner + Comparative Statement PDF)
-- PostgreSQL, idempotent. Ekbar chalao, tarpor server restart + cache reset +
-- logout/login (menu tree cached).
--
-- Winner  : standard C_RfQResponse.IsSelectedWinner (kono notun column lage na)
-- History : XX_Approval_History, DocumentTable = 'C_RFQ'
-- Access / menu position: "Document Approval Form" er moto hubohu copy kora hoy.
-- =====================================================================

-- 1. History table: RFQ er C_DocType nei, transaction nei -> optional.
--    Only touched when the table exists in this database (older installs use
--    ut_approvalhistory instead, so skip silently there).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema() AND table_name = 'xx_approval_history') THEN
        ALTER TABLE XX_Approval_History ALTER COLUMN C_DocType_ID DROP NOT NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'xx_approval_history'
                 AND column_name = 'xx_process_transaction_id') THEN
        ALTER TABLE XX_Approval_History ALTER COLUMN XX_Process_Transaction_ID DROP NOT NULL;
    END IF;
END $$;

-- 2. AD_Form + access + menu
DO $$
DECLARE
    c_class  CONSTANT text := 'org.mycompany.requisition.rfqapproval.WRFQApprovalForm'; -- 54 chars (max 60)
    v_form   NUMERIC;
    v_menu   NUMERIC;
    v_ref    NUMERIC;   -- existing Document Approval menu, to copy position from
    v_parent NUMERIC;
    v_seq    NUMERIC;
BEGIN
    SELECT AD_Form_ID INTO v_form FROM AD_Form WHERE Classname = c_class;
    IF v_form IS NULL THEN
        v_form := (SELECT COALESCE(MAX(AD_Form_ID) FILTER (WHERE AD_Form_ID >= 1000000), 999999) + 1 FROM AD_Form);
        INSERT INTO AD_Form (AD_Form_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                             Name, Description, Classname, AccessLevel, EntityType, IsBetaFunctionality)
        VALUES (v_form, 0, 0, 'Y', now(), 100, now(), 100,
                'RFQ Approval', 'Recommend Winner, Comparative Statement, Approve/Reject/Forward',
                c_class, '3', 'U', 'N');
    END IF;

    -- roles: same roles that can open the Document Approval Form or the
    -- RFQ Response Entry form. Both are the users who work with RFQs, so the
    -- RFQ Approval form opens for exactly them.
    INSERT INTO AD_Form_Access (AD_Form_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                Updated, UpdatedBy, IsReadWrite)
    SELECT v_form, fa.AD_Role_ID, fa.AD_Client_ID, fa.AD_Org_ID, 'Y', now(), 100, now(), 100, 'Y'
    FROM AD_Form_Access fa JOIN AD_Form f ON f.AD_Form_ID = fa.AD_Form_ID
    WHERE (f.Classname LIKE '%docapproval.DocumentApprovalForm%'
        OR f.Classname LIKE '%rfqresponse.WRFQResponseEntry%')
      AND NOT EXISTS (SELECT 1 FROM AD_Form_Access x WHERE x.AD_Form_ID = v_form AND x.AD_Role_ID = fa.AD_Role_ID);

    -- Safety net: if neither source form has an access row yet, give the
    -- administrator role access so the menu is not invisible.
    IF NOT EXISTS (SELECT 1 FROM AD_Form_Access WHERE AD_Form_ID = v_form) THEN
        INSERT INTO AD_Form_Access (AD_Form_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                    Updated, UpdatedBy, IsReadWrite)
        VALUES (v_form, 0, 0, 0, 'Y', now(), 100, now(), 100, 'Y');
    END IF;

    -- menu (Action 'X' = Form)
    SELECT AD_Menu_ID INTO v_menu FROM AD_Menu WHERE AD_Form_ID = v_form;
    IF v_menu IS NULL THEN
        v_menu := (SELECT COALESCE(MAX(AD_Menu_ID) FILTER (WHERE AD_Menu_ID >= 1000000), 999999) + 1 FROM AD_Menu);
        INSERT INTO AD_Menu (AD_Menu_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                             Name, Description, IsSummary, IsSOTrx, IsReadOnly, Action, AD_Form_ID, EntityType)
        VALUES (v_menu, 0, 0, 'Y', now(), 100, now(), 100, 'RFQ Approval', 'RFQ Approval', 'N', 'N', 'N', 'X', v_form, 'U');
    END IF;

    SELECT m.AD_Menu_ID INTO v_ref FROM AD_Menu m JOIN AD_Form f ON f.AD_Form_ID = m.AD_Form_ID
    WHERE f.Classname LIKE '%docapproval.DocumentApprovalForm%' LIMIT 1;
    IF v_ref IS NULL THEN
        SELECT m.AD_Menu_ID INTO v_ref FROM AD_Menu m JOIN AD_Form f ON f.AD_Form_ID = m.AD_Form_ID
        WHERE f.Classname LIKE '%rfqresponse.WRFQResponseEntry%' LIMIT 1;
    END IF;
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
