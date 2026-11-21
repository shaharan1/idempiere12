SET search_path TO adempiere, public;
-- =====================================================================
-- 19_approval_matrix_all_requisition_doctypes.sql
-- Copies the approval layers of Local Purchase Requisition (C_DocType_ID = 127:
-- Pomaker -> Pochecker -> Poapprove) to every other Requisition document type
-- (Foreign Purchase Requisition, Internal Requisition, Service Requisition, ...).
-- Doc types that already have matrix rows are skipped, so the script is
-- safe to run again whenever a new Requisition doc type is created.
-- PostgreSQL.
-- =====================================================================

-- STEP 1: PREVIEW (run this first, nothing is changed)
-- Shows which doc types will get the layers and which already have them.
SELECT dt.c_doctype_id, dt.name, dt.docbasetype,
       (SELECT count(*) FROM ut_approvalmatrix m WHERE m.c_doctype_id = dt.c_doctype_id) AS existing_rows,
       CASE WHEN dt.c_doctype_id = 127 THEN 'SOURCE'
            WHEN EXISTS (SELECT 1 FROM ut_approvalmatrix m WHERE m.c_doctype_id = dt.c_doctype_id) THEN 'skip (already has rows)'
            ELSE 'WILL GET 3 LAYERS' END AS action
FROM c_doctype dt
WHERE dt.docbasetype = 'POR' AND dt.isactive = 'Y'
ORDER BY dt.name;

-- STEP 2: COPY THE LAYERS
DO $$
DECLARE
    c_src CONSTANT numeric := 127;     -- Local Purchase Requisition = the template
    v_max numeric;
    v_n   integer;
BEGIN
    SELECT COALESCE(MAX(ut_approvalmatrix_id), 999999) INTO v_max FROM ut_approvalmatrix;

    INSERT INTO ut_approvalmatrix
           (ut_approvalmatrix_id, ut_approvalmatrix_uu, ad_client_id, ad_org_id, c_doctype_id,
            created, createdby, updated, updatedby, isactive,
            supervisor_id, userid, ad_user_id, isrecommendwinner)
    SELECT v_max + row_number() OVER (ORDER BY t.c_doctype_id, s.ut_approvalmatrix_id),
           gen_random_uuid()::text, s.ad_client_id, s.ad_org_id, t.c_doctype_id,
           now(), 100, now(), 100, 'Y',
           s.supervisor_id, s.userid, s.ad_user_id, s.isrecommendwinner
      FROM (SELECT dt.c_doctype_id
              FROM c_doctype dt
             WHERE dt.docbasetype = 'POR' AND dt.isactive = 'Y'
               AND dt.c_doctype_id <> c_src
               AND dt.ad_client_id = (SELECT ad_client_id FROM c_doctype WHERE c_doctype_id = c_src)
               AND NOT EXISTS (SELECT 1 FROM ut_approvalmatrix m WHERE m.c_doctype_id = dt.c_doctype_id)) t
     CROSS JOIN (SELECT * FROM ut_approvalmatrix WHERE c_doctype_id = c_src AND isactive = 'Y') s;
    GET DIAGNOSTICS v_n = ROW_COUNT;

    -- keep the ID sequence ahead of the rows just inserted (so the UI does not collide later)
    IF v_n > 0 THEN
        UPDATE ad_sequence SET currentnext = GREATEST(currentnext, v_max + v_n + 1)
         WHERE name = 'UT_ApprovalMatrix';
    END IF;

    RAISE NOTICE '% approval matrix rows inserted.', v_n;
END $$;

-- STEP 3: CHECK (every Requisition doc type should show 3 layers)
SELECT dt.name AS doctype, u.name AS user_name, s.name AS supervisor
FROM ut_approvalmatrix m
JOIN c_doctype dt ON dt.c_doctype_id = m.c_doctype_id
LEFT JOIN ad_user u ON u.ad_user_id = m.ad_user_id
LEFT JOIN ad_user s ON s.ad_user_id = m.supervisor_id
WHERE dt.docbasetype = 'POR' AND m.isactive = 'Y'
ORDER BY dt.name, u.name;