package org.idempiere.mms.factory;

import org.adempiere.base.IModelFactory;
import org.compiere.model.PO;
import org.idempiere.mms.model.MManagementMeeting;
import org.idempiere.mms.model.MMeetingAction;
import org.idempiere.mms.model.MMeetingAgenda;
import org.idempiere.mms.model.MMeetingDecision;
import org.idempiere.mms.model.MMeetingMinutes;
import org.idempiere.mms.model.MMeetingParticipant;
import org.idempiere.mms.model.MMeetingRoom;
import org.idempiere.mms.model.MMeetingType;

import java.sql.ResultSet;
import java.util.Properties;

/**
 * MMS Model Factory — maps table names to model classes.
 * iDempiere uses this to instantiate the correct PO subclass.
 */
public class MMSModelFactory implements IModelFactory {

    @Override
    public Class<?> getClass(String tableName) {
        return switch (tableName) {
            case MManagementMeeting.Table_Name   -> MManagementMeeting.class;
            case MMeetingParticipant.Table_Name  -> MMeetingParticipant.class;
            case MMeetingAgenda.Table_Name       -> MMeetingAgenda.class;
            case MMeetingMinutes.Table_Name      -> MMeetingMinutes.class;
            case MMeetingDecision.Table_Name     -> MMeetingDecision.class;
            case MMeetingAction.Table_Name       -> MMeetingAction.class;
            case MMeetingRoom.Table_Name         -> MMeetingRoom.class;
            case MMeetingType.Table_Name         -> MMeetingType.class;
            default                              -> null;
        };
    }

    @Override
    public PO getPO(String tableName, int Record_ID, String trxName) {
        Properties ctx = org.compiere.util.Env.getCtx();
        return switch (tableName) {
            case MManagementMeeting.Table_Name   -> new MManagementMeeting(ctx, Record_ID, trxName);
            case MMeetingParticipant.Table_Name  -> new MMeetingParticipant(ctx, Record_ID, trxName);
            case MMeetingAgenda.Table_Name       -> new MMeetingAgenda(ctx, Record_ID, trxName);
            case MMeetingMinutes.Table_Name      -> new MMeetingMinutes(ctx, Record_ID, trxName);
            case MMeetingDecision.Table_Name     -> new MMeetingDecision(ctx, Record_ID, trxName);
            case MMeetingAction.Table_Name       -> new MMeetingAction(ctx, Record_ID, trxName);
            case MMeetingRoom.Table_Name         -> new MMeetingRoom(ctx, Record_ID, trxName);
            case MMeetingType.Table_Name         -> new MMeetingType(ctx, Record_ID, trxName);
            default                              -> null;
        };
    }

    @Override
    public PO getPO(String tableName, ResultSet rs, String trxName) {
        Properties ctx = org.compiere.util.Env.getCtx();
        return switch (tableName) {
            case MManagementMeeting.Table_Name   -> new MManagementMeeting(ctx, rs, trxName);
            case MMeetingParticipant.Table_Name  -> new MMeetingParticipant(ctx, rs, trxName);
            case MMeetingAgenda.Table_Name       -> new MMeetingAgenda(ctx, rs, trxName);
            case MMeetingMinutes.Table_Name      -> new MMeetingMinutes(ctx, rs, trxName);
            case MMeetingDecision.Table_Name     -> new MMeetingDecision(ctx, rs, trxName);
            case MMeetingAction.Table_Name       -> new MMeetingAction(ctx, rs, trxName);
            case MMeetingRoom.Table_Name         -> new MMeetingRoom(ctx, rs, trxName);
            case MMeetingType.Table_Name         -> new MMeetingType(ctx, rs, trxName);
            default                              -> null;
        };
    }
}
