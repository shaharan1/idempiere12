package org.mycompany.requisition;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * Resolves the default values for a new iDempiere product.
 * <p>
 * The resolution order required by the business rule
 * "user value first, configured iDempiere default afterwards" is implemented by
 * {@link ProductCreationService}: this class only answers the question
 * "what is the configured default for this field?".
 * <p>
 * Defaults are taken from the {@code IsDefault} flag of the iDempiere master
 * data tables, never from a hard coded id, so the behaviour follows the
 * configuration of whichever tenant runs the form. If no default flag is set,
 * the first active record of the tenant is used, which is what the standard
 * iDempiere windows fall back to as well.
 */
public class ProductDefaultService {

	/** Table the marker column belongs to. */
	private static final String T_PRODUCT = "M_Product";

	/** Standard iDempiere master data tables used by the product form. */
	private static final String T_UOM = "C_UOM";
	private static final String T_PRODUCT_CATEGORY = "M_Product_Category";
	private static final String T_TAX_CATEGORY = "C_TaxCategory";

	/**
	 * Resolved on every use so a default is looked up for the tenant that is
	 * active when it is actually needed.
	 */
	private Properties ctx() {
		return Env.getCtx();
	}

	public ProductDefaultService(Properties ctx) {
	}

	/** Default unit of measure (C_UOM.IsDefault). */
	public int getDefaultUOM(String trxName) {
		return defaultId(T_UOM, "C_UOM_ID", trxName);
	}

	/** Default product category (M_Product_Category.IsDefault). */
	public int getDefaultProductCategory(String trxName) {
		return defaultId(T_PRODUCT_CATEGORY, "M_Product_Category_ID", trxName);
	}

	/** Default tax category (C_TaxCategory.IsDefault). */
	public int getDefaultTaxCategory(String trxName) {
		return defaultId(T_TAX_CATEGORY, "C_TaxCategory_ID", trxName);
	}

	/**
	 * True when the given unit of measure exists and is usable. Used to reject a
	 * value that cannot be stored instead of failing later with a raw error.
	 */
	public boolean isValidUOM(int uomId, String trxName) {
		return exists(T_UOM, "C_UOM_ID", uomId, trxName);
	}

	/** True when the given product category exists and is usable. */
	public boolean isValidProductCategory(int categoryId, String trxName) {
		return exists(T_PRODUCT_CATEGORY, "M_Product_Category_ID", categoryId, trxName);
	}

	/** True when the given tax category exists and is usable. */
	public boolean isValidTaxCategory(int taxCategoryId, String trxName) {
		return exists(T_TAX_CATEGORY, "C_TaxCategory_ID", taxCategoryId, trxName);
	}

	/**
	 * True when the custom GSM master contains the given record. The table is
	 * only used when the column really exists on M_Product, so the check is
	 * driven by the caller.
	 */
	public boolean isValidGSM(int gsmId, String trxName) {
		return exists("XX_GSM", "XX_GSM_ID", gsmId, trxName);
	}

	/** Display name of a unit of measure, or an empty string. */
	public String getUOMName(int uomId) {
		return text(T_UOM, "C_UOM_ID", uomId, "Name");
	}

	/** Display name of a product category, or an empty string. */
	public String getProductCategoryName(int categoryId) {
		return text(T_PRODUCT_CATEGORY, "M_Product_Category_ID", categoryId, "Name");
	}

	/** Display name of a tax category, or an empty string. */
	public String getTaxCategoryName(int taxCategoryId) {
		return text(T_TAX_CATEGORY, "C_TaxCategory_ID", taxCategoryId, "Name");
	}

	/** Display name of a GSM record, or an empty string. */
	public String getGSMName(int gsmId) {
		return text("XX_GSM", "XX_GSM_ID", gsmId, "Name");
	}

