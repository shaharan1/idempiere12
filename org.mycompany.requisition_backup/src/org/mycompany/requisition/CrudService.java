package org.mycompany.requisition;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.compiere.model.MClient;
import org.compiere.model.MOrg;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;

/**
 * Generic, metadata driven CRUD service for iDempiere 12 tables.
 * <p>
 * The service is intentionally table agnostic: everything (key column, business
 * columns, mandatory flags, column types) is resolved at runtime from the
 * iDempiere dictionary through {@link MTable} / {@link POInfo}. Nothing is
 * hard coded, so the very same service can be pointed at any iDempiere table.
 * <p>
 * All writes go through the iDempiere persistent object API
 * ({@link PO#save()}, {@link PO#delete(boolean)}), never through raw SQL, so
 * audit columns, UUID columns, business rules and validation are handled by
 * iDempiere itself. Raw SQL is only used for read-only listing and duplicate
 * lookup.
 * <p>
 * The caller (the ZK form) only has to supply the business {@code Name} value.
 * Client, organization, audit columns, active flag, UUID and any further
 * mandatory column are supplied automatically from the current iDempiere
 * context - see {@link #applyStandardDefaults(PO, String)}.
 */
public class CrudService {

	private static final CLogger log = CLogger.getCLogger(CrudService.class);

	/** Columns that iDempiere maintains itself and that must never be filled by hand. */
	private static final Set<String> SYSTEM_COLUMNS = new HashSet<String>(java.util.Arrays.asList(
			"AD_Client_ID", "AD_Org_ID", "Created", "CreatedBy", "Updated", "UpdatedBy"));

	/** Columns that are re-used as labels in the form. */
	private static final String COL_CLIENT = "AD_Client_ID";
	private static final String COL_ORG = "AD_Org_ID";
	private static final String COL_ACTIVE = "IsActive";
	private static final String COL_CREATED = "Created";
	private static final String COL_CREATED_BY = "CreatedBy";
	private static final String COL_UPDATED = "Updated";
	private static final String COL_UPDATED_BY = "UpdatedBy";
	private static final String COL_VALUE = "Value";

	/** Simple allow-list for identifiers that are used to build read-only SQL. */
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

	/** First integer found in a free text value, used to derive numeric defaults. */
	private static final Pattern FIRST_NUMBER = Pattern.compile("-?\\d+");

	/**
	 * Resolved on every use instead of being captured once. A ZK form is built
	 * while the desktop opens the tab, so a context kept in a field can be the
	 * one from before the window existed - that made a signed in GardenWorld user
	 * look like AD_Org_ID=0 here.
	 */
	private Properties ctx() {
		return Env.getCtx();
	}

	private final String tableName;
	private final String nameColumn;
	private final MTable table;
	private final POInfo info;
	private final String keyColumn;

	/**
	 * Thrown for every condition that has to be reported to the user. The message
	 * is already phrased for the end user, technical details are logged instead.
	 */
	public static class CrudException extends Exception {
		private static final long serialVersionUID = 1L;

		public CrudException(String message) {
			super(message);
		}

		public CrudException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** One entry of the record list shown in the form. */
	public static class RecordRef {
		private final int id;
		private final String name;

		RecordRef(int id, String name) {
			this.id = id;
			this.name = name;
		}

		public int getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/** Snapshot of a stored record, used to populate the read-only form fields. */
	public static class RecordData {
		private final int id;
		private final String name;
		private final List<String> columns;
		private final List<String> values;

		RecordData(int id, String name, List<String> columns, List<String> values) {
			this.id = id;
			this.name = name;
			this.columns = columns;
			this.values = values;
		}

		public int getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		/** All form fields of the record, keyed by column name (no key, no UUID). */
		public List<String> getColumns() {
			return columns;
		}

		/** Display value for each entry of {@link #getColumns()}, same order. */
		public List<String> getValues() {
			return values;
		}
	}

