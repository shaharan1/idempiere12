package org.mycompany.requisition.approval.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;

/**
 * Shared plumbing for the approval configuration services.
 * <p>
 * Everything goes through the standard iDempiere {@link PO} model, never through
 * hand written SQL for a write. That is what gives the custom tables the same
 * tenant rules, audit columns, mandatory field checks and UUID handling as every
 * other iDempiere record, without this plugin reimplementing any of it.
 * <p>
 * No column identifier is hard coded. Tables are looked up by name through
 * {@link MTable} and columns are checked through {@link POInfo} before they are
 * written, so a database that does not have an optional column still works.
 * <p>
 * The context is resolved on every call rather than captured in a field. A ZK
 * form is constructed while the desktop builds the tab, and a context kept in a
 * field is then the one from before the window existed - that once made a signed
 * in GardenWorld user look like {@code AD_Org_ID = 0}.
 *
 * @see ProcessConfigService
 * @see LevelConfigService
 * @see RuleConfigService
 */
public abstract class BaseConfigService {

	protected final Logger log = CLogger.getCLogger(getClass());

	/** Error already phrased for the end user; technical detail is logged. */
	public static class ConfigException extends Exception {
		private static final long serialVersionUID = 1L;

		public ConfigException(String message) {
			super(message);
		}

		public ConfigException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** Audit and UUID columns that are never written by this layer. */
	protected static final String[] SYSTEM_COLUMNS = { "AD_CLIENT_ID", "AD_ORG_ID", "CREATED", "CREATEDBY",
			"UPDATED", "UPDATEDBY", "ISACTIVE" };

	/** Physical table name, resolved once per instance from the dictionary. */
	private final String tableName;

	private final MTable table;
	private final POInfo info;
	private final String keyColumn;

	/**
	 * @param tableName the custom table, for example {@code XX_PROCESS}
	 * @throws ConfigException when the table is not registered or has no key
	 */
	protected BaseConfigService(String tableName) throws ConfigException {
		Properties ctx = ctx();
		this.tableName = tableName;

		this.table = MTable.get(ctx, tableName);
		if (table == null)
			throw new ConfigException("Table " + tableName + " is not registered in iDempiere.");

		this.info = POInfo.getPOInfo(ctx, table.getAD_Table_ID());
		this.keyColumn = table.getKeyColumns() != null && table.getKeyColumns().length > 0
				? table.getKeyColumns()[0]
				: null;
		if (keyColumn == null)
			throw new ConfigException("Table " + tableName + " has no single primary key.");
	}

	/** Context of the request that is being served right now. */
	protected Properties ctx() {
		return Env.getCtx();
	}

	public String getTableName() {
		return tableName;
	}

	protected MTable getTable() {
		return table;
	}

	protected POInfo getInfo() {
		return info;
	}

	protected String getKeyColumn() {
		return keyColumn;
	}

	/**
	 * True when the column exists and is usable.
	 * <p>
	 * POInfo is built from the active dictionary columns only, so a positive index
	 * is already the guarantee. An inactive or unknown column returns -1 and is
	 * simply skipped, which is what lets the same code run on a database that
	 * does not have an optional custom column yet.
	 */
	protected boolean hasColumn(String columnName) {
		if (columnName == null)
			return false;
		return info.getColumnIndex(columnName) >= 0;
	}