	/**
	 * True when M_Product really carries the Quick Entry marker column. The check
	 * keeps the form working on a database that does not have it, instead of
	 * failing every save with an unknown column.
	 */
	public boolean hasQuickEntryMarker() {
		return DB.getSQLValue(null,
				"SELECT COUNT(*) FROM information_schema.columns WHERE lower(table_name)=? "
						+ "AND lower(column_name)=?",
				T_PRODUCT.toLowerCase(),
				ProductCreationService.COL_QUICK_ENTRY.toLowerCase()) > 0;
	}

/**
	 * The number the next new product will get as its code, read straight from
	 * the iDempiere document sequence of the tenant. This is a read only peek:
	 * MSequence.getDocumentNo would consume a number, and showing a hint must
	 * never burn a code.
	 */
	public String nextSequenceValue(String trxName) {
		String prefix = DB.getSQLValueString(trxName,
				"SELECT s.prefix FROM AD_Sequence s WHERE s.name=? AND s.ad_client_id IN (0,?) AND s.isactive='Y' "
						+ "ORDER BY s.ad_client_id DESC LIMIT 1",
				"DocumentNo_" + T_PRODUCT, Integer.valueOf(Env.getAD_Client_ID(ctx())));
		if (prefix == null)
			prefix = "";

		int next = DB.getSQLValue(trxName,
				"SELECT s.currentnext FROM AD_Sequence s WHERE s.name=? AND s.ad_client_id IN (0,?) "
						+ "AND s.isactive='Y' ORDER BY s.ad_client_id DESC LIMIT 1",
				"DocumentNo_" + T_PRODUCT, Integer.valueOf(Env.getAD_Client_ID(ctx())));
		if (next <= 0)
			return null;
		return prefix + next;
	}

	/** One row of the "created by Quick Product Entry" list. */
	public static class QuickEntryProduct {
		private final int id;
		private final String value;
		private final String name;
		private final String created;

		QuickEntryProduct(int id, String value, String name, String created) {
			this.id = id;
			this.value = value;
			this.name = name;
			this.created = created;
		}

		public int getId() {
			return id;
		}

		public String getValue() {
			return value;
		}

		public String getName() {
			return name;
		}

		public String getCreated() {
			return created;
		}

		@Override
		public String toString() {
			return value + "  -  " + name;
		}
	}

	/**
	 * Products that were created through the Quick Product Entry form, newest
	 * first, limited to the tenant of the current session.
	 */
	public List<QuickEntryProduct> listQuickEntryProducts(int maxRows) {
		List<QuickEntryProduct> result = new ArrayList<QuickEntryProduct>();
		if (!hasQuickEntryMarker())
			return result;

		List<Object> params = new ArrayList<Object>();
		params.add("Y");
		params.add(Integer.valueOf(Env.getAD_Client_ID(ctx())));

		StringBuilder sql = new StringBuilder("SELECT p.M_Product_ID, p.Value, p.Name, p.Created FROM ")
				.append(T_PRODUCT).append(" p")
				.append(" WHERE p.").append(ProductCreationService.COL_QUICK_ENTRY).append("=?")
				.append(" AND p.AD_Client_ID=? AND p.IsActive='Y'")
				.append(" ORDER BY p.Created DESC, p.M_Product_ID DESC");

		if (maxRows > 0)
			sql.append(" LIMIT ").append(maxRows);

		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), null);
			for (int i = 0; i < params.size(); i++)
				pstmt.setObject(i + 1, params.get(i));
			rs = pstmt.executeQuery();
			while (rs.next())
				result.add(new QuickEntryProduct(rs.getInt(1), rs.getString(2), rs.getString(3),
						rs.getString(4)));
		} catch (java.sql.SQLException e) {
			// a failing list must never break the form
			result.clear();
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
		return result;
	}

	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	//  internals
	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	/**
	 * Default record of a master data table: the active record flagged as
	 * default, otherwise the first active record of the tenant.
	 */
	private int defaultId(String table, String idColumn, String trxName) {
		int id = DB.getSQLValue(trxName,
				"SELECT " + idColumn + " FROM " + table
						+ " WHERE IsActive='Y' AND IsDefault='Y'"
						+ " AND AD_Client_ID IN (0,?) ORDER BY " + idColumn,
				Integer.valueOf(Env.getAD_Client_ID(ctx())));
		if (id > 0)
			return id;

		return DB.getSQLValue(trxName,
				"SELECT " + idColumn + " FROM " + table
						+ " WHERE IsActive='Y'"
						+ " AND AD_Client_ID IN (0,?) ORDER BY " + idColumn,
				Integer.valueOf(Env.getAD_Client_ID(ctx())));
	}

	/** True when the id exists, is active and belongs to this tenant. */
	private boolean exists(String table, String idColumn, int id, String trxName) {
		if (id <= 0)
			return false;
		return DB.getSQLValue(trxName,
				"SELECT COUNT(*) FROM " + table
						+ " WHERE " + idColumn + "=?"
						+ " AND IsActive='Y'"
						+ " AND AD_Client_ID IN (0,?)",
				Integer.valueOf(id), Integer.valueOf(Env.getAD_Client_ID(ctx()))) > 0;
	}

	/** Read-only label of a master data record. */
	private String text(String table, String idColumn, int id, String textColumn) {
		if (id <= 0)
			return "";
		return DB.getSQLValueString(null,
				"SELECT " + textColumn + " FROM " + table + " WHERE " + idColumn + "=?",
				Integer.valueOf(id));
	}
}