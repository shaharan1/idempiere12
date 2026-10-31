-- =============================================================================
-- Management Meeting System (MMS) - Phase 1 Database Schema
-- iDempiere 12 | PostgreSQL
-- File: 01_create_tables.sql
-- Run as: psql -U adempiere -d idempiere -f 01_create_tables.sql
-- =============================================================================

SET search_path TO adempiere, public;

-- =============================================================================
-- TABLE 1: XX_MeetingType (Setup Table - no parent dependency)
-- =============================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_meetingtype (
    xx_meetingtype_id        NUMERIC(10)     NOT NULL,
    ad_client_id             NUMERIC(10)     NOT NULL DEFAULT 0,
    ad_org_id                NUMERIC(10)     NOT NULL DEFAULT 0,
    isactive                 CHAR(1)         NOT NULL DEFAULT 'Y',
    created                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    createdby                NUMERIC(10)     NOT NULL DEFAULT 0,
    updated                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updatedby                NUMERIC(10)     NOT NULL DEFAULT 0,
    -- Business Columns
    name                     VARCHAR(60)     NOT NULL,
    description              VARCHAR(255),
    isdefault                CHAR(1)         NOT NULL DEFAULT 'N',
    CONSTRAINT pk_xx_meetingtype PRIMARY KEY (xx_meetingtype_id),
    CONSTRAINT fk_xx_meetingtype_client FOREIGN KEY (ad_client_id)
        REFERENCES adempiere.ad_client (ad_client_id),
    CONSTRAINT fk_xx_meetingtype_org FOREIGN KEY (ad_org_id)
        REFERENCES adempiere.ad_org (ad_org_id),
    CONSTRAINT ch_xx_meetingtype_isactive CHECK (isactive IN ('Y','N')),
    CONSTRAINT ch_xx_meetingtype_isdefault CHECK (isdefault IN ('Y','N'))
);

COMMENT ON TABLE adempiere.xx_meetingtype IS 'MMS: Meeting Type (Board Meeting, Monthly Review, etc.)';

-- =============================================================================
-- TABLE 2: XX_MeetingRoom (Setup Table)
-- =============================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_meetingroom (
    xx_meetingroom_id        NUMERIC(10)     NOT NULL,
    ad_client_id             NUMERIC(10)     NOT NULL DEFAULT 0,
    ad_org_id                NUMERIC(10)     NOT NULL DEFAULT 0,
    isactive                 CHAR(1)         NOT NULL DEFAULT 'Y',
    created                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    createdby                NUMERIC(10)     NOT NULL DEFAULT 0,
    updated                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updatedby                NUMERIC(10)     NOT NULL DEFAULT 0,
    -- Business Columns
    name                     VARCHAR(60)     NOT NULL,
    location                 VARCHAR(255),
    capacity                 NUMERIC(10)     DEFAULT 0,
    description              VARCHAR(500),
    isavailable              CHAR(1)         NOT NULL DEFAULT 'Y',
    equipment                VARCHAR(500),
    notes                    VARCHAR(2000),
    CONSTRAINT pk_xx_meetingroom PRIMARY KEY (xx_meetingroom_id),
    CONSTRAINT fk_xx_meetingroom_client FOREIGN KEY (ad_client_id)
        REFERENCES adempiere.ad_client (ad_client_id),
    CONSTRAINT fk_xx_meetingroom_org FOREIGN KEY (ad_org_id)
        REFERENCES adempiere.ad_org (ad_org_id),
    CONSTRAINT ch_xx_meetingroom_isactive CHECK (isactive IN ('Y','N')),
    CONSTRAINT ch_xx_meetingroom_isavailable CHECK (isavailable IN ('Y','N'))
);

COMMENT ON TABLE adempiere.xx_meetingroom IS 'MMS: Physical Meeting Room';

