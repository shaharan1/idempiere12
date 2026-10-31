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
 * Comparative Statement PDF, laid out pixel for pixel like
 * {@code Comparative_Statement_Print_Format.pdf}: company logos, boxed title,
 * grey CS No/Date box, supplier header grid, green lowest unit price, the seven
 * total rows, terms, office address, page number and the five signature blocks.
 * <p>
 * The supplier count changes per RFQ, so the JRXML is generated for the RFQ at
 * hand and compiled with the Jasper engine already required by the bundle.
 */
public class ComparativeStatementReport {

	// page / grid, in points (A4 landscape)
	private static final int PAGE_W = 842, PAGE_H = 595, MARGIN = 20;
	private static final int W_SL = 30, W_PR = 80, W_ITEM = 120, W_UOM = 50, W_QTY = 66, W_LAST = 66;
	private static final int LEFT = W_SL + W_PR + W_ITEM + W_UOM + W_QTY + W_LAST; // 412
	private static final int TABLE_W = 802;
	private static final int SUP_W = TABLE_W - LEFT; // 390
	private static final String ROW = "16";
	private static final String GREEN = "#90EE90", GREY = "#E5E5E5";

	// vertical offsets inside the title band
	private static final int TITLE_Y = 50, CS_Y = 97, CS_H = 42;
	// vertical offsets inside the column header band
	private static final int HDR1 = 17, HDR2 = 28, HDR3 = 17;
	// vertical offsets inside the summary band
	private static final int SUM_ROWS = 7 * 16;
	private static final int TERMS_Y = 134, TERMS_H = 20;
	private static final int ADDR_LBL_Y = 170, ADDR_Y = 184;
	private static final int SIGN_Y = 206, SIGN_H = 62;

	private final DecimalFormat money = new DecimalFormat("#,##0.00");
	private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd/MM/yyyy");
	private final SimpleDateFormat dateTimeFmt = new SimpleDateFormat("dd-MM-yyyy hh:mm a");

	private int n;         // suppliers
	private int unitW, totW;

	public byte[] toPdf(Comparison c) throws Exception {
		n = Math.max(1, c.suppliers.size());
		int pair = SUP_W / n;
		unitW = pair * 6 / 13;
		totW = pair - unitW;

		String xml = buildJrxml(c);
		JasperReport report = JasperCompileManager.compileReport(new ByteArrayInputStream(xml.getBytes("UTF-8")));

		Map<String, Object> params = new HashMap<String, Object>();
		params.put("TERMS", c.terms == null ? "" : c.terms);
		params.put("CS_NO", c.csNo == null ? "" : c.csNo);
		params.put("CS_DATE", c.csDate == null ? "" : dateFmt.format(c.csDate));
		params.put("REQ_NOS", c.requisitionNos == null ? "" : c.requisitionNos);
		params.put("RAISERS", c.raisers == null ? "" : c.raisers);
		params.put("ADDRESS", officeAddress());
		params.put("LOGO_LEFT", logo("logo_left.png"));
		params.put("LOGO_RIGHT", logo("logo_right.png"));
		JasperPrint print = JasperFillManager.fillReport(report, params, new JRMapCollectionDataSource(rows(c)));
		return JasperExportManager.exportReportToPdf(print);
	}

	private java.io.InputStream logo(String name) {
		try {
			return ComparativeStatementReport.class.getResourceAsStream(name);
		} catch (Exception e) {
			return null;
		}
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
		if (q == null)
			return "";
		return q.stripTrailingZeros().scale() <= 0 ? q.setScale(0).toPlainString() : q.toPlainString();
	}

