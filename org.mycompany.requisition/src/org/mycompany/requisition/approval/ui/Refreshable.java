package org.mycompany.requisition.approval.ui;

/**
 * Anything on the window that has to reload when the selection changes.
 * <p>
 * Sections 04, 05, 07, 08 and 09 are configuration lists and extend
 * {@link ConfigListPanel}. Section 10 is not one of those - it shows approvals,
 * not configuration - but it still has to be told when the process or the version
 * changed, so it implements this as well.
 */
interface Refreshable {

	/** Reloads whatever this section shows. Must never throw. */
	void refresh();
}
