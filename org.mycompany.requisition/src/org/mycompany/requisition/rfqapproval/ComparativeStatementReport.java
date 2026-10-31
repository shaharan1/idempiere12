package org.mycompany.requisition.rfqapproval;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.compiere.util.DB;
import org.compiere.util.Env;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Comparison;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Item;
import org.mycompany.requisition.rfqapproval.RfQApprovalService.Supplier;

import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;

/**
 * Comparative Statement PDF (layout of Comparative_Statement_Print_Format.pdf).
 * <p>
 * The number of supplier columns changes from RFQ to RFQ, so a fixed .jrxml
 * cannot be used. This class writes the JRXML text for the RFQ at hand
 * (columns, header, footer rows, signature boxes), compiles it with the Jasper
 * engine the bundle already requires ({@code WRequisitionForm} uses the same
 * calls) and fills it with the item rows. No extra library is needed.
 */
public class ComparativeStatementReport {

	// fixed left block
	private static final int W_SL = 25, W_PR = 62, W_ITEM = 150, W_UOM = 35, W_QTY = 40, W_LAST = 55;
	private static final int LEFT = W_SL + W_PR + W_ITEM + W_UOM + W_QTY + W_LAST; // 367
	private static final int W_UNIT = 64, W_TOT = 74, ROW = 18;
	private static final String GREEN = "#90EE90", GREY = "#E5E5E5";

	private final DecimalFormat money = new DecimalFormat("#,##0.00");
	private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd/MM/yyyy");
	private final SimpleDateFormat dateTimeFmt = new SimpleDateFormat("dd-MM-yyyy hh:mm a");

	private int n;          // suppliers
	private int pageWidth;
	private int colWidth;

	public byte[] toPdf(Comparison c) throws Exception {
		n = c.suppliers.size();
		pageWidth = Math.max(842, LEFT + n * (W_UNIT + W_TOT) + 40);
		colWidth = pageWidth - 40;

		String xml = buildJrxml(c);
		JasperReport report = JasperCompileManager.compileReport(
				new ByteArrayInputStream(xml.getBytes("UTF-8")));

		Map<String, Object> params = new HashMap<String, Object>();
		params.put("TERMS", c.terms);
		params.put("ADDRESS", officeAddress());
		JasperPrint print = JasperFillManager.fillReport(report, params, new JRMapCollectionDataSource(rows(c)));
		return JasperExportManager.exportReportToPdf(print);
	}

	// ------------------------------------------------------------ data rows

	private List<Map<String, ?>> rows(Comparison c) {
		List<Map<String, ?>> list = new ArrayList<Map<String, ?>>();
		int sl = 0;
		for (Item it : c.items) {
			Map<String, Object> m = new HashMap<String, Object>();
			m.put("sl", String.valueOf(++sl));
			m.put("pr", it.prNo);
			m.put("item", it.name);
			m.put("uom", it.uom);
			m.put("qty", fmtQty(it.qty));
			m.put("last", it.lastPrice == null ? "" : money.format(it.lastPrice));
			for (int i = 0; i < n; i++) {
				Integer k = Integer.valueOf(c.suppliers.get(i).responseId);
				BigDecimal u = it.unit.get(k);
				BigDecimal t = it.total.get(k);
				m.put("u" + i, u == null ? "" : money.format(u));
				m.put("t" + i, t == null ? "" : money.format(t));
				m.put("l" + i, Boolean.valueOf(it.lowest.contains(k)));
			}
			list.add(m);
		}
		return list;
	}

	private String fmtQty(BigDecimal q) {
		return q.stripTrailingZeros().scale() <= 0 ? q.setScale(0).toPlainString() : q.toPlainString();
	}

	private String officeAddress() {
		try {
			return DB.getSQLValueString(null,
					"SELECT TRIM(COALESCE(l.Address1,'') || ' ' || COALESCE(l.Address2,'') || ' ' || COALESCE(l.City,'') "
							+ "|| CASE WHEN COALESCE(oi.Phone,'')<>'' THEN '. Hotline: ' || oi.Phone ELSE '' END) "
							+ "FROM AD_OrgInfo oi LEFT JOIN C_Location l ON l.C_Location_ID=oi.C_Location_ID "
							+ "WHERE oi.AD_Org_ID=?", Env.getAD_Org_ID(Env.getCtx()));
		} catch (Exception e) {
			return "";
		}
	}

