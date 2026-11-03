SET search_path TO adempiere, public;
-- =====================================================================
-- 11_winner_change_approval.sql
-- Fast-Track Winner Change Request & HOD Approval
-- =====================================================================

CREATE TABLE IF NOT EXISTS XX_RfQWinnerChange (
    XX_RfQWinnerChange_ID NUMERIC(10) PRIMARY KEY,
    AD_Client_ID          NUMERIC(10) NOT NULL,
    AD_Org_ID             NUMERIC(10) NOT NULL,
    IsActive              CHAR(1) DEFAULT 'Y' NOT NULL,
    Created               TIMESTAMP WITHOUT TIME ZONE DEFAULT now() NOT NULL,
    CreatedBy             NUMERIC(10) NOT NULL,
    Updated               TIMESTAMP WITHOUT TIME ZONE DEFAULT now() NOT NULL,
    UpdatedBy             NUMERIC(10) NOT NULL,
    C_RfQ_ID              NUMERIC(10) NOT NULL,
    C_RfQLine_ID          NUMERIC(10) NOT NULL,
    Old_RfQResponse_ID    NUMERIC(10),
    New_RfQResponse_ID    NUMERIC(10) NOT NULL,
    Justification         TEXT NOT NULL,
    Status                CHAR(1) DEFAULT 'P' NOT NULL, -- P = Pending, A = Approved, R = Rejected
    ApprovedBy            NUMERIC(10),
    ApprovedDate          TIMESTAMP WITHOUT TIME ZONE,
    ApproverComment       TEXT
);

CREATE INDEX IF NOT EXISTS idx_xx_winnerchange_rfqline ON XX_RfQWinnerChange(C_RfQLine_ID, Status);
CREATE INDEX IF NOT EXISTS idx_xx_winnerchange_status  ON XX_RfQWinnerChange(Status);
