package org.mycompany.requisition.approval.service;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.ApprovalType;
import org.mycompany.requisition.approval.config.ResponsibleType;

/**
 * Read and write access to the approval levels of a process version.
 * <p>
 * This is the service behind section 04 of the window, including the Add, Edit,
 * Delete, Move Up and Move Down buttons.
 * <p>
 * Reordering rewrites {@code LevelNo} and nothing else. That is deliberate: the
 * level identity stays the same, so the audit history of a transaction that
 * already ran still points at the right level even after the order of the chain
 * has been changed for future documents.
 */
public class LevelConfigService extends BaseConfigService {

	private static final String T_LEVEL = "XX_PROCESS_LEVEL";

	public LevelConfigService() throws ConfigException {
		super(T_LEVEL);
	}

	/** Levels of one version, ordered by level number. */
	public List<ApprovalLevel> listLevels(int processVersionId) {
		List<ApprovalLevel> result = new ArrayList<ApprovalLevel>();
		String sql = "SELECT XX_PROCESS_LEVEL_ID, XX_PROCESS_VERSION_ID, LevelNo, LevelName, ApprovalStage,"
				+ " ResponsibleType, AD_User_ID, AD_Role_ID, AD_Org_ID_Approver, ApprovalType, IsRequired,"
				+ " SLAHours, WarningBeforeHours, EscalationEnabled, EscalationAfterHours,"
				+ " Escalation_User_ID, Escalation_Role_ID, Description, LowerLimit, UpperLimit,"
				+ " IsFinalApprover, HR_Department_ID, XX_Process_ID, XX_Process_Category_ID"
				+ " FROM " + T_LEVEL + " WHERE XX_PROCESS_VERSION_ID=? ORDER BY LevelNo";
		PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, processVersionId);
			rs = pstmt.executeQuery();
			while (rs.next())
				result.add(read(rs));
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	private ApprovalLevel read(java.sql.ResultSet rs) throws java.sql.SQLException {
		ApprovalLevel l = new ApprovalLevel();
		l.setId(rs.getInt(1));
		l.setProcessVersionId(rs.getInt(2));
		l.setLevelNo(rs.getInt(3));
		l.setLevelName(rs.getString(4));
		l.setApprovalStage(rs.getString(5));
		l.setResponsibleType(ResponsibleType.of(rs.getString(6)));
		l.setUserId(rs.getInt(7));
		l.setRoleId(rs.getInt(8));
		l.setApproverOrgId(rs.getInt(9));
		l.setApprovalType(ApprovalType.of(rs.getString(10)));
		l.setRequired("Y".equalsIgnoreCase(rs.getString(11)));

		int sla = rs.getInt(12);
		l.setSlaHours(rs.wasNull() ? null : Integer.valueOf(sla));
		int warn = rs.getInt(13);
		l.setWarningBeforeHours(rs.wasNull() ? null : Integer.valueOf(warn));
		l.setEscalationEnabled("Y".equalsIgnoreCase(rs.getString(14)));
		int esc = rs.getInt(15);
		l.setEscalationAfterHours(rs.wasNull() ? null : Integer.valueOf(esc));
		l.setEscalationUserId(rs.getInt(16));
		l.setEscalationRoleId(rs.getInt(17));
		l.setDescription(rs.getString(18));
		l.setLowerLimit(decimal(rs, 19));
		l.setUpperLimit(decimal(rs, 20));
		l.setFinalApprover("Y".equalsIgnoreCase(rs.getString(21)));
		l.setDepartmentId(rs.getInt(22));
		l.setProcessId(rs.getInt(23));
		l.setProcessCategoryId(rs.getInt(24));
		return l;
	}

	/** A nullable numeric column, because a limit that is not set is not zero. */
	private java.math.BigDecimal decimal(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
		java.math.BigDecimal value = rs.getBigDecimal(index);
		return rs.wasNull() ? null : value;
	}