	// ------------------------------------------------------------ JRXML text

	private String buildJrxml(Comparison c) {
		StringBuilder x = new StringBuilder(16000);
		x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		x.append("<jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\" ")
		 .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
		 .append("xsi:schemaLocation=\"http://jasperreports.sourceforge.net/jasperreports ")
		 .append("http://jasperreports.sourceforge.net/xsd/jasperreport.xsd\" ")
		 .append("name=\"ComparativeStatement\" pageWidth=\"").append(pageWidth).append("\" pageHeight=\"595\" ")
		 .append("orientation=\"Landscape\" columnWidth=\"").append(colWidth).append("\" ")
		 .append("leftMargin=\"20\" rightMargin=\"20\" topMargin=\"20\" bottomMargin=\"20\" ")
		 .append("whenNoDataType=\"AllSectionsNoDetail\">\n");

		for (int i = 0; i < n; i++) // one conditional style per supplier column: green when lowest
			x.append("<style name=\"hl").append(i).append("\"><conditionalStyle><conditionExpression>")
			 .append("<![CDATA[$F{l").append(i).append("}.booleanValue()]]></conditionExpression>")
			 .append("<style mode=\"Opaque\" backcolor=\"").append(GREEN).append("\"/></conditionalStyle></style>\n");

		x.append("<parameter name=\"TERMS\" class=\"java.lang.String\"/>\n");
		x.append("<parameter name=\"ADDRESS\" class=\"java.lang.String\"/>\n");
		for (String f : new String[] { "sl", "pr", "item", "uom", "qty", "last" })
			x.append("<field name=\"").append(f).append("\" class=\"java.lang.String\"/>\n");
		for (int i = 0; i < n; i++) {
			x.append("<field name=\"u").append(i).append("\" class=\"java.lang.String\"/>\n");
			x.append("<field name=\"t").append(i).append("\" class=\"java.lang.String\"/>\n");
			x.append("<field name=\"l").append(i).append("\" class=\"java.lang.Boolean\"/>\n");
		}

		title(x, c);
		columnHeader(x, c);
		detail(x);
		pageFooter(x);
		summary(x, c);

		x.append("</jasperReport>\n");
		return x.toString();
	}

	private void title(StringBuilder x, Comparison c) {
		x.append("<title><band height=\"118\">\n");
		text(x, 0, 6, 400, 24, Env.getContext(Env.getCtx(), "#AD_Client_Name"), "Left", true, 15, false, null);
		text(x, (colWidth - 260) / 2, 40, 260, 28, "Comparative Statement", "Center", true, 15, true, null);
		text(x, 0, 78, 300, 40,
				"CS No.      :  " + c.csNo + "\nCS Date    :  " + (c.csDate == null ? "" : dateFmt.format(c.csDate)),
				"Left", false, 9, true, GREY);
		x.append("</band></title>\n");
	}

	private void columnHeader(StringBuilder x, Comparison c) {
		int supW = n * (W_UNIT + W_TOT);
		x.append("<columnHeader><band height=\"").append(ROW + 28 + ROW).append("\">\n");
		text(x, 0, 0, LEFT, ROW, "Requisiton No      :  " + c.requisitionNos, "Left", true, 8, true, null);
		text(x, LEFT, 0, supW, ROW, "Supplier Submitted Price", "Center", true, 8, true, null);
		text(x, 0, ROW, LEFT, 28, "Requisiton Raiser(s)   :  " + c.raisers, "Left", true, 8, true, null);
		for (int i = 0; i < n; i++) {
			Supplier s = c.suppliers.get(i);
			String label = s.name + (s.winner ? "  (Recommended)" : "") + "\n" + s.phone;
			text(x, LEFT + i * (W_UNIT + W_TOT), ROW, W_UNIT + W_TOT, 28, label, "Center", false, 8, true, null);
		}
		int y = ROW + 28;
		int xx = 0;
		xx = hdr(x, xx, y, W_SL, "Sl.");
		xx = hdr(x, xx, y, W_PR, "PR No.");
		xx = hdr(x, xx, y, W_ITEM, "Name of Items");
		xx = hdr(x, xx, y, W_UOM, "UoM");
		xx = hdr(x, xx, y, W_QTY, "Qty.");
		xx = hdr(x, xx, y, W_LAST, "Last P.");
		for (int i = 0; i < n; i++) {
			xx = hdr(x, xx, y, W_UNIT, "Unit Price");
			xx = hdr(x, xx, y, W_TOT, "Total Price");
		}
		x.append("</band></columnHeader>\n");
	}

