package org.mycompany.requisition.approval.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.PO;
import org.mycompany.requisition.approval.config.ProcessCategory;
import org.mycompany.requisition.approval.config.ProcessDefinition;
import org.mycompany.requisition.approval.config.ProcessVersion;
import org.mycompany.requisition.approval.config.VersionStatus;
import org.mycompany.requisition.approval.config.WorkflowDefinition;
import org.mycompany.requisition.approval.config.WorkflowType;

/**
 * Read and write access to the master data of the approval framework:
 * process category, business process, process version and the workflow header.
 * <p>
 * Persistence goes through the standard {@link PO} model, so the tenant rules,
 * audit columns and mandatory field checks are iDempiere's own.
 * <p>
 * The interesting method here is {@link #activateVersion(int, int)}. Activating a
 * version moves the {@code IsCurrent} flag and demotes the previous one, but it
 * never touches {@code XX_PROCESS_TRANSACTION}. Historical documents keep the
 * version they were submitted against, which is the whole reason the version
 * exists.
 */
public class ProcessConfigService extends BaseConfigService {

	private static final String T_CATEGORY = "XX_PROCESS_CATEGORY";
	private static final String T_PROCESS = "XX_PROCESS";
	private static final String T_VERSION = "XX_PROCESS_VERSION";
	private static final String T_WORKFLOW = "XX_PROCESS_WORKFLOW";

	public ProcessConfigService() throws ConfigException {
		super(T_PROCESS);
	}

	// ─────────────────────────────────────────────────────────────
	//  Process category
	// ─────────────────────────────────────────────────────────────

