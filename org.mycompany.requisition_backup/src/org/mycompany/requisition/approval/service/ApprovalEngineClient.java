package org.mycompany.requisition.approval.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.model.POInfo;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.mycompany.requisition.approval.config.ProcessVersion;
import org.mycompany.requisition.approval.model.ApprovalAction;
import org.mycompany.requisition.approval.model.ApprovalStatus;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.model.DocumentContext;

/**
 * The one class a business module talks to.
 * <p>
 * A module that wants approval for its documents - a purchase requisition, a
 * sales order, anything - calls {@link #start} when the document is submitted and
 * {@link #mayProceed} before it releases it. It never mentions a process, a
 * version, a level or a rule.
 *
 * <h3>Why this matters for onboarding</h3>
 * The engine resolves the process from the target table and the document type on
 * its own. Onboarding a new process is therefore configuration work - create a
 * process, activate a version, define levels and rules - and no change to a
 * business module and none to the engine. That is the reusability rule the design
 * states, expressed as an API.
 *
 * <h3>What it deliberately does not do</h3>
 * It does not write {@code DocStatus} or any other column of the business
 * document. Which column means "approved" differs per document, and this layer
 * does not know. A module decides that itself from {@link #mayProceed}, so the
 * approval engine can never quietly change a document a business module owns.
 *
 * @see WorkflowEngine for the transitions themselves
 */
public class ApprovalEngineClient {

	private static final CLogger log = CLogger.getCLogger(ApprovalEngineClient.class);

	/** Standard column names read from a business document when it supplies no value. */
	private static final String[] NO_COLUMNS = { "DocumentNo", "DocNo" };
	private static final String[] AMOUNT_COLUMNS = { "GrandTotal", "TotalLines", "TotalAmt", "LinesTotal",
			"Total_Amount" };

	/** Opened once: every method resolves the tables again on first use. */
	private WorkflowEngine engine;

	// ─────────────────────────────────────────────────────────────
	//  Starting an approval
	// ─────────────────────────────────────────────────────────────

	/**
	 * Starts the approval chain of a document, resolving the process itself.
	 * <p>
	 * This is the call a business module makes. The amount is read from the
	 * document, because it is what the rules are banded on, and a business module
	 * that has its own amount column in mind can use
	 * {@link #start(int, int, BigDecimal, int, String)} instead.
	 *
	 * @param tableId  table of the document, for example {@code AD_Table_ID} of
	 *                 the sales order window
	 * @param recordId the document record
	 * @return the transaction that was created, or null when no process is
	 *         configured for this table, in which case the document is simply not
	 *         under approval
	 * @throws BaseConfigService.ConfigException when the document cannot be
	 *                                            submitted for approval
	 */
	public ApprovalTransaction start(int tableId, int recordId) throws BaseConfigService.ConfigException {
		return start(tableId, recordId, null, 0, null);
	}

	/**
	 * Starts the approval chain with values the caller already knows.
	 *
	 * @param tableId       table of the document
	 * @param recordId      the document record
	 * @param amount        amount the rules are evaluated on, may be null to read
	 *                      it from the document
	 * @param documentTypeId document type, used to pick the process when a table
	 *                      carries more than one process
	 * @param comment       optional note kept in the audit trail
	 * @return the transaction, or null when no process matches
	 */
	public ApprovalTransaction start(int tableId, int recordId, BigDecimal amount, int documentTypeId,
			String comment) throws BaseConfigService.ConfigException {

		ProcessMatch match = findProcess(tableId, documentTypeId);
		if (match == null)
			return null;

		DocumentContext context = buildContext(tableId, recordId, amount, documentTypeId);
		return new WorkflowEngine().submit(match.versionId, context, comment);
	}

	// ─────────────────────────────────────────────────────────────
	//  Acting on a document
	// ─────────────────────────────────────────────────────────────

	/**
	 * Approves, rejects, returns or skips the level this user is on, addressing
	 * the step by document rather than by transaction id.
	 * <p>
	 * A business module and a notification handler both know the document, not the
	 * approval transaction, so this is the form they use.
	 *
	 * @param tableId  table of the document
	 * @param recordId the document record
	 * @param action   what to do
	 * @param comment  free text, mandatory for reject and return
	 * @return the transaction after the action
	 */
	public ApprovalTransaction act(int tableId, int recordId, ApprovalAction action, String comment)
			throws BaseConfigService.ConfigException {

		ApprovalTransaction chain = requireTransaction(tableId, recordId);
		ApprovalStep step = findActionableStep(chain);
		if (step == null)
			throw new BaseConfigService.ConfigException("No approval step of " + chain.getDisplayName()
					+ " is waiting for you.");
		return new WorkflowEngine().act(chain.getId(), step.getId(), action, comment);
	}