	/**
	 * @param ctx        current iDempiere context
	 * @param tableName  iDempiere table to work on, e.g. {@code XX_GSM}
	 * @param nameColumn the single business column the user enters
	 * @throws CrudException if the table or the name column is unknown
	 */
	public CrudService(Properties ctx, String tableName, String nameColumn) throws CrudException {
		if (ctx == null)
			throw new CrudException("No iDempiere context available.");
		if (tableName == null || !IDENTIFIER.matcher(tableName).matches())
			throw new CrudException("Invalid table name.");

		this.tableName = tableName;
		this.nameColumn = (nameColumn == null || nameColumn.trim().length() == 0)
				? "Name"
				: nameColumn.trim();

		this.table = MTable.get(ctx, tableName);
		if (table == null)
			throw new CrudException("Table " + tableName + " is not registered in iDempiere.");

		this.info = POInfo.getPOInfo(ctx, table.getAD_Table_ID());
		this.keyColumn = table.getKeyColumns() != null && table.getKeyColumns().length > 0
				? table.getKeyColumns()[0]
				: null;

		if (keyColumn == null)
			throw new CrudException("Table " + tableName + " has no single primary key.");
		if (info.getColumnIndex(nameColumn) < 0)
			throw new CrudException("Table " + tableName + " has no column " + nameColumn + ".");
	}

	public String getTableName() {
		return tableName;
	}

	public String getNameColumn() {
		return nameColumn;
	}

	public MTable getTable() {
		return table;
	}

	public POInfo getPOInfo() {
		return info;
	}

	/** True when the column exists on the table and is filled from metadata/defaults. */
	public boolean hasColumn(String column) {
		return column != null && info.getColumnIndex(column) >= 0;
	}

	/**
	 * Business columns that the form shows read-only: everything except the key,
	 * the UUID and the iDempiere system columns.
	 */
	public List<String> getDisplayColumns() {
		List<String> result = new ArrayList<String>();
		for (String column : getAllColumns()) {
			if (SYSTEM_COLUMNS.contains(column.toUpperCase()))
				continue;
			result.add(column);
		}
		return result;
	}

	/**
	 * Every column the form can display, i.e. all columns except the primary key
	 * and the UUID. Used to bind a loaded record to the form in one pass.
	 */
	public List<String> getAllColumns() {
		List<String> result = new ArrayList<String>();
		String uuidColumn = PO.getUUIDColumnName(tableName);
		for (int i = 0; i < info.getColumnCount(); i++) {
			String column = info.getColumnName(i);
			if (column == null)
				continue;
			if (column.equalsIgnoreCase(keyColumn) || column.equalsIgnoreCase(uuidColumn))
				continue;
			result.add(column);
		}
		return result;
	}

	// ------------------------------------------------------------------------------
	//  Read
	// ------------------------------------------------------------------------------

	/**
	 * Organization of the running session, falling back to the organization the
	 * user chose on the login screen, the first organization the role may access
	 * and finally the first active organization of the tenant. Only the System
	 * tenant, which owns no organization at all, fails.
	 */
	private int resolveOrgId(String trxName) throws CrudException {
		int sessionOrg = Env.getAD_Org_ID(ctx());
		if (sessionOrg > 0)
			return sessionOrg;

		int clientId = Env.getAD_Client_ID(ctx());

		int preferred = preferenceOrg();
		if (preferred > 0 && orgBelongsToClient(preferred, clientId))
			return preferred;

		// AD_Org_ID=0 is the virtual system organization, it can never own a record
		int roleOrg = DB.getSQLValue(null,
				"SELECT MIN(o.AD_Org_ID) FROM AD_Role_OrgAccess ra "
						+ "INNER JOIN AD_Org o ON o.AD_Org_ID=ra.AD_Org_ID AND o.IsActive='Y' "
						+ "WHERE ra.AD_Role_ID=? AND ra.IsActive='Y' AND o.AD_Client_ID IN (0,?) AND o.AD_Org_ID>0",
				Integer.valueOf(Env.getAD_Role_ID(ctx())), Integer.valueOf(clientId));
		if (roleOrg > 0)
			return roleOrg;

		int tenantOrg = DB.getSQLValue(trxName,
				"SELECT MIN(o.AD_Org_ID) FROM AD_Org o WHERE o.AD_Client_ID=? AND o.IsActive='Y'",
				Integer.valueOf(clientId));
		if (tenantOrg > 0)
			return tenantOrg;

		throw new CrudException("No organization is available. This is the System tenant, which has no "
				+ "organization. Switch to a tenant such as GardenWorld and save the record again.");
	}

