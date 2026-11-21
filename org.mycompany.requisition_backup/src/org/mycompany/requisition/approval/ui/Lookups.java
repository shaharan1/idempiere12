package org.mycompany.requisition.approval.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Lookup lists for the approval window, all read from the iDempiere dictionary.
 * <p>
 * They live in one place because every section needs the same handful of them and
 * because they are the part of the form most likely to be wrong quietly: a
 * dropdown that silently returns nothing looks like an empty configuration, not
 * like a broken lookup.
 *
 * <p>
 * Every method returns {@code id|name} rows, the shape
 * {@link ConfigListPanel#fill(org.zkoss.zul.Combobox, List, boolean)} consumes, and
 * every method returns an empty list rather than raising when the query fails.
 * A lookup that throws would take the whole window down for a reason the user
 * cannot act on.
 */
final class Lookups {

	private Lookups() {
		// helper class
	}

	/** Active users of the tenant, for an approver or delegate picker. */
	static List<String> users(int clientId) {
		return query("SELECT AD_User_ID, Value, Name FROM AD_User WHERE IsActive='Y' AND AD_Client_ID IN (0,?)"
				+ " ORDER BY Name", clientId);
	}

	/** Active roles of the tenant, for a responsible role picker. */
	static List<String> roles(int clientId) {
		return query("SELECT AD_Role_ID, Name FROM AD_Role WHERE IsActive='Y' ORDER BY Name", clientId);
	}

	/** Active organisations of the tenant, excluding the virtual organisation 0. */
	static List<String> orgs(int clientId) {
		return query("SELECT AD_Org_ID, Name FROM AD_Org WHERE IsActive='Y' AND AD_Client_ID=?"
				+ " AND AD_Org_ID>0 ORDER BY Name", clientId);
	}

	/** Active warehouses of the tenant. */
	static List<String> warehouses(int clientId) {
		return query("SELECT M_Warehouse_ID, Name FROM M_Warehouse WHERE IsActive='Y' AND AD_Client_ID=?"
				+ " ORDER BY Name", clientId);
	}

	/** Active product categories of the tenant. */
	static List<String> productCategories(int clientId) {
		return query("SELECT M_Product_Category_ID, Name FROM M_Product_Category WHERE IsActive='Y'"
				+ " AND AD_Client_ID=? ORDER BY Name", clientId);
	}

	/**
	 * Business partners of the tenant that can actually be a trading partner.
	 * <p>
	 * Filtered on the {@code IsCustomer} and {@code IsVendor} flags, which is how
	 * this iDempiere version records the partner category. There is no partner
	 * category table to join to any more, and joining to the sales and purchase
	 * orders instead would have been slow and wrong for a partner with no orders
	 * yet.
	 */
	static List<String> businessPartners(int clientId) {
		return query("SELECT C_BPartner_ID, Name FROM C_BPartner WHERE IsActive='Y' AND AD_Client_ID=?"
				+ " AND (IsCustomer='Y' OR IsVendor='Y') ORDER BY Name", clientId);
	}

	/** Active projects of the tenant. */
	static List<String> projects(int clientId) {
		return query("SELECT C_Project_ID, Name FROM C_Project WHERE IsActive='Y' AND AD_Client_ID=?"
				+ " ORDER BY Name", clientId);
	}

	/** Active document types of the tenant. */
	static List<String> documentTypes(int clientId) {
		return query("SELECT C_DocType_ID, Name FROM C_DocType WHERE IsActive='Y' AND AD_Client_ID=?"
				+ " ORDER BY Name", clientId);
	}

	private static List<String> query(String sql, int clientId) {
		List<String> result = new ArrayList<String>();
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, clientId);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				// the user list has a value and a name, the others only a name
				Object id = rs.getObject(1);
				if (id instanceof Integer)
					result.add(((Integer) id).intValue() + "|" + display(rs.getString(2), rs.getString(3)));
				else
					result.add(rs.getInt(1) + "|" + (rs.getString(2) == null ? "" : rs.getString(2)));
			}
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	/** Shows the value as well when it says something the name does not. */
	private static String display(String value, String name) {
		String label = name == null ? "" : name.trim();
		if (label.length() == 0)
			return value == null ? "" : value.trim();
		if (value == null || value.trim().length() == 0 || value.trim().equals(label))
			return label;
		return value.trim() + " - " + label;
	}
}
