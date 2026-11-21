-- RFQ Term table (form er "RFQ Term" dropdown + "Terms and Conditions" text er jonno)
-- PostgreSQL. Ekbar chalao.

CREATE TABLE IF NOT EXISTS XX_RFQ_Term (
    XX_RFQ_Term_ID   NUMERIC(10)   NOT NULL,
    AD_Client_ID     NUMERIC(10)   NOT NULL,
    AD_Org_ID        NUMERIC(10)   NOT NULL,
    IsActive         CHAR(1)       NOT NULL DEFAULT 'Y',
    Created          TIMESTAMP     NOT NULL DEFAULT now(),
    CreatedBy        NUMERIC(10)   NOT NULL,
    Updated          TIMESTAMP     NOT NULL DEFAULT now(),
    UpdatedBy        NUMERIC(10)   NOT NULL,
    Name             VARCHAR(120)  NOT NULL,
    TermsText        VARCHAR(4000),
    XX_RFQ_Term_UU   VARCHAR(36),
    CONSTRAINT XX_RFQ_Term_pkey PRIMARY KEY (XX_RFQ_Term_ID)
);

-- Sample data (tomar screenshot er text). AD_Client_ID=0 mane shob client dekhbe.
INSERT INTO XX_RFQ_Term (XX_RFQ_Term_ID, AD_Client_ID, AD_Org_ID, CreatedBy, UpdatedBy, Name, TermsText)
SELECT (SELECT COALESCE(MAX(XX_RFQ_Term_ID), 999999) + 1 FROM XX_RFQ_Term), 0, 0, 100, 100,
       'Foreign Payment Term',
       E'1. PARTIAL SHIPMENT: ALLOWED\n2. TRANSSHIPMENT: ALLOWED\n3. ALL BANKING CHARGES OUTSIDE BANGLADESH INCLUDING REMITTING CHARGES ARE ON APPLICANT''S ACCOUNT\n4. SHIPMENT DATE: WITHIN 90 DAYS FROM EXP ISSUING DATE.'
WHERE NOT EXISTS (SELECT 1 FROM XX_RFQ_Term WHERE Name = 'Foreign Payment Term');

INSERT INTO XX_RFQ_Term (XX_RFQ_Term_ID, AD_Client_ID, AD_Org_ID, CreatedBy, UpdatedBy, Name, TermsText)
SELECT (SELECT COALESCE(MAX(XX_RFQ_Term_ID), 999999) + 1 FROM XX_RFQ_Term), 0, 0, 100, 100,
       'Terms & Condition 01',
       E'1. VAT & AIT will be deducted as per Government (NBR) Circular.\n2. If the product is VAT imposed then MUSAK-6.3/VAT Challan to be enclosed with the bill copy.\n3. Please refer to the Work Order No. in all your correspondence.\n4. Terms of Delivery : Maximum 3 (three ) working days after receiving this work order.\n5. Delivery Location : Mirsarai.\n6. Terms of payment : The total payment will be made after the work is completed.'
WHERE NOT EXISTS (SELECT 1 FROM XX_RFQ_Term WHERE Name = 'Terms & Condition 01');
