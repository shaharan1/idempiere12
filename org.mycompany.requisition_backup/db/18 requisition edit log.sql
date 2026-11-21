SET search_path TO adempiere, public;
-- =====================================================================
-- 18_requisition_edit_log.sql
-- Requisition edit history: when a requisition that no approver has approved yet is opened
-- again and a product line is added / removed or a Qty is changed, the form writes one row here
-- (in the same transaction as the save). The Approval History pop-up shows these rows
-- ("Edit" rows) between the approval layers, by time.
-- Plain table, no Application Dictionary registration needed. Safe to run more than once.
-- =====================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_requisition_log (
    xx_requisition_log_id BIGSERIAL PRIMARY KEY,
    m_requisition_id      NUMERIC(10)  NOT NULL,
    ad_client_id          NUMERIC(10)  NOT NULL,
    action                VARCHAR(30)  NOT NULL,   -- Line added | Line removed | Qty changed
    detail                VARCHAR(500),
    createdby             NUMERIC(10),
    created               TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS xx_requisition_log_req ON adempiere.xx_requisition_log (m_requisition_id, created);