	/**
	 * Appends a level at the end of the chain.
	 *
	 * @return the new XX_PROCESS_LEVEL_ID
	 */
	public int addLevel(int processVersionId, ApprovalLevel level) throws ConfigException {
		if (processVersionId <= 0)
			throw new ConfigException("The process version of the level is unknown.");
		if (level == null)
			throw new ConfigException("No level data was entered.");
		validateBand(level);

		final String trxName = newTrx("Lvl");
		return inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_LEVEL).getPO(0, trxName);
			if (po == null)
				throw new ConfigException("Table " + T_LEVEL + " is not registered in iDempiere.");
			po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));

			set(po, "XX_PROCESS_VERSION_ID", Integer.valueOf(processVersionId));
			set(po, "LevelNo", Integer.valueOf(nextLevelNo(processVersionId)));
			apply(po, level);
			save(po, trxName);
			return Integer.valueOf(po.get_ID());
		}).intValue();
	}

	/** Updates an existing level. The level number is left to the move methods. */
	public void updateLevel(ApprovalLevel level) throws ConfigException {
		if (level == null || level.getId() <= 0)
			throw new ConfigException("No level is selected for updating.");
		validateBand(level);

		final String trxName = newTrx("LvlUpd");
		inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_LEVEL).getPO(level.getId(), trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Level " + level.getId() + " was not found.");

			apply(po, level);
			save(po, trxName);
			return null;
		});
	}

	private void apply(PO po, ApprovalLevel level) throws ConfigException {
		if (trim(level.getLevelName()) == null)
			throw new ConfigException("Level Name is required.");

		set(po, "LevelName", trim(level.getLevelName()));
		set(po, "ApprovalStage", trim(level.getApprovalStage()));
		set(po, "ResponsibleType", level.getResponsibleType().name());

		// only the responsible field that matches the type is stored, so a level
		// can never carry a user id while claiming to be a role. Which field that is
		// comes from the enum, so a new type cannot be added here without also being
		// made writable.
		set(po, "AD_User_ID", level.getResponsibleType() == ResponsibleType.USER
				? (level.getUserId() > 0 ? Integer.valueOf(level.getUserId()) : null) : null);
		set(po, "AD_Role_ID", level.getResponsibleType() == ResponsibleType.ROLE
				? (level.getRoleId() > 0 ? Integer.valueOf(level.getRoleId()) : null) : null);
		set(po, "HR_Department_ID", level.getResponsibleType() == ResponsibleType.DEPARTMENT
				? (level.getDepartmentId() > 0 ? Integer.valueOf(level.getDepartmentId()) : null) : null);

		set(po, "AD_Org_ID_Approver", level.getApproverOrgId() > 0 ? Integer.valueOf(level.getApproverOrgId()) : null);
		set(po, "ApprovalType", level.getApprovalType().name());
		set(po, "IsRequired", level.isRequired() ? "Y" : "N");
		set(po, "SLAHours", level.getSlaHours());
		set(po, "WarningBeforeHours", level.getWarningBeforeHours());
		set(po, "EscalationEnabled", level.isEscalationEnabled() ? "Y" : "N");
		set(po, "EscalationAfterHours", level.getEscalationAfterHours());
		set(po, "Escalation_User_ID", level.getEscalationUserId() > 0 ? Integer.valueOf(level.getEscalationUserId()) : null);
		set(po, "Escalation_Role_ID", level.getEscalationRoleId() > 0 ? Integer.valueOf(level.getEscalationRoleId()) : null);
		set(po, "LowerLimit", level.getLowerLimit());
		set(po, "UpperLimit", level.getUpperLimit());
		set(po, "IsFinalApprover", level.isFinalApprover() ? "Y" : "N");
		set(po, "Description", trim(level.getDescription()));
		set(po, "IsActive", "Y");

		// The native window can only show real columns, so the level mirrors the
		// process it belongs to. It is derived here rather than asked of the user,
		// and the window's validation rules refuse any combination that disagrees,
		// so the two copies cannot drift apart.
		int[] owner = processOfVersion(getInt(po, "XX_PROCESS_VERSION_ID", 0));
		set(po, "XX_Process_ID", owner[0] > 0 ? Integer.valueOf(owner[0]) : null);
		set(po, "XX_Process_Category_ID", owner[1] > 0 ? Integer.valueOf(owner[1]) : null);
	}

	/**
	 * Process and category that own a workflow version.
	 * <p>
	 * A single read of the version and its process rather than two lookups, because
	 * both mirrored columns have to come from the same row of the same statement.
	 *
	 * @return {@code {processId, categoryId}}, both 0 when the version is unknown
	 */
	private int[] processOfVersion(int processVersionId) {
		if (processVersionId <= 0)
			return new int[] { 0, 0 };
		PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			String sql = "SELECT v.XX_Process_ID, p.XX_Process_Category_ID"
					+ " FROM XX_PROCESS_VERSION v"
					+ " JOIN XX_PROCESS p ON p.XX_Process_ID = v.XX_Process_ID"
					+ " WHERE v.XX_Process_Version_ID = ?";
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, processVersionId);
			rs = pstmt.executeQuery();
			if (rs.next())
				return new int[] { rs.getInt(1), rs.getInt(2) };
		} catch (java.sql.SQLException e) {
			// an unresolvable version simply leaves both mirrors empty, the same as a
			// level that was configured before the process columns existed
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return new int[] { 0, 0 };
	}

	/**
	 * A band that ends before it starts would never match a document, so it is
	 * refused here rather than silently stored.
	 */
	private void validateBand(ApprovalLevel level) throws ConfigException {
		if (level.getLowerLimit() != null && level.getUpperLimit() != null
				&& level.getUpperLimit().compareTo(level.getLowerLimit()) < 0)
			throw new ConfigException("Upper Limit must be greater than or equal to Lower Limit.");
	}

	/**
	 * Deletes a level.
	 * <p>
	 * The row is deactivated rather than removed, because transactions that already
	 * ran may still reference it. The audit trail must stay readable.
	 */
	public void deleteLevel(int levelId) throws ConfigException {
		if (levelId <= 0)
			return;
		final String trxName = newTrx("LvlDel");
		inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_LEVEL).getPO(levelId, trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Level " + levelId + " was not found.");
			int versionId = getInt(po, "XX_PROCESS_VERSION_ID", 0);
			set(po, "IsActive", "N");
			save(po, trxName);
			renumber(trxName, versionId);
			return null;
		});
	}

	/** Moves a level one position earlier in the chain. */
	public void moveUp(int levelId) throws ConfigException {
		move(levelId, -1);
	}

	/** Moves a level one position later in the chain. */
	public void moveDown(int levelId) throws ConfigException {
		move(levelId, 1);
	}

	/**
	 * Swaps the level number with its neighbour and then renumbers the whole
	 * chain, which keeps the numbers contiguous without gaps.
	 */
	private void move(int levelId, int offset) throws ConfigException {
		List<ApprovalLevel> levels = listLevelsOf(levelId);
		if (levels.isEmpty())
			throw new ConfigException("Level " + levelId + " was not found.");

		int index = -1;
		for (int i = 0; i < levels.size(); i++)
			if (levels.get(i).getId() == levelId)
				index = i;
		if (index < 0)
			throw new ConfigException("Level " + levelId + " was not found.");

		int target = index + offset;
		if (target < 0 || target >= levels.size())
			return; // already at the edge, nothing to do

		final String trxName = newTrx("LvlMove");
		final ApprovalLevel a = levels.get(index);
		final ApprovalLevel b = levels.get(target);
		inTrx(trxName, () -> {
			writeLevelNo(a.getId(), b.getLevelNo(), trxName);
			writeLevelNo(b.getId(), a.getLevelNo(), trxName);
			renumber(trxName, a.getProcessVersionId());
			return null;
		});
	}

	private List<ApprovalLevel> listLevelsOf(int levelId) {
		List<ApprovalLevel> result = new ArrayList<ApprovalLevel>();
		String sql = "SELECT XX_PROCESS_VERSION_ID FROM " + T_LEVEL + " WHERE XX_PROCESS_LEVEL_ID=?";
		int versionId = org.compiere.util.DB.getSQLValue(null, sql, Integer.valueOf(levelId));
		if (versionId <= 0)
			return result;
		return listLevels(versionId);
	}

	private void writeLevelNo(int levelId, int levelNo, String trxName) {
		PO po = MTable.get(ctx(), T_LEVEL).getPO(levelId, trxName);
		if (po == null || !po.load(trxName, null))
			return;
		set(po, "LevelNo", Integer.valueOf(levelNo));
		try {
			po.save();
		} catch (RuntimeException e) {
			// renumber() runs afterwards and repairs any gap
		}
	}

	/**
	 * Rewrites the level numbers of one version to 1..n in their current order.
	 * Uses a two pass read and write so no unique constraint is ever violated,
	 * and runs in the caller's transaction so a half renumbered chain is never
	 * visible.
	 */
	private void renumber(String trxName, int processVersionId) {
		if (processVersionId <= 0)
			return;
		List<ApprovalLevel> levels = listLevels(processVersionId);
		int expected = 1;
		for (ApprovalLevel l : levels) {
			if (l.getLevelNo() != expected)
				writeLevelNo(l.getId(), expected, trxName);
			expected++;
		}
	}

	private int nextLevelNo(int processVersionId) {
		return org.compiere.util.DB.getSQLValue(null,
				"SELECT COALESCE(MAX(LevelNo), 0) FROM " + T_LEVEL + " WHERE XX_PROCESS_VERSION_ID=?",
				Integer.valueOf(processVersionId)) + 1;
	}

	/** Levels of a version that have at least one approver resolvable. */
	public List<ApprovalLevel> listRunnableLevels(int processVersionId) {
		List<ApprovalLevel> result = new ArrayList<ApprovalLevel>();
		for (ApprovalLevel l : listLevels(processVersionId)) {
			// hasStoredResponsible asks the type which field matters, so a department
			// level counts as configured rather than being dropped for having no user id
			boolean usable = l.getResponsibleType().isFixedAssignee() ? l.hasStoredResponsible() : true;
			if (usable && l.isRequired())
				result.add(l);
		}
		return result;
	}
}
