-- =============================================================================
-- MMS: Application Dictionary Registration
-- File: 03_ad_dictionary.sql
-- Run AFTER 01 and 02 scripts
--
-- This script registers MMS tables in iDempiere's Application Dictionary.
-- IMPORTANT: After running this SQL, you MUST:
--   1. Login to iDempiere as System Admin
--   2. Go to Application Dictionary → Table and Column
--   3. Find each XX_ table
--   4. Click "Synchronize Column" button
--   5. Then create Windows, Tabs, Fields manually (see instructions below)
-- =============================================================================

SET search_path TO adempiere, public;

-- Helper to get next ID
-- Usage: SELECT nextval('ad_element_seq') etc.
-- We use fixed IDs starting at 1000000 to avoid conflicts

DO $$
DECLARE
    -- AD_Table IDs
    v_tbl_meeting    NUMERIC(10) := 1000100;
    v_tbl_partcp     NUMERIC(10) := 1000101;
    v_tbl_agenda     NUMERIC(10) := 1000102;
    v_tbl_minutes    NUMERIC(10) := 1000103;
    v_tbl_decision   NUMERIC(10) := 1000104;
    v_tbl_action     NUMERIC(10) := 1000105;
    v_tbl_room       NUMERIC(10) := 1000106;
    v_tbl_type       NUMERIC(10) := 1000107;

BEGIN

-- =============================================================================
-- AD_Table: XX_MeetingType
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM adempiere.ad_table WHERE tablename = 'XX_MeetingType') THEN
    INSERT INTO adempiere.ad_table (
        ad_table_id, ad_client_id, ad_org_id, isactive, created, createdby,
        updated, updatedby, name, tablename, description, isview, accesslevel,
        entitytype, issecurityenabled, isdeleteable, ishighvolume,
        ischangelog, replicationtype, copycolumnsfromtable
    ) VALUES (
        v_tbl_type, 0, 0, 'Y', NOW(), 0, NOW(), 0,
        'Meeting Type', 'XX_MeetingType',
        'MMS: Meeting Type (Board Meeting, Monthly Review, etc.)',
        'N', '3', 'U', 'N', 'Y', 'N', 'Y', 'L', 'N'
    );
    RAISE NOTICE 'Created AD_Table: XX_MeetingType (ID=%)', v_tbl_type;
ELSE
    SELECT ad_table_id INTO v_tbl_type FROM adempiere.ad_table WHERE tablename = 'XX_MeetingType';
    RAISE NOTICE 'AD_Table XX_MeetingType already exists (ID=%)', v_tbl_type;
END IF;

-- =============================================================================
-- AD_Table: XX_MeetingRoom
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM adempiere.ad_table WHERE tablename = 'XX_MeetingRoom') THEN
    INSERT INTO adempiere.ad_table (
        ad_table_id, ad_client_id, ad_org_id, isactive, created, createdby,
        updated, updatedby, name, tablename, description, isview, accesslevel,
        entitytype, issecurityenabled, isdeleteable, ishighvolume,
        ischangelog, replicationtype, copycolumnsfromtable
    ) VALUES (
        v_tbl_room, 0, 0, 'Y', NOW(), 0, NOW(), 0,
        'Meeting Room', 'XX_MeetingRoom',
        'MMS: Physical Meeting Room setup',
        'N', '3', 'U', 'N', 'Y', 'N', 'Y', 'L', 'N'
    );
    RAISE NOTICE 'Created AD_Table: XX_MeetingRoom (ID=%)', v_tbl_room;
ELSE
    SELECT ad_table_id INTO v_tbl_room FROM adempiere.ad_table WHERE tablename = 'XX_MeetingRoom';
END IF;

-- =============================================================================
-- AD_Table: XX_ManagementMeeting
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM adempiere.ad_table WHERE tablename = 'XX_ManagementMeeting') THEN
    INSERT INTO adempiere.ad_table (
        ad_table_id, ad_client_id, ad_org_id, isactive, created, createdby,
        updated, updatedby, name, tablename, description, isview, accesslevel,
        entitytype, issecurityenabled, isdeleteable, ishighvolume,
        ischangelog, replicationtype, copycolumnsfromtable
    ) VALUES (
        v_tbl_meeting, 0, 0, 'Y', NOW(), 0, NOW(), 0,
        'Management Meeting', 'XX_ManagementMeeting',
        'MMS: Main Meeting Header Record',
        'N', '3', 'U', 'N', 'Y', 'N', 'Y', 'L', 'N'
    );
    RAISE NOTICE 'Created AD_Table: XX_ManagementMeeting (ID=%)', v_tbl_meeting;
ELSE
    SELECT ad_table_id INTO v_tbl_meeting FROM adempiere.ad_table WHERE tablename = 'XX_ManagementMeeting';
END IF;

-- =============================================================================
-- AD_Table: XX_MeetingParticipant
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM adempiere.ad_table WHERE tablename = 'XX_MeetingParticipant') THEN
    INSERT INTO adempiere.ad_table (
        ad_table_id, ad_client_id, ad_org_id, isactive, created, createdby,
        updated, updatedby, name, tablename, description, isview, accesslevel,
        entitytype, issecurityenabled, isdeleteable, ishighvolume,
        ischangelog, replicationtype, copycolumnsfromtable
    ) VALUES (
        v_tbl_partcp, 0, 0, 'Y', NOW(), 0, NOW(), 0,
        'Meeting Participant', 'XX_MeetingParticipant',
        'MMS: Meeting Participants',
        'N', '3', 'U', 'N', 'Y', 'N', 'Y', 'L', 'N'
    );
    RAISE NOTICE 'Created AD_Table: XX_MeetingParticipant (ID=%)', v_tbl_partcp;
