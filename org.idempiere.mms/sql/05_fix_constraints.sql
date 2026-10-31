-- =============================================================================
-- MMS Fix Script — সব সমস্যা একসাথে ঠিক করা
-- File: 05_fix_constraints.sql
--
-- সমস্যা ১: XX_ tables-এ DB-level Foreign Key আছে যা
--            iDempiere-এর "Synchronize Column" কাজ করতে দিচ্ছে না।
--            iDempiere নিজেই AD Dictionary দিয়ে FK manage করে।
--            তাই DB-level FK drop করতে হবে।
--
-- সমস্যা ২: AD_Form-এ ad_org_id = 1000000 আছে যেটা ad_org তে নেই।
--            0 দিয়ে ঠিক করতে হবে।
-- =============================================================================

SET search_path TO adempiere, public;

-- =============================================================================
-- STEP 1: সব XX_ tables থেকে Foreign Key constraints DROP করুন
--         (iDempiere-এ custom table-এ DB FK রাখতে হয় না)
-- =============================================================================

-- XX_ManagementMeeting FK drops
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS adclient_xxmanagementmeeting;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS adorg_xxmanagementmeeting;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS fk_meeting_chairperson;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS fk_meeting_secretary;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS fk_meeting_type;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS fk_meeting_room;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS pk_xx_managementmeeting;

-- XX_MeetingRoom FK drops
ALTER TABLE adempiere.xx_meetingroom DROP CONSTRAINT IF EXISTS fk_xx_meetingroom_client;
ALTER TABLE adempiere.xx_meetingroom DROP CONSTRAINT IF EXISTS fk_xx_meetingroom_org;
ALTER TABLE adempiere.xx_meetingroom DROP CONSTRAINT IF EXISTS adorg_xxmeetingroom;
ALTER TABLE adempiere.xx_meetingroom DROP CONSTRAINT IF EXISTS adclient_xxmeetingroom;
ALTER TABLE adempiere.xx_meetingroom DROP CONSTRAINT IF EXISTS pk_xx_meetingroom;

-- XX_MeetingType FK drops
ALTER TABLE adempiere.xx_meetingtype DROP CONSTRAINT IF EXISTS fk_xx_meetingtype_client;
ALTER TABLE adempiere.xx_meetingtype DROP CONSTRAINT IF EXISTS fk_xx_meetingtype_org;
ALTER TABLE adempiere.xx_meetingtype DROP CONSTRAINT IF EXISTS adorg_xxmeetingtype;
ALTER TABLE adempiere.xx_meetingtype DROP CONSTRAINT IF EXISTS adclient_xxmeetingtype;
ALTER TABLE adempiere.xx_meetingtype DROP CONSTRAINT IF EXISTS pk_xx_meetingtype;

-- XX_MeetingParticipant FK drops
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS fk_xx_part_meeting;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS fk_participant_meeting;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS fk_xx_part_client;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS fk_xx_part_user;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS fk_participant_user;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS adorg_xxmeetingparticipant;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS pk_xx_meetingparticipant;

-- XX_MeetingAgenda FK drops
ALTER TABLE adempiere.xx_meetingagenda DROP CONSTRAINT IF EXISTS fk_xx_agenda_meeting;
ALTER TABLE adempiere.xx_meetingagenda DROP CONSTRAINT IF EXISTS fk_agenda_meeting;
ALTER TABLE adempiere.xx_meetingagenda DROP CONSTRAINT IF EXISTS fk_xx_agenda_client;
ALTER TABLE adempiere.xx_meetingagenda DROP CONSTRAINT IF EXISTS fk_xx_agenda_presenter;
ALTER TABLE adempiere.xx_meetingagenda DROP CONSTRAINT IF EXISTS fk_agenda_presenter;
ALTER TABLE adempiere.xx_meetingagenda DROP CONSTRAINT IF EXISTS adorg_xxmeetingagenda;
ALTER TABLE adempiere.xx_meetingagenda DROP CONSTRAINT IF EXISTS pk_xx_meetingagenda;

-- XX_MeetingMinutes FK drops
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS fk_xx_minutes_meeting;
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS fk_minutes_meeting;
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS fk_xx_minutes_client;
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS fk_xx_minutes_preparedby;
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS fk_minutes_preparedby;
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS fk_xx_minutes_approvedby;
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS fk_minutes_approvedby;
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS adorg_xxmeetingminutes;
ALTER TABLE adempiere.xx_meetingminutes DROP CONSTRAINT IF EXISTS pk_xx_meetingminutes;

