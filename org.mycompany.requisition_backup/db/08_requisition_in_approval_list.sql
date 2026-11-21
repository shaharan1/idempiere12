SET search_path TO adempiere, public;
-- =====================================================================
-- Purchase Requisition ke "Purchase Order Approval" form er list e ana
--
-- Karon: form shudhu ut_approvalhistory (status P, checked = user) dekhe.
-- c_order e trigger ache, m_requisition e chilo na, tai PR er row-i hoy nai.
--
-- 1. ut_process_approval : PO na pele requisition khuje, 'FINALREQ:<id>' ferot dey
-- 2. trigger (m_requisition) : PR toiri (INSERT) hole pending history row toiri kore,
--    hubohu c_order er trg_ufn_approvalhistory er moto
--
-- Idempotent. Chalaner por Eclipse e notun WPOApprovalForm build kore server restart.
-- =====================================================================

-- ---------- 1. ut_process_approval (agerta hubohu, shudhu shesh ongsho notun) ----------
CREATE OR REPLACE FUNCTION adempiere.ut_process_approval(p_history_uu character varying, p_user_id integer, p_action character varying, p_client_id integer)
 RETURNS text
 LANGUAGE plpgsql
AS $function$
DECLARE
    h            adempiere.ut_approvalhistory%ROWTYPE;
    v_supervisor integer;
    v_order_id   integer;
    v_req_id     integer;
BEGIN
    IF p_action NOT IN ('A', 'R') THEN
        RAISE EXCEPTION 'Invalid action: %', p_action;
    END IF;
    SELECT *
      INTO h
      FROM adempiere.ut_approvalhistory
     WHERE ut_approvalhistory_uu::text = p_history_uu
       AND ad_client_id = p_client_id
       AND checked = p_user_id::text
       AND status = 'P'
       AND isactive = 'Y'
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF p_action = 'R' THEN
        UPDATE adempiere.ut_approvalhistory
           SET status    = 'R',
               updated   = NOW(),
               updatedby = p_user_id
         WHERE ut_approvalhistory_uu = h.ut_approvalhistory_uu;
        RETURN 'REJECTED';
    END IF;
    UPDATE adempiere.ut_approvalhistory
       SET status    = 'A',
           updated   = NOW(),
           updatedby = p_user_id
     WHERE ut_approvalhistory_uu = h.ut_approvalhistory_uu;
    SELECT supervisor_id
      INTO v_supervisor
      FROM adempiere.ut_approvalmatrix
     WHERE c_doctype_id = h.c_doctype_id
       AND ad_user_id   = p_user_id
     ORDER BY supervisor_id
     LIMIT 1;
    IF v_supervisor IS NOT NULL
       AND v_supervisor > 0
       AND v_supervisor <> p_user_id THEN
        INSERT INTO adempiere.ut_approvalhistory (
            ut_approvalhistory_uu, ad_client_id, ad_org_id, c_doctype_id,
            documentno, created, createdby, updated, updatedby,
            status, isactive, checked
        ) VALUES (
            gen_random_uuid()::text, h.ad_client_id, h.ad_org_id, h.c_doctype_id,
            h.documentno, NOW(), h.createdby, NOW(), p_user_id,
            'P', 'Y', v_supervisor::text
        );
        RETURN 'NEXT:' || v_supervisor;
    END IF;

    -- Shesh level: Purchase Order hole agerta moto.
    SELECT c_order_id
      INTO v_order_id
      FROM adempiere.c_order
     WHERE documentno         = h.documentno
       AND c_doctypetarget_id = h.c_doctype_id
       AND ad_client_id       = h.ad_client_id
       AND issotrx            = 'N'
     LIMIT 1;
    IF v_order_id IS NOT NULL THEN
        RETURN 'FINAL:' || v_order_id;
    END IF;

    -- PO na hole Purchase Requisition.
    SELECT m_requisition_id
      INTO v_req_id
      FROM adempiere.m_requisition
     WHERE documentno   = h.documentno
       AND c_doctype_id = h.c_doctype_id
       AND ad_client_id = h.ad_client_id
     LIMIT 1;
    IF v_req_id IS NOT NULL THEN
        RETURN 'FINALREQ:' || v_req_id;
    END IF;

    RAISE EXCEPTION 'Document % not found for document type %',
        h.documentno, h.c_doctype_id;
END;
$function$;