	/** Organization picked on the login screen of this user, if any. */
	private int preferenceOrg() {
		int userId = Env.getAD_User_ID(ctx());
		if (userId <= 0)
			return 0;
		String value = DB.getSQLValueString(null,
				"SELECT MAX(p.value) FROM AD_Preference p WHERE p.AD_User_ID=? AND p.Attribute='Organization' "
						+ "AND p.AD_Window_ID IS NULL AND p.PreferenceFor='W'",
				Integer.valueOf(userId));
		if (value == null || value.trim().length() == 0)
			return 0;
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private boolean orgBelongsToClient(int orgId, int clientId) {
		return orgId == clientId || DB.getSQLValue(null,
				"SELECT COUNT(*) FROM AD_Org o WHERE o.AD_Org_ID=? AND o.AD_Client_ID=? AND o.IsActive='Y'",
				Integer.valueOf(orgId), Integer.valueOf(clientId)) > 0;
	}

	/**
	 * Read-only record list for the current tenant. Only the current client is
	 * queried and organizations are limited to the session organization, so a
	 * user can never see records of another client or organization.
	 *
	 * @param search free text filter, may be empty
	 */
	public List<RecordRef> list(String search) throws CrudException {
		List<RecordRef> result = new ArrayList<RecordRef>();
		StringBuilder sql = new StringBuilder("SELECT ").append(keyColumn).append(", ").append(nameColumn)
				.append(" FROM ").append(tableName)
				.append(" WHERE ").append(COL_CLIENT).append("=?");

		List<Object> params = new ArrayList<Object>();
		params.add(Integer.valueOf(Env.getAD_Client_ID(ctx())));

		if (hasColumn(COL_ORG)) {
			sql.append(" AND (").append(COL_ORG).append("=? OR ").append(COL_ORG).append("=0)");
			params.add(Integer.valueOf(resolveOrgId(null)));
		}

		if (search != null && search.trim().length() > 0) {
			sql.append(" AND UPPER(").append(nameColumn).append(") LIKE ?");
			params.add("%" + search.trim().toUpperCase() + "%");
		}
		sql.append(" ORDER BY ").append(nameColumn);

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql.toString(), null);
			for (int i = 0; i < params.size(); i++)
				pstmt.setObject(i + 1, params.get(i));
			rs = pstmt.executeQuery();
			while (rs.next())
				result.add(new RecordRef(rs.getInt(1), rs.getString(2)));
		} catch (SQLException e) {
			log.log(Level.SEVERE, "Cannot list records of " + tableName, e);
			throw new CrudException("Unable to load the record list.", e);
		} finally {
			DB.close(rs, pstmt);
		}
		return result;
	}

	/**
	 * Loads one record and returns its display values for every form field.
	 * Access is verified on the server side; the record id is never trusted to
	 * come from the browser.
	 */
	public RecordData load(int id) throws CrudException {
		String trxName = Trx.createTrxName("CrudLoad");
		Trx trx = Trx.get(trxName, true);
		try {
			PO po = getRecord(id, trxName);
			List<String> columns = getAllColumns();
			List<String> values = new ArrayList<String>(columns.size());
			for (String column : columns)
				values.add(displayValue(po, column));
			RecordData data = new RecordData(id, po.get_ValueAsString(nameColumn), columns, values);
			trx.commit();
			return data;
		} finally {
			trx.close();
		}
	}

	// ------------------------------------------------------------------------------
	//  Create / Update / Delete
	// ------------------------------------------------------------------------------

	/**
	 * Creates a new record. The only user supplied value is the business name,
	 * everything else comes from the iDempiere context and the table metadata.
	 *
	 * @return id of the created record
	 */
	public int create(String name) throws CrudException {
		String trimmed = trimToNull(name);
		if (trimmed == null)
			throw new CrudException(nameColumn + " is required.");
		// The System tenant has no organization, and iDempiere refuses to store a
		// record without one - say that clearly instead of a bare "Organization".
		if (Env.getAD_Org_ID(ctx()) <= 0)
			throw new CrudException("No organization is selected. Switch to a tenant (for example GardenWorld) "
					+ "and select an organization before creating records.");

		String trxName = Trx.createTrxName("CrudCreate");
		Trx trx = Trx.get(trxName, true);
		try {
			checkDuplicate(trimmed, 0, trxName);

			PO po = table.getPO(0, trxName);
			if (po == null)
				throw new CrudException("Unable to create a new " + tableName + " record.");

			applyStandardDefaults(po, trimmed);
			po.set_ValueOfColumnReturningBoolean(nameColumn, trimmed);
			// iDempiere refuses to store a record without an organization
			if (hasColumn(COL_ORG))
				po.set_ValueOfColumnReturningBoolean(COL_ORG, Integer.valueOf(resolveOrgId(trxName)));

			if (!po.save())
				throw new CrudException(errorOr("Unable to save the record."));

			int id = po.get_ID();
			trx.commit();
			return id;
		} catch (CrudException e) {
			trx.rollback();
			throw e;
		} catch (RuntimeException e) {
			trx.rollback();
			log.log(Level.SEVERE, "Create failed for " + tableName, e);
			throw new CrudException("Unable to save the record.", e);
		} finally {
			trx.close();
		}
	}

