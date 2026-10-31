-- =============================================================================
-- MMS: Sequences, AD_Reference, and AD_Sequence Setup
-- File: 02_sequences_references.sql
-- Run AFTER 01_create_tables.sql
-- =============================================================================

SET search_path TO adempiere, public;

-- =============================================================================
-- PostgreSQL Sequences (for table PKs)
-- =============================================================================
CREATE SEQUENCE IF NOT EXISTS adempiere.xx_meetingtype_seq
    START WITH 1000000 INCREMENT BY 1 NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS adempiere.xx_meetingroom_seq
    START WITH 1000000 INCREMENT BY 1 NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS adempiere.xx_managementmeeting_seq
    START WITH 1000000 INCREMENT BY 1 NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS adempiere.xx_meetingparticipant_seq
    START WITH 1000000 INCREMENT BY 1 NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS adempiere.xx_meetingagenda_seq
    START WITH 1000000 INCREMENT BY 1 NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS adempiere.xx_meetingminutes_seq
    START WITH 1000000 INCREMENT BY 1 NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS adempiere.xx_meetingdecision_seq
    START WITH 1000000 INCREMENT BY 1 NO CYCLE;

CREATE SEQUENCE IF NOT EXISTS adempiere.xx_meetingaction_seq
    START WITH 1000000 INCREMENT BY 1 NO CYCLE;

-- =============================================================================
-- AD_Sequence records (iDempiere Document Sequence for DocumentNo)
-- iDempiere uses AD_Sequence table for document numbering
-- =============================================================================
-- Meeting Document Number: MTG-2026-0001
INSERT INTO adempiere.ad_sequence (
    ad_sequence_id, ad_client_id, ad_org_id, isactive, created, createdby,
    updated, updatedby, name, description, vformat, isautosequence,
    incrementno, startno, currentnext, currentnextsys, isaudited,
    istableid, prefix, suffix
)
SELECT
    (SELECT COALESCE(MAX(ad_sequence_id), 1000000) + 1 FROM adempiere.ad_sequence),
    0, 0, 'Y', NOW(), 0, NOW(), 0,
    'XX_ManagementMeeting',
    'Management Meeting Document Number',
    NULL,
    'Y', 1, 1, 1, 100000, 'N', 'N',
    'MTG-', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM adempiere.ad_sequence WHERE name = 'XX_ManagementMeeting'
);

-- Decision Document Number: DEC-0001
INSERT INTO adempiere.ad_sequence (
    ad_sequence_id, ad_client_id, ad_org_id, isactive, created, createdby,
    updated, updatedby, name, description, vformat, isautosequence,
    incrementno, startno, currentnext, currentnextsys, isaudited,
    istableid, prefix, suffix
)
SELECT
    (SELECT COALESCE(MAX(ad_sequence_id), 1000000) + 2 FROM adempiere.ad_sequence),
    0, 0, 'Y', NOW(), 0, NOW(), 0,
    'XX_MeetingDecision',
    'Meeting Decision Number',
    NULL,
    'Y', 1, 1, 1, 100000, 'N', 'N',
    'DEC-', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM adempiere.ad_sequence WHERE name = 'XX_MeetingDecision'
);

-- =============================================================================
-- AD_Reference: Meeting Mode (List)
-- =============================================================================
DO $$
DECLARE
    v_ref_id NUMERIC(10);
BEGIN
    -- Check if already exists
    SELECT ad_reference_id INTO v_ref_id
    FROM adempiere.ad_reference
    WHERE name = 'XX_MeetingMode' AND ad_client_id = 0;

    IF v_ref_id IS NULL THEN
        v_ref_id := (SELECT COALESCE(MAX(ad_reference_id), 1000000) + 1 FROM adempiere.ad_reference);

        INSERT INTO adempiere.ad_reference (
            ad_reference_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            name, description, validationtype, vformat, entitytype
        ) VALUES (
            v_ref_id, 0, 0, 'Y', NOW(), 0, NOW(), 0,
            'XX_MeetingMode', 'MMS Meeting Mode', 'L', NULL, 'U'
        );

        -- List Values
        INSERT INTO adempiere.ad_ref_list (ad_ref_list_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby, value, name, ad_reference_id, entitytype)
        VALUES
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+1 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'PHYSICAL', 'Physical', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+2 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'ONLINE', 'Online', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+3 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'HYBRID', 'Hybrid', v_ref_id, 'U');

        RAISE NOTICE 'XX_MeetingMode reference created with ID: %', v_ref_id;
    ELSE
        RAISE NOTICE 'XX_MeetingMode reference already exists with ID: %', v_ref_id;
    END IF;
END $$;

-- =============================================================================
-- AD_Reference: Meeting Platform
-- =============================================================================
DO $$
DECLARE
    v_ref_id NUMERIC(10);