-- XX_MeetingDecision FK drops
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS fk_xx_dec_meeting;
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS fk_decision_meeting;
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS fk_xx_dec_agenda;
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS fk_decision_agenda;
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS fk_xx_dec_client;
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS fk_xx_dec_responsible;
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS fk_decision_user;
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS adorg_xxmeetingdecision;
ALTER TABLE adempiere.xx_meetingdecision DROP CONSTRAINT IF EXISTS pk_xx_meetingdecision;

-- XX_MeetingAction FK drops
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_xx_act_meeting;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_action_meeting;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_xx_act_agenda;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_action_agenda;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_xx_act_decision;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_action_decision;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_xx_act_client;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_xx_act_responsible;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS fk_action_user;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS adorg_xxmeetingaction;
ALTER TABLE adempiere.xx_meetingaction DROP CONSTRAINT IF EXISTS pk_xx_meetingaction;

-- CHECK constraints drop করুন (iDempiere নিজে manage করে)
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS ch_xx_meeting_isactive;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS ch_xx_meeting_mode;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS ch_xx_meeting_platform;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS ch_xx_meeting_priority;
ALTER TABLE adempiere.xx_managementmeeting DROP CONSTRAINT IF EXISTS ch_xx_meeting_docstatus;
ALTER TABLE adempiere.xx_meetingroom       DROP CONSTRAINT IF EXISTS ch_xx_meetingroom_isactive;
ALTER TABLE adempiere.xx_meetingroom       DROP CONSTRAINT IF EXISTS ch_xx_meetingroom_isavailable;
ALTER TABLE adempiere.xx_meetingtype       DROP CONSTRAINT IF EXISTS ch_xx_meetingtype_isactive;
ALTER TABLE adempiere.xx_meetingtype       DROP CONSTRAINT IF EXISTS ch_xx_meetingtype_isdefault;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS ch_xx_part_isactive;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS ch_xx_part_role;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS ch_xx_part_attendance;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS ch_xx_part_response;
ALTER TABLE adempiere.xx_meetingparticipant DROP CONSTRAINT IF EXISTS ch_xx_part_invitation;
ALTER TABLE adempiere.xx_meetingagenda     DROP CONSTRAINT IF EXISTS ch_xx_agenda_isactive;
ALTER TABLE adempiere.xx_meetingagenda     DROP CONSTRAINT IF EXISTS ch_xx_agenda_status;
ALTER TABLE adempiere.xx_meetingagenda     DROP CONSTRAINT IF EXISTS ch_xx_agenda_priority;
ALTER TABLE adempiere.xx_meetingminutes    DROP CONSTRAINT IF EXISTS ch_xx_minutes_isactive;
ALTER TABLE adempiere.xx_meetingminutes    DROP CONSTRAINT IF EXISTS ch_xx_minutes_status;
ALTER TABLE adempiere.xx_meetingdecision   DROP CONSTRAINT IF EXISTS ch_xx_dec_isactive;
ALTER TABLE adempiere.xx_meetingdecision   DROP CONSTRAINT IF EXISTS ch_xx_dec_status;
ALTER TABLE adempiere.xx_meetingdecision   DROP CONSTRAINT IF EXISTS ch_xx_dec_priority;
ALTER TABLE adempiere.xx_meetingaction     DROP CONSTRAINT IF EXISTS ch_xx_act_isactive;
ALTER TABLE adempiere.xx_meetingaction     DROP CONSTRAINT IF EXISTS ch_xx_act_status;
ALTER TABLE adempiere.xx_meetingaction     DROP CONSTRAINT IF EXISTS ch_xx_act_priority;
ALTER TABLE adempiere.xx_meetingaction     DROP CONSTRAINT IF EXISTS ch_xx_act_progress;

SELECT 'All FK and CHECK constraints dropped successfully!' AS status;

-- =============================================================================
-- STEP 2: Primary Key কে iDempiere standard নামে রাখুন
-- =============================================================================
ALTER TABLE adempiere.xx_managementmeeting
    ADD CONSTRAINT xx_managementmeeting_key PRIMARY KEY (xx_managementmeeting_id);

ALTER TABLE adempiere.xx_meetingroom
    ADD CONSTRAINT xx_meetingroom_key PRIMARY KEY (xx_meetingroom_id);

