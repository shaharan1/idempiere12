package org.mycompany.requisition.approval.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.compiere.model.MTable;
import org.compiere.model.PO;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.mycompany.requisition.approval.config.ApprovalLevel;
import org.mycompany.requisition.approval.config.NotificationSetting.Event;
import org.mycompany.requisition.approval.config.WorkflowDefinition;
import org.mycompany.requisition.approval.config.WorkflowType;
import org.mycompany.requisition.approval.model.ApprovalAction;
import org.mycompany.requisition.approval.model.ApprovalHistoryEntry;
import org.mycompany.requisition.approval.model.ApprovalStatus;
import org.mycompany.requisition.approval.model.ApprovalStep;
import org.mycompany.requisition.approval.model.ApprovalTransaction;
import org.mycompany.requisition.approval.model.DocumentContext;
import org.mycompany.requisition.approval.model.RequiredApproval;
import org.mycompany.requisition.approval.model.ResolvedRoute;
import org.mycompany.requisition.approval.model.SlaState;
import org.mycompany.requisition.approval.model.StepStatus;

/**
 * Runs the approval chain of a document.
 * <p>
 * This is the authority. Every transition goes through {@link #act} and nowhere
 * else, so the rules the design states - a rejection ends the chain even on an
 * advisory level, a return goes back to the requester, delegation replaces the
 * approver without touching the level, and every action leaves one audit row -
 * hold no matter which screen calls it. No ZK type is imported here on purpose:
 * the same engine has to work from a window, from a business document and from a
 * scheduled job.
 *
 * <h3>Chain shape</h3>
 * A sequential workflow opens the lowest level only and promotes the next one
 * after each approval. A parallel workflow opens every level at once. Both finish
 * the same way: when no step is open any more the transaction is approved only if
 * {@link ApprovalTransaction#isFullyApproved()} agrees, which is what keeps an
 * advisory level from blocking a document while still honouring a rejection.
 *
 * @see RuleResolver for who has to approve
 * @see HistoryWriter for the audit trail
 */
public class WorkflowEngine extends BaseConfigService {

	private static final String T_TXN = "XX_PROCESS_TRANSACTION";
	private static final String T_STEP = "XX_APPROVAL_STEP";

	/** Key columns, exactly as registered in AD_Column. */
	private static final String K_TXN = "XX_PROCESS_TRANSACTION_ID";
	private static final String K_STEP = "XX_APPROVAL_STEP_ID";

	/** Role allowed to act on any step, resolved by name so no id is hard coded. */
	private static final String ADMIN_ROLE_NAME = "System Administrator";

	/** Access to the step table, reusing the column plumbing of the base class. */
	private final BaseConfigService stepAccess = new BaseConfigService(T_STEP) {
	};

	public WorkflowEngine() throws ConfigException {
		super(T_TXN);
	}

	// ─────────────────────────────────────────────────────────────
	//  Submission
	// ─────────────────────────────────────────────────────────────

	/**
	 * Starts the approval chain of a document.
	 * <p>
	 * The transaction pins {@code Process_Version_ID}. Reconfiguring the process
	 * afterwards therefore never changes the chain of a document that is already
	 * running, which is what makes the audit trail reliable.
	 *
	 * @param processVersionId version whose configuration decides the chain
	 * @param context          facts about the document
	 * @return the transaction, reloaded with its steps and history
	 * @throws ConfigException when the document cannot be submitted, for example
	 *                         because it is already under approval
	 */
	public ApprovalTransaction submit(int processVersionId, DocumentContext context) throws ConfigException {
		return submit(processVersionId, context, null);
	}

	/**
	 * Starts the approval chain of a document.
	 * <p>
	 * The transaction pins {@code Process_Version_ID}. Reconfiguring the process
	 * afterwards therefore never changes the chain of a document that is already
	 * running, which is what makes the audit trail reliable.
	 *
	 * @param processVersionId version whose configuration decides the chain
	 * @param context          facts about the document
	 * @param comment          note from the caller for the submission row, may be null
	 * @return the transaction, reloaded with its steps and history
	 * @throws ConfigException when the document cannot be submitted, for example
	 *                         because it is already under approval
	 */
	public ApprovalTransaction submit(int processVersionId, DocumentContext context, String comment)
			throws ConfigException {
		if (processVersionId <= 0)
			throw new ConfigException("The process version of the document is unknown.");
		if (context == null)
			throw new ConfigException("No document data was supplied.");
		if (context.getRecordId() <= 0)
			throw new ConfigException("The document record is unknown, so its approval cannot be started.");

		int open = findOpen(context.getTableId(), context.getRecordId());
		if (open > 0)
			throw new ConfigException("Document " + context.getDisplayName()
					+ " is already under approval (transaction " + open + ").");

		WorkflowDefinition workflow = new ProcessConfigService().getWorkflow(processVersionId);
		boolean approvalRequired = workflow == null || workflow.isApprovalRequired();
		boolean autoStart = workflow == null || workflow.isAutoStart();
		WorkflowType type = workflow == null || workflow.getWorkflowType() == null
				? WorkflowType.SEQUENTIAL
				: workflow.getWorkflowType();

		ResolvedRoute route = approvalRequired
				? new RuleResolver().resolve(processVersionId, context)
				: new ResolvedRoute();

		final Timestamp now = new Timestamp(System.currentTimeMillis());
		final int requester = context.getRequestedBy() > 0
				? context.getRequestedBy()
				: Env.getAD_User_ID(ctx());

		int transactionId = submitTransaction(context, processVersionId, workflow, route, type, autoStart,
				requester, now, comment);
		ApprovalTransaction chain = loadById(transactionId);
		announce(chain, ApprovalAction.SUBMIT, firstOpenStep(chain), now);
		return chain;
	}

	/** The step whose turn it is, used to address the pending notification. */
	private ApprovalStep firstOpenStep(ApprovalTransaction chain) {
		if (chain == null)
			return null;
		for (ApprovalStep step : chain.getSteps())
			if (step.getStatus().isOpen())
				return step;
		return null;
	}