	/**
	 * The step this user may act on, or null when there is none.
	 * <p>
	 * The engine is asked, so the answer already includes the administrator
	 * override and the delegation that was applied when the step was created.
	 */
	public ApprovalStep findActionableStep(ApprovalTransaction chain) {
		if (chain == null)
			return null;
		WorkflowEngine engine = engineOrNull();
		if (engine == null)
			return null;
		int user = Env.getAD_User_ID(Env.getCtx());
		int role = Env.getAD_Role_ID(Env.getCtx());
		for (ApprovalStep step : chain.getSteps())
			if (engine.canAct(step, user, role))
				return step;
		return null;
	}

	// ─────────────────────────────────────────────────────────────
	//  Reading the state of a document
	// ─────────────────────────────────────────────────────────────

	/**
	 * The status of the approval of a document, or null when the document was
	 * never submitted for approval.
	 */
	public ApprovalStatus statusOf(int tableId, int recordId) {
		ApprovalTransaction chain = findTransaction(tableId, recordId);
		return chain == null ? null : chain.getStatus();
	}

	/** The approval transaction of a document, newest first, or null. */
	public ApprovalTransaction findTransaction(int tableId, int recordId) {
		WorkflowEngine engine = engineOrNull();
		if (engine == null || tableId <= 0 || recordId <= 0)
			return null;
		int id = DB.getSQLValue(null,
				"SELECT MAX(XX_PROCESS_TRANSACTION_ID) FROM XX_PROCESS_TRANSACTION"
						+ " WHERE AD_TABLE_ID=? AND RECORD_ID=? AND IsActive='Y' AND AD_Client_ID=?",
				Integer.valueOf(tableId), Integer.valueOf(recordId),
				Integer.valueOf(Env.getAD_Client_ID(Env.getCtx())));
		if (id <= 0)
			return null;
		return engine.loadById(id);
	}

	/** The transaction currently in progress, or null when there is none. */
	public ApprovalTransaction openTransaction(int tableId, int recordId) {
		WorkflowEngine engine = engineOrNull();
		if (engine == null)
			return null;
		int id = engine.findOpen(tableId, recordId);
		return id <= 0 ? null : engine.loadById(id);
	}

	/**
	 * Whether the document may be released.
	 * <p>
	 * True only when an approval chain finished and every blocking level approved
	 * it. A document that was never submitted for approval, or whose chain is
	 * still running, rejected, returned or cancelled, is not releasable.
	 *
	 * @return true when the caller may continue
	 */
	public boolean mayProceed(int tableId, int recordId) {
		ApprovalTransaction chain = findTransaction(tableId, recordId);
		if (chain == null)
			return false;
		return chain.getStatus() == ApprovalStatus.APPROVED && chain.isFullyApproved();
	}

	/** True when the document is waiting for a decision. */
	public boolean isPending(int tableId, int recordId) {
		ApprovalStatus status = statusOf(tableId, recordId);
		return status == ApprovalStatus.PENDING || status == ApprovalStatus.CREATED;
	}

	/** True when the document was sent back for correction. */
	public boolean needsCorrection(int tableId, int recordId) {
		ApprovalStatus status = statusOf(tableId, recordId);
		return status == ApprovalStatus.REJECTED || status == ApprovalStatus.RETURNED;
	}

	/** Documents of this table that this user currently has to act on. */
	public List<ApprovalTransaction> pendingForMe() {
		WorkflowEngine engine = engineOrNull();
		int user = Env.getAD_User_ID(Env.getCtx());
		int role = Env.getAD_Role_ID(Env.getCtx());
		if (engine == null || user <= 0)
			return new ArrayList<ApprovalTransaction>();
		return engine.listPendingFor(user, role);
	}

	/** True when at least one process is configured for this table. */
	public boolean isUnderApproval(int tableId, int documentTypeId) {
		return findProcess(tableId, documentTypeId) != null;
	}