	private String officeAddress() {
		try {
			String a = DB.getSQLValueString(null,
					"SELECT TRIM(COALESCE(l.Address1,'') || ' ' || COALESCE(l.Address2,'') || ' ' || COALESCE(l.City,'') "
							+ "|| CASE WHEN COALESCE(oi.Phone,'')<>'' THEN '. Hotline: ' || oi.Phone ELSE '' END) "
							+ "FROM AD_OrgInfo oi LEFT JOIN C_Location l ON l.C_Location_ID=oi.C_Location_ID "
							+ "WHERE oi.AD_Org_ID=?",
					Env.getAD_Org_ID(Env.getCtx()));
			if (a != null && a.trim().length() > 0)
				return a.trim();
		} catch (Exception e) {
			// fall through to the default address
		}
		return "Primitek Tower, House #43/B, Road #24, Block #CWS (B), Gulshan-1, Dhaka-1212. "
				+ "Hotline: +88-09612-300800";
	}

	// ------------------------------------------------------------ JRXML text

	private String buildJrxml(Comparison c) {
		StringBuilder x = new StringBuilder(24000);
		x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		x.append("<jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\" ")
				.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
				.append("xsi:schemaLocation=\"http://jasperreports.sourceforge.net/jasperreports ")
				.append("http://jasperreports.sourceforge.net/xsd/jasperreport.xsd\" ")
				.append("name=\"ComparativeStatement\" pageWidth=\"").append(PAGE_W).append("\" pageHeight=\"")
				.append(PAGE_H).append("\" orientation=\"Landscape\" columnWidth=\"").append(TABLE_W).append("\" ")
				.append("leftMargin=\"").append(MARGIN).append("\" rightMargin=\"").append(MARGIN)
				.append("\" topMargin=\"").append(MARGIN).append("\" bottomMargin=\"").append(MARGIN).append("\" ")
				.append("whenNoDataType=\"AllSectionsNoDetail\" isIgnorePagination=\"false\">\n");

		for (int i = 0; i < n; i++)
			x.append("<style name=\"hl").append(i).append("\"><conditionalStyle><conditionExpression>")
					.append("<![CDATA[$F{l").append(i).append("}.booleanValue()]]></conditionExpression>")
					.append("<style mode=\"Opaque\" backcolor=\"").append(GREEN).append("\"/></conditionalStyle></style>\n");

		x.append("<parameter name=\"TERMS\" class=\"java.lang.String\"/>\n");
		x.append("<parameter name=\"CS_NO\" class=\"java.lang.String\"/>\n");
		x.append("<parameter name=\"CS_DATE\" class=\"java.lang.String\"/>\n");
		x.append("<parameter name=\"REQ_NOS\" class=\"java.lang.String\"/>\n");
		x.append("<parameter name=\"RAISERS\" class=\"java.lang.String\"/>\n");
		x.append("<parameter name=\"ADDRESS\" class=\"java.lang.String\"/>\n");
		x.append("<parameter name=\"LOGO_LEFT\" class=\"java.io.InputStream\"/>\n");
		x.append("<parameter name=\"LOGO_RIGHT\" class=\"java.io.InputStream\"/>\n");
		for (String f : new String[] { "sl", "pr", "item", "uom", "qty", "last" })
			x.append("<field name=\"").append(f).append("\" class=\"java.lang.String\"/>\n");
		for (int i = 0; i < n; i++) {
			x.append("<field name=\"u").append(i).append("\" class=\"java.lang.String\"/>\n");
			x.append("<field name=\"t").append(i).append("\" class=\"java.lang.String\"/>\n");
			x.append("<field name=\"l").append(i).append("\" class=\"java.lang.Boolean\"/>\n");
		}

		title(x);
		columnHeader(x, c);
		detail(x);
		summary(x, c);
		x.append("</jasperReport>\n");
		return x.toString();
	}

