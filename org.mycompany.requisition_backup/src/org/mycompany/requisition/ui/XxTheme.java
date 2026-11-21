package org.mycompany.requisition.ui;

import org.adempiere.webui.panel.ADForm;
import org.compiere.util.CLogger;
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
 * The CSS and the small button-colour script are kept inside this class, so the
 * theme never depends on a resource file being copied into the bundle.
 */
public final class XxTheme {

	private static final CLogger log = CLogger.getCLogger(XxTheme.class);

	/** Bump this after editing the CSS or JS so open browsers pick the new look. */
	private static final String VERSION = "5";

	private static final String CSS = """
/* =====================================================================
   XX Theme: shob form (.xx-form) ar dialog (.xx-win) er jonno ekta-i style file.
   Rong bodlate shudhu ei file er :root variable gulo bodlan.
   Kono logic nei, shudhu CSS.
   ===================================================================== */
:root {
  --xx-navy:    #1e3a8a;
  --xx-blue:    #2563eb;
  --xx-sky:     #0ea5e9;
  --xx-teal:    #0d9488;
  --xx-green:   #16a34a;
  --xx-red:     #dc2626;
  --xx-amber:   #d97706;
  --xx-violet:  #7c3aed;
  --xx-slate:   #64748b;
  --xx-line:    #c7d6ee;
  --xx-zebra:   #eef4ff;
  --xx-hover:   #dbe8ff;
  --xx-card-bg: #ffffff;
}

/* ---------- Responsive Layout & Zoom-Resistant Scaling ---------- */
.xx-form, .xx-win {
  box-sizing: border-box !important;
  width: 100% !important;
  max-width: 100% !important;
}

.xx-form *, .xx-win * {
  box-sizing: border-box !important;
}

/* Ensure tables fill 100% width cleanly without leaving empty dead space on the right */
.xx-form .z-listbox, .xx-win .z-listbox,
.xx-form .z-grid, .xx-win .z-grid {
  width: 100% !important;
}

.xx-form .z-listbox-header-table, .xx-form .z-listbox-body-table,
.xx-win .z-listbox-header-table, .xx-win .z-listbox-body-table,
.xx-form .z-grid-header-table, .xx-form .z-grid-body-table,
.xx-win .z-grid-header-table, .xx-win .z-grid-body-table {
  width: 100% !important;
  min-width: 100% !important;
  table-layout: fixed !important;
}

/* Ensure listheaders stretch proportionally across the full width */
.xx-form .z-listhead, .xx-win .z-listhead {
  width: 100% !important;
}

/* Always preserve full button visibility: no clipping or disappearing on zoom */
.xx-form button.z-button, .xx-win button.z-button {
  white-space: nowrap !important;
  flex-shrink: 0 !important;
}

.xx-form .z-listcell, .xx-win .z-listcell {
  overflow: visible !important;
}

/* Responsive scrollbars so user can always scroll smoothly if zoomed in */
.xx-form .z-listbox-body, .xx-win .z-listbox-body,
.xx-form .z-grid-body, .xx-win .z-grid-body {
  overflow-x: auto !important;
  overflow-y: auto !important;
}

/* Responsive Modal Dialog Windows (adapts to viewport on zoom in/out) */
.xx-win.z-window-modal,
.xx-win.z-window-highlighted {
  max-width: 95vw !important;
  max-height: 95vh !important;
}

/* ---------- Window (dialog) title bar ---------- */
.xx-win.z-window,
.xx-win.z-window-modal,
.xx-win.z-window-highlighted,
.xx-win.z-window-popup {
  border-radius: 10px !important;
  border: 1px solid var(--xx-navy) !important;
  box-shadow: 0 12px 38px rgba(15, 23, 42, .35) !important;
  overflow: hidden;
}
.xx-win > .z-window-header,
.xx-win .z-window-header {
  background: linear-gradient(90deg, var(--xx-navy), var(--xx-blue) 60%, var(--xx-sky)) !important;
  color: #fff !important;
  font-weight: 700 !important;
  letter-spacing: .3px;
  border: none !important;
}
.xx-win .z-window-header .z-label { color: #fff !important; }

/* ---------- Group boxes = coloured cards ---------- */
.xx-form .z-groupbox, .xx-win .z-groupbox {
  border: 1px solid var(--xx-line) !important;
  border-radius: 10px !important;
  background: var(--xx-card-bg);
  box-shadow: 0 2px 8px rgba(30, 58, 138, .10);
  margin-bottom: 8px;
}
.xx-form .z-groupbox-header, .xx-win .z-groupbox-header,
.xx-form .z-caption,         .xx-win .z-caption {
  background: linear-gradient(90deg, #e0ebff, #f3f7ff) !important;
  color: var(--xx-navy) !important;
  font-weight: 700 !important;
  border-radius: 9px 9px 0 0;
  border-bottom: 2px solid var(--xx-blue) !important;
}
.xx-form .z-caption .z-caption-content, .xx-win .z-caption .z-caption-content,
.xx-form .z-caption .z-label,           .xx-win .z-caption .z-label {
  color: var(--xx-navy) !important;
  font-weight: 700 !important;
}

/* ---------- Grid / list headers ---------- */
.xx-form .z-column, .xx-win .z-column,
.xx-form .z-listheader, .xx-win .z-listheader,
.xx-form .z-treecol, .xx-win .z-treecol {
  background: linear-gradient(180deg, var(--xx-blue), var(--xx-navy)) !important;
  border-color: #3b5fc4 !important;
  color: #fff !important;
}
.xx-form .z-column-content, .xx-win .z-column-content,
.xx-form .z-listheader-content, .xx-win .z-listheader-content,
.xx-form .z-column a, .xx-win .z-column a,
.xx-form .z-listheader a, .xx-win .z-listheader a,
.xx-form .z-column .z-label, .xx-win .z-column .z-label {
  color: #fff !important;
  font-weight: 700 !important;
}

/* ---------- Zebra rows + hover (inline row colours, e.g. winner green, still win) ---------- */
.xx-form .z-row:nth-child(even), .xx-win .z-row:nth-child(even),
.xx-form .z-row:nth-child(even) > .z-row-inner, .xx-win .z-row:nth-child(even) > .z-row-inner,
.xx-form .z-listitem:nth-child(even), .xx-win .z-listitem:nth-child(even),
.xx-form .z-listitem:nth-child(even) > .z-listcell, .xx-win .z-listitem:nth-child(even) > .z-listcell {
  background-color: var(--xx-zebra);
}
.xx-form .z-row:hover, .xx-win .z-row:hover,
.xx-form .z-row:hover > .z-row-inner, .xx-win .z-row:hover > .z-row-inner,
.xx-form .z-listitem:hover, .xx-win .z-listitem:hover,
.xx-form .z-listitem:hover > .z-listcell, .xx-win .z-listitem:hover > .z-listcell {
  background-color: var(--xx-hover);
}
.xx-form .z-listitem.z-listitem-selected, .xx-win .z-listitem.z-listitem-selected,
.xx-form .z-listitem.z-listitem-selected > .z-listcell, .xx-win .z-listitem.z-listitem-selected > .z-listcell {
  background-color: #bfd6ff !important;
}
.xx-form .z-grid, .xx-win .z-grid, .xx-form .z-listbox, .xx-win .z-listbox {
  border: 1px solid var(--xx-line) !important;
  border-radius: 8px;
  overflow: hidden;
}

/* ---------- Inputs ---------- */
.xx-form .z-textbox, .xx-win .z-textbox,
.xx-form .z-decimalbox, .xx-win .z-decimalbox,
.xx-form .z-intbox, .xx-win .z-intbox,
.xx-form .z-doublebox, .xx-win .z-doublebox,
.xx-form .z-longbox, .xx-win .z-longbox,
.xx-form .z-combobox-input, .xx-win .z-combobox-input,
.xx-form .z-bandbox-input, .xx-win .z-bandbox-input,
.xx-form .z-datebox-input, .xx-win .z-datebox-input,
.xx-form select, .xx-win select {
  border: 1px solid #9fb4d8;
  border-radius: 6px;
  transition: box-shadow .15s, border-color .15s;
}
.xx-form .z-textbox:focus, .xx-win .z-textbox:focus,
.xx-form .z-decimalbox:focus, .xx-win .z-decimalbox:focus,
.xx-form .z-intbox:focus, .xx-win .z-intbox:focus,
.xx-form .z-combobox-input:focus, .xx-win .z-combobox-input:focus,
.xx-form .z-datebox-input:focus, .xx-win .z-datebox-input:focus,
.xx-form select:focus, .xx-win select:focus {
  border-color: var(--xx-blue) !important;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, .22) !important;
  outline: none;
}

/* ---------- Buttons (colour by label comes from xx-theme.js) ---------- */
.xx-form button.z-button, .xx-win button.z-button {
  background: linear-gradient(180deg, #3b82f6, var(--xx-blue)) !important;
  color: #fff !important;
  border: 1px solid #1d4ed8 !important;
  border-radius: 7px !important;
  font-weight: 600 !important;
  box-shadow: 0 2px 4px rgba(30, 58, 138, .25);
  text-shadow: 0 1px 0 rgba(0, 0, 0, .15);
  transition: transform .08s, box-shadow .15s, filter .15s;
}
.xx-form button.z-button:hover, .xx-win button.z-button:hover {
  filter: brightness(1.08);
  box-shadow: 0 4px 10px rgba(30, 58, 138, .35);
  transform: translateY(-1px);
}
.xx-form button.z-button:active, .xx-win button.z-button:active { transform: translateY(0); }
.xx-form button.z-button[disabled], .xx-win button.z-button[disabled] {
  filter: grayscale(.8) opacity(.55);
  box-shadow: none; transform: none;
}
button.z-button.xx-b-ok     { background: linear-gradient(180deg, #22c55e, var(--xx-green)) !important;  border-color: #15803d !important; }
button.z-button.xx-b-no     { background: linear-gradient(180deg, #ef4444, var(--xx-red)) !important;    border-color: #b91c1c !important; }
button.z-button.xx-b-info   { background: linear-gradient(180deg, #38bdf8, var(--xx-sky)) !important;    border-color: #0284c7 !important; }
button.z-button.xx-b-teal   { background: linear-gradient(180deg, #14b8a6, var(--xx-teal)) !important;   border-color: #0f766e !important; }
button.z-button.xx-b-warn   { background: linear-gradient(180deg, #f59e0b, var(--xx-amber)) !important;  border-color: #b45309 !important; }
button.z-button.xx-b-accent { background: linear-gradient(180deg, #8b5cf6, var(--xx-violet)) !important; border-color: #6d28d9 !important; }
button.z-button.xx-b-gray   { background: linear-gradient(180deg, #94a3b8, var(--xx-slate)) !important;  border-color: #475569 !important; }

/* ---------- Tabs, checkboxes, scrollbars ---------- */
.xx-form .z-tab-selected, .xx-win .z-tab-selected {
  border-bottom: 3px solid var(--xx-blue) !important;
}
.xx-form .z-tab-selected .z-tab-text, .xx-win .z-tab-selected .z-tab-text { color: var(--xx-blue) !important; font-weight: 700; }
.xx-form input[type=checkbox], .xx-win input[type=checkbox],
.xx-form input[type=radio],    .xx-win input[type=radio] { accent-color: var(--xx-blue); }
.xx-form *::-webkit-scrollbar, .xx-win *::-webkit-scrollbar { width: 10px; height: 10px; }
.xx-form *::-webkit-scrollbar-thumb, .xx-win *::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, #93b4f0, #5b86e0); border-radius: 8px; border: 2px solid #eef4ff;
}
.xx-form *::-webkit-scrollbar-track, .xx-win *::-webkit-scrollbar-track { background: #eef4ff; }
""";

