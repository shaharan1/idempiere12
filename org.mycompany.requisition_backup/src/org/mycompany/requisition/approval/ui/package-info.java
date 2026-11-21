/**
 * ZK Web UI layer of the approval framework.
 *
 * <p>
 * Only presentation lives here. Every class extends
 * {@link org.adempiere.webui.panel.ADForm} or is a ZK component built by such a
 * form, so the whole UI is registerable as an {@code AD_Form} and reachable from
 * the iDempiere menu.
 * </p>
 *
 * <p>
 * Layout rule used throughout: one vertical scroll area per window, the page
 * title above it, and every logical group inside its own
 * {@code Groupbox + Caption} section. Frame content is always built as
 * {@code Grid > Columns + Rows} because a ZK {@code Rows} component only accepts
 * a {@code Grid} as parent.
 * </p>
 *
 * <p>
 * Expected classes:
 * </p>
 * <ul>
 * <li>{@code WProcessApprovalWindow} - the main window, hosts all sections</li>
 * <li>{@code ProcessInfoPanel} - section 01</li>
 * <li>{@code ProcessVersionPanel} - section 02</li>
 * <li>{@code WorkflowConfigPanel} - section 03</li>
 * <li>{@code ApprovalLevelPanel} - section 04, tabbed child grid</li>
 * <li>{@code ApprovalRulePanel} - section 05</li>
 * <li>{@code WorkflowDiagramPanel} - section 06, visual diagram</li>
 * <li>{@code SlaEscalationPanel} - section 07</li>
 * <li>{@code DelegationPanel} - section 08</li>
 * <li>{@code NotificationPanel} - section 09</li>
 * <li>{@code ApprovalTransactionPanel} - section 10, approve/reject/return</li>
 * <li>{@code ApprovalHistoryPanel} - section 11</li>
 * <li>{@code AuditPanel} - section 12</li>
 * <li>{@code ApprovalDashboardWindow} - dashboard and my pending approvals</li>
 * <li>{@code ApprovalStatusDecorator} - green / amber / red status styling</li>
 * </ul>
 */
package org.mycompany.requisition.approval.ui;
