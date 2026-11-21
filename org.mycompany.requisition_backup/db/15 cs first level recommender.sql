SET search_path TO adempiere, public;
-- =====================================================================
-- 15_cs_first_level_recommender.sql
-- Comparative Statement flow: Pochecker (recommender) -> Poapprove (final approver) -> PO.
--
--  1. XX_CS_RECOMMENDER_ID (AD_SysConfig): AD_User_ID a submitted CS goes to FIRST. This script
--     sets it to the user named 'Pochecker'. Change it any time in the System Configurator
--     window; remove the value (or the record) to go back to the matrix-only routing.
--  2. ut_process_approval: when the recommender approves a CS, it moves on to his supervisor
--     in UT_ApprovalMatrix (the one he has for his other documents, e.g. Poapprove). Only RFQ/CS
--     rows and only for a user with no matrix row for the CS document type are affected.
--  3. The final approver approves -> CS fully approved -> PO can be created from it.
--     A rejection goes back one level (see 14_cs_reject_returns_to_previous_level.sql).
-- Run after 13 and 14. Idempotent.
-- =====================================================================
DO $$
DECLARE
    v_user NUMERIC;
BEGIN
    IF EXISTS (SELECT 1 FROM AD_SysConfig WHERE Name = 'XX_CS_RECOMMENDER_ID') THEN
        RAISE NOTICE 'XX_CS_RECOMMENDER_ID already exists - left as it is';
    ELSE
        SELECT AD_User_ID INTO v_user FROM AD_User
         WHERE LOWER(Name) = 'pochecker' AND IsActive = 'Y' ORDER BY AD_User_ID LIMIT 1;
        IF v_user IS NULL THEN
            RAISE NOTICE 'No active user named Pochecker: create XX_CS_RECOMMENDER_ID by hand';
        ELSE
            INSERT INTO AD_SysConfig (AD_SysConfig_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                      Updated, UpdatedBy, Name, Value, Description, EntityType,
                                      ConfigurationLevel, AD_SysConfig_UU)
            VALUES ((SELECT COALESCE(MAX(AD_SysConfig_ID) FILTER (WHERE AD_SysConfig_ID >= 1000000), 999999) + 1
                       FROM AD_SysConfig),
                    0, 0, 'Y', now(), 100, now(), 100, 'XX_CS_RECOMMENDER_ID', v_user::text,
                    'AD_User_ID a submitted Comparative Statement goes to first (recommends the winner)',
                    'U', 'S', gen_random_uuid()::text);
        END IF;
    END IF;
END $$;

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

    -- Comparative Statement (RFQ) only: a user who has no matrix row for this document type
    -- (the CS recommender, e.g. Pochecker) passes it to the supervisor he has in the matrix
    -- for his other documents (the one most often used). Orders / requisitions are untouched.
    IF (v_supervisor IS NULL OR v_supervisor <= 0 OR v_supervisor = p_user_id)
       AND NOT EXISTS (SELECT 1 FROM adempiere.ut_approvalmatrix m0
                        WHERE m0.c_doctype_id = h.c_doctype_id AND m0.ad_user_id = p_user_id)
       AND NOT EXISTS (SELECT 1 FROM adempiere.c_order o
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
        SELECT m.supervisor_id
          INTO v_supervisor
          FROM adempiere.ut_approvalmatrix m
         WHERE m.ad_user_id = p_user_id AND m.supervisor_id > 0 AND m.supervisor_id <> p_user_id
         GROUP BY m.supervisor_id
         ORDER BY COUNT(*) DESC, m.supervisor_id
         LIMIT 1;
    END IF;

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