	private static final String JS = """
/* Button ke tar lekha dekhe rong dei. Kono click/logic chhuy na, shudhu CSS class boshay. */
(function () {
  var RULES = [
    ['no',     /(reject|cancel|delete|remove|void|discard|decline|^x$|\\u2716|\\u2715|\\u00d7)/],
    ['gray',   /(clear|reset|back|close|undo|skip)/],
    ['accent', /(recommend|winner|forward|delegate|escalate)/],
    ['info',   /(view|detail|print|history|attachment|preview|report|comparative|pdf|download|show)/],
    ['teal',   /(reload|refresh|search|load|find|filter|select|add|new)/],
    ['warn',   /(edit|modify|change|hold|return|revise|re-?activate)/],
    ['ok',     /(approve|save|submit|^ok$|confirm|generate|create|complete|process|send|accept|apply|post|run|finish|update|yes|\\u2714|\\u2713)/]
  ];
  var ALL = ['xx-b-no', 'xx-b-gray', 'xx-b-accent', 'xx-b-info', 'xx-b-teal', 'xx-b-warn', 'xx-b-ok'];
  function paint() {
    var list = document.querySelectorAll('.xx-form button.z-button, .xx-win button.z-button');
    for (var i = 0; i < list.length; i++) {
      var b = list[i], t = (b.textContent || '').replace(/\\s+/g, ' ').trim().toLowerCase(), cls = null;
      if (t) for (var r = 0; r < RULES.length; r++) if (RULES[r][1].test(t)) { cls = 'xx-b-' + RULES[r][0]; break; }
      for (var k = 0; k < ALL.length; k++) if (ALL[k] !== cls) b.classList.remove(ALL[k]);
      if (cls) b.classList.add(cls);
    }
  }
  var timer = null;
  if (window.__xxObserver) window.__xxObserver.disconnect();
  window.__xxObserver = new MutationObserver(function () {
    clearTimeout(timer); timer = setTimeout(paint, 60);
  });
  window.__xxObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
  paint();
})();
""";

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
			String script = "(function(){var V='" + VERSION + "';"
					+ "if(window.__xxThemeV!==V){window.__xxThemeV=V;"
					+ "var s=document.getElementById('xx-theme-css');"
					+ "if(!s){s=document.createElement('style');s.id='xx-theme-css';document.head.appendChild(s);}"
					+ "s.textContent='" + escape(CSS) + "';"
					+ "}})();" + JS;
			Clients.evalJavaScript(script);
		} catch (Exception e) {
			// cosmetic only: never stop a form from opening, but leave a trace in the log
			log.warning("XxTheme not installed: " + e);
		}
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n");
	}
}
