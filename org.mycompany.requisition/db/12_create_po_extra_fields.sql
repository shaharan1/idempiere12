SET search_path TO adempiere, public;
-- =====================================================================
-- Create PO popup: extra header / line fields kept on the PO
-- PostgreSQL, idempotent. Run once (after 09_create_po_from_cs.sql), then restart / cache reset.
--
--   C_Order.XX_TransportCost       Transport Cost typed in the Create PO popup (default: from the CS response)
--   C_Order.XX_OthersCost          Others Cost
--   C_Order.XX_IsGrossDiscountPct  'Y' = XX_GrossDiscount is a percent of (lines + VAT), 'N' = an amount
--   C_Order.XX_GrossDiscount       Gross Discount
--   C_Order.XX_PayableTotal        Lines + VAT - Gross Discount + Transport + Others. This is the amount the
--                                  approval matrix is started with.
--   C_OrderLine.XX_IsRebate        Rebate flag of the CS line
--   C_OrderLine.XX_IsTariff        TariffRate flag of the CS line
-- =====================================================================

ALTER TABLE C_Order     ADD COLUMN IF NOT EXISTS XX_TransportCost      NUMERIC;
ALTER TABLE C_Order     ADD COLUMN IF NOT EXISTS XX_OthersCost         NUMERIC;
ALTER TABLE C_Order     ADD COLUMN IF NOT EXISTS XX_IsGrossDiscountPct CHAR(1) DEFAULT 'N';
ALTER TABLE C_Order     ADD COLUMN IF NOT EXISTS XX_GrossDiscount      NUMERIC;
ALTER TABLE C_Order     ADD COLUMN IF NOT EXISTS XX_PayableTotal       NUMERIC;

ALTER TABLE C_OrderLine ADD COLUMN IF NOT EXISTS XX_IsRebate CHAR(1) DEFAULT 'N';
ALTER TABLE C_OrderLine ADD COLUMN IF NOT EXISTS XX_IsTariff CHAR(1) DEFAULT 'N';
