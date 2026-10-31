package org.mycompany.requisition.approval.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.mycompany.requisition.approval.config.Delegation;

/**
 * Read and write access to the approver delegations.
 * <p>
 * A delegation substitutes one person for another on a level, optionally limited
 * to a date range and to one level. The engine applies it when a chain is created,
 * through {@link RuleResolver}, so the level keeps its responsibility and the
 * history still shows who was really supposed to approve.
 *
 * <h3>Validation</h3>
 * Delegation is the one place where a configuration mistake silently moves work to
 * the wrong person, so this service refuses the combinations that cannot mean
 * anything: delegating to yourself, delegating to an inactive user, a range that
 * ends before it starts, and a range that has already expired. An open ended
 * delegation is legitimate and stays allowed.
 */
public class DelegationService extends BaseConfigService {

	private static final String T_DELEGATION = "XX_PROCESS_DELEGATION";

	public DelegationService() throws ConfigException {
		super(T_DELEGATION);
	}

	/** Delegations of the current tenant, newest first. */
	public List<Delegation> listAll() {
		List<Delegation> result = new ArrayList<Delegation>();
		String sql = "SELECT XX_PROCESS_DELEGATION_ID, AD_User_ID, Delegate_AD_User_ID, XX_PROCESS_LEVEL_ID,"
				+ " ValidFrom, ValidTo, Reason, Created FROM " + T_DELEGATION
				+ " WHERE IsActive='Y' AND AD_Client_ID=? ORDER BY Created DESC";
		java.sql.PreparedStatement pstmt = null;
		java.sql.ResultSet rs = null;
		try {
			pstmt = org.compiere.util.DB.prepareStatement(sql, null);
			pstmt.setInt(1, clientId());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				Delegation d = new Delegation();
				d.setId(rs.getInt(1));
				d.setUserId(rs.getInt(2));
				d.setDelegateUserId(rs.getInt(3));
				d.setLevelId(rs.getInt(4));
				d.setValidFrom(rs.getTimestamp(5));
				d.setValidTo(rs.getTimestamp(6));
				d.setReason(rs.getString(7));
				result.add(d);
			}
		} catch (java.sql.SQLException e) {
			result.clear();
		} finally {
			org.compiere.util.DB.close(rs);
			org.compiere.util.DB.close(pstmt);
		}
		return result;
	}

	/** Delegations that are valid right now, for the dashboard. */
	public List<Delegation> listValidNow() {
		Timestamp now = new Timestamp(System.currentTimeMillis());
		List<Delegation> result = new ArrayList<Delegation>();
		for (Delegation d : listAll())
			if (d.isValidAt(now))
				result.add(d);
		return result;
	}

	/** Delegations of one approver, valid or not. */
	public List<Delegation> listOf(int userId) {
		List<Delegation> result = new ArrayList<Delegation>();
		for (Delegation d : listAll())
			if (d.getUserId() == userId)
				result.add(d);
		return result;
	}

	/** The delegate that replaces this user on a level right now, or 0. */
	public int effectiveDelegate(int userId, int levelId, Timestamp now) {
		if (userId <= 0)
			return 0;
		for (Delegation d : listAll()) {
			if (d.getUserId() != userId || !d.isValidAt(now))
				continue;
			if (!d.appliesToLevel(levelId))
				continue;
			return d.getDelegateUserId();
		}
		return 0;
	}

	/**
	 * Creates a delegation.
	 *
	 * @return the new XX_PROCESS_DELEGATION_ID
	 */
	public int create(Delegation delegation) throws ConfigException {
		if (delegation == null)
			throw new ConfigException("No delegation data was entered.");
		validate(delegation);

		final String trxName = newTrx("Deleg");
		return inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_DELEGATION).getPO(0, trxName);
			if (po == null)
				throw new ConfigException("Table " + T_DELEGATION + " is not registered in iDempiere.");
			po.set_ValueOfColumnReturningBoolean("AD_Org_ID", Integer.valueOf(orgIdForWrite(trxName)));
			apply(po, delegation);
			save(po, trxName);
			return Integer.valueOf(po.get_ID());
		}).intValue();
	}

	/** Updates an existing delegation. */
	public void update(Delegation delegation) throws ConfigException {
		if (delegation == null || delegation.getId() <= 0)
			throw new ConfigException("No delegation is selected for updating.");
		validate(delegation);

		final String trxName = newTrx("DelegUpd");
		inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_DELEGATION).getPO(delegation.getId(), trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Delegation " + delegation.getId() + " was not found.");
			apply(po, delegation);
			save(po, trxName);
			return null;
		});
	}

	/**
	 * Ends a delegation.
	 * <p>
	 * The row is deactivated instead of removed, because a chain that already ran
	 * while the delegation was in force has to stay explainable afterwards.
	 */
	public void delete(int delegationId) throws ConfigException {
		if (delegationId <= 0)
			return;
		final String trxName = newTrx("DelegDel");
		inTrx(trxName, () -> {
			PO po = MTable.get(ctx(), T_DELEGATION).getPO(delegationId, trxName);
			if (po == null || !po.load(trxName, null))
				throw new ConfigException("Delegation " + delegationId + " was not found.");
			set(po, "IsActive", "N");
			save(po, trxName);
			return null;
		});
	}

	private void apply(PO po, Delegation delegation) {
		set(po, "AD_User_ID", Integer.valueOf(delegation.getUserId()));
		set(po, "Delegate_AD_User_ID", Integer.valueOf(delegation.getDelegateUserId()));
		set(po, "XX_PROCESS_LEVEL_ID",
				delegation.getLevelId() > 0 ? Integer.valueOf(delegation.getLevelId()) : null);
		set(po, "ValidFrom", delegation.getValidFrom());
		set(po, "ValidTo", delegation.getValidTo());
		set(po, "Reason", trim(delegation.getReason()));
		set(po, "IsActive", "Y");
	}

	/**
	 * Refuses the delegations that cannot mean anything.
	 * <p>
	 * The database has a check constraint for the range and for
	 * {@code delegate <> user}; this states the same rules with a message an
	 * administrator can act on, and adds the two that only Java can see - an
	 * inactive delegate and a delegation that has already expired.
	 */
	private void validate(Delegation delegation) throws ConfigException {
		if (delegation.getUserId() <= 0)
			throw new ConfigException("Choose the approver who is being replaced.");
		if (delegation.getDelegateUserId() <= 0)
			throw new ConfigException("Choose who approves while the approver is away.");
		if (delegation.getUserId() == delegation.getDelegateUserId())
			throw new ConfigException("An approver cannot delegate to themselves.");
		if (!userIsActive(delegation.getDelegateUserId()))
			throw new ConfigException("The chosen delegate is not an active user, so nothing could be approved.");

		Timestamp from = delegation.getValidFrom();
		Timestamp to = delegation.getValidTo();
		if (from != null && to != null && to.before(from))
			throw new ConfigException("Valid To must be later than Valid From.");
		if (to != null && to.before(new Timestamp(System.currentTimeMillis())))
			throw new ConfigException("Valid To is already in the past, so this delegation would never apply.");
	}

	private boolean userIsActive(int userId) {
		return org.compiere.util.DB.getSQLValue(null,
				"SELECT COUNT(*) FROM AD_User u WHERE u.AD_User_ID=? AND u.IsActive='Y'", Integer.valueOf(userId)) > 0;
	}

	/**
	 * User facing name of a delegation, for the list and for messages.
	 */
	public String describe(Delegation delegation) {
		if (delegation == null)
			return "";
		StringBuilder sb = new StringBuilder();
		sb.append(userName(delegation.getUserId()));
		sb.append(" to ").append(userName(delegation.getDelegateUserId()));
		if (delegation.getLevelId() > 0)
			sb.append(" on level ").append(levelName(delegation.getLevelId()));
		if (delegation.getValidFrom() != null || delegation.getValidTo() != null) {
			sb.append(" from ");
			sb.append(delegation.getValidFrom() == null ? "any" : delegation.getValidFrom().toString());
			sb.append(" to ");
			sb.append(delegation.getValidTo() == null ? "any" : delegation.getValidTo().toString());
		}
		return sb.toString();
	}

	private String userName(int userId) {
		if (userId <= 0)
			return "?";
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT Name FROM AD_User WHERE AD_User_ID=?", Integer.valueOf(userId));
		return name == null ? ("user " + userId) : name;
	}

	private String levelName(int levelId) {
		String name = org.compiere.util.DB.getSQLValueString(null,
				"SELECT LevelName FROM XX_PROCESS_LEVEL WHERE XX_PROCESS_LEVEL_ID=?", Integer.valueOf(levelId));
		return name == null ? String.valueOf(levelId) : name;
	}
}