	private int submitTransaction(DocumentContext context, int processVersionId, WorkflowDefinition workflow,
			ResolvedRoute route, WorkflowType type, boolean autoStart, int requester, Timestamp now,
			String comment) throws ConfigException {

		final String trxName = newTrx("Submit");
		return inTrx(trxName, () -> {
			PO po = newRecord(trxName);
			set(po, "AD_TABLE_ID", context.getTableId() > 0 ? Integer.valueOf(context.getTableId()) : null);
			set(po, "RECORD_ID", Integer.valueOf(context.getRecordId()));
			set(po, "DOCUMENTNO", trim(context.getDocumentNo()));
			set(po, "XX_PROCESS_VERSION_ID", Integer.valueOf(processVersionId));
			set(po, "AMOUNT", context.getAmount());
			set(po, "REQUESTEDBY", requester > 0 ? Integer.valueOf(requester) : null);
			set(po, "REQUESTDATE", now);
			set(po, "LASTACTION", ApprovalAction.SUBMIT.name());
			set(po, "LASTACTIONDATE", now);
			set(po, "STATUS", autoStart ? ApprovalStatus.PENDING.name() : ApprovalStatus.CREATED.name());

			int processId = versionProcessId(processVersionId);
			if (processId > 0)
				set(po, "XX_PROCESS_ID", Integer.valueOf(processId));
			if (workflow != null)
				set(po, "XX_PROCESS_WORKFLOW_ID", Integer.valueOf(workflow.getId()));

			save(po, trxName);

			// Read the new key directly from the saved record. If it cannot be
			// found, stop here with a clear message instead of creating steps
			// that point at nothing.
			int transactionId = resolveId(po, K_TXN, trxName);
			if (transactionId <= 0)
				throw new ConfigException("The approval transaction was saved but its ID could not be read (table "
						+ po.get_TableName() + ", key " + K_TXN + "). Check that the key column is registered "
						+ "with IsKey='Y' in AD_Column.");

			List<RequiredApproval> approvals = route.getApprovals();
			if (approvals.isEmpty()) {
				// nothing to approve: the document is released at once, but the
				// submission itself is still recorded so the trail is complete
				finishApproved(po, requester, now);
				save(po, trxName);
				history().append(transactionId, null, ApprovalAction.SUBMIT, requester, sessionRole(),
						submitReason(route, true, comment), now, trxName);
				return Integer.valueOf(transactionId);
			}

			ApprovalTransaction chain = new ApprovalTransaction();
			chain.setId(transactionId);
			for (RequiredApproval approval : approvals)
				createStep(chain, approval, type, autoStart, now, trxName);

			// Auto start switched off leaves the whole chain waiting, so the
			// configuration is honoured rather than quietly ignored
			if (autoStart)
				openInitialSteps(chain, type, now, trxName);

			applyCurrentLevel(po, chain);
			set(po, "STATUS", autoStart ? ApprovalStatus.PENDING.name() : ApprovalStatus.CREATED.name());
			save(po, trxName);

			history().append(transactionId, null, ApprovalAction.SUBMIT, requester, sessionRole(),
					submitReason(route, autoStart, comment), now, trxName);
			return Integer.valueOf(transactionId);
		}).intValue();
	}

	/**
	 * The text of the submission row: what the rule decided, any warning about the
	 * configuration, and whatever the caller wanted to say.
	 */
	private String submitReason(ResolvedRoute route, boolean autoStart, String comment) {
		String summary = route.hasApprovals() ? route.getSummary() : "no approval level required";
		StringBuilder sb = new StringBuilder(summary);
		for (String warning : route.getWarnings())
			sb.append(" Warning: ").append(warning);
		if (!autoStart)
			sb.append(", waiting to be started");
		if (trim(comment) != null)
			sb.append(" - ").append(trim(comment));
		return sb.toString();
	}

	/**
	 * Creates one step of the chain.
	 * <p>
	 * A sequential workflow leaves every level but the lowest waiting; a parallel
	 * one opens all of them. Auto start switched off leaves the whole chain
	 * waiting, because the configuration says somebody has to release it.
	 */
	private void createStep(ApprovalTransaction chain, RequiredApproval approval, WorkflowType type,
			boolean autoStart, Timestamp now, String trxName) throws ConfigException {

		if (chain == null || chain.getId() <= 0)
			throw new ConfigException("The approval transaction must be saved before its steps are created (id="
					+ (chain == null ? "null" : String.valueOf(chain.getId())) + ").");

		ApprovalLevel level = approval.getLevel();
		PO po = stepAccess.newRecord(trxName);

		// The parent link is mandatory in the database, so it is set directly on
		// the PO with the exact AD_Column name (uppercase, case sensitive).
		if (po.get_ColumnIndex(K_TXN) < 0)
			throw new ConfigException("The model loaded for " + T_STEP + " does not know column " + K_TXN
			        + " (got table " + po.get_TableName() + ", AD_Table_ID=" + po.get_Table_ID()
			        + ", PO class " + po.getClass().getName() + ").");
		po.set_ValueOfColumn(K_TXN, Integer.valueOf(chain.getId()));

		stepAccess.set(po, "XX_PROCESS_LEVEL_ID", level != null && level.getId() > 0
				? Integer.valueOf(level.getId()) : null);
		stepAccess.set(po, "LEVELNO", Integer.valueOf(approval.getLevelNo()));
		stepAccess.set(po, "APPROVER_AD_USER_ID", approval.getApproverUserId() > 0
				? Integer.valueOf(approval.getApproverUserId()) : null);
		stepAccess.set(po, "APPROVER_AD_ROLE_ID", approval.getApproverRoleId() > 0
				? Integer.valueOf(approval.getApproverRoleId()) : null);
		stepAccess.set(po, "DELEGATEDBY_AD_USER_ID", approval.getDelegatedFromUserId() > 0
				? Integer.valueOf(approval.getDelegatedFromUserId()) : null);
		stepAccess.set(po, "COMMENT", approval.getWarning());
		stepAccess.set(po, "SLASTATE", SlaState.NONE.name());
		stepAccess.save(po, trxName);

		int stepId = resolveId(po, K_STEP, trxName);
		if (stepId <= 0)
			throw new ConfigException("The approval step was saved but its ID could not be read (table "
					+ po.get_TableName() + ", key " + K_STEP + ").");

		ApprovalStep step = new ApprovalStep();
		step.setId(stepId);
		step.setTransactionId(chain.getId());
		step.setLevel(level);
		step.setLevelNo(approval.getLevelNo());
		step.setResponsibleType(level == null ? null : level.getResponsibleType());
		step.setApprovalType(level == null ? null : level.getApprovalType());
		step.setApproverUserId(approval.getApproverUserId());
		step.setApproverRoleId(approval.getApproverRoleId());
		step.setDelegatedByUserId(approval.getDelegatedFromUserId());
		step.setStatus(StepStatus.WAITING);
		step.setSlaState(SlaState.NONE);
		step.setComment(approval.getWarning());
		chain.addStep(step);
	}

