-- =============================================================================
-- MMS: Schema Migration / Compatibility Script
-- File: 04_schema_migration.sql
-- Run this to align existing tables with MMS codebase
-- Existing tables use 'status' column — we add 'docstatus' alias for compatibility
-- =============================================================================

SET search_path TO adempiere, public;

-- =============================================================================
-- XX_ManagementMeeting: Add docstatus if not exists (maps to status)
-- =============================================================================
DO $$
BEGIN
    -- Add docstatus column if missing (iDempiere standard)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'adempiere' AND table_name = 'xx_managementmeeting'
          AND column_name = 'docstatus'
    ) THEN
        ALTER TABLE adempiere.xx_managementmeeting
            ADD COLUMN docstatus VARCHAR(2) NOT NULL DEFAULT 'DR';

        -- Copy existing status values mapped to docstatus
        UPDATE adempiere.xx_managementmeeting SET docstatus =
            CASE status
                WHEN 'DRAFT'            THEN 'DR'
                WHEN 'PENDING_APPROVAL' THEN 'WA'
                WHEN 'APPROVED'         THEN 'AP'
                WHEN 'SCHEDULED'        THEN 'SC'
                WHEN 'IN_PROGRESS'      THEN 'IP'
                WHEN 'COMPLETED'        THEN 'CO'
                WHEN 'CANCELLED'        THEN 'VO'
                ELSE 'DR'
            END;

        RAISE NOTICE 'Added docstatus column to XX_ManagementMeeting';
    ELSE
        RAISE NOTICE 'docstatus already exists in XX_ManagementMeeting';
    END IF;

    -- Fix priority to use standard iDempiere single char (1,3,5,7)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'adempiere' AND table_name = 'xx_managementmeeting'
          AND column_name = 'priority' AND character_maximum_length = 1
    ) THEN
        -- Priority column already exists, update values if needed
        UPDATE adempiere.xx_managementmeeting SET priority =
            CASE priority
                WHEN 'URGENT'  THEN '1'
                WHEN 'HIGH'    THEN '3'
                WHEN 'MEDIUM'  THEN '5'
                WHEN 'LOW'     THEN '7'
                ELSE '5'
            END
        WHERE priority IN ('URGENT','HIGH','MEDIUM','LOW');

        RAISE NOTICE 'Updated priority values to iDempiere standard (1,3,5,7)';
    END IF;

    -- Ensure meetingdate is timestamp (not just date)
    -- (existing is DATE type, which is fine)

    -- Add isonlinemeetingcreated if missing
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'adempiere' AND table_name = 'xx_managementmeeting'
          AND column_name = 'isonlinemeetingcreated'
    ) THEN
        ALTER TABLE adempiere.xx_managementmeeting
            ADD COLUMN isonlinemeetingcreated CHAR(1) NOT NULL DEFAULT 'N';
        RAISE NOTICE 'Added isonlinemeetingcreated column';
    END IF;

    -- Add isinvitationsent if missing
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'adempiere' AND table_name = 'xx_managementmeeting'
          AND column_name = 'isinvitationsent'
    ) THEN
        ALTER TABLE adempiere.xx_managementmeeting
            ADD COLUMN isinvitationsent CHAR(1) NOT NULL DEFAULT 'N';
        RAISE NOTICE 'Added isinvitationsent column';
    END IF;

END $$;

-- =============================================================================
-- Verify XX_MeetingParticipant structure
-- =============================================================================
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'adempiere' AND table_name = 'xx_meetingparticipant'
          AND column_name = 'invitationstatus'
    ) THEN
        ALTER TABLE adempiere.xx_meetingparticipant
            ADD COLUMN invitationstatus VARCHAR(20) NOT NULL DEFAULT 'PENDING';
        RAISE NOTICE 'Added invitationstatus to XX_MeetingParticipant';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'adempiere' AND table_name = 'xx_meetingparticipant'
          AND column_name = 'responsestatus'
    ) THEN
        ALTER TABLE adempiere.xx_meetingparticipant
            ADD COLUMN responsestatus VARCHAR(20) NOT NULL DEFAULT 'PENDING';
        RAISE NOTICE 'Added responsestatus to XX_MeetingParticipant';
    END IF;
END $$;

-- =============================================================================
-- Create/replace Views using actual column names
-- =============================================================================

CREATE OR REPLACE VIEW adempiere.xx_v_overdue_actions AS
SELECT
    a.xx_meetingaction_id,
    a.xx_managementmeeting_id,
    m.documentno,
    m.subject AS meeting_subject,
    a.task,
    a.description,
    u.name AS responsible_user,
    a.duedate,
    CURRENT_TIMESTAMP - a.duedate::timestamp AS overdue_duration,
    EXTRACT(DAY FROM (CURRENT_TIMESTAMP - a.duedate::timestamp)) AS days_overdue,
    a.progresspercent,
    a.status,
    a.ad_client_id,
    a.ad_org_id
FROM adempiere.xx_meetingaction a
JOIN adempiere.xx_managementmeeting m ON m.xx_managementmeeting_id = a.xx_managementmeeting_id
LEFT JOIN adempiere.ad_user u ON u.ad_user_id = a.responsibleuser_id
WHERE a.status NOT IN ('COMPLETED','CANCELLED')
  AND a.duedate < CURRENT_DATE
  AND a.isactive = 'Y';

CREATE OR REPLACE VIEW adempiere.xx_v_meeting_dashboard AS
SELECT
    ad_client_id,
    COUNT(*) FILTER (WHERE DATE(meetingdate) = CURRENT_DATE AND status NOT IN ('CANCELLED','VO'))
        AS today_meetings,
    COUNT(*) FILTER (WHERE meetingdate > CURRENT_DATE AND status IN ('APPROVED','SCHEDULED','AP','SC'))
        AS upcoming_meetings,
    COUNT(*) FILTER (WHERE status IN ('COMPLETED','CO'))
        AS completed_meetings,
    COUNT(*) FILTER (WHERE status IN ('CANCELLED','VO'))
        AS cancelled_meetings,
    COUNT(*) FILTER (WHERE status IN ('PENDING_APPROVAL','WA'))
        AS pending_approval,
    COUNT(*) FILTER (WHERE status IN ('IN_PROGRESS','IP'))
        AS in_progress_meetings
FROM adempiere.xx_managementmeeting
WHERE isactive = 'Y'
GROUP BY ad_client_id;

SELECT 'Schema migration complete!' AS status;

-- Show current structure
SELECT column_name, data_type, character_maximum_length
FROM information_schema.columns
WHERE table_schema = 'adempiere' AND table_name = 'xx_managementmeeting'
ORDER BY ordinal_position;
