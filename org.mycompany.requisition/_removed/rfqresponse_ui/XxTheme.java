package org.mycompany.requisition.ui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.adempiere.webui.panel.ADForm;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Window;

/**
 * Presentation only. Gives every form and dialog of this bundle one shared look
 * (colours, cards, headers, buttons) without touching any business logic.
 * <ul>
 * <li>{@link #wrap(ADForm)} marks a form with the CSS class {@code xx-form}.</li>
 * <li>{@link #apply(Window)} marks a dialog with the CSS class {@code xx-win}.</li>
 * </ul>
 * The look itself lives in {@code xx-theme.css} (colours) and
 * {@code xx-theme.js} (button colour by label) next to this class.
 */
public final class XxTheme {

	/** Bump this after editing the css or js so open browsers pick the new look. */
	private static final String VERSION = "1";

	private static String css;
	private static String js;

	private XxTheme() {
	}

	/** Marks a form and installs the theme in the browser. Returns the same form. */
	public static <T extends ADForm> T wrap(T form) {
		if (form != null) {
			addClass(form);
			install();
		}
		return form;
	}

	/** Marks a dialog window and installs the theme in the browser. */
	public static void apply(Window win) {
		if (win == null)
			return;
		String s = win.getSclass();
		if (s == null || !s.contains("xx-win"))
			win.setSclass(s == null || s.isEmpty() ? "xx-win" : s + " xx-win");
		install();
	}

	private static void addClass(ADForm form) {
		String s = form.getSclass();
		if (s == null || !s.contains("xx-form"))
			form.setSclass(s == null || s.isEmpty() ? "xx-form" : s + " xx-form");
	}

	/** Sends the stylesheet and the button script to the browser (once per version). */
	private static void install() {
		if (Executions.getCurrent() == null)
			return;
		try {
			if (css == null)
				css = read("xx-theme.css");
			if (js == null)
				js = read("xx-theme.js");
			String script = "(function(){var V='" + VERSION + "';"
					+ "if(window.__xxThemeV!==V){window.__xxThemeV=V;"
					+ "var s=document.getElementById('xx-theme-css');"
					+ "if(!s){s=document.createElement('style');s.id='xx-theme-css';document.head.appendChild(s);}"
					+ "s.textContent='" + escape(css) + "';"
					+ "}})();" + js;
			Clients.evalJavaScript(script);
		} catch (Exception e) {
			// the theme is cosmetic: a missing file must never stop a form from opening
		}
	}

	private static String read(String name) throws Exception {
		try (InputStream in = XxTheme.class.getResourceAsStream(name)) {
			if (in == null)
				throw new IllegalStateException("missing " + name);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n");
	}
}