	// ---- logo + boxed title + grey CS box
	private void title(StringBuilder x) {
		x.append("<title><band height=\"159\">\n");
		image(x, 0, 0, 229, 28, "LOGO_LEFT");
		image(x, TABLE_W - 154, 0, 154, 29, "LOGO_RIGHT");
		st(x, 284, TITLE_Y, 230, 29, "Comparative Statement", "Center", true, 16, true, null, "Middle");
		st(x, 0, CS_Y, 372, CS_H, "", "Left", false, 10, true, GREY, "Middle");
		st(x, 13, CS_Y + 7, 60, 14, "CS No.", "Left", true, 10, false, null, "Middle");
		st(x, 71, CS_Y + 7, 10, 14, ":", "Left", true, 10, false, null, "Middle");
		tf(x, 83, CS_Y + 7, 240, 14, "$P{CS_NO}", "Left", false, 10, "Middle");
		st(x, 13, CS_Y + 25, 60, 14, "CS Date", "Left", true, 10, false, null, "Middle");
		st(x, 71, CS_Y + 25, 10, 14, ":", "Left", true, 10, false, null, "Middle");
		tf(x, 83, CS_Y + 25, 240, 14, "$P{CS_DATE}", "Left", false, 10, "Middle");
		x.append("</band></title>\n");
	}

	// ---- requisition block, supplier names and the column captions
	private void columnHeader(StringBuilder x, Comparison c) {
		x.append("<columnHeader><band height=\"").append(HDR1 + HDR2 + HDR3).append("\">\n");
		st(x, 0, 0, LEFT, HDR1, "", "Left", true, 9, true, null, "Middle");
		st(x, 3, 0, 110, HDR1, "Requisiton No      :", "Left", true, 9, false, null, "Middle");
		tf(x, 113, 0, LEFT - 116, HDR1, "$P{REQ_NOS}", "Left", true, 9, "Middle");
		st(x, LEFT, 0, SUP_W, HDR1, "Supplier Submitted Price", "Center", true, 9, true, null, "Middle");

		st(x, 0, HDR1, LEFT, HDR2, "", "Left", true, 9, true, null, "Middle");
		st(x, 3, HDR1, 110, HDR2, "Requisiton Raiser(s)   :", "Left", true, 9, false, null, "Middle");
		tf(x, 113, HDR1, LEFT - 116, HDR2, "$P{RAISERS}", "Left", true, 9, "Middle");
		for (int i = 0; i < n; i++) {
			Supplier s = c.suppliers.get(i);
			String label = s.name + (s.phone == null || s.phone.trim().length() == 0 ? "" : "\n" + s.phone);
			st(x, LEFT + i * (unitW + totW), HDR1, unitW + totW, HDR2, label, "Center", false, 9, true, null, "Middle");
		}

		int y = HDR1 + HDR2, xx = 0;
		xx = hdr(x, xx, y, W_SL, "Sl.");
		xx = hdr(x, xx, y, W_PR, "PR No.");
		xx = hdr(x, xx, y, W_ITEM, "Name of Items");
		xx = hdr(x, xx, y, W_UOM, "UoM");
		xx = hdr(x, xx, y, W_QTY, "Qty.");
		xx = hdr(x, xx, y, W_LAST, "Last P.");
		for (int i = 0; i < n; i++) {
			xx = hdr(x, xx, y, unitW, "Unit Price");
			xx = hdr(x, xx, y, totW, "Total Price");
		}
		x.append("</band></columnHeader>\n");
	}

	private int hdr(StringBuilder x, int xx, int y, int w, String t) {
		st(x, xx, y, w, HDR3, t, "Center", true, 9, true, null, "Middle");
		return xx + w;
	}

	// ---- one product row
	private void detail(StringBuilder x) {
		x.append("<detail><band height=\"").append(ROW).append("\" splitType=\"Stretch\">\n");
		int xx = 0;
		xx = fld(x, xx, W_SL, "sl", "Center", null);
		xx = fld(x, xx, W_PR, "pr", "Left", null);
		xx = fld(x, xx, W_ITEM, "item", "Left", null);
		xx = fld(x, xx, W_UOM, "uom", "Center", null);
		xx = fld(x, xx, W_QTY, "qty", "Right", null);
		xx = fld(x, xx, W_LAST, "last", "Right", null);
		for (int i = 0; i < n; i++) {
			xx = fld(x, xx, unitW, "u" + i, "Right", "hl" + i);
			xx = fld(x, xx, totW, "t" + i, "Right", null);
		}
		x.append("</band></detail>\n");
	}

