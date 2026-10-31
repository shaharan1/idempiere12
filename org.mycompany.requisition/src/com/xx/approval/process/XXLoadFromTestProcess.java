package com.xx.approval.process;

import java.sql.Timestamp;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.SvrProcess;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * Copies the supervisor mappings of {@code XX_Test} into {@code XX_Approve}, so
 * that the Approval window has real purchase orders to decide on.
 * <p>
 * The two tables hold different things. {@code XX_Test} says who approves whom,
 * {@code XX_Approve} holds the orders and their decision. This process joins
 * them: every mapping of {@code XX_Test} whose supervisor is the logged-in user
 * becomes one pending row.
 * <p>
 * It is safe to run repeatedly. A mapping that has already produced a row is
 * skipped, matched on the mapping id, which is kept in the description so the
 * link back is visible in the window rather than hidden in the code.
 * <p>
 * The process deliberately does nothing when the approver is the creator: those
 * rows would be created only to be refused by the approve process, and leaving
 * them out keeps the queue meaningful.
 */
public class XXLoadFromTestProcess extends SvrProcess {

	private static final CLogger log = CLogger.getCLogger(XXLoadFromTestProcess.class);

	/** Marks which mapping a row came from, so a second run cannot duplicate it. */
	static final String SOURCE_MARK = "from XX_Test mapping ";

	@Override
	protected void prepare() {
		if (getRecord_ID() < 0)
			throw new AdempiereException("Open the Approval window first.");
	}

	@Override
	protected String doIt() throws Exception {
		int userId = Env.getAD_User_ID(getCtx());
		int clientId = Env.getAD_Client_ID(getCtx());
		String trx = get_TrxName();

		String sql = "SELECT t.XX_Test_ID, t.AD_User_ID, t.Supervisor_ID, t.C_DocType_ID, t.Description "
				+ "FROM XX_Test t "
				+ "WHERE t.IsActive = 'Y' AND t.AD_Client_ID = ? AND t.Supervisor_ID = ? "
				+ "AND t.Supervisor_ID IS NOT NULL "
				+ "AND (t.AD_User_ID IS NULL OR t.AD_User_ID <> ?) "
				+ "AND NOT EXISTS (SELECT 1 FROM XX_Approve a "
				+ "WHERE a.Description LIKE ? AND a.Description LIKE ?) "
				+ "ORDER BY t.XX_Test_ID";

		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet rs = null;
		int created = 0, skipped = 0;
		try {
			ps = DB.prepareStatement(sql, trx);
			ps.setInt(1, clientId);
			ps.setInt(2, userId);
			ps.setInt(3, userId);
			ps.setString(4, "%" + SOURCE_MARK + "%");
			ps.setString(5, "%" + userId + "%");
			rs = ps.executeQuery();

			while (rs.next()) {
				int mappingId = rs.getInt(1);
				created += copy(getCtx(), rs, mappingId, userId, clientId, trx) ? 1 : 0;
			}
			skipped = countExisting(getCtx(), userId, clientId, trx);
		} catch (Exception e) {
			log.log(Level.SEVERE, "XX_Test could not be read", e);
			throw new AdempiereException("XX_Test could not be read: " + e.getMessage(), e);
		} finally {
			DB.close(rs, ps);
		}

		if (created == 0)
			return "Nothing to load: there is no new mapping for you. "
					+ (skipped > 0 ? "You already have " + skipped + " purchase order(s) waiting." : "");
		return created + " purchase order(s) loaded from XX_Test" + (skipped > 0 ? ", " + skipped + " already there." : ".");
	}

	/** One mapping becomes one pending approval row. */
	private boolean copy(Properties ctx, java.sql.ResultSet rs, int mappingId,
			int userId, int clientId, String trx) throws Exception {

		int creatorId = rs.getInt(2);
		int doctypeId = rs.getInt(4);
		String source = rs.getString(5);

		PO row = MTable.get(ctx, "XX_Approve").getPO(0, trx);
		if (row == null)
			throw new AdempiereException("XX_Approve row could not be created");

		row.set_ValueOfColumn("AD_Client_ID", Integer.valueOf(clientId));
		row.set_ValueOfColumn("AD_Org_ID", Integer.valueOf(orgOf(ctx, clientId, trx)));
		row.set_ValueOfColumn("IsActive", "Y");
		row.set_ValueOfColumn("PONum", "PO-" + mappingId);
		row.set_ValueOfColumn("Date1", new Timestamp(System.currentTimeMillis()));
		row.set_ValueOfColumn("Amount", java.math.BigDecimal.ZERO);
		row.set_ValueOfColumn("BPartner", "");
		row.set_ValueOfColumn("AD_User_ID", creatorId > 0 ? Integer.valueOf(creatorId) : null);
		row.set_ValueOfColumn("Approver_AD_User_ID", Integer.valueOf(userId));
		row.set_ValueOfColumn("approvalstatus", AbstractApprovalProcess.STATUS_PENDING);
		row.set_ValueOfColumn("approve", "");
		row.set_ValueOfColumn("reject", "");
		row.set_ValueOfColumn("Description", SOURCE_MARK + mappingId + " for user " + userId
				+ (source == null || source.trim().length() == 0 ? "" : " : " + source.trim()));

		if (!row.save())
			throw new AdempiereException("The purchase order from mapping " + mappingId
					+ " could not be saved.");
		return true;
	}

	private int orgOf(Properties ctx, int clientId, String trx) {
		int org = DB.getSQLValue(trx, "SELECT MIN(AD_Org_ID) FROM AD_Org WHERE AD_Client_ID=?", clientId);
		return org > 0 ? org : 0;
	}

	private int countExisting(Properties ctx, int userId, int clientId, String trx) {
		return DB.getSQLValue(trx,
				"SELECT COUNT(*) FROM XX_Approve WHERE AD_Client_ID=? AND Description LIKE ? AND Description LIKE ?",
				clientId, "%" + SOURCE_MARK + "%", "%" + userId + "%");
	}
}