package org.idempiere.mms.model;

import java.sql.ResultSet;
import java.util.Properties;

import org.compiere.model.PO;
import org.compiere.model.POInfo;

/**
 * MMeetingType — Meeting Type Setup (Board, Monthly, etc.)
 * Table: XX_MeetingType
 */
public class MMeetingType extends PO {

    private static final long serialVersionUID = 1L;

    public static final String Table_Name = "XX_MeetingType";
    public static final int    Table_ID   = 0;

    public MMeetingType(Properties ctx, int XX_MeetingType_ID, String trxName) {
        super(ctx, XX_MeetingType_ID, trxName);
    }
    public MMeetingType(Properties ctx, ResultSet rs, String trxName) {
        super(ctx, rs, trxName);
    }

    @Override
    protected int get_AccessLevel() { return 3; }

    @Override
    public POInfo initPO(Properties ctx) { return POInfo.getPOInfo(ctx, Table_ID); }

    public int getXX_MeetingType_ID() { return get_ID(); }

    public String getName() { return (String) get_Value("Name"); }
    public void setName(String name) { set_Value("Name", name); }

    public String getDescription() { return (String) get_Value("Description"); }
    public void setDescription(String desc) { set_Value("Description", desc); }

    public boolean isDefault() {
        Object oa = get_Value("IsDefault");
        return (oa != null && oa.equals("Y"));
    }
    public void setIsDefault(boolean def) { set_Value("IsDefault", def ? "Y" : "N"); }

    @Override
    public String toString() {
        return "MMeetingType[" + get_ID() + " - " + getName() + "]";
    }
}