	private int hdr(StringBuilder x, int xx, int y, int w, String t) {
		text(x, xx, y, w, ROW, t, "Center", true, 8, true, null);
		return xx + w;
	}

	private void detail(StringBuilder x) {
		x.append("<detail><band height=\"").append(ROW).append("\">\n");
		int xx = 0;
		xx = fld(x, xx, W_SL, "sl", "Center", null);
		xx = fld(x, xx, W_PR, "pr", "Left", null);
		xx = fld(x, xx, W_ITEM, "item", "Left", null);
		xx = fld(x, xx, W_UOM, "uom", "Center", null);
		xx = fld(x, xx, W_QTY, "qty", "Right", null);
		xx = fld(x, xx, W_LAST, "last", "Right", null);
		for (int i = 0; i < n; i++) {
			xx = fld(x, xx, W_UNIT, "u" + i, "Right", "hl" + i);
			xx = fld(x, xx, W_TOT, "t" + i, "Right", null);
		}
		x.append("</band></detail>\n");
	}

	private int fld(StringBuilder x, int xx, int w, String field, String align, String style) {
		x.append("<textField isStretchWithOverflow=\"true\" isBlankWhenNull=\"true\">")
		 .append("<reportElement stretchType=\"RelativeToTallestObject\" x=\"").append(xx)
		 .append("\" y=\"0\" width=\"").append(w).append("\" height=\"").append(ROW).append("\"")
		 .append(style == null ? "" : " style=\"" + style + "\"").append("/>")
		 .append(box())
		 .append("<textElement textAlignment=\"").append(align).append("\" verticalAlignment=\"Middle\">")
		 .append("<font fontName=\"SansSerif\" size=\"8\"/></textElement>")
		 .append("<textFieldExpression><![CDATA[$F{").append(field).append("}]]></textFieldExpression>")
		 .append("</textField>\n");
		return xx + w;
	}

	private void summary(StringBuilder x, Comparison c) {
		String[] labels = { "Sub Total Amount", "Discount Amount (if any)", "Transportation Cost", "Other Cost",
				"Grand Total Amount", "VAT (if any)", "Cost of Ownership Amount" };
		x.append("<summary><band height=\"").append(labels.length * ROW + 40 + 80 + 70).append("\">\n");
		for (int r = 0; r < labels.length; r++) {
			int y = r * ROW;
			text(x, 0, y, LEFT, ROW, labels[r], "Left", false, 8, true, null);
			for (int i = 0; i < n; i++) {
				Supplier s = c.suppliers.get(i);
				BigDecimal v;
				switch (r) {
				case 0: v = s.subTotal; break;
				case 1: v = s.discount; break;
				case 2: v = s.transport; break;
				case 3: v = s.other; break;
				case 4: v = s.grand; break;
				case 5: v = s.vat; break;
				default: v = s.ownership;
				}
				int bx = LEFT + i * (W_UNIT + W_TOT);
				text(x, bx, y, W_UNIT, ROW, "", "Right", false, 8, true, null);
				text(x, bx + W_UNIT, y, W_TOT, ROW, money.format(v), "Right", false, 8, true, null);
			}
		}
		int y = labels.length * ROW + 14;
		text(x, 0, y, 300, 14, "Terms and Conditions:", "Left", true, 8, false, null);
		x.append("<textField isStretchWithOverflow=\"true\" isBlankWhenNull=\"true\">")
		 .append("<reportElement positionType=\"Float\" stretchType=\"RelativeToTallestObject\" x=\"0\" y=\"")
		 .append(y + 14).append("\" width=\"").append(colWidth).append("\" height=\"40\"/>")
		 .append("<textElement><font fontName=\"SansSerif\" size=\"8\"/></textElement>")
		 .append("<textFieldExpression><![CDATA[$P{TERMS}]]></textFieldExpression></textField>\n");

		// signature boxes (positionType Float so they follow the terms text)
		String[] heads = { "Prepared by", "Checked by", "Audited by", "Recommended by", "Approved by" };
		String[] body = {
				sign(c.preparedBy, c.preparedTitle, c.preparedDate),
				"", "",
				sign(c.recommendedBy, c.recommendedTitle, c.recommendedDate),
				sign(c.approvedBy, c.approvedTitle, c.approvedDate) };
		int bw = colWidth / 5;
		int sy = y + 14 + 40 + 30;
		for (int i = 0; i < 5; i++) {
			textFloat(x, i * bw, sy, bw, 50, body[i], "Center", false, 8, true, 3);
			textFloat(x, i * bw, sy + 50, bw, 16, heads[i], "Center", true, 8, true, 3);
		}
		x.append("</band></summary>\n");
	}