	/** Categories of the current tenant, ordered by name. */
	public List<ProcessCategory> listCategories() {
		List<ProcessCategory> result = new ArrayList<ProcessCategory>();
		String sql = "SELECT XX_PROCESS_CATEGORY_ID, Value, Name, Description FROM " + T_CATEGORY
				+ " WHERE IsActive='Y' AND AD_Client_ID=? ORDER BY COALESCE(Name, Value)";
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, clientId());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ProcessCategory c = new ProcessCategory();
				c.setId(rs.getInt(1));
				c.setValue(rs.getString(2));
				c.setName(rs.getString(3));
				c.setDescription(rs.getString(4));
				result.add(c);
			}
		} catch (java.sql.SQLException e) {
			// a broken list must never break the window
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	/**
	 * Creates a category.
	 *
	 * @return the new XX_PROCESS_CATEGORY_ID
	 */
	public int createCategory(String value, String name, String description) throws ConfigException {
		String code = require(value, "Process Category code is required.");
		String label = name == null ? code : name;
		if (exists(T_CATEGORY, "Value", code))
			throw new ConfigException("Process Category \"" + code + "\" already exists.");

		final String trxName = newTrx("Cat");
		return inTrx(trxName, () -> {
			PO po = org.compiere.model.MTable.get(ctx(), T_CATEGORY).getPO(0, trxName);
			if (po == null)
				throw new ConfigException("Table " + T_CATEGORY + " is not registered in iDempiere.");
			po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));
			set(po, "Value", code);
			set(po, "Name", label);
			set(po, "Description", description);
			set(po, "IsActive", "Y");
			save(po, trxName);
			return Integer.valueOf(po.get_ID());
		}).intValue();
	}

	// ─────────────────────────────────────────────────────────────
	//  Process
	// ─────────────────────────────────────────────────────────────

	/** Processes of the current tenant, ordered by name. */
	public List<ProcessDefinition> listProcesses(int categoryId) {
		List<ProcessDefinition> result = new ArrayList<ProcessDefinition>();
		// documenttype_id is the physical column; the dictionary spells it
		// DocumentTypeID and the rule table spells the same idea C_DocType_ID. Raw
		// SQL has to use the physical name because an unquoted identifier is folded
		// to lower case.
		StringBuilder sql = new StringBuilder("SELECT XX_PROCESS_ID, XX_PROCESS_CATEGORY_ID, Value, Name,")
				.append(" Description, ProcessType, AD_Table_ID, AD_Window_ID, AD_Workflow_ID, documenttype_id")
				.append(" FROM ").append(T_PROCESS)
				.append(" WHERE IsActive='Y' AND AD_Client_ID=?");
		List<Object> params = new ArrayList<Object>();
		params.add(Integer.valueOf(clientId()));
		if (categoryId > 0) {
			sql.append(" AND XX_PROCESS_CATEGORY_ID=?");
			params.add(Integer.valueOf(categoryId));
		}
		sql.append(" ORDER BY COALESCE(Name, Value)");

		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql.toString(), null);
			for (int i = 0; i < params.size(); i++)
				pstmt.setObject(i + 1, params.get(i));
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ProcessDefinition p = new ProcessDefinition();
				p.setId(rs.getInt(1));
				p.setCategoryId(rs.getInt(2));
				p.setValue(rs.getString(3));
				p.setName(rs.getString(4));
				p.setDescription(rs.getString(5));
				p.setProcessType(rs.getString(6));
				p.setTableId(rs.getInt(7));
				p.setWindowId(rs.getInt(8));
				p.setWorkflowId(rs.getInt(9));
				p.setDocumentTypeId(rs.getInt(10));
				result.add(p);
			}
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	/**
	 * Creates a business process.
	 *
	 * @param def      the process to create, its id is ignored
	 * @return the new XX_PROCESS_ID
	 */
	public int createProcess(ProcessDefinition def) throws ConfigException {
		if (def == null)
			throw new ConfigException("No process data was entered.");
		String code = require(def.getValue(), "Process Code is required.");
		if (exists(T_PROCESS, "Value", code))
			throw new ConfigException("Process \"" + code + "\" already exists.");

		final String trxName = newTrx("Proc");
		return inTrx(trxName, () -> {
			PO po = org.compiere.model.MTable.get(ctx(), T_PROCESS).getPO(0, trxName);
			if (po == null)
				throw new ConfigException("Table " + T_PROCESS + " is not registered in iDempiere.");
			po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));
			apply(po, def);
			save(po, trxName);
			return Integer.valueOf(po.get_ID());
		}).intValue();
	}

	/** Updates an existing process. */
	public void updateProcess(ProcessDefinition def) throws ConfigException {
		if (def == null || def.getId() <= 0)
			throw new ConfigException("No process is selected for updating.");

		final String trxName = newTrx("ProcUpd");
		inTrx(trxName, () -> {
			PO po = org.compiere.model.MTable.get(ctx(), T_PROCESS).getPO(def.getId(), trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Process " + def.getId() + " was not found.");

			String code = require(def.getValue(), "Process Code is required.");
			if (existsOther(T_PROCESS, "Value", code, def.getId()))
				throw new ConfigException("Process \"" + code + "\" already exists.");

			apply(po, def);
			save(po, trxName);
			return null;
		});
	}

	private void apply(PO po, ProcessDefinition def) throws ConfigException {
		set(po, "XX_PROCESS_CATEGORY_ID", def.getCategoryId() > 0 ? Integer.valueOf(def.getCategoryId()) : null);
		set(po, "Value", require(def.getValue(), "Process Code is required."));
		set(po, "Name", def.getName());
		set(po, "Description", def.getDescription());
		set(po, "ProcessType", def.getProcessType());
		set(po, "AD_Table_ID", def.getTableId() > 0 ? Integer.valueOf(def.getTableId()) : null);
		set(po, "AD_Window_ID", def.getWindowId() > 0 ? Integer.valueOf(def.getWindowId()) : null);
		set(po, "AD_Workflow_ID", def.getWorkflowId() > 0 ? Integer.valueOf(def.getWorkflowId()) : null);
		// the column is DocumentTypeID on XX_PROCESS, not C_DocType_ID as on the rule table
		set(po, "DocumentTypeID", def.getDocumentTypeId() > 0 ? Integer.valueOf(def.getDocumentTypeId()) : null);
		set(po, "IsActive", "Y");
	}

	// ─────────────────────────────────────────────────────────────
	//  Process version
	// ─────────────────────────────────────────────────────────────

	/** Versions of one process, newest first. */
	public List<ProcessVersion> listVersions(int processId) {
		List<ProcessVersion> result = new ArrayList<ProcessVersion>();
		String sql = "SELECT XX_PROCESS_VERSION_ID, XX_PROCESS_ID, Version, Name, EffectiveFrom,"
				+ " EffectiveTo, IsCurrent, Status, Description FROM " + T_VERSION
				+ " WHERE XX_PROCESS_ID=? ORDER BY Created DESC";
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, processId);
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ProcessVersion v = new ProcessVersion();
				v.setId(rs.getInt(1));
				v.setProcessId(rs.getInt(2));
				v.setVersion(rs.getString(3));
				v.setName(rs.getString(4));
				v.setEffectiveFrom(rs.getTimestamp(5));
				v.setEffectiveTo(rs.getTimestamp(6));
				v.setCurrent("Y".equalsIgnoreCase(rs.getString(7)));
				v.setStatus(VersionStatus.of(rs.getString(8)));
				v.setDescription(rs.getString(9));
				result.add(v);
			}
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	/**
	 * The version new submissions will use, or null when none is active.
	 */
	public ProcessVersion getCurrentVersion(int processId) {
		String sql = "SELECT XX_PROCESS_VERSION_ID FROM " + T_VERSION
				+ " WHERE XX_PROCESS_ID=? AND IsCurrent='Y' AND IsActive='Y' ORDER BY Created DESC LIMIT 1";
		int id = org.compiere.util.DB.getSQLValue(null, sql, Integer.valueOf(processId));
		if (id <= 0)
			return null;
		for (ProcessVersion v : listVersions(processId))
			if (v.getId() == id)
				return v;
		return null;
	}

	/** Creates a new version of a process. */
	public int createVersion(ProcessVersion version) throws ConfigException {
		if (version == null || version.getProcessId() <= 0)
			throw new ConfigException("The process of the version is unknown.");
		String code = require(version.getVersion(), "Version is required.");
		if (existsVersion(version.getProcessId(), code, 0))
			throw new ConfigException("Version \"" + code + "\" already exists for this process.");

		final String trxName = newTrx("Ver");
		return inTrx(trxName, () -> {
			PO po = org.compiere.model.MTable.get(ctx(), T_VERSION).getPO(0, trxName);
			if (po == null)
				throw new ConfigException("Table " + T_VERSION + " is not registered in iDempiere.");
			po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));
			set(po, "XX_PROCESS_ID", Integer.valueOf(version.getProcessId()));
			set(po, "Version", code);
			set(po, "Name", version.getName());
			set(po, "EffectiveFrom", version.getEffectiveFrom());
			set(po, "EffectiveTo", version.getEffectiveTo());
			set(po, "Status", version.getStatus().name());
			set(po, "IsCurrent", "N");
			set(po, "Description", version.getDescription());
			set(po, "IsActive", "Y");
			save(po, trxName);
			return Integer.valueOf(po.get_ID());
		}).intValue();
	}

	/**
	 * Makes one version the current one and demotes the others.
	 * <p>
	 * Only the {@code IsCurrent} flag moves. Transactions created earlier keep
	 * their own {@code Process_Version_ID}, so their approval chain is unchanged.
	 *
	 * @param versionId version to activate
	 * @param status    status to store, normally ACTIVE or INACTIVE
	 */
	public void activateVersion(int versionId, VersionStatus status) throws ConfigException {
		if (versionId <= 0)
			throw new ConfigException("No version is selected.");

		PO target = org.compiere.model.MTable.get(ctx(), T_VERSION).getPO(versionId, null);
		if (target == null || !target.load(null, null))
			throw new ConfigException("Version " + versionId + " was not found.");

		int processId = getInt(target, "XX_PROCESS_ID", 0);
		final String trxName = newTrx("VerAct");

		inTrx(trxName, () -> {
			// demote the rest of this process, only the flag, nothing else
			PO others = org.compiere.model.MTable.get(ctx(), T_VERSION).getPO(0, trxName);
			if (others != null) {
				java.sql.PreparedStatement pstmt = null;
				try {
					pstmt = org.compiere.util.DB.prepareStatement(
							"UPDATE " + T_VERSION + " SET IsCurrent='N' WHERE XX_PROCESS_ID=? AND XX_PROCESS_VERSION_ID<>?",
							trxName);
					pstmt.setInt(1, processId);
					pstmt.setInt(2, versionId);
					pstmt.executeUpdate();
				} catch (java.sql.SQLException e) {
					throw new ConfigException("Unable to demote the previous version.", e);
				} finally {
					org.compiere.util.DB.close(pstmt);
				}
			}

			PO po = org.compiere.model.MTable.get(ctx(), T_VERSION).getPO(versionId, trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Version " + versionId + " was not found.");
			set(po, "IsCurrent", "Y");
			set(po, "Status", (status == null ? VersionStatus.ACTIVE : status).name());
			save(po, trxName);
			return null;
		});
	}

	// ─────────────────────────────────────────────────────────────
	//  Workflow header
	// ─────────────────────────────────────────────────────────────

	/** The workflow of a version, or null when none is configured yet. */
	public WorkflowDefinition getWorkflow(int processVersionId) {
		String sql = "SELECT XX_PROCESS_WORKFLOW_ID, XX_PROCESS_VERSION_ID, Value, Name, WorkflowType,"
				+ " IsApprovalRequired, IsAutoStart, Description FROM " + T_WORKFLOW
				+ " WHERE XX_PROCESS_VERSION_ID=? AND IsActive='Y' ORDER BY Created DESC LIMIT 1";
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, processVersionId);
			rs = pstmt.executeQuery();
			if (rs.next()) {
				WorkflowDefinition w = new WorkflowDefinition();
				w.setId(rs.getInt(1));
				w.setProcessVersionId(rs.getInt(2));
				w.setValue(rs.getString(3));
				w.setName(rs.getString(4));
				w.setWorkflowType(WorkflowType.of(rs.getString(5)));
				w.setApprovalRequired("Y".equalsIgnoreCase(rs.getString(6)));
				w.setAutoStart("Y".equalsIgnoreCase(rs.getString(7)));
				w.setDescription(rs.getString(8));
				return w;
			}
		} catch (java.sql.SQLException e) {
			return null;
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return null;
	}

	/** Creates or replaces the workflow header of a version. */
	public void saveWorkflow(WorkflowDefinition workflow) throws ConfigException {
		if (workflow == null || workflow.getProcessVersionId() <= 0)
			throw new ConfigException("The process version of the workflow is unknown.");

		final String trxName = newTrx("Wf");
		inTrx(trxName, () -> {
			PO po = getWorkflow(workflow.getProcessVersionId()) == null
					? org.compiere.model.MTable.get(ctx(), T_WORKFLOW).getPO(0, trxName)
					: org.compiere.model.MTable.get(ctx(), T_WORKFLOW).getPO(workflow.getId(), trxName);
			if (po == null)
				throw new ConfigException("Table " + T_WORKFLOW + " is not registered in iDempiere.");
			if (po.get_ID() > 0)
				po.load(trxName, null);
			else
				po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));

			set(po, "XX_PROCESS_VERSION_ID", Integer.valueOf(workflow.getProcessVersionId()));
			set(po, "Value", workflow.getValue());
			set(po, "Name", workflow.getName());
			set(po, "WorkflowType", workflow.getWorkflowType().name());
			set(po, "IsApprovalRequired", workflow.isApprovalRequired() ? "Y" : "N");
			set(po, "IsAutoStart", workflow.isAutoStart() ? "Y" : "N");
			set(po, "Description", workflow.getDescription());
			set(po, "IsActive", "Y");
			save(po, trxName);
			workflow.setId(po.get_ID());
			return null;
		});
	}

	// ─────────────────────────────────────────────────────────────
	//  helpers
	// ─────────────────────────────────────────────────────────────

	private static String require(String value, String message) throws ConfigException {
		String t = trim(value);
		if (t == null)
			throw new ConfigException(message);
		return t;
	}

	private boolean exists(String table, String column, String value) {
		return org.compiere.util.DB.getSQLValue(null,
				"SELECT COUNT(*) FROM " + table + " WHERE UPPER(" + column + ")=UPPER(?) AND AD_Client_ID=?",
				value, Integer.valueOf(clientId())) > 0;
	}

	private boolean existsOther(String table, String column, String value, int excludeId) {
		return org.compiere.util.DB.getSQLValue(null,
				"SELECT COUNT(*) FROM " + table + " WHERE UPPER(" + column + ")=UPPER(?)"
						+ " AND AD_Client_ID=? AND " + table + "_ID<>?",
				value, Integer.valueOf(clientId()), Integer.valueOf(excludeId)) > 0;
	}

	private boolean existsVersion(int processId, String version, int excludeId) {
		return org.compiere.util.DB.getSQLValue(null,
				"SELECT COUNT(*) FROM " + T_VERSION + " WHERE UPPER(Version)=UPPER(?)"
						+ " AND XX_PROCESS_ID=? AND XX_PROCESS_VERSION_ID<>?",
				version, Integer.valueOf(processId), Integer.valueOf(excludeId)) > 0;
	}
}