	/**
	 * Reads the key of a record that was just saved.
	 * <p>
	 * Tries, in order: the PO's own key, the value of the named key column, and a
	 * lookup by the record's UUID inside the same transaction. Returns 0 when none
	 * of them yields a positive id.
	 */
	private int resolveId(PO po, String keyColumn, String trxName) {
		if (po == null)
			return 0;

		int id = po.get_ID();
		if (id > 0)
			return id;

		if (po.get_ColumnIndex(keyColumn) >= 0) {
			Object value = po.get_Value(keyColumn);
			if (value instanceof Number)
				id = ((Number) value).intValue();
			if (id > 0)
				return id;
		}

		String uuid = po.get_UUID();
		if (uuid != null && uuid.length() > 0) {
			String table = po.get_TableName();
			id = DB.getSQLValueEx(trxName,
					"SELECT " + keyColumn + " FROM " + table + " WHERE " + table + "_UU=?", uuid);
		}
		return id > 0 ? id : 0;
	}

	/**
	 * Opens the steps that may start now.
	 * <p>
	 * Called both on submission and whenever a workflow that was configured with
	 * Auto start switched off is released by hand, which is why it is a separate
	 * step from {@link #createStep}.
	 */
	private void openInitialSteps(ApprovalTransaction chain, WorkflowType type, Timestamp now, String trxName)
			throws ConfigException {

		for (ApprovalStep step : chain.getSteps()) {
			boolean first = isLowestLevel(step, chain);
			boolean open = type == WorkflowType.PARALLEL || first;
			if (open)
				openStep(step, now);
		}
		for (ApprovalStep step : chain.getSteps())
			writeStep(step, trxName);
	}

	private boolean isLowestLevel(ApprovalStep candidate, ApprovalTransaction chain) {
		for (ApprovalStep other : chain.getSteps())
			if (other.getStatus() == StepStatus.WAITING && other.getLevelNo() < candidate.getLevelNo())
				return false;
		return true;
	}

	private void openStep(ApprovalStep step, Timestamp now) {
		step.setStatus(StepStatus.PENDING);
		step.setOpenedDate(now);
		ApprovalLevel level = step.getLevel();
		Timestamp due = level == null ? null : level.getDueDate(now);
		step.setDueDate(due);
		step.setSlaState(due == null ? SlaState.NONE : SlaState.NORMAL);
	}

	private void writeStep(ApprovalStep step, String trxName) throws ConfigException {
		PO po = stepAccess.load(step.getId(), trxName);
		if (po == null)
			return;
		stepAccess.set(po, "STATUS", step.getStatus().name());
		stepAccess.set(po, "OPENEDDATE", step.getOpenedDate());
		stepAccess.set(po, "DUEDATE", step.getDueDate());
		stepAccess.set(po, "COMPLETEDDATE", step.getCompletedDate());
		stepAccess.set(po, "SLASTATE", step.getSlaState().name());
		stepAccess.set(po, "COMMENT", trim(step.getComment()));
		stepAccess.save(po, trxName);
	}

	// ─────────────────────────────────────────────────────────────
	//  Acting on a step
	// ─────────────────────────────────────────────────────────────

