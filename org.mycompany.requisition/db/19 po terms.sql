SET search_path TO adempiere, public;
-- =====================================================================
-- Create PO popup: Terms and Conditions Type / Terms and Conditions kept on the PO
-- PostgreSQL, idempotent. Run once (after 04_create_rfq_term.sql and 12_create_po_extra_fields.sql),
-- then restart / cache reset.
--   C_Order.XX_RFQ_Term_ID  Terms and Conditions Type (XX_RFQ_Term)
--   C_Order.XX_TermsText    Terms and Conditions text (the type's text, as edited in the popup)
-- =====================================================================
ALTER TABLE C_Order ADD COLUMN IF NOT EXISTS XX_RFQ_Term_ID NUMERIC(10);
ALTER TABLE C_Order ADD COLUMN IF NOT EXISTS XX_TermsText   VARCHAR(4000);