	/** Updates the business name of an existing record, keeping its primary key. */
	public void update(int id, String name) throws CrudException {
		String trimmed = trimToNull(name);
		if (trimmed == null)
			throw new CrudException(nameColumn + " is required.");
		if (id <= 0)
			throw new CrudException("No record selected.");

		String trxName = Trx.createTrxName("CrudUpdate");
		Trx trx = Trx.get(trxName, true);
		try {
			PO po = getRecord(id, trxName);
			checkDuplicate(trimmed, id, trxName);

			if (!trimmed.equals(po.get_ValueAsString(nameColumn)))
				po.set_ValueOfColumnReturningBoolean(nameColumn, trimmed);

			if (!po.save())
				throw new CrudException(errorOr("Unable to update the record."));

			trx.commit();
		} catch (CrudException e) {
			trx.rollback();
			throw e;
		} catch (RuntimeException e) {
			trx.rollback();
			log.log(Level.SEVERE, "Update failed for " + tableName + " id=" + id, e);
			throw new CrudException("Unable to update the record.", e);
		} finally {
			trx.close();
		}
	}

	/** Deletes a record through the iDempiere model, honouring delete rules. */
	public void delete(int id) throws CrudException {
		if (id <= 0)
			throw new CrudException("No record selected.");

		String trxName = Trx.createTrxName("CrudDelete");
		Trx trx = Trx.get(trxName, true);
		try {
			PO po = getRecord(id, trxName);
			if (!po.delete(true))
				throw new CrudException(errorOr("Unable to delete the record."));
			trx.commit();
		} catch (CrudException e) {
			trx.rollback();
			throw e;
		} catch (RuntimeException e) {
			trx.rollback();
			log.log(Level.SEVERE, "Delete failed for " + tableName + " id=" + id, e);
			throw new CrudException("Unable to delete the record.", e);
		} finally {
			trx.close();
		}
	}

	// ------------------------------------------------------------------------------
	//  Defaults, validation and security
	// ------------------------------------------------------------------------------

	/**
	 * Supplies every value the user must not type:
	 * <ul>
	 * <li>Client, organization, Created/Updated and CreatedBy/UpdatedBy are
	 * already maintained by {@link PO} itself from the session context</li>
	 * <li>IsActive defaults to true</li>
	 * <li>a master data Value is derived from the name when the table has one</li>
	 * <li>every remaining mandatory column is derived from the name (numbers) or
	 * set to a neutral value of its own type, so the database NOT NULL
	 * constraints can never block a save</li>
	 * </ul>
	 */
	private void applyStandardDefaults(PO po, String name) {
		if (hasColumn(COL_ACTIVE))
			po.setIsActive(true);

		if (hasColumn(COL_VALUE) && trimToNull(po.get_ValueAsString(COL_VALUE)) == null)
			po.set_ValueOfColumnReturningBoolean(COL_VALUE, deriveValue(name));

		String uuidColumn = PO.getUUIDColumnName(tableName);
		BigDecimal derivedNumber = firstNumber(name);

		for (int i = 0; i < info.getColumnCount(); i++) {
			String column = info.getColumnName(i);
			if (column == null || !info.isColumnMandatory(i))
				continue;
			if (column.equalsIgnoreCase(keyColumn) || column.equalsIgnoreCase(uuidColumn))
				continue;
			if (SYSTEM_COLUMNS.contains(column.toUpperCase()) || column.equalsIgnoreCase(COL_ACTIVE))
				continue;
			if (column.equalsIgnoreCase(nameColumn) || column.equalsIgnoreCase(COL_VALUE))
				continue;
			if (po.get_Value(i) != null)
				continue;

			po.set_ValueOfColumnReturningBoolean(column, defaultFor(info.getColumnClass(i), derivedNumber));
		}
	}

	/** Neutral value matching the column type, preferring a number taken from the name. */
	private Object defaultFor(Class<?> columnClass, BigDecimal derivedNumber) {
		if (columnClass == null)
			return "";
		if (Boolean.class.isAssignableFrom(columnClass) || boolean.class.equals(columnClass))
			return Boolean.TRUE;
		if (Date.class.isAssignableFrom(columnClass) || Timestamp.class.equals(columnClass))
			return new Timestamp(System.currentTimeMillis());
		if (BigDecimal.class.isAssignableFrom(columnClass))
			return derivedNumber != null ? derivedNumber : BigDecimal.ZERO;
		if (Integer.class.equals(columnClass) || int.class.equals(columnClass))
			return Integer.valueOf(derivedNumber != null ? derivedNumber.intValue() : 0);
		if (Long.class.equals(columnClass) || long.class.equals(columnClass))
			return Long.valueOf(derivedNumber != null ? derivedNumber.longValue() : 0L);
		if (Double.class.equals(columnClass) || double.class.equals(columnClass))
			return Double.valueOf(derivedNumber != null ? derivedNumber.doubleValue() : 0d);
		if (Number.class.isAssignableFrom(columnClass))
			return derivedNumber != null ? derivedNumber : BigDecimal.ZERO;
		return "";
	}