	private String sign(String name, String title, Date date) {
		if (name == null || name.length() == 0) return "";
		return name + "\n" + (title == null ? "" : title) + "\n" + (date == null ? "" : dateTimeFmt.format(date));
	}

	private void pageFooter(StringBuilder x) {
		x.append("<pageFooter><band height=\"34\">\n");
		text(x, 0, 2, 300, 12, "Corporate Office Address:", "Left", true, 8, false, null);
		x.append("<textField isBlankWhenNull=\"true\"><reportElement x=\"0\" y=\"16\" width=\"")
		 .append(colWidth - 40).append("\" height=\"14\"/>")
		 .append("<textElement><font fontName=\"SansSerif\" size=\"8\"/></textElement>")
		 .append("<textFieldExpression><![CDATA[$P{ADDRESS}]]></textFieldExpression></textField>\n");
		x.append("<textField><reportElement x=\"").append(colWidth - 40)
		 .append("\" y=\"16\" width=\"40\" height=\"14\"/>")
		 .append("<textElement textAlignment=\"Right\"><font fontName=\"SansSerif\" size=\"8\"/></textElement>")
		 .append("<textFieldExpression><![CDATA[$V{PAGE_NUMBER}]]></textFieldExpression></textField>\n");
		x.append("</band></pageFooter>\n");
	}

	// ------------------------------------------------------------ element helpers

	private String box() {
		return "<box leftPadding=\"3\" rightPadding=\"3\"><pen lineWidth=\"0.5\" lineColor=\"#000000\"/></box>";
	}

	private void text(StringBuilder x, int px, int py, int w, int h, String t, String align, boolean bold, int size,
			boolean border, String back) {
		element(x, "", px, py, w, h, t, align, bold, size, border, back);
	}

	private void textFloat(StringBuilder x, int px, int py, int w, int h, String t, String align, boolean bold,
			int size, boolean border, int unused) {
		element(x, " positionType=\"Float\"", px, py, w, h, t, align, bold, size, border, null);
	}

	private void element(StringBuilder x, String extra, int px, int py, int w, int h, String t, String align,
			boolean bold, int size, boolean border, String back) {
		x.append("<staticText><reportElement").append(extra).append(" x=\"").append(px).append("\" y=\"").append(py)
		 .append("\" width=\"").append(w).append("\" height=\"").append(h).append("\"");
		if (back != null)
			x.append(" mode=\"Opaque\" backcolor=\"").append(back).append("\"");
		x.append("/>");
		x.append(border ? box() : "<box leftPadding=\"3\" rightPadding=\"3\"/>");
		x.append("<textElement textAlignment=\"").append(align).append("\" verticalAlignment=\"Middle\">")
		 .append("<font fontName=\"SansSerif\" size=\"").append(size).append("\" isBold=\"").append(bold)
		 .append("\"/></textElement>");
		x.append("<text><![CDATA[").append(t == null ? "" : t.replace("]]>", "]]]]><![CDATA[>"))
		 .append("]]></text></staticText>\n");
	}
}