BEGIN
    SELECT ad_reference_id INTO v_ref_id
    FROM adempiere.ad_reference
    WHERE name = 'XX_MeetingPlatform' AND ad_client_id = 0;

    IF v_ref_id IS NULL THEN
        v_ref_id := (SELECT COALESCE(MAX(ad_reference_id), 1000000) + 10 FROM adempiere.ad_reference);

        INSERT INTO adempiere.ad_reference (
            ad_reference_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            name, description, validationtype, vformat, entitytype
        ) VALUES (
            v_ref_id, 0, 0, 'Y', NOW(), 0, NOW(), 0,
            'XX_MeetingPlatform', 'MMS Meeting Platform', 'L', NULL, 'U'
        );

        INSERT INTO adempiere.ad_ref_list (ad_ref_list_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby, value, name, ad_reference_id, entitytype)
        VALUES
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+10 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'INTERNAL', 'Internal', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+11 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'GOOGLE_MEET', 'Google Meet', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+12 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'ZOOM', 'Zoom', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+13 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'OTHER', 'Other', v_ref_id, 'U');

        RAISE NOTICE 'XX_MeetingPlatform reference created with ID: %', v_ref_id;
    END IF;
END $$;

-- =============================================================================
-- AD_Reference: Meeting Doc Status
-- =============================================================================
DO $$
DECLARE
    v_ref_id NUMERIC(10);
BEGIN
    SELECT ad_reference_id INTO v_ref_id
    FROM adempiere.ad_reference
    WHERE name = 'XX_MeetingStatus' AND ad_client_id = 0;

    IF v_ref_id IS NULL THEN
        v_ref_id := (SELECT COALESCE(MAX(ad_reference_id), 1000000) + 20 FROM adempiere.ad_reference);

        INSERT INTO adempiere.ad_reference (
            ad_reference_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            name, description, validationtype, vformat, entitytype
        ) VALUES (
            v_ref_id, 0, 0, 'Y', NOW(), 0, NOW(), 0,
            'XX_MeetingStatus', 'MMS Meeting Status', 'L', NULL, 'U'
        );

        INSERT INTO adempiere.ad_ref_list (ad_ref_list_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby, value, name, ad_reference_id, entitytype)
        VALUES
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+20 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'DR', 'Draft', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+21 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'WA', 'Pending Approval', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+22 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'AP', 'Approved', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+23 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'SC', 'Scheduled', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+24 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'IP', 'In Progress', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+25 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'CO', 'Completed', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+26 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'VO', 'Cancelled', v_ref_id, 'U');

        RAISE NOTICE 'XX_MeetingStatus reference created with ID: %', v_ref_id;
    END IF;
END $$;

-- =============================================================================
-- AD_Reference: Participant Role
-- =============================================================================
DO $$
DECLARE
    v_ref_id NUMERIC(10);
BEGIN
    SELECT ad_reference_id INTO v_ref_id
    FROM adempiere.ad_reference
    WHERE name = 'XX_ParticipantRole' AND ad_client_id = 0;

    IF v_ref_id IS NULL THEN
        v_ref_id := (SELECT COALESCE(MAX(ad_reference_id), 1000000) + 30 FROM adempiere.ad_reference);

        INSERT INTO adempiere.ad_reference (
            ad_reference_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            name, description, validationtype, vformat, entitytype
        ) VALUES (
            v_ref_id, 0, 0, 'Y', NOW(), 0, NOW(), 0,
            'XX_ParticipantRole', 'MMS Participant Role', 'L', NULL, 'U'
        );

        INSERT INTO adempiere.ad_ref_list (ad_ref_list_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby, value, name, ad_reference_id, entitytype)
        VALUES
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+30 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'CHAIRPERSON', 'Chairperson', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+31 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'SECRETARY', 'Secretary', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+32 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'PARTICIPANT', 'Participant', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+33 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'PRESENTER', 'Presenter', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+34 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'OBSERVER', 'Observer', v_ref_id, 'U');

        RAISE NOTICE 'XX_ParticipantRole reference created with ID: %', v_ref_id;
    END IF;
END $$;

-- =============================================================================
-- AD_Reference: Attendance Status
-- =============================================================================
DO $$
DECLARE
    v_ref_id NUMERIC(10);
BEGIN
    SELECT ad_reference_id INTO v_ref_id
    FROM adempiere.ad_reference
    WHERE name = 'XX_AttendanceStatus' AND ad_client_id = 0;

    IF v_ref_id IS NULL THEN
        v_ref_id := (SELECT COALESCE(MAX(ad_reference_id), 1000000) + 40 FROM adempiere.ad_reference);

        INSERT INTO adempiere.ad_reference (
            ad_reference_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            name, description, validationtype, vformat, entitytype
        ) VALUES (
            v_ref_id, 0, 0, 'Y', NOW(), 0, NOW(), 0,
            'XX_AttendanceStatus', 'MMS Attendance Status', 'L', NULL, 'U'
        );

        INSERT INTO adempiere.ad_ref_list (ad_ref_list_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby, value, name, ad_reference_id, entitytype)
        VALUES
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+40 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'INVITED', 'Invited', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+41 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'ACCEPTED', 'Accepted', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+42 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'DECLINED', 'Declined', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+43 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'PRESENT', 'Present', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+44 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'ABSENT', 'Absent', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+45 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'LATE', 'Late', v_ref_id, 'U');

        RAISE NOTICE 'XX_AttendanceStatus reference created with ID: %', v_ref_id;
    END IF;
