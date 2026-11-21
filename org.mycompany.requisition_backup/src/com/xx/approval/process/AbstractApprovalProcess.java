package com.xx.approval.process;

import java.sql.Timestamp;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.process.ProcessInfo;
import org.compiere.process.SvrProcess;
import org.compiere.util.CLogger;
import org.compiere.util.Env;

/**
 * Shared behaviour of the Approve and Reject processes on {@code XX_Approve}.
 * <p>
 * Both do the same three things and differ only in the status letter they write,
 * so the rules live here once: read the record the Process Info points at, refuse
 * anything that must not be decided, then write the status together with the
 * approver and the timestamp.
 * <p>
 * Two rules are enforced, and the order matters to what the user is told:
 * <ol>
 * <li><b>Nobody decides their own request.</b> The creator named in
 * {@code AD_User_ID} cannot approve or reject their own record. This is checked
 * before the status, so someone who created a record and then tries to approve it
 * is told why, rather than being told it has already been decided.</li>
 * <li><b>A decided record stays decided.</b> Once the status is A or R the
 * process refuses, because re-deciding would overwrite the previous approver and
 * erase the trail of who actually decided it.</li>
 * </ol>
 * The row is read and written inside the process transaction, so a second person
 * pressing the button at the same moment cannot slip past the check.
 */
abstract class AbstractApprovalProcess extends SvrProcess {

	private static final CLogger log = CLogger.getCLogger(AbstractApprovalProcess.class);

	/** The table these processes act on, verified by name rather than assumed. */
	static final String TABLE_NAME = "XX_Approve";

	/** P pending, A approved, R rejected. */
	static final String STATUS_PENDING = "P";
	static final String STATUS_APPROVED = "A";
	static final String STATUS_REJECTED = "R";

	/** The letter this process writes. */
	private final String newStatus;
	/** Past tense, used in the message: "Approved" or "Rejected". */
	private final String doneWord;

	AbstractApprovalProcess(String newStatus, String doneWord) {
		this.newStatus = newStatus;
		this.doneWord = doneWord;
	}

	@Override
	protected void prepare() {
		ProcessInfo pi = getProcessInfo();
		if (pi == null)
			throw new AdempiereException("ProcessInfo is missing.");
		if (getRecord_ID() <= 0)
			throw new AdempiereException(
					"No record was selected. Open the purchase order and press the button again.");
		log.fine("prepare: record " + getRecord_ID() + " is about to be " + doneWord.toLowerCase());
	}

	@Override
	protected String doIt() throws Exception {
		int recordId = getRecord_ID();
		int userId = Env.getAD_User_ID(getCtx());

		PO record = MTable.get(getCtx(), TABLE_NAME).getPO(recordId, get_TrxName());
		if (record.get_ID() == 0)
			throw new AdempiereException("Purchase order " + recordId + " was not found.");

		// rule 1 : no self approval
		int creatorId = record.get_Value("AD_User_ID") == null ? 0
				: ((Number) record.get_Value("AD_User_ID")).intValue();
		if (creatorId > 0 && creatorId == userId) {
			throw new AdempiereException("You created this purchase order as "
					+ userName(creatorId) + ", so you cannot " + doneWord.toLowerCase()
					+ " it yourself. Ask the next approver to do it.");
		}

		// rule 2 : a decided record stays decided
		Object raw = record.get_Value("approvalstatus");
		String status = raw == null ? STATUS_PENDING : raw.toString().trim();
		if (STATUS_APPROVED.equals(status))
			throw new AdempiereException("This purchase order was already Approved by "
					+ describeApprover(record) + ", so its status cannot be changed.");
		if (STATUS_REJECTED.equals(status))
			throw new AdempiereException("This purchase order was already Rejected by "
					+ describeApprover(record) + ", so its status cannot be changed.");
		if (!STATUS_PENDING.equals(status))
			throw new AdempiereException("This purchase order is in status '" + status
					+ "', which is not Pending, so there is nothing to "
					+ doneWord.toLowerCase() + ".");

		// the write itself
		record.set_ValueOfColumn("approvalstatus", newStatus);
		record.set_ValueOfColumn("Approver_AD_User_ID", Integer.valueOf(userId));
		record.set_ValueOfColumn("Updated", new Timestamp(System.currentTimeMillis()));
		record.set_ValueOfColumn("UpdatedBy", Integer.valueOf(userId));

		if (!record.save())
			throw new AdempiereException("The purchase order could not be saved in transaction "
					+ get_TrxName() + ". Nothing was changed.");

		return describe(record) + " " + doneWord + " by " + userName(userId) + ".";
	}

	/** "PO 1001" when the record carries a purchase order number. */
	private String describe(PO record) {
		Object ponum = record.get_Value("ponum");
		if (ponum != null && ponum.toString().trim().length() > 0)
			return "PO " + ponum.toString().trim();
		return "Record " + record.get_ID();
	}

	/** Who decided it already, for the "already decided" message. */
	private String describeApprover(PO record) {
		Object who = record.get_Value("Approver_AD_User_ID");
		if (who == null)
			return "someone";
		try {
			return userName(((Number) who).intValue());
		} catch (Exception e) {
			return "user " + who;
		}
	}

	private String userName(int userId) {
		if (userId <= 0)
			return "user " + userId;
		try {
			MTable t = MTable.get(getCtx(), "AD_User");
			PO u = t.getPO(userId, get_TrxName());
			Object name = u.get_Value("Name");
			if (name != null)
				return name.toString();
		} catch (Exception e) {
			log.log(Level.FINE, "user " + userId + " could not be read", e);
		}
		return "user " + userId;
	}
}