	// ─────────────────────────────────────────────────────────────
	//  Process resolution
	// ─────────────────────────────────────────────────────────────

	/**
	 * Finds the process that governs a document.
	 * <p>
	 * A table may carry several processes, one per document type. The most
	 * specific match wins, so a document type wins over the catch all entry.
	 *
	 * @return the match, or null when no process covers this document
	 */
	public ProcessMatch findProcess(int tableId, int documentTypeId) {
		if (tableId <= 0)
			return null;

		// The physical column is documenttype_id; the dictionary spells it
		// DocumentTypeID and the rule table spells the same idea C_DocType_ID. Raw
		// SQL has to use the physical name because an unquoted identifier is folded
		// to lower case, so p.DocumentTypeID would not resolve.
		String sql = "SELECT p.XX_PROCESS_ID, p.Value, p.Name, p.AD_Window_ID, v.XX_PROCESS_VERSION_ID,"
				+ " v.Version, v.Status, v.IsCurrent"
				+ " FROM XX_PROCESS p"
				+ " INNER JOIN XX_PROCESS_VERSION v ON v.XX_PROCESS_ID=p.XX_PROCESS_ID"
				+ " WHERE p.AD_TABLE_ID=? AND p.IsActive='Y' AND v.IsActive='Y'"
				+ " AND v.IsCurrent='Y' AND v.Status='ACTIVE'";
		if (documentTypeId > 0)
			sql += " AND (p.documenttype_id=? OR p.documenttype_id IS NULL)";
		sql += " ORDER BY CASE WHEN p.documenttype_id=? THEN 0 ELSE 1 END, p.XX_PROCESS_ID";

		List<ProcessMatch> matches = new ArrayList<ProcessMatch>();
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, tableId);
			int index = 2;
			if (documentTypeId > 0)
				pstmt.setInt(index++, documentTypeId);
			pstmt.setInt(index, documentTypeId > 0 ? documentTypeId : 0);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ProcessMatch m = new ProcessMatch();
				m.processId = rs.getInt(1);
				m.value = rs.getString(2);
				m.name = rs.getString(3);
				m.windowId = rs.getInt(4);
				m.versionId = rs.getInt(5);
				m.version = rs.getString(6);
				matches.add(m);
			}
		} catch (java.sql.SQLException e) {
			log.log(java.util.logging.Level.WARNING, "Process lookup for table " + tableId + " failed", e);
			return null;
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}

		if (matches.isEmpty())
			return null;
		if (matches.size() > 1)
			log.log(java.util.logging.Level.INFO, "Table " + tableId + " is covered by "
					+ matches.size() + " processes; using " + matches.get(0).value
					+ ". Narrow this down by giving the document type.");
		return matches.get(0);
	}

	/** Process and current version found for a document. */
	public static final class ProcessMatch {

		public int processId;
		public int versionId;
		public int windowId;
		public String value;
		public String name;
		public String version;

		public int getProcessId() {
			return processId;
		}

		public int getVersionId() {
			return versionId;
		}

		public int getWindowId() {
			return windowId;
		}

		public String getValue() {
			return value;
		}

		public String getName() {
			return name;
		}

		public String getVersion() {
			return version;
		}

		public String getDisplayName() {
			return value + (name == null || name.equals(value) ? "" : " - " + name) + " (version " + version + ")";
		}
	}

	// ─────────────────────────────────────────────────────────────
	//  helpers
	// ─────────────────────────────────────────────────────────────

	/** Document facts the rule engine needs, filled from the document record. */
	private DocumentContext buildContext(int tableId, int recordId, BigDecimal amount, int documentTypeId)
			throws BaseConfigService.ConfigException {

		DocumentContext context = new DocumentContext();
		context.setTableId(tableId);
		context.setRecordId(recordId);
		context.setAmount(amount);
		context.setDocumentTypeId(documentTypeId);
		context.setOrgId(orgOf(tableId, recordId));
		context.setUserId(Env.getAD_User_ID(Env.getCtx()));
		context.setRoleId(Env.getAD_Role_ID(Env.getCtx()));
		context.setWarehouseId(sessionWarehouse());
		context.setRequestedBy(Env.getAD_User_ID(Env.getCtx()));
		context.setBusinessPartnerId(businessPartnerOf(tableId, recordId));
		context.setProjectId(0);

		String documentNo = readString(tableId, recordId, NO_COLUMNS);
		if (documentNo != null)
			context.setDocumentNo(documentNo);
		if (context.getAmount() == null)
			context.setAmount(readAmount(tableId, recordId));
		return context;
	}

	/** Reads the first column of a candidate list that the table actually has. */
	private Object readAny(int tableId, int recordId, String[] candidates) {
		POInfo info = poInfo(tableId);
		MTable table = MTable.get(Env.getCtx(), tableId);
		if (info == null || table == null)
			return null;
		PO po = table.getPO(recordId, null);
		if (po == null || !po.load(null, null))
			return null;
		for (String column : candidates)
			if (info.getColumnIndex(column) >= 0)
				return po.get_Value(column);
		return null;
	}

	private String readString(int tableId, int recordId, String[] candidates) {
		Object value = readAny(tableId, recordId, candidates);
		return value == null ? null : value.toString().trim();
	}

	private BigDecimal readAmount(int tableId, int recordId) {
		Object value = readAny(tableId, recordId, AMOUNT_COLUMNS);
		if (value == null)
			return null;
		if (value instanceof BigDecimal)
			return (BigDecimal) value;
		try {
			return new BigDecimal(value.toString().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Business partner of the document, when it has one. */
	private int businessPartnerOf(int tableId, int recordId) {
		Object value = readAny(tableId, recordId, new String[] { "C_BPartner_ID" });
		if (value == null)
			return 0;
		try {
			return Integer.parseInt(value.toString().trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Organisation of the document itself, so the rules see the right one. */
	private int orgOf(int tableId, int recordId) {
		Object value = readAny(tableId, recordId, new String[] { "AD_Org_ID" });
		if (value != null)
			try {
				int org = Integer.parseInt(value.toString().trim());
				if (org > 0)
					return org;
			} catch (NumberFormatException e) {
				// fall through to the organisation of the session
			}
		int session = Env.getAD_Org_ID(Env.getCtx());
		return session > 0 ? session : 0;
	}

	/**
	 * Warehouse of the session.
	 * <p>
	 * Read from the context property rather than through a typed accessor, because
	 * this iDempiere build keeps the warehouse as a plain context entry and
	 * {@code Env} has no getter for it. Zero simply means no warehouse, which the
	 * rule engine reads as "any".
	 */
	private int sessionWarehouse() {
		String value = Env.getContext(Env.getCtx(), Env.M_WAREHOUSE_ID);
		if (value == null || value.trim().length() == 0)
			return 0;
		try {
			int warehouse = Integer.parseInt(value.trim());
			return warehouse > 0 ? warehouse : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private POInfo poInfo(int tableId) {
		try {
			return POInfo.getPOInfo(Env.getCtx(), tableId);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private ApprovalTransaction requireTransaction(int tableId, int recordId)
			throws BaseConfigService.ConfigException {
		ApprovalTransaction chain = openTransaction(tableId, recordId);
		if (chain == null)
			throw new BaseConfigService.ConfigException("Document " + tableId + "/" + recordId
					+ " is not currently under approval.");
		return chain;
	}

	/**
	 * The engine, or null when it cannot be opened.
	 * <p>
	 * A read of the approval state must never take a screen down, so the
	 * configuration being unavailable degrades to "nothing known" instead of
	 * raising. Only the calls that change something report a failure.
	 */
	private WorkflowEngine engineOrNull() {
		if (engine == null) {
			try {
				engine = new WorkflowEngine();
			} catch (BaseConfigService.ConfigException e) {
				log.log(java.util.logging.Level.WARNING,
						"Approval engine is not available, the approval tables may not be installed", e);
			}
		}
		return engine;
	}

	/** Versions usable for new transactions, for the Test Workflow screen. */
	public List<ProcessVersion> usableVersions(int processId) {
		List<ProcessVersion> result = new ArrayList<ProcessVersion>();
		try {
			for (ProcessVersion v : new ProcessConfigService().listVersions(processId))
				if (v.isUsableForNewTransactions())
					result.add(v);
		} catch (BaseConfigService.ConfigException e) {
			log.log(java.util.logging.Level.WARNING,
					"Versions of process " + processId + " could not be read", e);
		}
		return result;
	}
}