	/**
	 * Master data convention: derive a short search key from the name,
	 * e.g. "Non-woven bag 40 GSM" becomes "NON-WOVEN-BAG-40-GSM".
	 */
	private String deriveValue(String name) {
		String upper = name.trim().toUpperCase();
		String value = upper.replaceAll("[^A-Z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (value.length() > 40)
			value = value.substring(0, 40);
		return value.length() == 0 ? upper : value;
	}

	private BigDecimal firstNumber(String name) {
		Matcher m = FIRST_NUMBER.matcher(name);
		if (m.find())
			return new BigDecimal(m.group());
		return null;
	}

	/**
	 * Loads a record and verifies that the current user may see and change it.
	 * The id is re-read from the database, never taken from the UI.
	 */
	private PO getRecord(int id, String trxName) throws CrudException {
		PO po = table.getPO(id, trxName);
		if (po == null || po.get_ID() <= 0)
			throw new CrudException("Record not found.");

		int clientId = po.get_ValueAsInt(COL_CLIENT);
		if (clientId != Env.getAD_Client_ID(ctx()))
			throw new CrudException("You do not have permission to modify this record.");
		if (hasColumn(COL_ORG)) {
			int orgId = po.get_ValueAsInt(COL_ORG);
			int myOrg = Env.getAD_Org_ID(ctx());
			if (orgId != 0 && orgId != myOrg)
				throw new CrudException("You do not have permission to modify this record.");
		}
		return po;
	}

	/**
	 * Rejects a second record with the same business name of the same client.
	 * The check excludes the record that is currently being updated.
	 */
	private void checkDuplicate(String name, int excludeId, String trxName) throws CrudException {
		String sql = "SELECT COUNT(*) FROM " + tableName
				+ " WHERE UPPER(" + nameColumn + ")=UPPER(?)"
				+ " AND " + COL_CLIENT + "=?"
				+ " AND " + keyColumn + "<>?";
		int count = DB.getSQLValue(trxName, sql, name, Integer.valueOf(Env.getAD_Client_ID(ctx())),
				Integer.valueOf(excludeId));
		if (count > 0)
			throw new CrudException("A record with this " + nameColumn.toLowerCase() + " already exists.");
	}

	/** User friendly message from the iDempiere error stack, falling back to a default. */
	private String errorOr(String fallback) {
		String error = CLogger.retrieveErrorString(null);
		if (error == null || error.trim().length() == 0)
			return fallback;
		return error;
	}

	/** Read-only display value, resolving client, organization and user through master data. */
	private String displayValue(PO po, String column) {
		String raw = po.get_ValueAsString(column);
		if (raw == null)
			return "";

		if (column.equalsIgnoreCase(COL_CLIENT))
			return clientName(po.get_ValueAsInt(COL_CLIENT));
		if (column.equalsIgnoreCase(COL_ORG))
			return orgName(po.get_ValueAsInt(COL_ORG));
		if (column.equalsIgnoreCase(COL_CREATED_BY) || column.equalsIgnoreCase(COL_UPDATED_BY))
			return userName(po.get_ValueAsInt(column));
		return raw;
	}

	private String clientName(int id) {
		if (id <= 0)
			return "";
		MClient client = MClient.get(ctx(), id);
		return client == null ? String.valueOf(id) : client.get_ValueAsString("Name");
	}

	private String orgName(int id) {
		if (id <= 0)
			return "* (all organizations)";
		MOrg org = MOrg.get(ctx(), id);
		return org == null ? String.valueOf(id) : org.get_ValueAsString("Name");
	}

	private String userName(int id) {
		if (id <= 0)
			return "System";
		MUser user = MUser.get(ctx(), id);
		return user == null ? String.valueOf(id) : user.get_ValueAsString("Name");
	}

	private static String trimToNull(String value) {
		if (value == null)
			return null;
		String trimmed = value.trim();
		return trimmed.length() == 0 ? null : trimmed;
	}
}
