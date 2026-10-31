/**
 * Service layer - all persistence and engine logic.
 *
 * <p>
 * Two strictly separated responsibilities:
 * </p>
 * <ul>
 * <li><b>Configuration services</b> read and write the master data. They are used
 * by the administration UI.</li>
 * <li><b>Engine services</b> drive a document through the approval chain. They are
 * used by the business transactions (Purchase Requisition, Sales Order, Invoice,
 * ...) and must not depend on ZK at all, so the same code can also run from a
 * batch job or a web service.</li>
 * </ul>
 *
 * <p>
 * Persistence uses the standard iDempiere {@code PO} / {@code M*} model so audit
 * columns, multi tenant rules and validation behave exactly like the rest of the
 * system. No raw SQL insert or update is used for CRUD.
 * </p>
 *
 * <p>
 * Expected classes:
 * </p>
 * <ul>
 * <li>{@code ProcessConfigService} - category, process and version CRUD</li>
 * <li>{@code LevelConfigService} - approval levels and reordering</li>
 * <li>{@code RuleConfigService} - approval rules</li>
 * <li>{@code DelegationService} - delegation lookup</li>
 * <li>{@code NotificationService} - notification dispatch</li>
 * <li>{@code WorkflowEngine} - starts, advances and completes a transaction</li>
 * <li>{@code RuleResolver} - picks the applicable rule for a document</li>
 * <li>{@code SlaMonitor} - computes warning, overdue and escalation state</li>
 * <li>{@code ApprovalAuditService} - immutable approval history</li>
 * <li>{@code WorkflowTestService} - dry run for the Test Workflow button</li>
 * </ul>
 */
package org.mycompany.requisition.approval.service;
