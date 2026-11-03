package org.mycompany.requisition.rfqpo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * Service for Fast-track Winner Change requests and HOD approval.
 */
public class WinnerChangeService {

	private static final CLogger log = CLogger.getCLogger(WinnerChangeService.class);

	public static class ChangeRequest {
		public int changeId;
		public int rfqId;
		public int rfqLineId;
		public int oldRespId;
		public int newRespId;
		public String rfqDocNo = "";
		public String productCode = "";
		public String productName = "";
		public String oldBpName = "";
		public String newBpName = "";
		public String justification = "";
		public String status = "P"; // P=Pending, A=Approved, R=Rejected
		public String requestedBy = "";
		public Timestamp requestDate;
		public String approverComment = "";
	}

	/** Ensures table exists automatically */
	public static void ensureTable() {
		try {
			String sql = "CREATE TABLE IF NOT EXISTS XX_RfQWinnerChange ("
					+ "XX_RfQWinnerChange_ID NUMERIC(10) PRIMARY KEY,"
					+ "AD_Client_ID          NUMERIC(10) NOT NULL,"
					+ "AD_Org_ID             NUMERIC(10) NOT NULL,"
					+ "IsActive              CHAR(1) DEFAULT 'Y' NOT NULL,"
					+ "Created               TIMESTAMP WITHOUT TIME ZONE DEFAULT now() NOT NULL,"
					+ "CreatedBy             NUMERIC(10) NOT NULL,"
					+ "Updated               TIMESTAMP WITHOUT TIME ZONE DEFAULT now() NOT NULL,"
					+ "UpdatedBy             NUMERIC(10) NOT NULL,"
					+ "C_RfQ_ID              NUMERIC(10) NOT NULL,"
					+ "C_RfQLine_ID          NUMERIC(10) NOT NULL,"
					+ "Old_RfQResponse_ID    NUMERIC(10),"
					+ "New_RfQResponse_ID    NUMERIC(10) NOT NULL,"
					+ "Justification         TEXT NOT NULL,"
					+ "Status                CHAR(1) DEFAULT 'P' NOT NULL,"
					+ "ApprovedBy            NUMERIC(10),"
					+ "ApprovedDate          TIMESTAMP WITHOUT TIME ZONE,"
					+ "ApproverComment       TEXT"
					+ ")";
			DB.executeUpdateEx(sql, null);
			DB.executeUpdateEx("CREATE INDEX IF NOT EXISTS idx_xx_winnerchange_rfqline ON XX_RfQWinnerChange(C_RfQLine_ID, Status)", null);
			DB.executeUpdateEx("CREATE INDEX IF NOT EXISTS idx_xx_winnerchange_status ON XX_RfQWinnerChange(Status)", null);
		} catch (Exception e) {
			log.warning("ensureTable: " + e.getMessage());
		}
	}

	/** Submits a change request */
	public static void submitRequest(int rfqId, int rfqLineId, int newRespId, String justification) throws Exception {
		ensureTable();

		// Get current winner response for this line
		int oldRespId = DB.getSQLValue(null,
				"SELECT C_RfQResponse_ID FROM C_RfQResponseLine "
				+ "WHERE C_RfQLine_ID=? AND IsSelectedWinner='Y' AND IsActive='Y' LIMIT 1", rfqLineId);

		int id = DB.getNextID(Env.getAD_Client_ID(Env.getCtx()), "XX_RfQWinnerChange", null);
		if (id <= 0) {
			id = DB.getSQLValue(null, "SELECT COALESCE(MAX(XX_RfQWinnerChange_ID),0)+1 FROM XX_RfQWinnerChange");
		}

		String sql = "INSERT INTO XX_RfQWinnerChange ("
				+ "XX_RfQWinnerChange_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, "
				+ "C_RfQ_ID, C_RfQLine_ID, Old_RfQResponse_ID, New_RfQResponse_ID, Justification, Status) "
				+ "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, ?, ?, 'P')";

		DB.executeUpdateEx(sql, new Object[] {
				Integer.valueOf(id),
				Integer.valueOf(Env.getAD_Client_ID(Env.getCtx())),
				Integer.valueOf(Env.getAD_Org_ID(Env.getCtx())),
				Integer.valueOf(Env.getAD_User_ID(Env.getCtx())),
				Integer.valueOf(Env.getAD_User_ID(Env.getCtx())),
				Integer.valueOf(rfqId),
				Integer.valueOf(rfqLineId),
				oldRespId > 0 ? Integer.valueOf(oldRespId) : null,
				Integer.valueOf(newRespId),
				justification
		}, null);
	}