END $$;

-- =============================================================================
-- AD_Reference: Action/Decision Status
-- =============================================================================
DO $$
DECLARE
    v_ref_id NUMERIC(10);
BEGIN
    SELECT ad_reference_id INTO v_ref_id
    FROM adempiere.ad_reference
    WHERE name = 'XX_ActionStatus' AND ad_client_id = 0;

    IF v_ref_id IS NULL THEN
        v_ref_id := (SELECT COALESCE(MAX(ad_reference_id), 1000000) + 50 FROM adempiere.ad_reference);

        INSERT INTO adempiere.ad_reference (
            ad_reference_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            name, description, validationtype, vformat, entitytype
        ) VALUES (
            v_ref_id, 0, 0, 'Y', NOW(), 0, NOW(), 0,
            'XX_ActionStatus', 'MMS Action/Decision Status', 'L', NULL, 'U'
        );

        INSERT INTO adempiere.ad_ref_list (ad_ref_list_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby, value, name, ad_reference_id, entitytype)
        VALUES
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+50 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'OPEN', 'Open', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+51 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'IN_PROGRESS', 'In Progress', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+52 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'COMPLETED', 'Completed', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+53 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'OVERDUE', 'Overdue', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+54 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'CANCELLED', 'Cancelled', v_ref_id, 'U');

        RAISE NOTICE 'XX_ActionStatus reference created with ID: %', v_ref_id;
    END IF;
END $$;

-- =============================================================================
-- AD_Reference: Priority (reuse iDempiere standard if exists, else create)
-- iDempiere standard priority: 1=Urgent, 3=High, 5=Medium, 7=Low, 9=Minor
-- We use standard AD_Reference ID 154 (Priority) if available
-- =============================================================================
-- Check: SELECT ad_reference_id, name FROM adempiere.ad_reference WHERE name ILIKE '%priority%';

-- =============================================================================
-- AD_Reference: Minutes Status
-- =============================================================================
DO $$
DECLARE
    v_ref_id NUMERIC(10);
BEGIN
    SELECT ad_reference_id INTO v_ref_id
    FROM adempiere.ad_reference
    WHERE name = 'XX_MinutesStatus' AND ad_client_id = 0;

    IF v_ref_id IS NULL THEN
        v_ref_id := (SELECT COALESCE(MAX(ad_reference_id), 1000000) + 60 FROM adempiere.ad_reference);

        INSERT INTO adempiere.ad_reference (
            ad_reference_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby,
            name, description, validationtype, vformat, entitytype
        ) VALUES (
            v_ref_id, 0, 0, 'Y', NOW(), 0, NOW(), 0,
            'XX_MinutesStatus', 'MMS Minutes Status', 'L', NULL, 'U'
        );

        INSERT INTO adempiere.ad_ref_list (ad_ref_list_id, ad_client_id, ad_org_id, isactive,
            created, createdby, updated, updatedby, value, name, ad_reference_id, entitytype)
        VALUES
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+60 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'DRAFT', 'Draft', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+61 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'SUBMITTED', 'Submitted', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+62 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'APPROVED', 'Approved', v_ref_id, 'U'),
            ((SELECT COALESCE(MAX(ad_ref_list_id),1000000)+63 FROM adempiere.ad_ref_list), 0, 0, 'Y', NOW(), 0, NOW(), 0, 'REJECTED', 'Rejected', v_ref_id, 'U');

        RAISE NOTICE 'XX_MinutesStatus reference created with ID: %', v_ref_id;
    END IF;
END $$;

-- =============================================================================
-- Insert some default Meeting Types
-- =============================================================================
INSERT INTO adempiere.xx_meetingtype (
    xx_meetingtype_id, ad_client_id, ad_org_id, isactive,
    created, createdby, updated, updatedby,
    name, description, isdefault
)
SELECT
    nextval('adempiere.xx_meetingtype_seq'),
    0, 0, 'Y', NOW(), 0, NOW(), 0,
    t.name, t.description, t.isdefault
FROM (VALUES
    ('Management Meeting',  'Regular Management Team Meeting',        'Y'),
    ('Board Meeting',       'Board of Directors Meeting',             'N'),
    ('Monthly Review',      'Monthly Performance Review Meeting',     'N'),
    ('Weekly Review',       'Weekly Status Review Meeting',           'N'),
    ('Project Review',      'Project Status Review Meeting',          'N'),
    ('Department Meeting',  'Department Level Meeting',               'N'),
    ('Emergency Meeting',   'Urgent / Emergency Meeting',             'N'),
    ('Client Meeting',      'Meeting with External Clients',          'N'),
    ('Supplier Meeting',    'Meeting with Suppliers/Vendors',         'N')
) AS t(name, description, isdefault)
WHERE NOT EXISTS (
    SELECT 1 FROM adempiere.xx_meetingtype WHERE name = t.name AND ad_client_id = 0
);

SELECT 'MMS Sequences, References, and Default Data created!' AS status;