ALTER TABLE adempiere.xx_meetingtype
    ADD CONSTRAINT xx_meetingtype_key PRIMARY KEY (xx_meetingtype_id);

ALTER TABLE adempiere.xx_meetingparticipant
    ADD CONSTRAINT xx_meetingparticipant_key PRIMARY KEY (xx_meetingparticipant_id);

ALTER TABLE adempiere.xx_meetingagenda
    ADD CONSTRAINT xx_meetingagenda_key PRIMARY KEY (xx_meetingagenda_id);

ALTER TABLE adempiere.xx_meetingminutes
    ADD CONSTRAINT xx_meetingminutes_key PRIMARY KEY (xx_meetingminutes_id);

ALTER TABLE adempiere.xx_meetingdecision
    ADD CONSTRAINT xx_meetingdecision_key PRIMARY KEY (xx_meetingdecision_id);

ALTER TABLE adempiere.xx_meetingaction
    ADD CONSTRAINT xx_meetingaction_key PRIMARY KEY (xx_meetingaction_id);

SELECT 'Primary Keys re-added!' AS status;

-- =============================================================================
-- STEP 3: AD_Table records ঠিক করুন (ad_org_id = 0 করুন)
-- =============================================================================
UPDATE adempiere.ad_table
SET ad_org_id = 0, ad_client_id = 0
WHERE tablename IN (
    'XX_ManagementMeeting', 'XX_MeetingParticipant', 'XX_MeetingAgenda',
    'XX_MeetingMinutes', 'XX_MeetingDecision', 'XX_MeetingAction',
    'XX_MeetingRoom', 'XX_MeetingType'
);

SELECT 'AD_Table org/client fixed!' AS status;

-- =============================================================================
-- STEP 4: AD_Form fix করুন (Menu lookup-এ পাওয়া যাচ্ছিল না)
-- =============================================================================
UPDATE adempiere.ad_form
SET ad_org_id = 0, ad_client_id = 0,
    isactive = 'Y'
WHERE classname = 'org.idempiere.mms.form.WMeetingDashboard';

-- UUID যোগ করুন যদি না থাকে
UPDATE adempiere.ad_form
SET ad_form_uu = gen_random_uuid()::text
WHERE classname = 'org.idempiere.mms.form.WMeetingDashboard'
  AND (ad_form_uu IS NULL OR ad_form_uu = '');

SELECT 'AD_Form fixed!' AS status;

-- =============================================================================
-- STEP 5: AD_Column records fix (যদি কোনো ভুল org_id থাকে)
-- =============================================================================
UPDATE adempiere.ad_column
SET ad_org_id = 0, ad_client_id = 0
WHERE ad_table_id IN (
    SELECT ad_table_id FROM adempiere.ad_table
    WHERE tablename IN (
        'XX_ManagementMeeting', 'XX_MeetingParticipant', 'XX_MeetingAgenda',
        'XX_MeetingMinutes', 'XX_MeetingDecision', 'XX_MeetingAction',
        'XX_MeetingRoom', 'XX_MeetingType'
    )
);

SELECT 'AD_Column org/client fixed!' AS status;

-- =============================================================================
-- STEP 6: Verify — সব ঠিক আছে কিনা দেখুন
-- =============================================================================
SELECT
    t.tablename,
    t.ad_table_id,
    COUNT(c.ad_column_id) AS column_count
FROM adempiere.ad_table t
LEFT JOIN adempiere.ad_column c ON c.ad_table_id = t.ad_table_id
WHERE t.tablename IN (
    'XX_ManagementMeeting', 'XX_MeetingParticipant', 'XX_MeetingAgenda',
    'XX_MeetingMinutes', 'XX_MeetingDecision', 'XX_MeetingAction',
    'XX_MeetingRoom', 'XX_MeetingType'
)
GROUP BY t.tablename, t.ad_table_id
ORDER BY t.tablename;

-- Check remaining constraints
SELECT table_name, constraint_name, constraint_type
FROM information_schema.table_constraints
WHERE table_schema = 'adempiere'
  AND table_name LIKE 'xx_%'
  AND table_name NOT LIKE 'xx_v_%'
  AND constraint_type = 'FOREIGN KEY'
ORDER BY table_name;

SELECT '=== Fix Complete! Now go back to iDempiere and Synchronize Column ===' AS final_status;
