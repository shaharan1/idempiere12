/**
 * Runtime model of an approval transaction.
 *
 * <p>
 * This package represents one document moving through the approval process. It
 * is deliberately separate from {@link org.mycompany.requisition.approval.config}
 * so that the configuration can change without invalidating historical
 * transactions: every transaction stores the process version id it was created
 * against, and therefore keeps using the rules that were current at that moment.
 * </p>
 *
 * <p>
 * Expected classes:
 * </p>
 * <ul>
 * <li>{@code ApprovalTransaction} - header: document, process, version, status</li>
 * <li>{@code ApprovalStep} - one level instance inside a transaction</li>
 * <li>{@code ApprovalAction} - approve / reject / return / delegate / skip</li>
 * <li>{@code ApprovalStatus} - draft, pending, approved, rejected, returned</li>
 * <li>{@code SlaState} - normal, warning, overdue, escalated</li>
 * <li>{@code WorkflowTestResult} - output of the Test Workflow feature</li>
 * </ul>
 */
package org.mycompany.requisition.approval.model;
