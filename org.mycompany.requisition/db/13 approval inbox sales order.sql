SET search_path TO adempiere, public;
-- =====================================================================
-- 13_approval_inbox_sales_order.sql
-- Approval inbox: let Sales Orders complete at the last approval level.
--
-- ut_process_approval looked for the final document with
--   c_order ... AND issotrx = 'N'
-- so a Sales Order (issotrx = 'Y') was approved but then failed with
-- "Document ... not found for document type ...". The inbox form already completes
-- any C_Order returned as 'FINAL:<id>' (MOrder.processIt), so the only change is
-- dropping that IsSOTrx filter. Everything else is identical to ut_process_approval.sql.
-- Idempotent (CREATE OR REPLACE). No restart needed for this part.
-- =====================================================================
CREATE OR REPLACE FUNCTION adempiere.ut_process_approval(p_history_uu character varying, p_user_id integer, p_action character varying, p_client_id integer)
 RETURNS text
 LANGUAGE plpgsql
AS $function$
DECLARE
    h            adempiere.ut_approvalhistory%ROWTYPE;
    v_supervisor integer;
    v_order_id   integer;
    v_req_id     integer;
    v_rfq_id     integer;
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

    -- Last level: Purchase Order or Sales Order
    SELECT c_order_id
      INTO v_order_id
      FROM adempiere.c_order
     WHERE documentno         = h.documentno
       AND c_doctypetarget_id = h.c_doctype_id
       AND ad_client_id       = h.ad_client_id
     LIMIT 1;
    IF v_order_id IS NOT NULL THEN
        RETURN 'FINAL:' || v_order_id;
    END IF;

    -- Purchase Requisition
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

    -- Comparative Statement (RFQ): the history row is already 'A'
    SELECT r.c_rfq_id
      INTO v_rfq_id
      FROM adempiere.c_rfq r
      JOIN adempiere.m_requisition rq ON rq.m_requisition_id = r.m_requisition_id
     WHERE COALESCE(r.documentno, r.name) = h.documentno
       AND rq.c_doctype_id = h.c_doctype_id
       AND r.ad_client_id  = h.ad_client_id
     LIMIT 1;
    IF v_rfq_id IS NOT NULL THEN
        RETURN 'FINALRFQ:' || v_rfq_id;
    END IF;

    RAISE EXCEPTION 'Document % not found for document type %',
        h.documentno, h.c_doctype_id;
END;
$function$;