	/**
	 * Writes a value only when the column exists and the value is not null, so an
	 * absent optional column never turns into a database error.
	 *
	 * @return true when something was written
	 */
	protected boolean set(PO po, String columnName, Object value) {
		if (value == null || !hasColumn(columnName))
			return false;
		int colIndex = info.getColumnIndex(columnName);
		if (colIndex >= 0) {
			Class<?> colClass = info.getColumnClass(colIndex);
			if (colClass != null) {
				try {
					if (String.class.equals(colClass) && !(value instanceof String)) {
						// Column defined as String/List in AD_Column but we received a number — convert
						value = value.toString();
					} else if (Integer.class.equals(colClass) || int.class.equals(colClass)) {
						// Column expects Integer
						if (value instanceof String) {
							value = Integer.valueOf(((String) value).trim());
						} else if (value instanceof BigDecimal) {
							value = Integer.valueOf(((BigDecimal) value).intValue());
						} else if (value instanceof Long) {
							value = Integer.valueOf(((Long) value).intValue());
						}
					} else if (BigDecimal.class.equals(colClass)) {
						if (value instanceof Integer) {
							value = BigDecimal.valueOf(((Integer) value).longValue());
						} else if (value instanceof Long) {
							value = BigDecimal.valueOf((Long) value);
						} else if (value instanceof String) {
							value = new BigDecimal(((String) value).trim());
						}
					}
				} catch (Exception convEx) {
					log.warning("Type conversion failed for column " + columnName
							+ " (expected " + colClass.getSimpleName() + ", got "
							+ value.getClass().getSimpleName() + "=" + value + "): " + convEx.getMessage());
					// Keep original value — PO.saveEx will report the precise error
				}
			}
		}
		po.set_ValueOfColumnReturningBoolean(columnName, value);
		return true;
	}

	/** Reads a column when it exists, otherwise null. */
	protected Object get(PO po, String columnName) {
		if (!hasColumn(columnName))
			return null;
		return po.get_Value(columnName);
	}

