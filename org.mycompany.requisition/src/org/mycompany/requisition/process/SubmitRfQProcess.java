package org.mycompany.requisition.process;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MRfQ;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.Trx;

public class SubmitRfQProcess extends SvrProcess {

	@Override
	protected void prepare() {
	}

	@Override
	protected String doIt() throws Exception {
		int rfqId = getRecord_ID();
		if (rfqId <= 0) {
			throw new AdempiereException("No RFQ selected");
		}
		
		MRfQ rfq = new MRfQ(getCtx(), rfqId, get_TrxName());
		if (rfq.get_ID() <= 0) {
			throw new AdempiereException("RFQ not found");
		}
		
		if (rfq.getLines().length == 0) {
			throw new AdempiereException("RFQ has no lines");
		}
		
		String trxName = get_TrxName();
		if (trxName == null) {
			trxName = Trx.createTrxName("SubmitRfQ-");
		}
		Trx trx = Trx.get(trxName, true);
		try {
			trx.start();
			
			// Lock RFQ
			String lockSql = "SELECT C_RfQ_ID FROM C_RfQ WHERE C_RfQ_ID = ? FOR UPDATE NOWAIT";
			java.sql.PreparedStatement pstmt = DB.prepareStatement(lockSql, trxName);
			pstmt.setInt(1, rfqId);
			java.sql.ResultSet rs = pstmt.executeQuery();
			if (!rs.next()) {
				throw new AdempiereException("Could not lock RFQ for submission");
			}
			DB.close(rs, pstmt);
			
			// Submit RFQ - set to in progress/completed as appropriate
			if (!rfq.isProcessed()) {
				rfq.setProcessed(true);
				rfq.saveEx(trxName);
			}
			
			trx.commit();
			return "RFQ submitted successfully";
		} catch (Exception e) {
			trx.rollback();
			if (e instanceof AdempiereException) {
				throw (AdempiereException) e;
			}
			throw new AdempiereException(e);
		} finally {
			trx.close();
		}
	}
}