-- =============================================================================
-- TABLE 3: XX_ManagementMeeting (Main Header Table)
-- =============================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_managementmeeting (
    xx_managementmeeting_id  NUMERIC(10)     NOT NULL,
    ad_client_id             NUMERIC(10)     NOT NULL DEFAULT 0,
    ad_org_id                NUMERIC(10)     NOT NULL DEFAULT 0,
    isactive                 CHAR(1)         NOT NULL DEFAULT 'Y',
    created                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    createdby                NUMERIC(10)     NOT NULL DEFAULT 0,
    updated                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updatedby                NUMERIC(10)     NOT NULL DEFAULT 0,
    -- Document
    documentno               VARCHAR(30)     NOT NULL,
    -- Meeting Details
    subject                  VARCHAR(255)    NOT NULL,
    meetingdate              TIMESTAMP       NOT NULL,
    starttime                TIMESTAMP       NOT NULL,
    endtime                  TIMESTAMP       NOT NULL,
    -- Type & Classification
    xx_meetingtype_id        NUMERIC(10),
    priority                 CHAR(1)         NOT NULL DEFAULT '5',  -- 1=Urgent,3=High,5=Medium,7=Low
    -- Status & Mode
    docstatus                VARCHAR(2)      NOT NULL DEFAULT 'DR', -- DR=Draft, IP=InProgress, CO=Completed, VO=Void
    meetingmode              VARCHAR(20)     NOT NULL DEFAULT 'PHYSICAL',   -- PHYSICAL,ONLINE,HYBRID
    meetingplatform          VARCHAR(20)     NOT NULL DEFAULT 'INTERNAL',   -- INTERNAL,GOOGLE_MEET,ZOOM,OTHER
    -- Location
    location                 VARCHAR(255),
    xx_meetingroom_id        NUMERIC(10),
    -- Online Meeting
    meetinglink              VARCHAR(1000),
    externalmeetingid        VARCHAR(255),
    meetingpassword          VARCHAR(255),
    calendareventid          VARCHAR(500),
    -- People
    chairperson_id           NUMERIC(10),
    secretary_id             NUMERIC(10),
    -- Description
    description              VARCHAR(2000),
    notes                    VARCHAR(2000),
    CONSTRAINT pk_xx_managementmeeting PRIMARY KEY (xx_managementmeeting_id),
    CONSTRAINT fk_xx_meeting_client FOREIGN KEY (ad_client_id)
        REFERENCES adempiere.ad_client (ad_client_id),
    CONSTRAINT fk_xx_meeting_org FOREIGN KEY (ad_org_id)
        REFERENCES adempiere.ad_org (ad_org_id),
    CONSTRAINT fk_xx_meeting_type FOREIGN KEY (xx_meetingtype_id)
        REFERENCES adempiere.xx_meetingtype (xx_meetingtype_id),
    CONSTRAINT fk_xx_meeting_room FOREIGN KEY (xx_meetingroom_id)
        REFERENCES adempiere.xx_meetingroom (xx_meetingroom_id),
    CONSTRAINT fk_xx_meeting_chair FOREIGN KEY (chairperson_id)
        REFERENCES adempiere.ad_user (ad_user_id),
    CONSTRAINT fk_xx_meeting_secretary FOREIGN KEY (secretary_id)
        REFERENCES adempiere.ad_user (ad_user_id),
    CONSTRAINT ch_xx_meeting_isactive CHECK (isactive IN ('Y','N')),
    CONSTRAINT ch_xx_meeting_mode CHECK (meetingmode IN ('PHYSICAL','ONLINE','HYBRID')),
    CONSTRAINT ch_xx_meeting_platform CHECK (meetingplatform IN ('INTERNAL','GOOGLE_MEET','ZOOM','OTHER')),
    CONSTRAINT ch_xx_meeting_priority CHECK (priority IN ('1','3','5','7')),
    CONSTRAINT ch_xx_meeting_docstatus CHECK (docstatus IN ('DR','IP','WA','AP','SC','CO','VO'))
);

COMMENT ON TABLE adempiere.xx_managementmeeting IS 'MMS: Main Management Meeting Header Record';
COMMENT ON COLUMN adempiere.xx_managementmeeting.priority IS '1=Urgent, 3=High, 5=Medium, 7=Low';
COMMENT ON COLUMN adempiere.xx_managementmeeting.docstatus IS 'DR=Draft, WA=Pending Approval, AP=Approved, SC=Scheduled, IP=In Progress, CO=Completed, VO=Void/Cancelled';

