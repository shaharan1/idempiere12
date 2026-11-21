SET search_path TO adempiere, public;
-- =====================================================================
-- 18_requisition_print_pdf.sql
-- Standard Requisition window: the Print button gives the Requisition PDF
-- (PurchaseRequisition.jrxml: same fields as the Requisition Form).
--
-- 1. Copy PurchaseRequisition.jrxml to the server folder named in c_path below
--    (the logos logo_left.png / logo_right.png are read from the same folder,
--    as before). Change c_path if your folder is another one and run this again.
-- 2. This script creates the process "Requisition PDF" (a Jasper report process),
--    gives it to every active role, and sets it as the Jasper Process of the Print
--    Format(s) of table M_Requisition. PostgreSQL, idempotent.
-- 3. Cache Reset (or log out / in), open a requisition, click the Print icon.
--
-- If the notice at the end says no Print Format was found for M_Requisition:
-- Print Format window > new record > Table = Requisition (M_Requisition) >
-- Jasper Process = Requisition PDF, tick Default.
-- =====================================================================
DO $$
DECLARE
    c_path CONSTANT text := 'C:/jasper/PurchaseRequisition.jrxml';   -- <== folder of the .jrxml on the server
    v_proc NUMERIC;
    v_n    INTEGER;
BEGIN
    SELECT AD_Process_ID INTO v_proc FROM AD_Process WHERE Value = 'XX_RequisitionPDF';
    IF v_proc IS NULL THEN
        v_proc := (SELECT COALESCE(MAX(AD_Process_ID) FILTER (WHERE AD_Process_ID >= 1000000), 999999) + 1
                     FROM AD_Process);
        INSERT INTO AD_Process (AD_Process_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy,
                                Value, Name, Description, AccessLevel, EntityType, IsReport, IsDirectPrint,
                                Classname, JasperReport, ShowHelp, IsBetaFunctionality, AD_Process_UU)
        VALUES (v_proc, 0, 0, 'Y', now(), 100, now(), 100,
                'XX_RequisitionPDF', 'Requisition PDF', 'Purchase Requisition as PDF (same fields as the Requisition Form)',
                '3', 'U', 'Y', 'N',
                'org.compiere.report.ReportStarter', c_path, 'N', 'N', gen_random_uuid()::text);
    ELSE
        UPDATE AD_Process SET JasperReport = c_path, IsReport = 'Y', IsActive = 'Y', Updated = now()
         WHERE AD_Process_ID = v_proc;
    END IF;

    INSERT INTO AD_Process_Access (AD_Process_ID, AD_Role_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy,
                                   Updated, UpdatedBy, IsReadWrite, AD_Process_Access_UU)
    SELECT v_proc, r.AD_Role_ID, r.AD_Client_ID, 0, 'Y', now(), 100, now(), 100, 'Y', gen_random_uuid()::text
      FROM AD_Role r
     WHERE r.IsActive = 'Y'
       AND NOT EXISTS (SELECT 1 FROM AD_Process_Access x WHERE x.AD_Process_ID = v_proc AND x.AD_Role_ID = r.AD_Role_ID);

    UPDATE AD_PrintFormat
       SET JasperProcess_ID = v_proc, Updated = now()
     WHERE AD_Table_ID = (SELECT AD_Table_ID FROM AD_Table WHERE TableName = 'M_Requisition');
    GET DIAGNOSTICS v_n = ROW_COUNT;

    IF v_n = 0 THEN
        RAISE NOTICE 'Process % created, but no Print Format exists for M_Requisition: create one (see the top of this script).', v_proc;
    ELSE
        RAISE NOTICE 'Process % ready; Jasper Process set on % Print Format(s) of M_Requisition.', v_proc, v_n;
    END IF;
END $$;