	private int fld(StringBuilder x, int xx, int w, String field, String align, String style) {
		x.append("<textField isStretchWithOverflow=\"true\" isBlankWhenNull=\"true\">")
				.append("<reportElement stretchType=\"RelativeToTallestObject\" x=\"").append(xx)
				.append("\" y=\"0\" width=\"").append(w).append("\" height=\"").append(ROW).append("\"")
				.append(style == null ? "" : " style=\"" + style + "\"").append("/>")
				.append(box(3, 3))
				.append("<textElement textAlignment=\"").append(align)
				.append("\" verticalAlignment=\"Middle\"><font fontName=\"SansSerif\" size=\"9\"/></textElement>")
				.append("<textFieldExpression><![CDATA[$F{").append(field).append("}]]></textFieldExpression>")
				.append("</textField>\n");
		return xx + w;
	}

	// ---- totals, terms, address, page number, signatures
	private void summary(StringBuilder x, Comparison c) {
		String[] labels = { "Sub Total Amount", "Discount Amount (if any)", "Transportation Cost", "Other Cost",
				"Grand Total Amount", "VAT (if any)", "Cost of Ownership Amount" };
		int height = SIGN_Y + SIGN_H;
		x.append("<summary><band height=\"").append(height).append("\" splitType=\"Stretch\">\n");

		for (int r = 0; r < labels.length; r++) {
			int y = r * 16;
			st(x, 0, y, LEFT, 16, labels[r], "Left", false, 9, true, null, "Middle");
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
				int bx = LEFT + i * (unitW + totW);
				st(x, bx, y, unitW, 16, "", "Right", false, 9, true, null, "Middle");
				st(x, bx + unitW, y, totW, 16, money.format(v == null ? BigDecimal.ZERO : v), "Right", false, 9, true,
						null, "Middle");
			}
		}

		st(x, 0, TERMS_Y, 300, 14, "Terms and Conditions:", "Left", true, 9, false, null, "Top");
		textFloat(x, 0, TERMS_Y + 14, 420, TERMS_H, "$P{TERMS}", "Left", false, 8, 0);

		st(x, 0, ADDR_LBL_Y, 300, 12, "Corporate Office Address:", "Left", true, 8, false, null, "Middle");
		tf(x, 0, ADDR_Y, TABLE_W - 40, 12, "$P{ADDRESS}", "Left", true, 8, "Middle");
		st(x, TABLE_W - 40, ADDR_Y, 40, 12, "1", "Right", false, 8, false, null, "Middle");

