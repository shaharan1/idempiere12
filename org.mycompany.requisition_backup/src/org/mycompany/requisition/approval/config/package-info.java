/**
 * Configuration layer - pure data holders for the master configuration.
 *
 * <p>
 * Everything in this package is metadata only: process, version, workflow,
 * level, rule, SLA, escalation, delegation and notification settings. No
 * database access and no ZK code lives here, which keeps the rules testable and
 * reusable from a workflow engine, a report or a scheduled job.
 * </p>
 *
 * <p>
 * Expected classes:
 * </p>
 * <ul>
 * <li>{@code ProcessCategory} - group of business processes</li>
 * <li>{@code ProcessDefinition} - one business process</li>
 * <li>{@code ProcessVersion} - effective dated version of a process</li>
 * <li>{@code WorkflowDefinition} - sequential or parallel workflow</li>
 * <li>{@code ApprovalLevel} - one approval step</li>
 * <li>{@code ApprovalRule} - condition that selects required levels</li>
 * <li>{@code SlaPolicy} - response time and escalation</li>
 * <li>{@code Delegation} - approver substitution</li>
 * <li>{@code NotificationSetting} - who is notified when</li>
 * <li>{@code ResponsibleType} / {@code ApprovalType} / {@code ApprovalStatus}</li>
 * </ul>
 */
package org.mycompany.requisition.approval.config;