	/**
	 * Performs one action on one step and moves the chain on.
	 * <p>
	 * Everything happens in one transaction: the step, the transaction header and
	 * the audit row are stored together or not at all, so the trail can never
	 * describe a transition that was rolled back.
	 *
	 * @param transactionId transaction the step belongs to
	 * @param stepId        step to act on
	 * @param action        what to do
	 * @param comment       free text; mandatory for reject and return
	 * @return the transaction as it is after the action
	 */
	public ApprovalTransaction act(int transactionId, int stepId, ApprovalAction action, String comment)
			throws ConfigException {

		if (action == null)
			throw new ConfigException("No action was chosen.");
		if (action == ApprovalAction.SUBMIT || action == ApprovalAction.RESUBMIT
				|| action == ApprovalAction.DELEGATE)
			throw new ConfigException(action.getLabel() + " is not an approval action. Use start(), delegate() or "
					+ "resubmit() instead.");

		if (action.isCommentMandatory() && trim(comment) == null)
			throw new ConfigException("A comment is required to " + action.getLabel().toLowerCase() + ".");

		final String trxName = newTrx("Act");
		// Holder for the step that was acted on, so the notification can be raised
		// once the transaction has committed rather than from inside it
		final ApprovalStep[] acted = new ApprovalStep[1];
		final Timestamp[] actedAt = new Timestamp[1];

		inTrx(trxName, () -> {
			PO po = load(transactionId, trxName);
			if (po == null)
				throw new ConfigException("Approval transaction " + transactionId + " was not found.");

			ApprovalStatus status = ApprovalStatus.of(getString(po, "STATUS"));
			if (status.isFinal())
				throw new ConfigException("Document " + getString(po, "DOCUMENTNO") + " is already "
						+ status.getLabel().toLowerCase() + " and cannot be acted on.");

			ApprovalTransaction chain = readChain(po, transactionId, trxName);
			ApprovalStep step = chain.getStep(stepId);
			if (step == null)
				throw new ConfigException("Approval step " + stepId + " was not found.");
			if (step.getStatus() != StepStatus.PENDING)
				throw new ConfigException("Approval step " + step.getLevelNo() + " is "
						+ step.getStatus().getLabel().toLowerCase() + " and no longer waits for an action.");

			int user = Env.getAD_User_ID(ctx());
			if (!canAct(step, user, Env.getAD_Role_ID(ctx())))
				throw new ConfigException("You are not the approver of level " + step.getLevelNo()
						+ ", so this action is not allowed.");

			Timestamp now = new Timestamp(System.currentTimeMillis());

			if (action == ApprovalAction.APPROVE) {
				completeStep(step, StepStatus.APPROVED, now, comment);
			} else if (action == ApprovalAction.REJECT) {
				completeStep(step, StepStatus.REJECTED, now, comment);
				blockChain(chain);
			} else if (action == ApprovalAction.RETURN) {
				completeStep(step, StepStatus.RETURNED, now, comment);
				blockChain(chain);
			} else {
				// SKIP
				completeStep(step, StepStatus.SKIPPED, now, comment);
			}

			writeStep(step, trxName);
			for (ApprovalStep other : chain.getSteps())
				writeStep(other, trxName);

			settle(chain, po, user, now, trxName);

			set(po, "LASTACTION", action.name());
			set(po, "LASTACTIONDATE", now);
			save(po, trxName);

			history().append(transactionId, step, action, user, sessionRole(), comment, now, trxName);
			acted[0] = step;
			actedAt[0] = now;
			return null;
		});

		ApprovalTransaction chain = loadById(transactionId);
		// Raised outside the transaction that recorded the action: a notification
		// that cannot be sent must never undo an approval that is already correct.
		announce(chain, action, acted[0], actedAt[0]);
		return chain;
	}

	/**
	 * Starts a chain that was created with Auto start switched off.
	 *
	 * @return the transaction after the first level opened
	 */
	public ApprovalTransaction start(int transactionId) throws ConfigException {
		final String trxName = newTrx("Start");
		inTrx(trxName, () -> {
			PO po = load(transactionId, trxName);
			if (po == null)
				throw new ConfigException("Approval transaction " + transactionId + " was not found.");
			if (ApprovalStatus.of(getString(po, "STATUS")) != ApprovalStatus.CREATED)
				throw new ConfigException("This document is not waiting to be started.");

			ApprovalTransaction chain = readChain(po, transactionId, trxName);
			openInitialSteps(chain, workflowTypeOf(chain), new Timestamp(System.currentTimeMillis()), trxName);
			applyCurrentLevel(po, chain);
			set(po, "STATUS", ApprovalStatus.PENDING.name());
			save(po, trxName);

			history().append(transactionId, null, ApprovalAction.SUBMIT, Env.getAD_User_ID(ctx()),
					sessionRole(), "approval chain started", new Timestamp(System.currentTimeMillis()), trxName);
			return null;
		});
		return loadById(transactionId);
	}

	/**
	 * Hands a step to somebody else without changing the level.
	 * <p>
	 * The level keeps its responsibility, so the audit trail still shows who was
	 * really supposed to approve, and the delegation is recorded in its own row.
	 */
	public ApprovalTransaction delegate(int transactionId, int stepId, int delegateUserId, String reason)
			throws ConfigException {

		if (delegateUserId <= 0)
			throw new ConfigException("Choose the person who will approve instead.");

		final String trxName = newTrx("Deleg");
		inTrx(trxName, () -> {
			PO po = load(transactionId, trxName);
			if (po == null)
				throw new ConfigException("Approval transaction " + transactionId + " was not found.");

			ApprovalTransaction chain = readChain(po, transactionId, trxName);
			ApprovalStep step = chain.getStep(stepId);
			if (step == null)
				throw new ConfigException("Approval step " + stepId + " was not found.");
			if (step.getStatus() != StepStatus.PENDING)
				throw new ConfigException("Only a pending step can be handed over.");
			if (delegateUserId == step.getApproverUserId())
				throw new ConfigException("This person is already the approver of this step.");

			PO stepPo = stepAccess.load(stepId, trxName);
			stepAccess.set(stepPo, "APPROVER_AD_USER_ID", Integer.valueOf(delegateUserId));
			stepAccess.set(stepPo, "DELEGATEDBY_AD_USER_ID",
					step.getApproverUserId() > 0 ? Integer.valueOf(step.getApproverUserId()) : null);
			stepAccess.set(stepPo, "COMMENT", trim(reason));
			stepAccess.save(stepPo, trxName);

			step.setApproverUserId(delegateUserId);
			step.setComment(trim(reason));

			history().append(transactionId, step, ApprovalAction.DELEGATE, Env.getAD_User_ID(ctx()),
					sessionRole(), trim(reason) == null ? "handed over" : trim(reason),
					new Timestamp(System.currentTimeMillis()), trxName);
			return null;
		});

		ApprovalTransaction chain = loadById(transactionId);
		announce(chain, ApprovalAction.DELEGATE, chain.getStep(stepId), new Timestamp(System.currentTimeMillis()));
		return chain;
	}

	/**
	 * Assigns a step whose approver could not be resolved when it was created.
	 * <p>
	 * A dynamic responsible type deliberately produces an unassigned step rather
	 * than a wrong approver. This is how an administrator fixes it, so such a
	 * step can never leave the chain stuck without a way out.
	 */
	public ApprovalTransaction assignStep(int transactionId, int stepId, int userId, String reason)
			throws ConfigException {

		if (userId <= 0)
			throw new ConfigException("Choose the approver of this step.");

		final String trxName = newTrx("Assign");
		inTrx(trxName, () -> {
			PO stepPo = stepAccess.load(stepId, trxName);
			if (stepPo == null)
				throw new ConfigException("Approval step " + stepId + " was not found.");

			stepAccess.set(stepPo, "APPROVER_AD_USER_ID", Integer.valueOf(userId));
			stepAccess.set(stepPo, "COMMENT", trim(reason));
			stepAccess.save(stepPo, trxName);

			history().append(transactionId, null, ApprovalAction.DELEGATE, Env.getAD_User_ID(ctx()),
					sessionRole(), "approver assigned" + (trim(reason) == null ? "" : ": " + trim(reason)),
					new Timestamp(System.currentTimeMillis()), trxName);
			return null;
		});
		return loadById(transactionId);
	}