		signatures(x, c);
		x.append("</band></summary>\n");
	}

	private void signatures(StringBuilder x, Comparison c) {
		String[] heads = { "Prepared by", "Checked by", "Audited by", "Recommended by", "Approved by" };
		String[] body = { sign(c.preparedBy, c.preparedTitle, c.preparedDate), "",
				"", sign(c.recommendedBy, c.recommendedTitle, c.recommendedDate),
				sign(c.approvedBy, c.approvedTitle, c.approvedDate) };
		int bw = TABLE_W / 5;
		for (int i = 0; i < 5; i++) {
			String[] lines = body[i].split("\n", -1);
			int bx = i * bw;
			for (int j = 0; j < 3; j++)
				st(x, bx, SIGN_Y + j * 12, bw, 12, lines.length > j ? lines[j] : "", "Center", true, 10, false, null,
						"Middle");
			line(x, bx + 10, SIGN_Y + 40, bw - 20);
			st(x, bx, SIGN_Y + 48, bw, 14, heads[i], "Center", true, 10, false, null, "Middle");
		}
	}

	private String sign(String name, String title, Date date) {
		if (name == null || name.length() == 0)
			return "\n\n";
		return name + "\n" + (title == null ? "" : title) + "\n" + (date == null ? "" : dateTimeFmt.format(date));
	}

	private void tf(StringBuilder x, int px, int py, int w, int h, String expr, String align,
			boolean bold, int size, String valign) {
		x.append("<textField isBlankWhenNull=\"true\"><reportElement x=\"").append(px).append("\" y=\"").append(py)
				.append("\" width=\"").append(w).append("\" height=\"").append(h).append("\"/>")
				.append("<textElement textAlignment=\"").append(align).append("\" verticalAlignment=\"")
				.append(valign == null ? "Middle" : valign).append("\"><font fontName=\"SansSerif\" size=\"")
				.append(size).append("\" isBold=\"").append(bold).append("\"/></textElement>")
				.append("<textFieldExpression><![CDATA[").append(expr).append("]]></textFieldExpression>")
				.append("</textField>\n");
	}

	private String box(int left, int right) {
		return "<box leftPadding=\"" + left + "\" rightPadding=\"" + right
				+ "\"><pen lineWidth=\"0.5\" lineColor=\"#000000\"/></box>";
	}

	private void image(StringBuilder x, int px, int py, int w, int h, String param) {
		x.append("<image scaleImage=\"RetainShape\" onErrorType=\"Blank\"><reportElement x=\"").append(px)
				.append("\" y=\"").append(py).append("\" width=\"").append(w).append("\" height=\"").append(h)
				.append("\"/><imageExpression><![CDATA[$P{").append(param).append("}]]></imageExpression></image>\n");
	}

	private void line(StringBuilder x, int px, int py, int w) {
		x.append("<staticText><reportElement x=\"").append(px).append("\" y=\"").append(py).append("\" width=\"")
				.append(w).append("\" height=\"1\"/><box><topPen lineWidth=\"0.75\"/></box>")
				.append("<textElement/><text><![CDATA[]]></text></staticText>\n");
	}

	private void st(StringBuilder x, int px, int py, int w, int h, String t, String align, boolean bold, int size,
			boolean border, String back, String valign) {
		element(x, px, py, w, h, t, align, bold, size, border, back, valign);
	}

	private void textFloat(StringBuilder x, int px, int py, int w, int h, String expression, String align,
			boolean bold, int size, int unused) {
		x.append("<textField isStretchWithOverflow=\"true\" isBlankWhenNull=\"true\">")
				.append("<reportElement positionType=\"Float\" x=\"").append(px).append("\" y=\"").append(py)
				.append("\" width=\"").append(w).append("\" height=\"").append(h).append("\"/>")
				.append("<textElement textAlignment=\"").append(align)
				.append("\" verticalAlignment=\"Top\"><font fontName=\"SansSerif\" size=\"").append(size)
				.append("\" isBold=\"").append(bold).append("\"/></textElement>")
				.append("<textFieldExpression><![CDATA[").append(expression)
				.append("]]></textFieldExpression></textField>\n");
	}

	private void element(StringBuilder x, int px, int py, int w, int h, String t, String align, boolean bold,
			int size, boolean border, String back, String valign) {
		x.append("<staticText><reportElement x=\"").append(px).append("\" y=\"").append(py).append("\" width=\"")
				.append(w).append("\" height=\"").append(h).append("\"");
		if (back != null)
			x.append(" mode=\"Opaque\" backcolor=\"").append(back).append("\"");
		x.append("/>");
		x.append(border ? box(3, 3) : "<box leftPadding=\"3\" rightPadding=\"3\"/>");
		x.append("<textElement textAlignment=\"").append(align).append("\" verticalAlignment=\"")
				.append(valign == null ? "Middle" : valign).append("\">")
				.append("<font fontName=\"SansSerif\" size=\"").append(size).append("\" isBold=\"").append(bold)
				.append("\"/></textElement>");
		x.append("<text><![CDATA[").append(t == null ? "" : t.replace("]]>", "]]]]><![CDATA[>"))
				.append("]]></text></staticText>\n");
	}
}
