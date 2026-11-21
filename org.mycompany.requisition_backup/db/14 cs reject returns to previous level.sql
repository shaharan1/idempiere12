SET search_path TO adempiere, public;
-- =====================================================================
-- 14_cs_reject_returns_to_previous_level.sql
-- A Comparative Statement (RFQ) rejected in the approval inbox goes back one level.
--
-- Chain example: submitter -> Pochecker (recommends the winner) -> final approver.
-- Final approver rejects -> a new pending row appears for Pochecker (the user who approved it
-- last before the rejecter). Pochecker can change the winner and approve again, which sends
-- it to the final approver once more. If nobody approved before (first level rejects) the
-- result stays REJECTED, as it was.
-- Only RFQ/CS rows are affected; orders and requisitions behave exactly as before.
-- Built on 13_approval_inbox_sales_order.sql (run that one first). Idempotent.
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
    v_prev       integer;
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

        -- Comparative Statement (RFQ) only: a rejection goes back one level, to the user who
        -- approved it last before this one. Orders and requisitions keep plain REJECTED.
        IF NOT EXISTS (SELECT 1 FROM adempiere.c_order o
                        WHERE o.documentno = h.documentno AND o.c_doctypetarget_id = h.c_doctype_id
                          AND o.ad_client_id = h.ad_client_id)
           AND NOT EXISTS (SELECT 1 FROM adempiere.m_requisition q
                            WHERE q.documentno = h.documentno AND q.c_doctype_id = h.c_doctype_id
                              AND q.ad_client_id = h.ad_client_id)
           AND EXISTS (SELECT 1 FROM adempiere.c_rfq r
                         JOIN adempiere.m_requisition rq ON rq.m_requisition_id = r.m_requisition_id
                        WHERE COALESCE(r.documentno, r.name) = h.documentno
                          AND rq.c_doctype_id = h.c_doctype_id AND r.ad_client_id = h.ad_client_id)
        THEN
            SELECT x.checked::integer
              INTO v_prev
              FROM adempiere.ut_approvalhistory x
             WHERE x.ad_client_id = h.ad_client_id
               AND x.documentno   = h.documentno
               AND x.c_doctype_id = h.c_doctype_id
               AND x.status       = 'A'
               AND x.checked ~ '^[0-9]+$'
               AND x.checked     <> p_user_id::text
               AND x.ut_approvalhistory_uu <> h.ut_approvalhistory_uu
             ORDER BY x.updated DESC, x.created DESC
             LIMIT 1;
            IF v_prev IS NOT NULL AND v_prev > 0 THEN
                INSERT INTO adempiere.ut_approvalhistory (
                    ut_approvalhistory_uu, ad_client_id, ad_org_id, c_doctype_id,
                    documentno, created, createdby, updated, updatedby,
                    status, isactive, checked
                ) VALUES (
                    gen_random_uuid()::text, h.ad_client_id, h.ad_org_id, h.c_doctype_id,
                    h.documentno, NOW(), h.createdby, NOW(), p_user_id,
                    'P', 'Y', v_prev::text
                );
                RETURN 'RETURNED:' || v_prev;
            END IF;
        END IF;
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