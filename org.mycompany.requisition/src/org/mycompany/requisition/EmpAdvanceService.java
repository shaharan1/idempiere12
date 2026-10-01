package org.mycompany.requisition;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MBPartner;
import org.compiere.model.MCharge;
import org.compiere.model.MDocType;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MPriceList;
import org.compiere.model.MRole;
import org.compiere.print.ReportEngine;
import org.compiere.process.DocAction;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Trx;

/**
 * All SQL + AP Invoice + PDF logic lives here, so the ZK form class stays UI-only.
 */
public final class EmpAdvanceService {

	/** Fixed charge name this form always uses */
	public static final String DEFAULT_CHARGE = "Advance to Employee";

	/** Fixed Org name shown as default (Primitek Industries Ltd) */
	public static final String DEFAULT_ORG_NAME = "Primitek Industries Ltd";

	/** true = AP Invoice is completed on save, false = left in Drafted */
	public static final boolean COMPLETE_ON_SAVE = false;

	private EmpAdvanceService() {
	}

	/** Employee master info for the header */
	public static class EmpInfo {
		public int bpartnerId;
		public String code, name, designation, department, section;
		public Timestamp joinDate;
	}

	// ------------------------------------------------------------------ helpers

	private static List<KeyNamePair> queryKeyNames(String sql, Object... params) {
		List<KeyNamePair> list = new ArrayList<>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(sql, null);
			for (int i = 0; i < params.length; i++)
				ps.setObject(i + 1, params[i]);
			rs = ps.executeQuery();
			while (rs.next())
				list.add(new KeyNamePair(rs.getInt(1), rs.getString(2)));
		} catch (SQLException e) {
			throw new AdempiereException(e.getMessage(), e);
		} finally {
			DB.close(rs, ps);
		}
		return list;
	}

	// ------------------------------------------------------------ current user

	/**
	 * Loads the EMPLOYEE record linked to the CURRENTLY LOGGED IN user
	 * (AD_User -> C_BPartner). Header fields are auto-filled from this - no
	 * search box needed.
	 */
	public static EmpInfo getCurrentUserEmployee(Properties ctx) {
		EmpInfo e = new EmpInfo();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = DB.prepareStatement(
					"SELECT c_bpartner_id, emp_code, emp_name, designation, department, section, date_of_join "
							+ "FROM xx_employee_info_v WHERE c_bpartner_id = "
							+ "(SELECT c_bpartner_id FROM ad_user WHERE ad_user_id=?)", null);
			ps.setInt(1, Env.getAD_User_ID(ctx));
			rs = ps.executeQuery();
			if (rs.next()) {
				e.bpartnerId = rs.getInt(1);
				e.code = rs.getString(2);
				e.name = rs.getString(3);
				e.designation = rs.getString(4);
				e.department = rs.getString(5);
				e.section = rs.getString(6);
				e.joinDate = rs.getTimestamp(7);
			}
		} catch (SQLException ex) {
			throw new AdempiereException(ex.getMessage(), ex);
		} finally {
			DB.close(rs, ps);
		}
		return e;
	}

	/** Segment list the user is allowed to pick from + a suggested default for their Role. */
	public static List<KeyNamePair> getSegments(Properties ctx) {
		return queryKeyNames("SELECT C_Activity_ID, Name FROM C_Activity "
				+ "WHERE AD_Client_ID=? AND IsActive='Y' AND IsSummary='N' ORDER BY Name",
				Env.getAD_Client_ID(ctx));
	}

	/**
	 * Default Segment for the logged-in Role: a Segment (C_Activity) whose name
	 * matches the Role's name, if one exists. Falls back to null (no default).
	 */
	public static KeyNamePair getDefaultSegmentForRole(Properties ctx) {
		MRole role = MRole.get(ctx, Env.getAD_Role_ID(ctx));
		if (role == null)
			return null;
		String roleName = role.getName();
		List<KeyNamePair> match = queryKeyNames("SELECT C_Activity_ID, Name FROM C_Activity "
				+ "WHERE AD_Client_ID=? AND IsActive='Y' AND UPPER(Name) LIKE UPPER(?) "
				+ "FETCH FIRST 1 ROW ONLY", Env.getAD_Client_ID(ctx), "%" + roleName + "%");
		return match.isEmpty() ? null : match.get(0);
	}

	/**
	 * The ONLY charge this form uses. Creates it (with default tax category)
	 * the first time it is needed.
	 */
	public static KeyNamePair getAdvanceCharge(Properties ctx) {
		List<KeyNamePair> found = queryKeyNames("SELECT C_Charge_ID, Name FROM C_Charge "
				+ "WHERE AD_Client_ID IN (0,?) AND IsActive='Y' AND Name LIKE ? "
				+ "FETCH FIRST 1 ROW ONLY", Env.getAD_Client_ID(ctx), DEFAULT_CHARGE + "%");
		if (!found.isEmpty())
			return found.get(0);

		int taxCat = DB.getSQLValue(null, "SELECT C_TaxCategory_ID FROM C_TaxCategory WHERE AD_Client_ID=? "
				+ "AND IsActive='Y' ORDER BY IsDefault DESC, C_TaxCategory_ID FETCH FIRST 1 ROW ONLY",
				Env.getAD_Client_ID(ctx));
		if (taxCat <= 0)
			throw new AdempiereException("No Tax Category found - create charge '" + DEFAULT_CHARGE + "' manually");

		MCharge ch = new MCharge(ctx, 0, null);
		ch.setAD_Org_ID(0);
		ch.setName(DEFAULT_CHARGE);
		ch.setC_TaxCategory_ID(taxCat);
		ch.saveEx();
		return new KeyNamePair(ch.getC_Charge_ID(), ch.getName());
	}

	public static String getOrgName(Properties ctx) {
		int orgId = Env.getAD_Org_ID(ctx);
		String n = DB.getSQLValueString(null, "SELECT Name FROM AD_Org WHERE AD_Org_ID=?", orgId);
		return n == null ? DEFAULT_ORG_NAME : n;
	}

	// ------------------------------------------------------------ AP Invoice

	/**
	 * Creates one AP Invoice (charge lines) for the employee linked to bpartnerId.
	 * @return document no of the new invoice
	 */
	public static InvoiceResult createApInvoice(Properties ctx, int bpartnerId, Timestamp date,
			List<AdvanceLine> lines) {
		if (lines == null || lines.isEmpty())
			throw new AdempiereException("No lines selected");

		String trxName = Trx.createTrxName("EmpAdv");
		Trx trx = Trx.get(trxName, true);
		try {
			MBPartner bp = new MBPartner(ctx, bpartnerId, trxName);

			MInvoice inv = new MInvoice(ctx, 0, trxName);
			int orgId = Env.getAD_Org_ID(ctx);
			if (orgId <= 0)
				orgId = DB.getSQLValue(trxName, "SELECT MIN(AD_Org_ID) FROM AD_Org "
						+ "WHERE AD_Org_ID>0 AND AD_Client_ID=? AND IsActive='Y'", Env.getAD_Client_ID(ctx));
			inv.setAD_Org_ID(orgId);
			inv.setIsSOTrx(false);
			inv.setC_DocTypeTarget_ID(MDocType.DOCBASETYPE_APInvoice);
			inv.setBPartner(bp);
			inv.setDateInvoiced(date);
			inv.setDateAcct(date);
			inv.setDescription("Employee Advance - " + bp.getName());

			if (inv.getM_PriceList_ID() <= 0) {
				MPriceList pl = MPriceList.getDefault(ctx, false);
				if (pl == null)
					throw new AdempiereException("No default purchase price list found");
				inv.setM_PriceList_ID(pl.get_ID());
			}
			MPriceList pl = MPriceList.get(ctx, inv.getM_PriceList_ID(), trxName);
			inv.setC_Currency_ID(pl.getC_Currency_ID());

			if (inv.getC_PaymentTerm_ID() <= 0) {
				int pt = DB.getSQLValue(trxName, "SELECT MIN(C_PaymentTerm_ID) FROM C_PaymentTerm "
						+ "WHERE AD_Client_ID=? AND IsActive='Y' AND IsDefault='Y'", Env.getAD_Client_ID(ctx));
				if (pt > 0)
					inv.setC_PaymentTerm_ID(pt);
			}
			inv.saveEx();

			for (AdvanceLine l : lines) {
				MInvoiceLine il = new MInvoiceLine(inv);
				il.setAD_Org_ID(inv.getAD_Org_ID());
				il.setC_Charge_ID(l.chargeId);
				il.setQty(Env.ONE);
				il.setPrice(l.amount);
				il.setDescription(l.remark);
				if (l.segmentId > 0)
					il.setC_Activity_ID(l.segmentId);
				il.saveEx();
			}

			if (COMPLETE_ON_SAVE) {
				inv.setDocAction(DocAction.ACTION_Complete);
				if (!inv.processIt(DocAction.ACTION_Complete))
					throw new AdempiereException(inv.getProcessMsg());
				inv.saveEx();
			}

			trx.commit(true);
			InvoiceResult r = new InvoiceResult();
			r.invoiceId = inv.getC_Invoice_ID();
			r.documentNo = inv.getDocumentNo();
			return r;
		} catch (Exception e) {
			trx.rollback();
			if (e instanceof RuntimeException)
				throw (RuntimeException) e;
			throw new AdempiereException(e.getMessage(), e);
		} finally {
			trx.close();
		}
	}

	public static class InvoiceResult {
		public int invoiceId;
		public String documentNo;
	}

	public static BigDecimal sum(List<AdvanceLine> lines) {
		BigDecimal t = BigDecimal.ZERO;
		for (AdvanceLine l : lines)
			t = t.add(l.amount);
		return t;
	}

	// ---------------------------------------------------------------- PDF

	/**
	 * Renders the standard AP Invoice print format to PDF bytes, using the
	 * default print format configured for C_Invoice / Invoice document type.
	 */
	public static byte[] getInvoicePdf(Properties ctx, int invoiceId) {
		ReportEngine re = ReportEngine.get(ctx, ReportEngine.INVOICE, invoiceId);
		if (re == null)
			throw new AdempiereException("No print format configured for AP Invoice");
		File f = null;
		try {
			f = File.createTempFile("EmpAdvance_" + invoiceId + "_", ".pdf");
			re.getPDF(f);
			return Files.readAllBytes(f.toPath());
		} catch (Exception e) {
			throw new AdempiereException("Could not generate PDF: " + e.getMessage(), e);
		} finally {
			if (f != null)
				f.delete();
		}
	}
}