	/**
	 * Puts a rejected or returned document back in front of the requester.
	 * <p>
	 * The chain is rebuilt from the rule engine rather than from the old steps, so
	 * a document whose amount changed in the meantime is routed by the rule that
	 * matches its new amount instead of following a stale path.
	 */
	public ApprovalTransaction resubmit(int transactionId, DocumentContext context) throws ConfigException {
		final String trxName = newTrx("Resub");
		inTrx(trxName, () -> {
			PO po = load(transactionId, trxName);
			if (po == null)
				throw new ConfigException("Approval transaction " + transactionId + " was not found.");

			ApprovalStatus status = ApprovalStatus.of(getString(po, "STATUS"));
			if (status != ApprovalStatus.REJECTED && status != ApprovalStatus.RETURNED
					&& status != ApprovalStatus.CANCELLED)
				throw new ConfigException("Only a rejected, returned or cancelled document can be resubmitted.");

			int versionId = getInt(po, "XX_PROCESS_VERSION_ID", 0);
			ResolvedRoute route = new RuleResolver().resolve(versionId, context);

			PO oldSteps = MTable.get(ctx(), T_STEP);
			if (oldSteps != null) {
				PreparedStatement pstmt = null;
				try {
					pstmt = DB.prepareStatement(
							"UPDATE " + T_STEP + " SET IsActive='N' WHERE XX_PROCESS_TRANSACTION_ID=?", trxName);
					pstmt.setInt(1, transactionId);
					pstmt.executeUpdate();
				} catch (java.sql.SQLException e) {
					throw new ConfigException("Unable to retire the previous steps.", e);
				} finally {
					DB.close(pstmt);
				}
			}

			ApprovalTransaction chain = new ApprovalTransaction();
			chain.setId(transactionId);
			Timestamp now = new Timestamp(System.currentTimeMillis());
			WorkflowType type = workflowTypeOfVersion(versionId);

			for (RequiredApproval approval : route.getApprovals())
				createStep(chain, approval, type, true, now, trxName);

			if (route.getApprovals().isEmpty()) {
				finishApproved(po, Env.getAD_User_ID(ctx()), now);
			} else {
				openInitialSteps(chain, type, now, trxName);
				applyCurrentLevel(po, chain);
				set(po, "STATUS", ApprovalStatus.PENDING.name());
			}
			set(po, "LASTACTION", ApprovalAction.RESUBMIT.name());
			set(po, "LASTACTIONDATE", now);
			if (context.getAmount() != null)
				set(po, "AMOUNT", context.getAmount());
			save(po, trxName);

			history().append(transactionId, null, ApprovalAction.RESUBMIT, Env.getAD_User_ID(ctx()),
					sessionRole(), route.getSummary(), now, trxName);
			return null;
		});
		return loadById(transactionId);
	}

	/**
	 * Withdraws a running approval.
	 * <p>
	 * Only the requester or an administrator may do this, and the reason is kept in
	 * the trail: an approval that quietly disappears is exactly what an audit
	 * cannot tolerate.
	 */
	public ApprovalTransaction cancel(int transactionId, String reason) throws ConfigException {
		final String trxName = newTrx("Cancel");
		inTrx(trxName, () -> {
			PO po = load(transactionId, trxName);
			if (po == null)
				throw new ConfigException("Approval transaction " + transactionId + " was not found.");

			ApprovalStatus status = ApprovalStatus.of(getString(po, "STATUS"));
			if (status.isFinal())
				throw new ConfigException("This approval is already " + status.getLabel().toLowerCase() + ".");

			int user = Env.getAD_User_ID(ctx());
			int requester = getInt(po, "REQUESTEDBY", 0);
			if (user != requester && !isAdministrator(user))
				throw new ConfigException("Only the requester or an administrator can withdraw this approval.");

			ApprovalTransaction chain = readChain(po, transactionId, trxName);
			Timestamp now = new Timestamp(System.currentTimeMillis());
			for (ApprovalStep step : chain.getSteps()) {
				if (!step.isFinished())
					completeStep(step, StepStatus.SKIPPED, now, "withdrawn");
				writeStep(step, trxName);
			}

			set(po, "STATUS", ApprovalStatus.CANCELLED.name());
			set(po, "CURRENTLEVEL", Integer.valueOf(0));
			set(po, "LASTACTION", ApprovalAction.SKIP.name());
			set(po, "LASTACTIONDATE", now);
			save(po, trxName);

			history().append(transactionId, null, ApprovalAction.SKIP, user, sessionRole(),
					trim(reason) == null ? "approval withdrawn" : "approval withdrawn: " + trim(reason), now,
					trxName);
			return null;
		});
		return loadById(transactionId);
	}

	// ─────────────────────────────────────────────────────────────
	//  Chain transitions
	// ─────────────────────────────────────────────────────────────

	private void completeStep(ApprovalStep step, StepStatus outcome, Timestamp now, String comment) {
		step.setStatus(outcome);
		step.setCompletedDate(now);
		step.setComment(trim(comment));
		if (step.getDueDate() != null)
			step.recomputeSlaState(now);
	}

	/**
	 * Closes every step that has not reached a decision yet.
	 * <p>
	 * A rejection or a return stops the chain here, whatever the level type was.
	 * Both {@link StepStatus#PENDING} and {@link StepStatus#WAITING} have to be
	 * closed: leaving a waiting level alone would let the chain promote it after
	 * the document was already rejected, which is precisely what the design
	 * forbids.
	 */
	private void blockChain(ApprovalTransaction chain) {
		for (ApprovalStep step : chain.getSteps())
			if (!step.isFinished())
				step.setStatus(StepStatus.SKIPPED);
	}