-- =============================================================================
-- TABLE 4: XX_MeetingParticipant (Child of XX_ManagementMeeting)
-- =============================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_meetingparticipant (
    xx_meetingparticipant_id NUMERIC(10)     NOT NULL,
    ad_client_id             NUMERIC(10)     NOT NULL DEFAULT 0,
    ad_org_id                NUMERIC(10)     NOT NULL DEFAULT 0,
    isactive                 CHAR(1)         NOT NULL DEFAULT 'Y',
    created                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    createdby                NUMERIC(10)     NOT NULL DEFAULT 0,
    updated                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updatedby                NUMERIC(10)     NOT NULL DEFAULT 0,
    -- Parent
    xx_managementmeeting_id  NUMERIC(10)     NOT NULL,
    -- Business
    ad_user_id               NUMERIC(10)     NOT NULL,
    participantrole          VARCHAR(20)     NOT NULL DEFAULT 'PARTICIPANT',
    attendancestatus         VARCHAR(20)     NOT NULL DEFAULT 'INVITED',
    responsestatus           VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    invitationstatus         VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    remarks                  VARCHAR(500),
    CONSTRAINT pk_xx_meetingparticipant PRIMARY KEY (xx_meetingparticipant_id),
    CONSTRAINT fk_xx_part_meeting FOREIGN KEY (xx_managementmeeting_id)
        REFERENCES adempiere.xx_managementmeeting (xx_managementmeeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_xx_part_client FOREIGN KEY (ad_client_id)
        REFERENCES adempiere.ad_client (ad_client_id),
    CONSTRAINT fk_xx_part_user FOREIGN KEY (ad_user_id)
        REFERENCES adempiere.ad_user (ad_user_id),
    CONSTRAINT ch_xx_part_isactive CHECK (isactive IN ('Y','N')),
    CONSTRAINT ch_xx_part_role CHECK (participantrole IN ('CHAIRPERSON','SECRETARY','PARTICIPANT','PRESENTER','OBSERVER')),
    CONSTRAINT ch_xx_part_attendance CHECK (attendancestatus IN ('INVITED','ACCEPTED','DECLINED','PRESENT','ABSENT','LATE')),
    CONSTRAINT ch_xx_part_response CHECK (responsestatus IN ('PENDING','ACCEPTED','DECLINED')),
    CONSTRAINT ch_xx_part_invitation CHECK (invitationstatus IN ('PENDING','SENT','FAILED'))
);

COMMENT ON TABLE adempiere.xx_meetingparticipant IS 'MMS: Meeting Participant (child of XX_ManagementMeeting)';

-- =============================================================================
-- TABLE 5: XX_MeetingAgenda (Child of XX_ManagementMeeting)
-- =============================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_meetingagenda (
    xx_meetingagenda_id      NUMERIC(10)     NOT NULL,
    ad_client_id             NUMERIC(10)     NOT NULL DEFAULT 0,
    ad_org_id                NUMERIC(10)     NOT NULL DEFAULT 0,
    isactive                 CHAR(1)         NOT NULL DEFAULT 'Y',
    created                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    createdby                NUMERIC(10)     NOT NULL DEFAULT 0,
    updated                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updatedby                NUMERIC(10)     NOT NULL DEFAULT 0,
    -- Parent
    xx_managementmeeting_id  NUMERIC(10)     NOT NULL,
    -- Business
    lineno                   NUMERIC(10)     NOT NULL DEFAULT 10,
    agendatitle              VARCHAR(255)    NOT NULL,
    description              VARCHAR(2000),
    presentedby_id           NUMERIC(10),
    allocatedminutes         NUMERIC(10)     DEFAULT 0,
    priority                 CHAR(1)         NOT NULL DEFAULT '5',
    status                   VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    remarks                  VARCHAR(500),
    CONSTRAINT pk_xx_meetingagenda PRIMARY KEY (xx_meetingagenda_id),
    CONSTRAINT fk_xx_agenda_meeting FOREIGN KEY (xx_managementmeeting_id)
        REFERENCES adempiere.xx_managementmeeting (xx_managementmeeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_xx_agenda_client FOREIGN KEY (ad_client_id)
        REFERENCES adempiere.ad_client (ad_client_id),
    CONSTRAINT fk_xx_agenda_presenter FOREIGN KEY (presentedby_id)
        REFERENCES adempiere.ad_user (ad_user_id),
    CONSTRAINT ch_xx_agenda_isactive CHECK (isactive IN ('Y','N')),
    CONSTRAINT ch_xx_agenda_status CHECK (status IN ('OPEN','DISCUSSED','DEFERRED','COMPLETED')),
    CONSTRAINT ch_xx_agenda_priority CHECK (priority IN ('1','3','5','7'))
);

COMMENT ON TABLE adempiere.xx_meetingagenda IS 'MMS: Meeting Agenda Items (child of XX_ManagementMeeting)';

-- =============================================================================
-- TABLE 6: XX_MeetingMinutes (Child of XX_ManagementMeeting)
-- =============================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_meetingminutes (
    xx_meetingminutes_id     NUMERIC(10)     NOT NULL,
    ad_client_id             NUMERIC(10)     NOT NULL DEFAULT 0,
    ad_org_id                NUMERIC(10)     NOT NULL DEFAULT 0,
    isactive                 CHAR(1)         NOT NULL DEFAULT 'Y',
    created                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    createdby                NUMERIC(10)     NOT NULL DEFAULT 0,
    updated                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updatedby                NUMERIC(10)     NOT NULL DEFAULT 0,
    -- Parent
    xx_managementmeeting_id  NUMERIC(10)     NOT NULL,
    -- Business
    summary                  VARCHAR(4000),
    discussion               VARCHAR(4000),
    conclusion               VARCHAR(4000),
    preparedby_id            NUMERIC(10),
    prepareddate             TIMESTAMP,
    approvedby_id            NUMERIC(10),
    approvaldate             TIMESTAMP,
    status                   VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    notes                    VARCHAR(2000),
    CONSTRAINT pk_xx_meetingminutes PRIMARY KEY (xx_meetingminutes_id),
    CONSTRAINT fk_xx_minutes_meeting FOREIGN KEY (xx_managementmeeting_id)
        REFERENCES adempiere.xx_managementmeeting (xx_managementmeeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_xx_minutes_client FOREIGN KEY (ad_client_id)
        REFERENCES adempiere.ad_client (ad_client_id),
    CONSTRAINT fk_xx_minutes_preparedby FOREIGN KEY (preparedby_id)
        REFERENCES adempiere.ad_user (ad_user_id),
    CONSTRAINT fk_xx_minutes_approvedby FOREIGN KEY (approvedby_id)
        REFERENCES adempiere.ad_user (ad_user_id),
    CONSTRAINT ch_xx_minutes_isactive CHECK (isactive IN ('Y','N')),
    CONSTRAINT ch_xx_minutes_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED'))
);

COMMENT ON TABLE adempiere.xx_meetingminutes IS 'MMS: Meeting Minutes (child of XX_ManagementMeeting)';

-- =============================================================================
-- TABLE 7: XX_MeetingDecision (Child of XX_ManagementMeeting)
-- =============================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_meetingdecision (
    xx_meetingdecision_id    NUMERIC(10)     NOT NULL,
    ad_client_id             NUMERIC(10)     NOT NULL DEFAULT 0,
    ad_org_id                NUMERIC(10)     NOT NULL DEFAULT 0,
    isactive                 CHAR(1)         NOT NULL DEFAULT 'Y',
    created                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    createdby                NUMERIC(10)     NOT NULL DEFAULT 0,
    updated                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updatedby                NUMERIC(10)     NOT NULL DEFAULT 0,
    -- Parent
    xx_managementmeeting_id  NUMERIC(10)     NOT NULL,
    xx_meetingagenda_id      NUMERIC(10),
    -- Business
    decisionno               VARCHAR(30)     NOT NULL,
    decisiontitle            VARCHAR(255)    NOT NULL,
    decisiondescription      VARCHAR(4000),
    responsibleuser_id       NUMERIC(10),
    duedate                  TIMESTAMP,
    priority                 CHAR(1)         NOT NULL DEFAULT '5',
    status                   VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    remarks                  VARCHAR(500),
    CONSTRAINT pk_xx_meetingdecision PRIMARY KEY (xx_meetingdecision_id),
    CONSTRAINT fk_xx_dec_meeting FOREIGN KEY (xx_managementmeeting_id)
        REFERENCES adempiere.xx_managementmeeting (xx_managementmeeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_xx_dec_agenda FOREIGN KEY (xx_meetingagenda_id)
        REFERENCES adempiere.xx_meetingagenda (xx_meetingagenda_id),
    CONSTRAINT fk_xx_dec_client FOREIGN KEY (ad_client_id)
        REFERENCES adempiere.ad_client (ad_client_id),
    CONSTRAINT fk_xx_dec_responsible FOREIGN KEY (responsibleuser_id)
        REFERENCES adempiere.ad_user (ad_user_id),
    CONSTRAINT ch_xx_dec_isactive CHECK (isactive IN ('Y','N')),
    CONSTRAINT ch_xx_dec_status CHECK (status IN ('OPEN','IN_PROGRESS','COMPLETED','CANCELLED')),
    CONSTRAINT ch_xx_dec_priority CHECK (priority IN ('1','3','5','7'))
);

COMMENT ON TABLE adempiere.xx_meetingdecision IS 'MMS: Meeting Decisions (child of XX_ManagementMeeting)';

-- =============================================================================
-- TABLE 8: XX_MeetingAction (Child of XX_ManagementMeeting)
-- =============================================================================
CREATE TABLE IF NOT EXISTS adempiere.xx_meetingaction (
    xx_meetingaction_id      NUMERIC(10)     NOT NULL,
    ad_client_id             NUMERIC(10)     NOT NULL DEFAULT 0,
    ad_org_id                NUMERIC(10)     NOT NULL DEFAULT 0,
    isactive                 CHAR(1)         NOT NULL DEFAULT 'Y',
    created                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    createdby                NUMERIC(10)     NOT NULL DEFAULT 0,
    updated                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updatedby                NUMERIC(10)     NOT NULL DEFAULT 0,
    -- Parent
    xx_managementmeeting_id  NUMERIC(10)     NOT NULL,
    xx_meetingagenda_id      NUMERIC(10),
    xx_meetingdecision_id    NUMERIC(10),
    -- Business
    task                     VARCHAR(255)    NOT NULL,
    description              VARCHAR(2000),
    responsibleuser_id       NUMERIC(10),
    priority                 CHAR(1)         NOT NULL DEFAULT '5',
    startdate                TIMESTAMP,
    duedate                  TIMESTAMP,
    completiondate           TIMESTAMP,
    status                   VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    progresspercent          NUMERIC(5,2)    DEFAULT 0,
    remarks                  VARCHAR(500),
    CONSTRAINT pk_xx_meetingaction PRIMARY KEY (xx_meetingaction_id),
    CONSTRAINT fk_xx_act_meeting FOREIGN KEY (xx_managementmeeting_id)
        REFERENCES adempiere.xx_managementmeeting (xx_managementmeeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_xx_act_agenda FOREIGN KEY (xx_meetingagenda_id)
        REFERENCES adempiere.xx_meetingagenda (xx_meetingagenda_id),
    CONSTRAINT fk_xx_act_decision FOREIGN KEY (xx_meetingdecision_id)
        REFERENCES adempiere.xx_meetingdecision (xx_meetingdecision_id),
    CONSTRAINT fk_xx_act_client FOREIGN KEY (ad_client_id)
        REFERENCES adempiere.ad_client (ad_client_id),
    CONSTRAINT fk_xx_act_responsible FOREIGN KEY (responsibleuser_id)
        REFERENCES adempiere.ad_user (ad_user_id),
    CONSTRAINT ch_xx_act_isactive CHECK (isactive IN ('Y','N')),
    CONSTRAINT ch_xx_act_status CHECK (status IN ('OPEN','IN_PROGRESS','COMPLETED','OVERDUE','CANCELLED')),
    CONSTRAINT ch_xx_act_priority CHECK (priority IN ('1','3','5','7')),
    CONSTRAINT ch_xx_act_progress CHECK (progresspercent BETWEEN 0 AND 100)
);

COMMENT ON TABLE adempiere.xx_meetingaction IS 'MMS: Meeting Action Items (child of XX_ManagementMeeting)';

-- =============================================================================
-- Indexes for performance
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_xx_meeting_date ON adempiere.xx_managementmeeting (meetingdate);
CREATE INDEX IF NOT EXISTS idx_xx_meeting_status ON adempiere.xx_managementmeeting (docstatus);
CREATE INDEX IF NOT EXISTS idx_xx_meeting_client ON adempiere.xx_managementmeeting (ad_client_id);

CREATE INDEX IF NOT EXISTS idx_xx_part_meeting ON adempiere.xx_meetingparticipant (xx_managementmeeting_id);
CREATE INDEX IF NOT EXISTS idx_xx_part_user ON adempiere.xx_meetingparticipant (ad_user_id);

CREATE INDEX IF NOT EXISTS idx_xx_agenda_meeting ON adempiere.xx_meetingagenda (xx_managementmeeting_id);
CREATE INDEX IF NOT EXISTS idx_xx_agenda_lineno ON adempiere.xx_meetingagenda (xx_managementmeeting_id, lineno);

CREATE INDEX IF NOT EXISTS idx_xx_minutes_meeting ON adempiere.xx_meetingminutes (xx_managementmeeting_id);
CREATE INDEX IF NOT EXISTS idx_xx_dec_meeting ON adempiere.xx_meetingdecision (xx_managementmeeting_id);
CREATE INDEX IF NOT EXISTS idx_xx_dec_status ON adempiere.xx_meetingdecision (status);
CREATE INDEX IF NOT EXISTS idx_xx_act_meeting ON adempiere.xx_meetingaction (xx_managementmeeting_id);
CREATE INDEX IF NOT EXISTS idx_xx_act_status ON adempiere.xx_meetingaction (status);
CREATE INDEX IF NOT EXISTS idx_xx_act_duedate ON adempiere.xx_meetingaction (duedate);

-- =============================================================================
-- View: Overdue Action Items (automatic overdue detection)
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
    CURRENT_TIMESTAMP - a.duedate AS overdue_duration,
    EXTRACT(DAY FROM (CURRENT_TIMESTAMP - a.duedate)) AS days_overdue,
    a.progresspercent,
    a.status,
    a.ad_client_id,
    a.ad_org_id
FROM adempiere.xx_meetingaction a
JOIN adempiere.xx_managementmeeting m ON m.xx_managementmeeting_id = a.xx_managementmeeting_id
LEFT JOIN adempiere.ad_user u ON u.ad_user_id = a.responsibleuser_id
WHERE a.status NOT IN ('COMPLETED','CANCELLED')
  AND a.duedate < CURRENT_TIMESTAMP
  AND a.isactive = 'Y';

COMMENT ON VIEW adempiere.xx_v_overdue_actions IS 'MMS: Automatically shows all overdue action items';

-- =============================================================================
-- View: Meeting Dashboard Summary
-- =============================================================================
CREATE OR REPLACE VIEW adempiere.xx_v_meeting_dashboard AS
SELECT
    ad_client_id,
    COUNT(*) FILTER (WHERE DATE(meetingdate) = CURRENT_DATE AND docstatus NOT IN ('VO'))
        AS today_meetings,
    COUNT(*) FILTER (WHERE meetingdate > CURRENT_TIMESTAMP AND docstatus IN ('AP','SC'))
        AS upcoming_meetings,
    COUNT(*) FILTER (WHERE docstatus = 'CO')
        AS completed_meetings,
    COUNT(*) FILTER (WHERE docstatus = 'VO')
        AS cancelled_meetings,
    COUNT(*) FILTER (WHERE docstatus = 'WA')
        AS pending_approval,
    COUNT(*) FILTER (WHERE docstatus = 'IP')
        AS in_progress_meetings
FROM adempiere.xx_managementmeeting
WHERE isactive = 'Y'
GROUP BY ad_client_id;

COMMENT ON VIEW adempiere.xx_v_meeting_dashboard IS 'MMS: Dashboard summary counts per client';

SELECT 'MMS Schema created successfully!' AS status;
