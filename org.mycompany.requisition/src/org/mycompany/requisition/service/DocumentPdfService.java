package org.mycompany.requisition.service;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import org.compiere.model.MOrder;
import org.compiere.model.MRequisition;
import org.compiere.print.ReportEngine;
import org.compiere.util.DB;
import org.compiere.util.Env;

import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperReport;

/**
 * Builds the PDF of a document: Requisition (Jasper, report/PurchaseRequisition.jrxml) or
 * Purchase Order (the standard print format). No UI code here.
 */
public class DocumentPdfService {

	private static final String REQUISITION_JRXML = "/org/mycompany/requisition/report/PurchaseRequisition.jrxml";
	private static final String LOGO_LEFT = "/org/mycompany/requisition/rfqapproval/logo_left.png";
	private static final String LOGO_RIGHT = "/org/mycompany/requisition/rfqapproval/logo_right.png";

	/** true when a PDF can be built for this table */
	public boolean supports(int tableId) {
		return tableId == MRequisition.Table_ID || tableId == MOrder.Table_ID;
	}

	/** the PDF bytes, or null when there is no PDF for this kind of document */
	public byte[] build(int tableId, int recordId) throws Exception {
		if (tableId == MRequisition.Table_ID)
			return requisition(recordId);
		if (tableId == MOrder.Table_ID)
			return order(recordId);
		return null;
	}

	public byte[] requisition(int requisitionId) throws Exception {
		try (InputStream jr = DocumentPdfService.class.getResourceAsStream(REQUISITION_JRXML)) {
			if (jr == null)
				return null;
			JasperReport report = JasperCompileManager.compileReport(jr);
			Map<String, Object> params = new HashMap<String, Object>();
			params.put("RECORD_ID", Integer.valueOf(requisitionId));
			params.put("LOGO_LEFT", logoPath(LOGO_LEFT));
			params.put("LOGO_RIGHT", logoPath(LOGO_RIGHT));
			Connection conn = DB.getConnectionRO();
			try {
				return JasperExportManager.exportReportToPdf(JasperFillManager.fillReport(report, params, conn));
			} finally {
				try {
					conn.close();
				} catch (Exception ignore) {
					// ignore
				}
			}
		}
	}

	public byte[] order(int orderId) throws Exception {
		ReportEngine re = ReportEngine.get(Env.getCtx(), ReportEngine.ORDER, orderId, null);
		File f = re == null ? null : re.getPDF();
		return f == null ? null : Files.readAllBytes(f.toPath());
	}

	private static String logoPath(String resource) {
		try (InputStream in = DocumentPdfService.class.getResourceAsStream(resource)) {
			if (in == null)
				return "";
			File f = File.createTempFile("xxlogo", ".png");
			f.deleteOnExit();
			Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return f.getAbsolutePath();
		} catch (Exception e) {
			return "";
		}
	}
}
