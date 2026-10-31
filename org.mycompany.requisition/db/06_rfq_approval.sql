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

-- 1. History table: RFQ er C_DocType nei, transaction nei -> optional
ALTER TABLE XX_Approval_History ALTER COLUMN C_DocType_ID DROP NOT NULL;
ALTER TABLE XX_Approval_History ALTER COLUMN XX_Process_Transaction_ID DROP NOT NULL;

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
                c_class, '7', 'U', 'N');
    END IF;

    -- roles: same roles that can open the Document Approval Form
    INSERT INTO AD_Form_Access (AD_Form_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                Updated, UpdatedBy, IsReadWrite)
    SELECT v_form, fa.AD_Role_ID, fa.AD_Client_ID, fa.AD_Org_ID, 'Y', now(), 100, now(), 100, 'Y'
    FROM AD_Form_Access fa JOIN AD_Form f ON f.AD_Form_ID = fa.AD_Form_ID
    WHERE f.Classname LIKE '%docapproval.DocumentApprovalForm%'
      AND NOT EXISTS (SELECT 1 FROM AD_Form_Access x WHERE x.AD_Form_ID = v_form AND x.AD_Role_ID = fa.AD_Role_ID);

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
    SELECT Parent_ID, COALESCE(SeqNo, 0) + 1 INTO v_parent, v_seq FROM AD_TreeNodeMM WHERE AD_Tree_ID = 10 AND Node_ID = v_ref;
    IF v_parent IS NULL THEN v_parent := 1000019; v_seq := 14; END IF;

    IF NOT EXISTS (SELECT 1 FROM AD_TreeNodeMM WHERE AD_Tree_ID = 10 AND Node_ID = v_menu) THEN
        INSERT INTO AD_TreeNodeMM (AD_Tree_ID, Node_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                   Updated, UpdatedBy, Parent_ID, SeqNo)
        VALUES (10, v_menu, 0, 0, 'Y', now(), 100, now(), 100, v_parent, v_seq);
    END IF;
END $$;
