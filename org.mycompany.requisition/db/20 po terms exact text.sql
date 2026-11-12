SET search_path TO adempiere, public;
-- =====================================================================
-- Terms and Conditions Type: exactly two types, text same as the screenshots (spaces included).
-- PostgreSQL, idempotent. Safe to run again. Cache reset after.
-- =====================================================================
INSERT INTO XX_RFQ_Term (XX_RFQ_Term_ID, AD_Client_ID, AD_Org_ID, CreatedBy, UpdatedBy, Name, TermsText)
SELECT (SELECT COALESCE(MAX(XX_RFQ_Term_ID), 999999) + 1 FROM XX_RFQ_Term), 0, 0, 100, 100, 'Foreign Payment Term', ''
WHERE NOT EXISTS (SELECT 1 FROM XX_RFQ_Term WHERE Name = 'Foreign Payment Term');

INSERT INTO XX_RFQ_Term (XX_RFQ_Term_ID, AD_Client_ID, AD_Org_ID, CreatedBy, UpdatedBy, Name, TermsText)
SELECT (SELECT COALESCE(MAX(XX_RFQ_Term_ID), 999999) + 1 FROM XX_RFQ_Term), 0, 0, 100, 100, 'Terms & Condition 01', ''
WHERE NOT EXISTS (SELECT 1 FROM XX_RFQ_Term WHERE Name = 'Terms & Condition 01');

UPDATE XX_RFQ_Term SET IsActive = 'Y', Updated = now(), TermsText =
E'1. PARTIAL SHIPMENT: ALLOWED\n2. TRANSSHIPMENT: ALLOWED\n3. ALL BANKING CHARGES OUTSIDE BANGLADESH INCLUDING REMITTING CHARGES ARE ON APPLICANT''S  ACCOUNT\n4. SHIPMENT DATE: WITHIN 90 DAYS FROM EXP ISSUING DATE.'
WHERE Name = 'Foreign Payment Term';

UPDATE XX_RFQ_Term SET IsActive = 'Y', Updated = now(), TermsText =
E'1. VAT & AIT will be deducted as per Government (NBR) Circular.\n2. If the product is VAT imposed then MUSAK-6.3/VAT Challan to be enclosed with the bill copy.\n3. Please refer to the Work Order No. in all your correspondence.\n4. Terms of Delivery  : Maximum 3 (three ) working days after receiving this work order.\n5. Delivery Location  :  Mirsarai.\n6. Terms of payment : The total payment will be made after the work is completed.'
WHERE Name = 'Terms & Condition 01';

-- only these two types in the dropdown
UPDATE XX_RFQ_Term SET IsActive = 'N', Updated = now()
WHERE Name NOT IN ('Foreign Payment Term', 'Terms & Condition 01') AND IsActive = 'Y';