ELSE
    SELECT ad_table_id INTO v_tbl_partcp FROM adempiere.ad_table WHERE tablename = 'XX_MeetingParticipant';
END IF;

-- =============================================================================
-- AD_Table: XX_MeetingAgenda
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM adempiere.ad_table WHERE tablename = 'XX_MeetingAgenda') THEN
    INSERT INTO adempiere.ad_table (
        ad_table_id, ad_client_id, ad_org_id, isactive, created, createdby,
        updated, updatedby, name, tablename, description, isview, accesslevel,
        entitytype, issecurityenabled, isdeleteable, ishighvolume,
        ischangelog, replicationtype, copycolumnsfromtable
    ) VALUES (
        v_tbl_agenda, 0, 0, 'Y', NOW(), 0, NOW(), 0,
        'Meeting Agenda', 'XX_MeetingAgenda',
        'MMS: Meeting Agenda Items',
        'N', '3', 'U', 'N', 'Y', 'N', 'Y', 'L', 'N'
    );
    RAISE NOTICE 'Created AD_Table: XX_MeetingAgenda (ID=%)', v_tbl_agenda;
ELSE
    SELECT ad_table_id INTO v_tbl_agenda FROM adempiere.ad_table WHERE tablename = 'XX_MeetingAgenda';
END IF;

-- =============================================================================
-- AD_Table: XX_MeetingMinutes
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM adempiere.ad_table WHERE tablename = 'XX_MeetingMinutes') THEN
    INSERT INTO adempiere.ad_table (
        ad_table_id, ad_client_id, ad_org_id, isactive, created, createdby,
        updated, updatedby, name, tablename, description, isview, accesslevel,
        entitytype, issecurityenabled, isdeleteable, ishighvolume,
        ischangelog, replicationtype, copycolumnsfromtable
    ) VALUES (
        v_tbl_minutes, 0, 0, 'Y', NOW(), 0, NOW(), 0,
        'Meeting Minutes', 'XX_MeetingMinutes',
        'MMS: Minutes of Meeting',
        'N', '3', 'U', 'N', 'Y', 'N', 'Y', 'L', 'N'
    );
ELSE
    SELECT ad_table_id INTO v_tbl_minutes FROM adempiere.ad_table WHERE tablename = 'XX_MeetingMinutes';
END IF;

-- =============================================================================
-- AD_Table: XX_MeetingDecision
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM adempiere.ad_table WHERE tablename = 'XX_MeetingDecision') THEN
    INSERT INTO adempiere.ad_table (
        ad_table_id, ad_client_id, ad_org_id, isactive, created, createdby,
        updated, updatedby, name, tablename, description, isview, accesslevel,
        entitytype, issecurityenabled, isdeleteable, ishighvolume,
        ischangelog, replicationtype, copycolumnsfromtable
    ) VALUES (
        v_tbl_decision, 0, 0, 'Y', NOW(), 0, NOW(), 0,
        'Meeting Decision', 'XX_MeetingDecision',
        'MMS: Meeting Decisions',
        'N', '3', 'U', 'N', 'Y', 'N', 'Y', 'L', 'N'
    );
ELSE
    SELECT ad_table_id INTO v_tbl_decision FROM adempiere.ad_table WHERE tablename = 'XX_MeetingDecision';
END IF;

-- =============================================================================
-- AD_Table: XX_MeetingAction
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM adempiere.ad_table WHERE tablename = 'XX_MeetingAction') THEN
    INSERT INTO adempiere.ad_table (
        ad_table_id, ad_client_id, ad_org_id, isactive, created, createdby,
        updated, updatedby, name, tablename, description, isview, accesslevel,
        entitytype, issecurityenabled, isdeleteable, ishighvolume,
        ischangelog, replicationtype, copycolumnsfromtable
    ) VALUES (
        v_tbl_action, 0, 0, 'Y', NOW(), 0, NOW(), 0,
        'Meeting Action Item', 'XX_MeetingAction',
        'MMS: Meeting Action Items',
        'N', '3', 'U', 'N', 'Y', 'N', 'Y', 'L', 'N'
    );
ELSE
    SELECT ad_table_id INTO v_tbl_action FROM adempiere.ad_table WHERE tablename = 'XX_MeetingAction';
END IF;

RAISE NOTICE '=== All MMS AD_Table records created/verified ===';
RAISE NOTICE 'NEXT STEP: Login to iDempiere Web UI and Synchronize Columns for each table.';
RAISE NOTICE 'Path: Application Dictionary → Table and Column → Find XX_ table → Synchronize Column button';

END $$;

-- =============================================================================
-- Register MMS Form (Dashboard)
-- =============================================================================
INSERT INTO adempiere.ad_form (
    ad_form_id, ad_client_id, ad_org_id, isactive, created, createdby,
    updated, updatedby, name, description, classname, accesslevel,
    entitytype, isactive
)
SELECT
    1000100, 0, 0, 'Y', NOW(), 0, NOW(), 0,
    'Meeting Dashboard',
    'Management Meeting System Dashboard',
    'org.idempiere.mms.form.WMeetingDashboard',
    '3',
    'U',
    'Y'
WHERE NOT EXISTS (
    SELECT 1 FROM adempiere.ad_form WHERE classname = 'org.idempiere.mms.form.WMeetingDashboard'
);

SELECT 'MMS Application Dictionary registration complete!' AS status;
SELECT 'IMPORTANT: Now run "Synchronize Column" for each XX_ table in iDempiere Web UI' AS next_step;