	/** Checks if a pending change request exists for this RFQ line */
	public static ChangeRequest getPendingRequest(int rfqLineId) {
		ensureTable();
		String sql = "SELECT c.XX_RfQWinnerChange_ID, c.C_RfQ_ID, c.C_RfQLine_ID, c.Old_RfQResponse_ID, c.New_RfQResponse_ID, "
				+ "c.Justification, c.Created, u.Name, bp_new.Name, bp_old.Name "
				+ "FROM XX_RfQWinnerChange c "
				+ "JOIN AD_User u ON u.AD_User_ID=c.CreatedBy "
				+ "JOIN C_RfQResponse r_new ON r_new.C_RfQResponse_ID=c.New_RfQResponse_ID "
				+ "JOIN C_BPartner bp_new ON bp_new.C_BPartner_ID=r_new.C_BPartner_ID "
				+ "LEFT JOIN C_RfQResponse r_old ON r_old.C_RfQResponse_ID=c.Old_RfQResponse_ID "
				+ "LEFT JOIN C_BPartner bp_old ON bp_old.C_BPartner_ID=r_old.C_BPartner_ID "
				+ "WHERE c.C_RfQLine_ID=? AND c.Status='P' ORDER BY c.XX_RfQWinnerChange_ID DESC LIMIT 1";

		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, rfqLineId);
			rs = ps.executeQuery();
			if (rs.next()) {
				ChangeRequest cr = new ChangeRequest();
				cr.changeId = rs.getInt(1);
				cr.rfqId = rs.getInt(2);
				cr.rfqLineId = rs.getInt(3);
				cr.oldRespId = rs.getInt(4);
				cr.newRespId = rs.getInt(5);
				cr.justification = rs.getString(6);
				cr.requestDate = rs.getTimestamp(7);
				cr.requestedBy = rs.getString(8);
				cr.newBpName = rs.getString(9);
				cr.oldBpName = rs.getString(10);
				cr.status = "P";
				return cr;
			}
		} catch (Exception e) {
			log.warning("getPendingRequest: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return null;
	}

	/** Returns all pending change requests across RFQs for HOD review */
	public static List<ChangeRequest> listPendingRequests() {
		ensureTable();
		List<ChangeRequest> list = new ArrayList<ChangeRequest>();
		String sql = "SELECT c.XX_RfQWinnerChange_ID, c.C_RfQ_ID, c.C_RfQLine_ID, c.Old_RfQResponse_ID, c.New_RfQResponse_ID, "
				+ "c.Justification, c.Created, u.Name, bp_new.Name, bp_old.Name, rfq.DocumentNo, "
				+ "COALESCE(p.Value, ''), COALESCE(p.Name, rl.Description, '') "
				+ "FROM XX_RfQWinnerChange c "
				+ "JOIN C_RfQ rfq ON rfq.C_RfQ_ID=c.C_RfQ_ID "
				+ "JOIN C_RfQLine rl ON rl.C_RfQLine_ID=c.C_RfQLine_ID "
				+ "LEFT JOIN M_Product p ON p.M_Product_ID=rl.M_Product_ID "
				+ "JOIN AD_User u ON u.AD_User_ID=c.CreatedBy "
				+ "JOIN C_RfQResponse r_new ON r_new.C_RfQResponse_ID=c.New_RfQResponse_ID "
				+ "JOIN C_BPartner bp_new ON bp_new.C_BPartner_ID=r_new.C_BPartner_ID "
				+ "LEFT JOIN C_RfQResponse r_old ON r_old.C_RfQResponse_ID=c.Old_RfQResponse_ID "
				+ "LEFT JOIN C_BPartner bp_old ON bp_old.C_BPartner_ID=r_old.C_BPartner_ID "
				+ "WHERE c.Status='P' AND c.AD_Client_ID=? ORDER BY c.XX_RfQWinnerChange_ID DESC";

		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			rs = ps.executeQuery();
			while (rs.next()) {
				ChangeRequest cr = new ChangeRequest();
				cr.changeId = rs.getInt(1);
				cr.rfqId = rs.getInt(2);
				cr.rfqLineId = rs.getInt(3);
				cr.oldRespId = rs.getInt(4);
				cr.newRespId = rs.getInt(5);
				cr.justification = rs.getString(6);
				cr.requestDate = rs.getTimestamp(7);
				cr.requestedBy = rs.getString(8);
				cr.newBpName = rs.getString(9);
				cr.oldBpName = rs.getString(10);
				cr.rfqDocNo = rs.getString(11);
				cr.productCode = rs.getString(12);
				cr.productName = rs.getString(13);
				cr.status = "P";
				list.add(cr);
			}
		} catch (Exception e) {
			log.warning("listPendingRequests: " + e.getMessage());
		} finally {
			DB.close(rs, ps);
		}
		return list;
	}

	/** Approves the winner change request and updates CS winner flags */
	public static void approveRequest(int changeId, String approverComment) throws Exception {
		ensureTable();

		// Get request details
		String sql = "SELECT C_RfQLine_ID, Old_RfQResponse_ID, New_RfQResponse_ID, Justification FROM XX_RfQWinnerChange WHERE XX_RfQWinnerChange_ID=?";
		PreparedStatement ps = null;
		ResultSet rs = null;
		int rfqLineId = 0, oldRespId = 0, newRespId = 0;
		String justification = "";
		try {
			ps = DB.prepareStatement(sql, null);
			ps.setInt(1, changeId);
			rs = ps.executeQuery();
			if (rs.next()) {
				rfqLineId = rs.getInt(1);
				oldRespId = rs.getInt(2);
				newRespId = rs.getInt(3);
				justification = rs.getString(4);
			}
		} finally {
			DB.close(rs, ps);
		}

		if (rfqLineId <= 0 || newRespId <= 0)
			throw new Exception("Invalid change request ID: " + changeId);

		int userId = Env.getAD_User_ID(Env.getCtx());

		// 1. Unset old winner for this line
		DB.executeUpdateEx(
				"UPDATE C_RfQResponseLine SET IsSelectedWinner='N', Updated=now(), UpdatedBy=? WHERE C_RfQLine_ID=?",
				new Object[] { Integer.valueOf(userId), Integer.valueOf(rfqLineId) }, null);

		// 2. Set new winner for this line with justification comment
		String fullComment = justification;
		if (approverComment != null && !approverComment.trim().isEmpty()) {
			fullComment += " (HOD: " + approverComment.trim() + ")";
		}
		DB.executeUpdateEx(
				"UPDATE C_RfQResponseLine SET IsSelectedWinner='Y', XX_WinnerComment=?, Updated=now(), UpdatedBy=? "
				+ "WHERE C_RfQLine_ID=? AND C_RfQResponse_ID=?",
				new Object[] { fullComment, Integer.valueOf(userId), Integer.valueOf(rfqLineId), Integer.valueOf(newRespId) }, null);

		// 3. Update response-level winner flag
		DB.executeUpdateEx("UPDATE C_RfQResponse SET IsSelectedWinner='Y' WHERE C_RfQResponse_ID=?",
				new Object[] { Integer.valueOf(newRespId) }, null);
		if (oldRespId > 0) {
			int hasOther = DB.getSQLValue(null,
					"SELECT COUNT(1) FROM C_RfQResponseLine WHERE C_RfQResponse_ID=? AND IsSelectedWinner='Y'", oldRespId);
			if (hasOther <= 0) {
				DB.executeUpdateEx("UPDATE C_RfQResponse SET IsSelectedWinner='N' WHERE C_RfQResponse_ID=?",
						new Object[] { Integer.valueOf(oldRespId) }, null);
			}
		}

		// 4. Mark request as Approved
		DB.executeUpdateEx(
				"UPDATE XX_RfQWinnerChange SET Status='A', ApprovedBy=?, ApprovedDate=now(), ApproverComment=?, Updated=now(), UpdatedBy=? "
				+ "WHERE XX_RfQWinnerChange_ID=?",
				new Object[] { Integer.valueOf(userId), approverComment, Integer.valueOf(userId), Integer.valueOf(changeId) }, null);
	}

	/** Rejects the winner change request */
	public static void rejectRequest(int changeId, String approverComment) throws Exception {
		ensureTable();
		int userId = Env.getAD_User_ID(Env.getCtx());
		DB.executeUpdateEx(
				"UPDATE XX_RfQWinnerChange SET Status='R', ApprovedBy=?, ApprovedDate=now(), ApproverComment=?, Updated=now(), UpdatedBy=? "
				+ "WHERE XX_RfQWinnerChange_ID=?",
				new Object[] { Integer.valueOf(userId), approverComment, Integer.valueOf(userId), Integer.valueOf(changeId) }, null);
	}
}