	/**
	 * Decides what has to happen after a step reached an outcome.
	 * <p>
	 * Pure on purpose: this is the second most important rule of the framework -
	 * when does the next level open and when is the document released - and it has
	 * to be provable without a database. {@link #settle} only carries the answer
	 * out.
	 *
	 * @param steps the chain as it stands, including the step just decided
	 * @return the decision, never null
	 */
	public static Advance decide(List<ApprovalStep> steps) {
		Advance advance = new Advance();
		if (steps == null || steps.isEmpty()) {
			advance.finished = true;
			advance.outcome = ApprovalStatus.APPROVED;
			return advance;
		}

		boolean anyPending = false;
		boolean anyWaiting = false;
		ApprovalStep lowestWaiting = null;
		for (ApprovalStep step : steps) {
			if (step.getStatus() == StepStatus.PENDING)
				anyPending = true;
			else if (step.getStatus() == StepStatus.WAITING) {
				anyWaiting = true;
				if (lowestWaiting == null || step.getLevelNo() < lowestWaiting.getLevelNo())
					lowestWaiting = step;
			}
		}

		if (anyPending) {
			// something is still being worked on, the chain stays where it is
			return advance;
		}

		if (anyWaiting && lowestWaiting != null) {
			advance.toOpen = lowestWaiting;
			return advance;
		}

		advance.finished = true;
		boolean returned = false;
		boolean rejected = false;
		for (ApprovalStep step : steps) {
			if (step.getStatus() == StepStatus.RETURNED)
				returned = true;
			else if (step.getStatus() == StepStatus.REJECTED)
				rejected = true;
		}
		if (returned)
			advance.outcome = ApprovalStatus.RETURNED;
		else if (rejected)
			advance.outcome = ApprovalStatus.REJECTED;
		else
			advance.outcome = ApprovalStatus.APPROVED;
		return advance;
	}

	/** Carries everything the chain decides, so the decision can be asserted. */
	public static final class Advance {

		/** Step that has to be opened now, or null. */
		ApprovalStep toOpen;

		/** True when no step is open any more and the transaction is settled. */
		boolean finished;

		/** Final status, only meaningful once {@link #finished} is true. */
		ApprovalStatus outcome = ApprovalStatus.PENDING;

		/**
		 * Step the chain decided to open, or null when nothing has to open.
		 * Public because the Test Workflow button shows the same decision the
		 * engine will take later.
		 */
		public ApprovalStep getToOpen() {
			return toOpen;
		}

		public boolean isFinished() {
			return finished;
		}

		public ApprovalStatus getOutcome() {
			return outcome;
		}
	}

	/**
	 * Carries the decision of {@link #decide} out to the database.
	 * <p>
	 * Promotes the next level of a sequential chain, or settles the transaction
	 * once nothing is open any more. Advisory levels never hold the document back,
	 * but a rejection or a return always decides it.
	 */
	private void settle(ApprovalTransaction chain, PO po, int user, Timestamp now, String trxName)
			throws ConfigException {

		Advance advance = decide(chain.getSteps());

		if (!advance.finished) {
			if (advance.toOpen != null) {
				openStep(advance.toOpen, now);
				writeStep(advance.toOpen, trxName);
			}
			applyCurrentLevel(po, chain);
			set(po, "STATUS", ApprovalStatus.PENDING.name());
			save(po, trxName);
			return;
		}

		if (advance.outcome == ApprovalStatus.APPROVED && chain.isFullyApproved())
			finishApproved(po, user, now);
		else {
			set(po, "STATUS", advance.outcome.name());
			set(po, "CURRENTLEVEL", Integer.valueOf(0));
			set(po, "REJECTEDBY", Integer.valueOf(user));
			set(po, "REJECTEDDATE", now);
		}
		save(po, trxName);
	}

	private void finishApproved(PO po, int user, Timestamp now) {
		set(po, "STATUS", ApprovalStatus.APPROVED.name());
		set(po, "CURRENTLEVEL", Integer.valueOf(0));
		set(po, "APPROVEDBY", user > 0 ? Integer.valueOf(user) : null);
		set(po, "APPROVEDDATE", now);
	}

	/** The lowest level that still waits for a decision, for the header. */
	private void applyCurrentLevel(PO po, ApprovalTransaction chain) {
		int lowest = 0;
		for (ApprovalStep step : chain.getSteps())
			if (step.getStatus().isOpen() && (lowest == 0 || step.getLevelNo() < lowest))
				lowest = step.getLevelNo();
		set(po, "CURRENTLEVEL", Integer.valueOf(lowest));
	}

	// ─────────────────────────────────────────────────────────────
	//  Reading
	// ─────────────────────────────────────────────────────────────

	/** A transaction with its steps and audit rows, or null when it does not exist. */
	public ApprovalTransaction loadById(int transactionId) {
		PO po = load(transactionId, null);
		if (po == null)
			return null;
		ApprovalTransaction chain = readHeader(po);
		readSteps(chain);
		try {
			for (ApprovalHistoryEntry entry : history().list(transactionId))
				chain.addHistory(entry);
		} catch (ConfigException e) {
			// the chain is still usable without its trail, the screen reports it
			log.log(java.util.logging.Level.WARNING,
					"Approval history of transaction " + transactionId + " could not be read", e);
		}
		return chain;
	}

	/**
	 * Id of the transaction that is currently in progress for a document, or 0.
	 * <p>
	 * Only {@link ApprovalStatus#CREATED} and {@link ApprovalStatus#PENDING}
	 * count. A rejected, returned or cancelled document is not in progress - it is
	 * waiting for the requester, and blocking a fresh submission there would
	 * leave it with no way forward except {@link #resubmit}.
	 */
	public int findOpen(int tableId, int recordId) {
		String sql = "SELECT XX_PROCESS_TRANSACTION_ID FROM " + T_TXN
				+ " WHERE AD_TABLE_ID=? AND RECORD_ID=? AND IsActive='Y' AND AD_Client_ID=?"
				+ " AND Status IN ('" + ApprovalStatus.CREATED.name() + "','" + ApprovalStatus.PENDING.name() + "')"
				+ " ORDER BY Created DESC LIMIT 1";
		return DB.getSQLValue(null, sql, Integer.valueOf(tableId), Integer.valueOf(recordId),
				Integer.valueOf(clientId()));
	}

