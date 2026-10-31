-- Add custom columns for Requisition Line-Based RFQ Customization
ALTER TABLE M_RequisitionLine ADD COLUMN IF NOT EXISTS XX_RfQ_ID NUMERIC(10);
ALTER TABLE M_RfQLine ADD COLUMN IF NOT EXISTS XX_RequisitionLine_ID NUMERIC(10);

-- Add foreign keys
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'm_requisitionline_xx_rfq') THEN
        ALTER TABLE M_RequisitionLine 
        ADD CONSTRAINT m_requisitionline_xx_rfq 
        FOREIGN KEY (XX_RfQ_ID) REFERENCES C_RfQ(C_RfQ_ID) ON DELETE SET NULL;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'm_rfqlines_xx_requisitionline') THEN
        ALTER TABLE M_RfQLine 
        ADD CONSTRAINT m_rfqlines_xx_requisitionline 
        FOREIGN KEY (XX_RequisitionLine_ID) REFERENCES M_RequisitionLine(M_RequisitionLine_ID) ON DELETE SET NULL;
    END IF;
END $$;

-- Add indexes
CREATE INDEX IF NOT EXISTS idx_m_requisitionline_xx_rfq ON M_RequisitionLine(XX_RfQ_ID);
CREATE INDEX IF NOT EXISTS idx_m_rfqlines_xx_requisitionline ON M_RfQLine(XX_RequisitionLine_ID);
CREATE INDEX IF NOT EXISTS idx_m_requisitionline_eligibility ON M_RequisitionLine(M_Requisition_ID, M_Product_ID, XX_RfQ_ID) WHERE XX_RfQ_ID IS NULL;