	protected Integer getInt(PO po, String columnName) {
		Object v = get(po, columnName);
		if (v == null)
			return null;
		try {
			return Integer.valueOf(v.toString().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	protected int getInt(PO po, String columnName, int fallback) {
		Integer v = getInt(po, columnName);
		return v == null ? fallback : v.intValue();
	}

	protected String getString(PO po, String columnName) {
		Object v = get(po, columnName);
		return v == null ? null : v.toString();
	}

	protected BigDecimal getDecimal(PO po, String columnName) {
		Object v = get(po, columnName);
		if (v == null)
			return null;
		if (v instanceof BigDecimal)
			return (BigDecimal) v;
		try {
			return new BigDecimal(v.toString().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	protected Timestamp getTimestamp(PO po, String columnName) {
		Object v = get(po, columnName);
		if (v == null)
			return null;
		if (v instanceof Timestamp)
			return (Timestamp) v;
		try {
			return Timestamp.valueOf(v.toString());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	protected boolean getBoolean(PO po, String columnName) {
		Object v = get(po, columnName);
		return v != null && "Y".equalsIgnoreCase(v.toString().trim());
	}

	/** New record with the key cleared, ready to be filled. */
	protected PO newRecord(String trxName) throws ConfigException {
		PO po = table.getPO(0, trxName);
		if (po == null)
			throw new ConfigException("Unable to create a new " + tableName + " record.");
		int client = clientId();
		int org = orgIdForWrite(trxName);
		po.set_ValueOfColumnReturningBoolean("AD_Client_ID", Integer.valueOf(client));
		po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(org));
		return po;
	}

	/**
	 * Organisation a new record is stored in.
	 * <p>
	 * A session can legitimately report {@code AD_Org_ID = 0} - the System tenant
	 * has no organisation, and a login that only picked a client reports none
	 * either. Writing organisation 0 would put the record outside every tenant's
	 * reach, so the value is resolved through the same chain this plugin already
	 * uses for master data:
	 *
	 * <ol>
	 * <li>the organisation of the session</li>
	 * <li>the organisation picked on the login screen of this user</li>
	 * <li>the first organisation the current role may access</li>
	 * <li>the first active organisation of the tenant</li>
	 * </ol>
	 *
	 * @throws ConfigException only for a tenant that has no organisation at all,
	 *                         because such a record cannot be stored anywhere
	 */
	protected int orgIdForWrite(String trxName) throws ConfigException {
		int sessionOrg = Env.getAD_Org_ID(ctx());
		if (sessionOrg > 0)
			return sessionOrg;

		int client = clientId();

		Integer preferred = loginPreferenceOrg();
		if (preferred != null && preferred.intValue() > 0 && orgBelongsToClient(preferred.intValue(), client))
			return preferred.intValue();

		int roleOrg = firstOrgForRole();
		if (roleOrg > 0)
			return roleOrg;

		int tenantOrg = DB.getSQLValue(trxName,
				"SELECT MIN(o.AD_Org_ID) FROM AD_Org o WHERE o.AD_Client_ID=? AND o.IsActive='Y'",
				Integer.valueOf(client));
		if (tenantOrg > 0)
			return tenantOrg;

		throw new ConfigException("No organization is available for " + tableName
				+ ". This is the System tenant, which has no organization. Sign in to a tenant such as "
				+ "GardenWorld and try again.");
	}

	/** Organisation this user picked on the login screen, when there is one. */
	private Integer loginPreferenceOrg() {
		int user = Env.getAD_User_ID(ctx());
		if (user <= 0)
			return null;
		try {
			String value = DB.getSQLValueString(null,
					"SELECT MAX(p.value) FROM AD_Preference p WHERE p.AD_User_ID=? AND p.Attribute='Organization' "
							+ "AND p.AD_Window_ID IS NULL AND p.PreferenceFor='W'",
					Integer.valueOf(user));
			if (value == null || value.trim().length() == 0)
				return null;
			return Integer.valueOf(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private boolean orgBelongsToClient(int org, int client) {
		return org == client || DB.getSQLValue(null,
				"SELECT COUNT(*) FROM AD_Org o WHERE o.AD_Org_ID=? AND o.AD_Client_ID=? AND o.IsActive='Y'",
				Integer.valueOf(org), Integer.valueOf(client)) > 0;
	}

	/** First organisation the role of the session is allowed to work in. */
	private int firstOrgForRole() {
		int role = Env.getAD_Role_ID(ctx());
		int client = clientId();
		if (role <= 0)
			return 0;
		// AD_Org_ID = 0 is the virtual system organisation and can never own a record
		return DB.getSQLValue(null,
				"SELECT MIN(o.AD_Org_ID) FROM AD_Role_OrgAccess ra "
						+ "INNER JOIN AD_Org o ON o.AD_Org_ID=ra.AD_Org_ID AND o.IsActive='Y' "
						+ "WHERE ra.AD_Role_ID=? AND ra.IsActive='Y' AND o.AD_Client_ID IN (0,?) AND o.AD_Org_ID>0",
				Integer.valueOf(role), Integer.valueOf(client));
	}

	/** Existing record by id, or null when it does not exist. */
	protected PO load(int id, String trxName) {
		PO po = table.getPO(id, trxName);
		if (po == null)
			return null;
		return po.load(trxName, null) ? po : null;
	}

	/**
	 * Saves inside one transaction. The caller decides when to
	 * {@link #commit(String)}, so a method that writes several records still
	 * commits them atomically.
	 *
	 * @param po     the record to save
	 * @param trxName transaction created by {@link #newTrx(String)}
	 */
	protected void save(PO po, String trxName) throws ConfigException {
		try {
			// Log what we're about to save for diagnostics
			if (log.isLoggable(java.util.logging.Level.FINE)) {
				log.fine("Saving " + tableName + " (ID=" + po.get_ID() + ", is_new=" + po.is_new() + ") trx=" + trxName);
			}
			po.saveEx(trxName);
		} catch (Exception e) {
			// Identify the offending column if this is a ClassCastException
			String detail = e.getMessage();
			if (e instanceof ClassCastException || (detail != null && detail.contains("cannot be cast"))) {
				StringBuilder colDump = new StringBuilder("Column types for " + tableName + ": ");
				for (int i = 0; i < info.getColumnCount(); i++) {
					String cn = info.getColumnName(i);
					Class<?> cc = info.getColumnClass(i);
					Object val = po.get_Value(cn);
					if (val != null) {
						colDump.append(cn).append("=").append(val.getClass().getSimpleName()).append("(")
								.append(cc != null ? cc.getSimpleName() : "?").append(") ");
					}
				}
				log.severe(colDump.toString());
			}
			throw new ConfigException("Save failed for " + tableName + ": Error: " + e.getMessage(), e);
		}
	}

	/**
	 * Commits the transaction of a completed unit of work.
	 * <p>
	 * This has to be explicit. {@link #newTrx(String)} creates a real, independent
	 * database transaction, so {@link PO#save()} alone leaves the rows uncommitted
	 * and iDempiere discards them at shutdown - the service would appear to work
	 * and the configuration would be gone.
	 *
	 * @throws ConfigException when the commit itself fails
	 */
	protected void commit(String trxName) throws ConfigException {
		try {
			Trx trx = Trx.get(trxName, false);
			if (trx != null) {
				trx.commit();
				trx.close();
			}
		} catch (RuntimeException e) {
			rollback(trxName);
			throw new ConfigException(errorOr("Unable to commit the " + tableName + " change."), e);
		}
	}

	/** Undoes everything written in a failed unit of work. */
	protected void rollback(String trxName) {
		try {
			Trx trx = Trx.get(trxName, false);
			if (trx != null) {
				trx.rollback();
				trx.close();
			}
		} catch (RuntimeException e) {
			log.log(Level.SEVERE, "Rollback of " + tableName + " failed", e);
		}
	}

	/**
	 * Runs one unit of work in its own transaction.
	 * <p>
	 * Every write of this plugin goes through here, which is what makes
	 * "either the whole change is stored or none of it is" true everywhere rather
	 * than something each method has to remember.
	 *
	 * @param trxName transaction created by {@link #newTrx(String)}
	 * @param work    the work to perform, never null
	 * @return whatever the work returns
	 */
	protected <T> T inTrx(String trxName, Work<T> work) throws ConfigException {
		try {
			T result = work.run();
			commit(trxName);
			return result;
		} catch (ConfigException e) {
			rollback(trxName);
			throw e;
		} catch (RuntimeException e) {
			rollback(trxName);
			throw new ConfigException(failed("Work on " + tableName, e), e);
		}
	}

	/** Body of a unit of work run by {@link #inTrx(String, Work)}. */
	protected interface Work<T> {
		T run() throws ConfigException;
	}

	/** Creates a transaction name derived from the table being changed. */
	protected String newTrx(String suffix) {
		return Trx.createTrxName(tableName + suffix);
	}

	/** Id of a freshly inserted record. */
	protected int idOf(PO po) {
		if (po == null)
			return 0;
		int id = po.get_ID();
		if (id > 0)
			return id;
		if (hasColumn(keyColumn)) {
			Integer val = getInt(po, keyColumn);
			if (val != null && val.intValue() > 0)
				return val.intValue();
		}
		return 0;
	}

	/** Last iDempiere error, phrased for the user when there is one. */
	protected String errorOr(String fallback) {
		String error = CLogger.retrieveErrorString(null);
		return error == null || error.trim().length() == 0 ? fallback : error;
	}

	/** Logs an unexpected failure and returns the user facing text. */
	protected String failed(String what, RuntimeException e) {
		log.log(Level.SEVERE, what + " failed for " + tableName, e);
		String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		return errorOr("Unable to process the " + tableName + " record: " + msg);
	}

	/** Client of the running session. */
	protected int clientId() {
		return Env.getAD_Client_ID(ctx());
	}

	/** Organisation of the running session. */
	protected int orgId() {
		return Env.getAD_Org_ID(ctx());
	}

	/** Trims a value, returning null for an empty string. */
	protected static String trim(String value) {
		if (value == null)
			return null;
		String t = value.trim();
		return t.length() == 0 ? null : t;
	}
}