	/** All transactions where this user currently has something to do. */
	public List<ApprovalTransaction> listPendingFor(int userId, int roleId) {
		List<ApprovalTransaction> result = new ArrayList<ApprovalTransaction>();

		// RequestDate is selected because PostgreSQL refuses an ORDER BY on an
		// expression that a DISTINCT does not list. The rows are de-duplicated here
		// instead: a user matching both the user and the role condition of the same
		// step would otherwise be listed twice.
		String sql = "SELECT t.XX_PROCESS_TRANSACTION_ID, t.RequestDate FROM " + T_TXN + " t"
				+ " INNER JOIN " + T_STEP + " s ON s.XX_PROCESS_TRANSACTION_ID=t.XX_PROCESS_TRANSACTION_ID"
				+ " WHERE t.AD_Client_ID=? AND t.IsActive='Y' AND t.Status IN ('"
				+ ApprovalStatus.PENDING.name() + "','" + ApprovalStatus.CREATED.name() + "')"
				+ " AND s.Status='" + StepStatus.PENDING.name() + "'"
				+ " AND ((s.Approver_AD_User_ID=? AND s.Approver_AD_User_ID>0)"
				+ " OR (s.Approver_AD_Role_ID=? AND s.Approver_AD_Role_ID>0))"
				+ " ORDER BY t.RequestDate, t.XX_PROCESS_TRANSACTION_ID";

		Set<Integer> ids = new LinkedHashSet<Integer>();
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, clientId());
			pstmt.setInt(2, userId);
			pstmt.setInt(3, roleId);
			rs = pstmt.executeQuery();
			while (rs.next())
				ids.add(Integer.valueOf(rs.getInt(1)));
		} catch (java.sql.SQLException e) {
			ids.clear();
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}

		for (Integer id : ids) {
			ApprovalTransaction chain = loadById(id.intValue());
			if (chain != null)
				result.add(chain);
		}
		return result;
	}

	private ApprovalTransaction readChain(PO po, int transactionId, String trxName) {
		ApprovalTransaction chain = readHeader(po);
		readSteps(chain);
		return chain;
	}

	private ApprovalTransaction readHeader(PO po) {
		ApprovalTransaction chain = new ApprovalTransaction();
		chain.setId(getInt(po, "XX_PROCESS_TRANSACTION_ID", 0));
		chain.setTableId(getInt(po, "AD_TABLE_ID", 0));
		chain.setRecordId(getInt(po, "RECORD_ID", 0));
		chain.setDocumentNo(getString(po, "DOCUMENTNO"));
		chain.setProcessId(getInt(po, "XX_PROCESS_ID", 0));
		chain.setProcessVersionId(getInt(po, "XX_PROCESS_VERSION_ID", 0));
		chain.setWorkflowId(getInt(po, "XX_PROCESS_WORKFLOW_ID", 0));
		chain.setStatus(ApprovalStatus.of(getString(po, "STATUS")));
		chain.setCurrentLevel(getInt(po, "CURRENTLEVEL", 0));
		chain.setRequestedBy(getInt(po, "REQUESTEDBY", 0));
		chain.setRequestDate(getTimestamp(po, "REQUESTDATE"));
		chain.setAmount(getDecimal(po, "AMOUNT"));
		chain.setApprovedBy(getInt(po, "APPROVEDBY", 0));
		chain.setApprovedDate(getTimestamp(po, "APPROVEDDATE"));
		chain.setRejectedBy(getInt(po, "REJECTEDBY", 0));
		chain.setRejectedDate(getTimestamp(po, "REJECTEDDATE"));
		chain.setLastAction(ApprovalAction.of(getString(po, "LASTACTION")));
		chain.setLastActionDate(getTimestamp(po, "LASTACTIONDATE"));
		return chain;
	}

	private void readSteps(ApprovalTransaction chain) {
		String sql = "SELECT s.XX_APPROVAL_STEP_ID, s.LEVELNO, s.STATUS, s.Approver_AD_User_ID,"
				+ " s.Approver_AD_Role_ID, s.DelegatedBy_AD_User_ID, s.OpenedDate, s.DueDate,"
				+ " s.CompletedDate, s.EscalatedDate, s.EscalatedBy_AD_User_ID, s.SLAState, s.COMMENT,"
				+ " s.XX_PROCESS_LEVEL_ID, l.LevelName, l.SLAHours, l.WarningBeforeHours,"
				+ " l.ResponsibleType, l.ApprovalType"
				+ " FROM " + T_STEP + " s"
				+ " LEFT JOIN XX_PROCESS_LEVEL l ON l.XX_PROCESS_LEVEL_ID=s.XX_PROCESS_LEVEL_ID"
				+ " WHERE s.XX_PROCESS_TRANSACTION_ID=? AND s.IsActive='Y' ORDER BY s.LEVELNO, s.Created";

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try {
			pstmt = DB.prepareStatement(sql, null);
			pstmt.setInt(1, chain.getId());
			rs = pstmt.executeQuery();
			while (rs.next()) {
				ApprovalStep step = new ApprovalStep();
				step.setId(rs.getInt(1));
				step.setTransactionId(chain.getId());
				step.setLevelNo(rs.getInt(2));
				step.setStatus(StepStatus.of(rs.getString(3)));
				step.setApproverUserId(rs.getInt(4));
				step.setApproverRoleId(rs.getInt(5));
				step.setDelegatedByUserId(rs.getInt(6));
				step.setOpenedDate(rs.getTimestamp(7));
				step.setDueDate(rs.getTimestamp(8));
				step.setCompletedDate(rs.getTimestamp(9));
				step.setEscalatedDate(rs.getTimestamp(10));
				step.setEscalatedByUserId(rs.getInt(11));
				step.setSlaState(SlaState.of(rs.getString(12)));
				step.setComment(rs.getString(13));

				// rebuild just enough of the level to show it and to recompute SLA
				ApprovalLevel level = new ApprovalLevel();
				level.setId(rs.getInt(14));
				level.setLevelNo(step.getLevelNo());
				level.setLevelName(rs.getString(15));
				int sla = rs.getInt(16);
				level.setSlaHours(rs.wasNull() ? null : Integer.valueOf(sla));
				int warn = rs.getInt(17);
				level.setWarningBeforeHours(rs.wasNull() ? null : Integer.valueOf(warn));
				level.setResponsibleType(org.mycompany.requisition.approval.config.ResponsibleType.of(rs.getString(18)));
				level.setApprovalType(org.mycompany.requisition.approval.config.ApprovalType.of(rs.getString(19)));
				step.setLevel(level);
				step.setResponsibleType(level.getResponsibleType());
				step.setApprovalType(level.getApprovalType());
				chain.addStep(step);
			}
		} catch (java.sql.SQLException e) {
			chain.getSteps().clear();
		} finally {
			DB.close(rs);
			DB.close(pstmt);
		}
	}

	// ─────────────────────────────────────────────────────────────
	//  Authorisation
	// ─────────────────────────────────────────────────────────────

	/**
	 * Whether this user may act on this step.
	 * <p>
	 * Checked inside the engine and not only in the form, because the same engine
	 * is reachable from a business document and from a scheduled job. Holding the
	 * administrator role overrides the assignment, which is what lets a stuck
	 * chain be dealt with.
	 */
	public boolean canAct(ApprovalStep step, int userId, int roleId) {
		if (step == null || !step.getStatus().isOpen() || userId <= 0)
			return false;
		if (step.getApproverUserId() > 0 && step.getApproverUserId() == userId)
			return true;
		if (step.getApproverRoleId() > 0 && step.getApproverRoleId() == roleId)
			return true;
		return isAdministrator(userId);
	}

	/** True when the user holds the system administrator role, resolved by name. */
	public boolean isAdministrator(int userId) {
		if (userId <= 0)
			return false;
		return DB.getSQLValue(null,
				"SELECT COUNT(*) FROM AD_User_Roles ur INNER JOIN AD_Role r ON r.AD_Role_ID=ur.AD_Role_ID"
						+ " WHERE ur.AD_User_ID=? AND ur.IsActive='Y' AND UPPER(r.Name)=UPPER(?)",
				Integer.valueOf(userId), ADMIN_ROLE_NAME) > 0;
	}

	// ─────────────────────────────────────────────────────────────
	//  helpers
	// ─────────────────────────────────────────────────────────────

	private HistoryWriter history() throws ConfigException {
		return new HistoryWriter();
	}

	/**
	 * Raises the notifications that belong to an action, and never raises.
	 * <p>
	 * Deliberately outside the transaction of the action and deliberately silent on
	 * failure. Who hears about an approval is configuration, so a missing or broken
	 * notification setting must not turn a correct approval into a failed one - it
	 * is reported in the log and the audit trail keeps the truth.
	 */
	private void announce(ApprovalTransaction chain, ApprovalAction action, ApprovalStep step, Timestamp now) {
		if (chain == null || action == null)
			return;
		Event event = eventOf(chain, action);
		if (event == null)
			return;
		try {
			new NotificationService().notify(chain, event, step, now);
		} catch (ConfigException e) {
			log.log(java.util.logging.Level.WARNING,
					"Notification for " + action + " of document " + chain.getDisplayName()
							+ " could not be raised", e);
		}
	}

	/**
	 * The event an action produces.
	 * <p>
	 * An approval only raises {@code APPROVAL_PENDING} while another level is
	 * still waiting, and {@code APPROVAL_COMPLETED} once the document is released,
	 * so a chain of three levels tells the last approver that it is done instead of
	 * claiming another approval is pending.
	 */
	private Event eventOf(ApprovalTransaction chain, ApprovalAction action) {
		if (action == ApprovalAction.DELEGATE)
			return Event.DELEGATION_RECORDED;

		boolean open = false;
		for (ApprovalStep step : chain.getSteps())
			if (step.getStatus().isOpen())
				open = true;

		if (action == ApprovalAction.APPROVE)
			return open ? Event.APPROVAL_PENDING : Event.APPROVAL_COMPLETED;
		if (action == ApprovalAction.REJECT)
			return Event.APPROVAL_REJECTED;
		if (action == ApprovalAction.RETURN)
			return Event.APPROVAL_RETURNED;
		if (action == ApprovalAction.SUBMIT || action == ApprovalAction.RESUBMIT)
			return Event.APPROVAL_PENDING;
		return null;
	}

	private int sessionRole() {
		return Env.getAD_Role_ID(ctx());
	}

	private int versionProcessId(int processVersionId) {
		return DB.getSQLValue(null,
				"SELECT XX_PROCESS_ID FROM XX_PROCESS_VERSION WHERE XX_PROCESS_VERSION_ID=?",
				Integer.valueOf(processVersionId));
	}

	private WorkflowType workflowTypeOf(ApprovalTransaction chain) {
		return workflowTypeOfVersion(chain.getProcessVersionId());
	}

	private WorkflowType workflowTypeOfVersion(int processVersionId) {
		if (processVersionId <= 0)
			return WorkflowType.SEQUENTIAL;
		try {
			WorkflowDefinition w = new ProcessConfigService().getWorkflow(processVersionId);
			return w == null || w.getWorkflowType() == null ? WorkflowType.SEQUENTIAL : w.getWorkflowType();
		} catch (ConfigException e) {
			// sequential is the safe default: it never releases a document earlier
			// than the alternative would
			log.log(java.util.logging.Level.WARNING,
					"Workflow type of version " + processVersionId + " could not be read, using sequential", e);
			return WorkflowType.SEQUENTIAL;
		}
	}
}