-- ---------- 2. PR er jonno pending history row ----------
CREATE OR REPLACE FUNCTION adempiere.ut_req_approval_history() RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_checker integer;
BEGIN
    -- ekoi document er pending row thakle notun toiri kori na
    IF EXISTS (SELECT 1 FROM adempiere.ut_approvalhistory
                WHERE ad_client_id = NEW.ad_client_id AND documentno = NEW.documentno
                  AND c_doctype_id = NEW.c_doctype_id AND status = 'P' AND isactive = 'Y') THEN
        RETURN NEW;
    END IF;

    -- prothom approver: je requisition toiri korechhe tar supervisor (matrix theke)
    SELECT supervisor_id INTO v_checker
      FROM adempiere.ut_approvalmatrix
     WHERE c_doctype_id = NEW.c_doctype_id AND ad_user_id = NEW.createdby
       AND isactive = 'Y' AND supervisor_id > 0
     ORDER BY supervisor_id LIMIT 1;
    IF v_checker IS NULL THEN
        SELECT supervisor_id INTO v_checker
          FROM adempiere.ut_approvalmatrix
         WHERE c_doctype_id = NEW.c_doctype_id AND isactive = 'Y' AND supervisor_id > 0
         ORDER BY ut_approvalmatrix_id LIMIT 1;
    END IF;
    IF v_checker IS NULL THEN
        RAISE NOTICE 'Requisition %: matrix e approver nei, history row toiri hoy ni', NEW.documentno;
        RETURN NEW;
    END IF;

    INSERT INTO adempiere.ut_approvalhistory (
        ut_approvalhistory_uu, ad_client_id, ad_org_id, c_doctype_id,
        documentno, created, createdby, updated, updatedby,
        status, isactive, checked
    ) VALUES (
        gen_random_uuid()::text, NEW.ad_client_id, NEW.ad_org_id, NEW.c_doctype_id,
        NEW.documentno, NOW(), NEW.createdby, NOW(), NEW.createdby,
        'P', 'Y', v_checker::text
    );
    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_ut_req_approvalhistory ON adempiere.m_requisition;
CREATE TRIGGER trg_ut_req_approvalhistory
    AFTER INSERT ON adempiere.m_requisition
    FOR EACH ROW EXECUTE FUNCTION adempiere.ut_req_approval_history();

-- ---------- 3. (Optional) ager toiri hoye thaka PR gulo list e ana ----------
-- Trigger shudhu notun PR er jonno kaj kore. Je Draft/In Progress PR gulo age
-- thekei ache ar history row nei, segulor jonno niche ta uncomment kore chalan.
--
-- INSERT INTO adempiere.ut_approvalhistory (ut_approvalhistory_uu, ad_client_id, ad_org_id, c_doctype_id,
--        documentno, created, createdby, updated, updatedby, status, isactive, checked)
-- SELECT gen_random_uuid()::text, r.ad_client_id, r.ad_org_id, r.c_doctype_id, r.documentno,
--        NOW(), r.createdby, NOW(), r.createdby, 'P', 'Y',
--        COALESCE((SELECT m.supervisor_id FROM adempiere.ut_approvalmatrix m
--                   WHERE m.c_doctype_id = r.c_doctype_id AND m.ad_user_id = r.createdby
--                     AND m.isactive = 'Y' AND m.supervisor_id > 0
--                   ORDER BY m.supervisor_id LIMIT 1),
--                 (SELECT m.supervisor_id FROM adempiere.ut_approvalmatrix m
--                   WHERE m.c_doctype_id = r.c_doctype_id AND m.isactive = 'Y' AND m.supervisor_id > 0
--                   ORDER BY m.ut_approvalmatrix_id LIMIT 1))::text
--   FROM adempiere.m_requisition r
--  WHERE r.docstatus IN ('DR','IP')
--    AND NOT EXISTS (SELECT 1 FROM adempiere.ut_approvalhistory h
--                     WHERE h.ad_client_id = r.ad_client_id AND h.documentno = r.documentno
--                       AND h.c_doctype_id = r.c_doctype_id)
--    AND COALESCE((SELECT m.supervisor_id FROM adempiere.ut_approvalmatrix m
--                   WHERE m.c_doctype_id = r.c_doctype_id AND m.isactive = 'Y' AND m.supervisor_id > 0
--                   LIMIT 1), 0) > 0;
