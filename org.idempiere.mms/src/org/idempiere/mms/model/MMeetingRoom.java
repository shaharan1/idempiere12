package org.idempiere.mms.model;

import java.sql.ResultSet;
import java.util.Properties;

import org.compiere.model.PO;
import org.compiere.model.POInfo;

/**
 * MMeetingRoom — Physical Meeting Room Setup
 * Table: XX_MeetingRoom
 */
public class MMeetingRoom extends PO {

    private static final long serialVersionUID = 1L;

    public static final String Table_Name = "XX_MeetingRoom";
    public static final int    Table_ID   = 0;

    public MMeetingRoom(Properties ctx, int XX_MeetingRoom_ID, String trxName) {
        super(ctx, XX_MeetingRoom_ID, trxName);
    }
    public MMeetingRoom(Properties ctx, ResultSet rs, String trxName) {
        super(ctx, rs, trxName);
    }

    @Override
    protected int get_AccessLevel() { return 3; }

    @Override
    public POInfo initPO(Properties ctx) { return POInfo.getPOInfo(ctx, Table_ID); }

    public int getXX_MeetingRoom_ID() { return get_ID(); }

    public String getName() { return (String) get_Value("Name"); }
    public void setName(String name) { set_Value("Name", name); }

    public String getLocation() { return (String) get_Value("Location"); }
    public void setLocation(String loc) { set_Value("Location", loc); }

    public int getCapacity() {
        Integer ii = (Integer) get_Value("Capacity");
        return (ii != null) ? ii : 0;
    }
    public void setCapacity(int cap) { set_Value("Capacity", cap); }

    public String getDescription() { return (String) get_Value("Description"); }
    public void setDescription(String desc) { set_Value("Description", desc); }

    public boolean isAvailable() {
        Object oa = get_Value("IsAvailable");
        return (oa != null && oa.equals("Y"));
    }
    public void setIsAvailable(boolean available) { set_Value("IsAvailable", available ? "Y" : "N"); }

    public String getEquipment() { return (String) get_Value("Equipment"); }
    public void setEquipment(String eq) { set_Value("Equipment", eq); }

    public String getNotes() { return (String) get_Value("Notes"); }
    public void setNotes(String notes) { set_Value("Notes", notes); }

    @Override
    public String toString() {
        return "MMeetingRoom[" + get_ID() + " - " + getName() + "]";
    }
}
