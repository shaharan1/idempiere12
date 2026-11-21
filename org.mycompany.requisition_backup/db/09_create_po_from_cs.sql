SET search_path TO adempiere, public;
-- =====================================================================
-- Create PO From CS (approved Comparative Statement -> Drafted PO -> approval matrix)
-- PostgreSQL, idempotent. Ekbar chalao, tarpor server restart + cache reset +
-- logout/login (menu tree cached).
--
-- 1. Tracing columns (plain SQL columns, same way as 01_/05_ scripts; not in the AD):
--      C_Order.XX_RfQ_ID          which CS the PO was created from
--      C_OrderLine.XX_RfQLine_ID  which CS line; "Already PO Qty" is the sum of these
-- 2. AD_Form + access + menu "Create PO From CS", next to "RFQ Approval".
-- =====================================================================

ALTER TABLE C_Order     ADD COLUMN IF NOT EXISTS XX_RfQ_ID     NUMERIC(10);
ALTER TABLE C_OrderLine ADD COLUMN IF NOT EXISTS XX_RfQLine_ID NUMERIC(10);
CREATE INDEX IF NOT EXISTS idx_c_order_xx_rfq         ON C_Order(XX_RfQ_ID)         WHERE XX_RfQ_ID IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_c_orderline_xx_rfqline ON C_OrderLine(XX_RfQLine_ID) WHERE XX_RfQLine_ID IS NOT NULL;

DO $$
DECLARE
    c_class  CONSTANT text := 'org.mycompany.requisition.rfqpo.WCreatePOFromCS'; -- 47 chars (max 60)
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
                'Create PO From CS', 'Create a PO from the winner of an approved Comparative Statement',
                c_class, '3', 'U', 'N');
    END IF;

    -- same roles that can open the RFQ Approval form
    INSERT INTO AD_Form_Access (AD_Form_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                Updated, UpdatedBy, IsReadWrite)
    SELECT v_form, fa.AD_Role_ID, fa.AD_Client_ID, fa.AD_Org_ID, 'Y', now(), 100, now(), 100, 'Y'
    FROM AD_Form_Access fa JOIN AD_Form f ON f.AD_Form_ID = fa.AD_Form_ID
    WHERE f.Classname LIKE '%rfqapproval.WRFQApprovalForm%'
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
        VALUES (v_menu, 0, 0, 'Y', now(), 100, now(), 100, 'Create PO From CS', 'Create PO From CS', 'N', 'N', 'N', 'X', v_form, 'U');
    END IF;

    SELECT m.AD_Menu_ID INTO v_ref FROM AD_Menu m JOIN AD_Form f ON f.AD_Form_ID = m.AD_Form_ID
    WHERE f.Classname LIKE '%rfqapproval.WRFQApprovalForm%' LIMIT 1;
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
