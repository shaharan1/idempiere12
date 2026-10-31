# Business Process & Approval Management System - Design

Target: iDempiere 12, Java 17, ZK 10, PostgreSQL, plugin `org.mycompany.requisition`.

## Progress

| Step | State |
|---|---|
| 1 package skeleton + design | done |
| 2 tables + dictionary registration | done - 11 tables (AD_Table 1000108-1000118), 217 AD_Column, 60 new AD_Element |
| 3 model and config value classes | done - 18 classes, 75 assertions green |
| 4 config services (CRUD via PO) | done - BaseConfigService, ProcessConfigService, LevelConfigService, RuleConfigService |
| 5 RuleResolver | done - pure evaluate() + resolve(), 52 assertions green |
| 6 WorkflowEngine + history writer | done - WorkflowEngine, HistoryWriter, 38 assertions green |
| 7 ApprovalEngineClient | done - process resolution by table + document type |
| 8 SlaMonitor, Delegation, Notification | done - all three services, hooked into the engine |
| 9 UI shell + sections 01-03 | done - UiLayouts, WProcessApprovalWindow |
| 10 section 04 Approval Levels | done - ConfigListPanel base, LevelPanel |
| 11 section 05 Approval Rules | done - RulePanel, Lookups |
| 12-13 section 06 diagram, 07-09 SLA/delegation/notification | done - DiagramPanel, SlaPanel, DelegationPanel, NotificationPanel |
| 14 WorkflowTestService + Test button | done - dry run through the same resolver the engine uses |
| 15 sections 10-12 transaction, history, audit | done - TransactionPanel, HistoryPanel, AuditPanel |
| 16 ApprovalDashboardWindow | done - counters, my pending, late, unassigned, remembered filter |
| 17 AD_Form + AD_Menu + AD_Form_Access registration | done - form 1000101 + menu 1000017, form 1000102 + menu 1000018 |
| 18a level columns for limits, department and final approver | done - AD_Column 1000872-1000875 |
| 18b the custom WApprovalAssignmentWindow form | superseded, see 18c |
| 18c **native** assignment window (AD_Window/AD_Tab/AD_Field) | done - window 1000006, 6 tabs, 28 fields, menu 1000020 |
| 17 native AD_Workflow hand-over for a pilot process | pending |
| 18 example configuration (Purchasing / Purchase Requisition) | pending |

## Design decisions taken

Were open questions in the original brief.

* The engine runs the approval chain itself. A native `AD_Workflow` is linked
  through `XX_PROCESS.AD_Workflow_ID` for visibility and cross reference, but a
  mid-chain hand over would lose SLA, delegation and audit.
* Approval levels are immutable once a transaction exists. Reconfiguration is
  done by creating a new process version, which is why transactions pin their
  `Process_Version_ID` at submission time.
* Delegation is stored per user with an optional level scope, so both a holiday
  delegation for everything and a one level substitution are possible.
* Notification channels are data, not code: iDempiere notice and mail are both
  selectable per event.
* A rejection or return stops the chain even when the level type is advisory
  (review, noted). Blocking only governs whether an approved level is required.

---

## 1. Architecture

Four layers, strictly separated. A business document never talks to the UI, and
the UI never contains business rules.

```
+-----------------------------------------------------------------+
| 1. UI LAYER            org.mycompany.requisition.approval.ui    |
|    ADForm windows, Groupbox sections, tabbed child grids         |
|    ZK only. No SQL, no rules.                                    |
+----------------------------+------------------------------------+
                             | calls services
+----------------------------v------------------------------------+
| 2. ENGINE LAYER         ...approval.service                     |
|    WorkflowEngine  RuleResolver  SlaMonitor                     |
|    No ZK imports: runs from a window, a batch job or a service   |
+----------------------------+------------------------------------+
                             | reads / writes via PO and M*
+----------------------------v------------------------------------+
| 3. PERSISTENCE          ...approval.service (config services)  |
|    M* model classes, tenant rules, audit columns                 |
+----------------------------+------------------------------------+
                             | plain objects
+----------------------------v------------------------------------+
| 4. MODEL                ...approval.model  and  ...config       |
|    ApprovalTransaction, ApprovalStep, ApprovalLevel, rules       |
|    No behaviour that touches the database                        |
+-----------------------------------------------------------------+

Business document (Purchase Requisition, Sales Order, ...)
   -> ApprovalEngineClient.start(document)  is the only entry point a
      business module needs to call.
```

Reusability rule: a new business process is onboarded with configuration only,
the engine is never modified. That is what the runtime model pins
`Process_Version_ID` on every transaction for.

---

## 2. Entity relationship

```
AD_Client, AD_Org
      |
      v
XX_PROCESS_CATEGORY          1
      |                      |
      | n                    | n
XX_PROCESS -------------------+-------------------+----------------+
      | 1                                          |                |
      | n                                          | n              | n
XX_PROCESS_VERSION                        XX_PROCESS_DELEGATION  (AD_User -> Delegate)
      | 1
      | n
      +-- XX_PROCESS_WORKFLOW
      +-- XX_PROCESS_LEVEL              (ordered by LevelNo)
      +-- XX_PROCESS_APPROVAL_RULE       (amount / org / dept / ...)
      |
      | 1
      | n
XX_PROCESS_TRANSACTION ----------> AD_Table + record id (any table)
      | 1
      | n
XX_APPROVAL_STEP ----------------> XX_PROCESS_LEVEL
      | n
      v
XX_APPROVAL_HISTORY                  (append only audit)

XX_NOTIFICATION_SETTING ---------------> XX_PROCESS_VERSION
```

Every table carries `AD_Client_ID` / `AD_Org_ID` and the standard audit columns.
Reference tables are referenced, never copied.

---

## 3. Table structure

| Table | AD_Table_ID | Purpose |
|---|---|---|
| `XX_PROCESS_CATEGORY` | 1000108 | group of processes |
| `XX_PROCESS` | 1000109 | one business process, links table / window / workflow |
| `XX_PROCESS_VERSION` | 1000110 | effective dated version |
| `XX_PROCESS_WORKFLOW` | 1000111 | workflow header |
| `XX_PROCESS_LEVEL` | 1000112 | one approval step |
| `XX_PROCESS_APPROVAL_RULE` | 1000113 | which levels a document needs |
| `XX_PROCESS_DELEGATION` | 1000114 | approver substitution |
| `XX_PROCESS_TRANSACTION` | 1000115 | one document under approval |
| `XX_APPROVAL_STEP` | 1000116 | level instance |
| `XX_APPROVAL_HISTORY` | 1000117 | append only audit |
| `XX_NOTIFICATION_SETTING` | 1000118 | who is notified when |

Registration facts:

* Key columns take their id from `AD_Sequence` with `IsTableID = 'Y'`, matching
  how the existing `XX_GSM` works.
* Existing iDempiere `AD_Element` ids are reused wherever the meaning is
  identical (AD_User 138, AD_Role 123, AD_Table 126, Amount 1367, ...). Only 60
  genuinely new labels were created.
* Column references were derived from the physical DDL, so the dictionary and
  the database cannot drift apart.
* Check constraints enforce Y/N flags, `EffectiveTo >= EffectiveFrom`,
  `MaximumAmount >= MinimumAmount` and `delegate <> user`.

No identifier is hard coded in Java. Column ids are resolved from `AD_Column` by
name, following the rule this plugin already uses in
`WQuickProductForm.adColumnId(...)`.

---

## 4. iDempiere integration

| Need | iDempiere concept reused |
|---|---|
| Approver identity | `AD_User`, `AD_Role`, `AD_User_Roles` |
| Organisation scoping | `AD_Org`, `MRole.addAccessSQL` |
| Target document | `AD_Table` + record id, any table including custom |
| Screen to show | `AD_Window` |
| Workflow linkage | `AD_Workflow`, `AD_WF_Activity`, `AD_WF_Node`, `AD_WF_NodeNext`, `AD_WF_Responsible` |
| Authorisation | `AD_Form.AccessLevel`, `AD_Form_Access`, role checks in the engine |
| Labels | `AD_Element` + `AD_*_Trl` |
| Sequences | `AD_Sequence` |
| Notifications | iDempiere notice and mail pipeline |
| Audit | standard `Created`, `CreatedBy`, `Updated`, `UpdatedBy` |

The custom engine is the authority for the chain because the native `AD_WF_*`
tables are bound to a workflow document and cannot express amount-banded rules,
SLA escalation or delegation. Where a process already has a native
`AD_Workflow`, `XX_PROCESS.AD_Workflow_ID` links to it.

---

## 5. Approval lifecycle

```
CREATED
   | submit
   v
PENDING --------------------------------------------------+
   |                                                      |
   | Level 1 pending --approve--> approved                |
   |       |--reject--> REJECTED --resubmit--> PENDING     |
   |       |--return--> RETURNED --resubmit--> PENDING     |
   |                                                      |
   | Level 2 pending --approve--> approved                |
   |                                                      |
   | Level N pending --approve--> COMPLETED               |
   +----------------------skip (rule says not needed)------+
```

Rules encoded in `WorkflowEngine`:

1. Only levels selected by the resolved rule become steps.
2. A delegation active for the approver and the current date replaces the
   approver without changing the level.
3. The SLA countdown starts when the step becomes pending. Warning, overdue and
   escalation are derived, never stored twice.
4. Reject ends the transaction and blocks further levels.
5. Return sends the document back to the requester and stops the chain.
6. Every state change writes exactly one `XX_APPROVAL_HISTORY` row.

---

## 6. SLA and escalation

| State | Condition |
|---|---|
| `NONE` | no SLA configured |
| `NORMAL` | time left is larger than the warning window |
| `WARNING` | time left is within the warning window |
| `OVERDUE` | due date passed, no escalation sent |
| `ESCALATED` | escalation after time passed, fallback notified |

`SLAState` is recomputed from the clock on every read by `SlaMonitor`, so the
state can never drift away from the due date.

---

## 7. Approval rules

Evaluation order:

1. Collect active rules of the current process version.
2. Sort ascending by `Priority`.
3. First rule whose conditions all match wins.
4. Its `RequiredLevels` produce the steps.
5. If no rule matches, fall back to the levels flagged `IsRequired = 'Y'`.

Unset condition values (0 or null) mean "any". Supported conditions are amount,
organisation, user, role, warehouse, product category, business partner, project
and document type. A new condition needs a new column plus a mapping, not a
change to the engine.

---

## 8. Window and tab structure

`AD_Form` "Business Process Approval Management" -> `WProcessApprovalWindow`.

One scroll area, sections as `Groupbox + Caption`, each section content built as
`Grid > Columns + Rows` because a ZK `Rows` component only accepts a `Grid` as
parent.

```
QUICK -> Business Process Approval Management
|  scrollArea  (one vertical scroll, full width)
|
+- 01. Process Information        category, process, code, name, type,
|                                  related table/window, status, active
+- 02. Process Version & Period   version, name, effective from/to, is current
+- 03. Workflow Configuration     name/code, sequential or parallel,
|                                  approval required, auto start, active
+- 04. Approval Levels            Tab: levels, with add / edit / delete /
|                                  move up / move down
+- 05. Approval Rules             Tab: rules, amount band plus required levels
+- 06. Workflow Visualization     View Workflow -> diagram with current state
+- 07. SLA & Escalation           sla, hours, warning before, escalation
+- 08. Approval Delegation        approver, delegate, valid from/to, reason
+- 09. Notification Settings      per event: approver, requester, manager, channel
+- 10. Approval Transaction       document, current level, approve / reject /
|                                  return / delegate
+- 11. Approval History           append only list
+- 12. Audit Information          created/updated/approved/rejected by and date
```

Second `AD_Form` "Approval Dashboard" -> `ApprovalDashboardWindow`: counters, my
pending approvals, overdue list, saved filters.

Field visibility is driven by `ResponsibleType`: the responsible field is shown
only when it applies. Dropdowns come from iDempiere references resolved through
`MTable` / `POInfo` / `MLookup`.

---

## 9. Security design

| Role | May do |
|---|---|
| Administrator | full configuration, activate workflow, view audit, run Test Workflow |
| Manager | view assigned approvals, approve, reject, delegate when allowed |
| Requester | submit own document, view status, resubmit rejected |
| Normal user | view only |

Enforcement in three layers:

1. `AD_Form.AccessLevel` plus `AD_Form_Access` decides whether the window opens.
2. Role checks inside each configuration service before any write.
3. Engine level: an action is accepted only if the acting user is the current
   step approver or holds an administrator role. This holds regardless of which
   UI calls the engine.

`XX_APPROVAL_HISTORY` rows are never updated or deleted by application code.

---

## 10. Step 3 class inventory

config package, no database and no ZK:

* `ResponsibleType`, `ApprovalType`, `WorkflowType`, `VersionStatus`
* `ProcessCategory`, `ProcessDefinition`, `ProcessVersion`, `WorkflowDefinition`
* `ApprovalLevel` (with SLA and escalation), `ApprovalRule`, `Delegation`,
  `NotificationSetting` (with Event / SendTo / Channel)

model package, runtime transaction:

* `ApprovalStatus`, `StepStatus`, `SlaState`, `ApprovalAction`
* `ApprovalTransaction`, `ApprovalStep`, `ApprovalHistoryEntry`,
  `WorkflowTestResult`

Behaviour that was verified with 75 assertions before any service was written:
version usability, effective periods, safe defaulting of unknown stored codes,
the three amount bands from the design example, SLA due dates and derived state,
chain completion including skipped and advisory levels, and delegation scoping.

---

## 10b. Step 4 service layer

| Class | Table | Responsibility |
|---|---|---|
| `BaseConfigService` | - | shared plumbing: context on demand, `MTable`/`POInfo` lookup, typed column read/write, save, error text |
| `ProcessConfigService` | `XX_PROCESS_CATEGORY`, `XX_PROCESS`, `XX_PROCESS_VERSION`, `XX_PROCESS_WORKFLOW` | master data CRUD plus `activateVersion` |
| `LevelConfigService` | `XX_PROCESS_LEVEL` | level CRUD, `moveUp`, `moveDown`, contiguous renumbering |
| `RuleConfigService` | `XX_PROCESS_APPROVAL_RULE` | rule CRUD, priority ordering, `findOverlap` |

Decisions taken while writing them:

* The context is resolved per call. This plugin already shipped that bug once: a
  context captured in a field made a signed in GardenWorld user look like
  `AD_Org_ID = 0`. `BaseConfigService.ctx()` now always reads `Env.getCtx()`.
* Every write goes through `PO`. `AD_Client_ID`, `AD_Org_ID` and the audit
  columns are filled by iDempiere, not by this code.
* `activateVersion` only moves the `IsCurrent` flag. `XX_PROCESS_TRANSACTION`
  is never touched, so historical documents keep their chain.
* A level stores only the responsible field that matches its
  `ResponsibleType`, so a level can never claim to be a role while carrying a
  user id.
* Move up/down rewrites `LevelNo` and nothing else, so the level identity - and
  therefore the audit history of a transaction that already ran - survives a
  reorder.
* Deletion deactivates rather than removes, for the same audit reason.

Verification done so far, since a standalone JVM cannot load the iDempiere
database driver (it is resolved through the OSGi service locator):

* Dictionary and DDL are consistent: every table has its `AD_Sequence` with
  `IsTableID = 'Y'` and its `_uu` column, and no column is mandatory in the
  dictionary while nullable in the database. Five columns were corrected from
  mandatory to optional, and `XX_PROCESS.AD_TABLE_ID` was made `NOT NULL` to
  match the design. Remaining mismatch: 0.
* Runtime read and write will be exercised by the first window, which is the
  first code that runs inside the server.


---

## 10c. Step 5 rule engine

`RuleResolver` answers the only question the framework asks at submission time:
given this document, who has to approve it.

| Class | Role |
|---|---|
| `DocumentContext` | facts about the document: amount, org, and the optional user, role, warehouse, product category, business partner, project, document type |
| `RequiredApproval` | one approval step: the level, who it belongs to, and why |
| `ResolvedRoute` | the chain, plus the rule that produced it and any warnings |
| `RuleResolver` | `evaluate()` is pure, `resolve()` loads and resolves |

The split matters. `evaluate(rules, levels, context)` touches no database, so
the single most important rule of the framework - who has to approve what - is
exhaustively unit testable. `resolve(processVersionId, context)` is the thin
part that loads the configuration for a version and applies delegation.

Evaluation order, which is now the documented contract:

1. active rules of the process version, ascending priority
2. the first rule whose conditions all match wins
3. its level numbers become the chain, sorted and de-duplicated
4. if no rule matches, every level flagged `IsRequired` is used

Decisions taken while writing it:

* A malformed level list never stops a running workflow. `parseLevels` skips
  entries that are not numbers, and a rule naming a level that does not exist
  loses only that level. A workflow in flight must not fail because somebody
  typed a comma in a text field.
* The chain is always delivered sorted by level number, whatever order the rule
  stored, because the sequence is what the SLA clock and the audit trail assume.
* A rule that matches but resolves to nothing is reported honestly
  ("requires no usable level") instead of silently approving the document.
* `getLevelNumbers()` is the canonical compact form `1,2,3` for logs and tests;
  `getSummary()` is the sentence for the screen.
* `ResponsibleType.DYNAMIC_USER` cannot be resolved before the document is known,
  so it is carried with a warning and resolved by the engine at submission time.
* Overlapping bands are not rejected - they are warned about. The first rule wins,
  which is why the warning names the priority order.

52 assertions in `RuleResolverTest` cover the three amount bands from section 10a
(`0-50000` level 1, `50001-500000` levels 1-2, `500001+` levels 1-2-3), the band
edges at 0 / 50000 / 50001 / 500000 / 500001, the `IsRequired` fallback, optional
levels being honoured by a rule but skipped by the fallback, sorting and
de-duplication of a stored list, malformed and null input, the org condition, the
overlap warning, and approver resolution per responsible type. Together with
`ApprovalLogicTest` that is 127 assertions green.

---

## 10d. Step 6 runtime engine

| Class | Table | Responsibility |
|---|---|---|
| `WorkflowEngine` | `XX_PROCESS_TRANSACTION`, `XX_APPROVAL_STEP` | submit, act, start, delegate, assign, resubmit, cancel, load |
| `HistoryWriter` | `XX_APPROVAL_HISTORY` | append only audit, and reading it back |

`WorkflowEngine.decide(steps)` is pure, exactly like `RuleResolver.evaluate`, and
`settle` only carries the answer out. When does the next level open and when is
the document released is the second most important rule of the framework, and it
is now provable without a database: `ChainLogicTest` covers the sequential chain,
the parallel chain, rejection, return, advisory levels, skipped levels and out of
order input. Together with the other two suites that is 165 assertions green.

Decisions taken while writing it:

* Every write runs inside `inTrx`, which commits on success and rolls back on any
  failure. Step 4 created real transactions through `Trx.createTrxName` and never
  committed them, so those configuration saves would have been discarded by
  iDempiere at shutdown; all of them now commit, and a method that writes several
  records still writes them atomically.
* `orgIdForWrite` resolves the organisation through the same chain as the master
  data services, so an approval transaction can no longer land in organisation 0
  where no tenant would ever find it.
* The audit class has no update and no delete method at all. There is no call a
  future developer could make to rewrite the trail.
* A history row is written per action. Opening the next level is the direct
  consequence of the previous row rather than an event of its own, so the trail
  stays one row per decision and the step table keeps the derived opening and due
  dates.
* A rejection or a return closes every step that has not reached a decision, the
  waiting ones included. `StepStatus.WAITING` is not "open", so the first version
  of `blockChain` left the next level waiting and `decide` then promoted it - a
  rejected document would have continued to the next approver. `ChainLogicTest`
  caught this before the first window existed.
* `findOpen` only counts `CREATED` and `PENDING`. A rejected or returned document
  is waiting for its requester, not in progress, so it stays eligible for
  `resubmit` instead of being refused a fresh submission.
* `resubmit` rebuilds the chain from the rule engine rather than reopening the
  old steps, so a document whose amount changed in the meantime is routed by the
  rule that matches its new amount.
* `Auto start` switched off really parks the document in `CREATED`; `start`
  releases it. The flag is never quietly ignored.
* A dynamic responsible type creates an unassigned step instead of a guessed
  approver, and `assignStep` is the way out, so such a step can never leave the
  chain stuck with nobody able to act on it.
* Authorisation is checked inside the engine, not only in the form, because the
  same engine is reachable from a business document and from a scheduled job. The
  administrator role is resolved by name through `AD_Role`, so no role id is
  hard coded.
* The history is ordered by `ACTIONDATE` first rather than by level. Ordering by
  level needed database specific null handling and would have put the document
  level submit row at the bottom.

Verified without a running server: every column the engine touches exists in both
`AD_Column` and the physical table, so the `PO` writes cannot fail on a column
that the dictionary does not know. Runtime read and write still needs the first
window.

---

## 10e. Step 7 the entry point for business modules

`ApprovalEngineClient` is the whole API a business module needs:

```java
ApprovalEngineClient approvals = new ApprovalEngineClient();

// when the document is submitted
approvals.start(tableId, recordId);

// before the module releases it
if (!approvals.mayProceed(tableId, recordId))
    return;   // still waiting, rejected or returned

// when somebody acts on it
approvals.act(tableId, recordId, ApprovalAction.APPROVE, "checked");
```

A module never names a process, a version, a level or a rule. The engine resolves
the process from `AD_Table_ID` plus the document type, reads the amount from the
document, and `start` returns null when no process covers that table, so a
document that is not configured for approval simply is not under approval and no
business module has to change.

Decisions taken while writing it:

* The class does not write `DocStatus` or any other column of the business
  document. Which column means "approved" differs per document, and this layer
  does not know it. A module decides from `mayProceed`, so the approval engine can
  never quietly change a document a business module owns.
* The amount and the document number are read through `POInfo`, which means only
  candidate columns that the table really has are tried. Nothing is guessed.
* A read never raises. If the approval tables are not installed the reads degrade
  to "nothing known" and log it; only the calls that change something report a
  failure. A missing configuration must not take a sales order screen down.
* When several processes match the same table, the one for the given document type
  wins over the catch all entry, and the ambiguity is logged rather than silently
  resolved.
* `WorkflowEngine.submit` gained a comment parameter, so the note a module passes
  with its submission reaches the audit trail instead of being dropped.

Two more real defects were found while writing this step, both by checking the
code against the database instead of against the dictionary alone:

* `XX_PROCESS` has the column `documenttype_id`, not `C_DocType_ID` as the rule
  table has. `ProcessConfigService` was writing `C_DocType_ID`, which
  `BaseConfigService.set` silently skips for an unknown column, so the document
  type of a process was never stored and could never be read back. Fixed in the
  read, the write and the new lookup. The reference was also raised from a plain
  Integer to a Table Direct reference, taken from the rule table's own column so
  no id is hard coded, which is what lets the window offer a lookup list.
* `SELECT DISTINCT ... ORDER BY t.RequestDate` is rejected by PostgreSQL. The
  de-duplication moved into Java, which is also more correct: a user matching both
  the user and the role condition of one step was going to be listed twice.

All 12 raw SQL statements of the engine and the client were executed against the
database; every one of them parses and runs.

---

## 10f. Step 8 SLA, delegation and notification

| Class | Table | Responsibility |
|---|---|---|
| `SlaMonitor` | `XX_APPROVAL_STEP` | refresh SLA, escalate once, list overdue and unassigned steps |
| `DelegationService` | `XX_PROCESS_DELEGATION` | delegation CRUD plus effective-delegate lookup |
| `NotificationService` | `XX_NOTIFICATION_SETTING` | notification CRUD plus delivery |

All three are now called from the engine. Without that the three services would
have been configuration-only and nothing would ever have been notified or
escalated: `submit`, `act` and `delegate` raise their event, and `SlaMonitor`
raises the escalation.

Decisions taken while writing them:

* Escalation is sent once. The step keeps the moment it was escalated, and the
  flag is re-read inside the escalation transaction, so two schedulers running at
  the same moment cannot both escalate the same step.
* The escalation window is measured from the moment the step opened, not from the
  due date. A level that opens late must not be escalated on arrival.
* Mail is queued in `AD_UserMail` with `IsDelivered = 'N'` rather than sent
  through SMTP. An approval action then never waits for a mail server, and a mail
  outage cannot fail an approval.
* An in application notice is an `AD_Note` row with `AD_Message_ID = 0`, which
  resolves to the "no message registered" entry, and the text travels in
  `TextMsg`. That is the pattern iDempiere's own broadcast code uses.
* Every notification is raised in its own transaction, after the action has
  committed. A broken or missing notification setting must never turn a correct
  approval into a failed one.
* A recipient that matches through two settings is notified once per channel, so
  one person does not receive the same notice twice.
* `MANAGER` uses `AD_User.Supervisor_ID`, the field iDempiere itself uses for the
  reporting line, and falls back to the administrators when a user has none.
* A user whose address iDempiere reports as not sendable is skipped rather than
  queued, so a rejected mail cannot sit in the outbox forever.
* Placeholders that a configuration misspells are left as they are instead of
  becoming an error an administrator would have to find in a log.
* `DelegationService` refuses the delegations that cannot mean anything:
  delegating to oneself, to an inactive user, with a range that ends before it
  starts, or with a date that has already passed. An open ended delegation stays
  allowed, because that is a legitimate holiday arrangement.

Two more defects were caught by running the SQL against the database:

* `SELECT DISTINCT ... ORDER BY DueDate` in `listOverdue` is the same PostgreSQL
  rejection as in step 7. Fixed the same way, with the de-duplication in Java.
* `supervisorsOf` first assumed the reporting line ran through
  `AD_User_BPartner`, a table that does not exist. `AD_User.Supervisor_ID` does,
  which is both simpler and the field iDempiere already uses.

Steps 2 to 8 are now complete: 50 compiled classes, 165 assertions green. What
remains is the presentation layer, and the first window is also the first code
that runs inside the server, so it is where the configuration services get their
runtime read and write test.

---

## 10g. Step 9 the window shell and sections 01-03

| Class | Role |
|---|---|
| `UiLayouts` | frames, field grids, cells, dropdowns, toolbars, date reading |
| `WProcessApprovalWindow` | the shell, the header selection, sections 01 to 03 |

`UiLayouts` exists because three ZK details have to be right in every section at
once, and each of them already went wrong once in this plugin:

* a rows component only accepts a grid as its parent, so `grid()` returns the grid
  and its rows together in one `FieldGrid` holder
* the iDempiere combobox appends by label and its `getValue()` returns the selected
  <em>label</em> as a string, so values are read through `getSelectedItem()` and
  selected through `setSelectedItem()`
* a ZK date box stores a `java.util.Date`, not a timestamp, so `dateOf()` converts
  instead of casting

Decisions taken while writing it:

* Selection drives everything. Category, process and version are chosen once in the
  header and every section reads from that selection, so a level can never be
  edited for the wrong process.
* A process without a version cannot approve anything, so section 02 can create
  one. Without it a newly created process would be a dead end.
* Delete explains that a process is deactivated rather than removed instead of
  asking a question whose only honest answer is no. The audit trail is the reason,
  and it is better to say so once than to pretend the button removes something.
* Every SQL statement the window needs was executed against the database.

One more instance of the case bug from step 7 was found here and is now fixed
everywhere: `ProcessConfigService.listProcesses` also selected `DocumentTypeID`,
which PostgreSQL folds to `documenttypeid` and does not find. A sweep of every SQL
string in the approval package for mixed-case identifiers that are not physical
columns now comes back clean.

The approval tables are empty, which is the correct starting point: the first
category, process and version are created through this window, and that is also
the first real runtime test of the configuration services.

---

## 10h. Step 10 section 04, the approval levels

| Class | Role |
|---|---|
| `ConfigListPanel` | the shape every list section has: list, editor, Add/Update/Delete and two extra actions |
| `LevelPanel` | section 04, the levels of the selected version |

Sections 04, 05, 08 and 09 all manage a list with an editor underneath, so the
common part is in `ConfigListPanel` rather than repeated four times with four
slightly different versions of the same bug.

Decisions taken while writing it:

* The id travels on the list item. A ZK list item here carries no payload of its
  own, so the alternative was matching the selected index back into a parallel list
  and hoping the two stayed in step.
* Move Up and Move Down are disabled at the ends of the list rather than doing
  nothing, so a button that cannot act says so.
* Delete confirms, and says in the confirmation that the row is deactivated rather
  than removed, because approvals that already ran refer to it.
* The responsible fields follow the responsible type. A level stores exactly one
  responsible field, so the editor hides the fields that do not apply; a level that
  carried both a user and a role could be read either way and the engine would
  have to guess.
* A dynamic responsible type says so on screen instead of pretending the approver
  is known. The step is created unassigned and an administrator assigns it.
* Escalation cannot be switched on without naming somebody, because escalating to
  nobody would only look as if nothing had happened.
* `SLA Hours` and `Warn Before Hours` reject a negative value and treat zero as not
  set, so the SLA monitor never has to interpret a zero hour budget.
* Every panel is refreshed whenever the version changes, and one panel that fails
  to refresh is reported instead of taking the whole form down.

Three ZK details had to be checked rather than assumed: the iDempiere list box has
no single argument `appendItem`, a component cannot span grid columns itself
(the span belongs on the cell), and there is no `show` overload for a checkbox.

---

## 10i. Step 11 section 05, the approval rules

| Class | Role |
|---|---|
| `RulePanel` | section 05, the rules of the selected version |
| `Lookups` | warehouse, category, partner, project and document type lists |
| `RuleResolverAccess` | the one place the panel reads a stored level list |

Decisions taken while writing it:

* The required levels are a multiple selection, not a text field. The engine stores
  them as `"1,2"`, which came out of the design as a comma separated text box. On
  screen that produces `"1, 2"`, `"1;2"` and `"1,,2"` as three silently different
  values, and the rule then quietly selects one level instead of two. The picker
  only offers levels that exist and writes back the same canonical string the
  engine has always read.
* The selected levels are sorted before they are stored, so a rule reads the same
  whichever end of the list the user picked from.
* The overlap warning of the whole rule list is shown under the editor rather than
  only when saving, because two overlapping bands make the evaluation order
  silently decide the outcome and that has to be visible while the rules are being
  written.
* A non-numeric amount is refused with an example in the message, rather than being
  stored as null and turning into "any amount".
* `RuleResolverAccess` exists so the panel parses the stored level list with the
  same method the engine uses. A second parser would be a second opinion about the
  same string.

Two more real defects, both found by executing the SQL rather than reading it:

* `C_BPartner_Category` does not exist in this iDempiere version. The partner
  category is now the `IsCustomer` and `IsVendor` flags on `C_BPartner`, which is
  also simpler and correct for a partner that has no order yet.
* The level picker was declared as a raw ZK list box, which has no value carrying
  `appendItem`, so the level number had nowhere to go. It needs the iDempiere list
  box; the raw one only stores the label.

---

## 10j. Step 13 sections 07 to 09

| Class | Role |
|---|---|
| `SlaPanel` | section 07, the SLA and escalation of every level in one view |
| `DelegationPanel` | section 08, approver substitution |
| `NotificationPanel` | section 09, who is told what |
| `SlaFields` | the one rule for what an empty or zero hour means |

Decisions taken while writing them:

* Section 07 shows the SLA of the whole chain rather than repeating the editor of
  section 04. An approver reads SLA across the chain, not level by level. Both
  sections write the same level row through the same service, so there is only one
  value for a level's SLA, and `SlaFields` states the meaning of an empty or zero
  hour in one place instead of twice.
* Section 07 has no Delete button that removes a level. Clearing the SLA is what it
  offers, and removing the level stays section 04's job; a second delete that did
  something different would be a trap.
* The escalation editor says what will actually happen before it is saved: on with
  no time, on with nobody, and both a user and a role chosen.
* Section 08 is not tied to the selected version. A delegation is tenant wide with an
  optional level scope, so switching to another process must not hide a delegation
  somebody is relying on. It offers "all levels" rather than pretending a
  delegation belongs to one process, and it opens its service lazily so a missing
  table cannot take the whole window down.
* A delegation row says whether it is in force right now, because an expired
  delegation that still looks like an active one is exactly the mistake this
  section exists to prevent.
* Section 09 states the placeholders on screen, and an empty subject or body means
  the default text of the event rather than an empty notification.

Two mistakes were caught while writing these three, both of the kind that compile
happily and only misbehave in the browser: a stray empty text box left in the
notification editor after a cell was widened, and a `appendChild(null)` on the
level picker, which would have thrown when the editor was built.

---

## 10k. Step 12 and 15, the diagram and the approval views

| Class | Section |
|---|---|
| `DiagramPanel` | 06, the chain as configured and as running |
| `TransactionPanel` | 10, the approvals and the actions on them |
| `HistoryPanel` | 11, the append only audit of a document |
| `AuditPanel` | 12, who released or rejected it and when |
| `Refreshable` | the one method the window needs from a panel |

With these the window has all twelve sections.

Decisions taken while writing them:

* The diagram is built from components, not from a generated picture. A picture
  would look more like a diagram and tell an administrator less: the text in a box
  is searchable, copyable and readable by a screen reader, and it cannot drift
  from the configuration because it is built from the same level rows the engine
  reads.
* The diagram marks state with a border colour and says the same thing in words in
  the box, so the meaning does not depend on telling the colours apart.
* The four views of a document are driven by one selection. Choosing a document in
  section 10 moves the diagram, the history and the audit with it, so the four can
  never show four different documents.
* The action buttons are offered whenever a step is open and the engine decides
  whether the user may act. Checking in the form as well would be a second
  implementation of the same rule, and the engine is the one that has to hold
  because it is also reachable from a business document and from a scheduler.
* Reject and return refuse to run without a comment, and say why on the button
  rather than after the engine rejects the call.
* Handing a step over uses a user dropdown on the panel. ZK has no input prompt to
  reuse, and asking for a raw `AD_User_ID` would mean an approver has to look a
  number up elsewhere before they could delegate at all.
* The history has no edit and no delete, and the audit is read only, which is the
  point of both.
* `Refreshable` exists because section 10 shows approvals rather than configuration
  and so cannot extend the configuration list panel, but it still has to be told
  when the process or the version changed.

The panel lookups were consolidated into `Lookups` at this point: the user, role
and organisation queries existed in three places and had begun to drift.

---

## 10l. Step 14 the Test Workflow dry run

| Class | Role |
|---|---|
| `WorkflowTestService` | simulates an approval for an amount, writing nothing |
| `WorkflowTestResult.LevelOutcome.of` | the only way to build a line of the report |

The Test button sits under the diagram in section 06 rather than in a section of
its own, because the answer belongs next to the picture it explains.

Decisions taken while writing it:

* The run goes through `RuleResolver.resolve` - the same call the engine makes when
  a document is really submitted, delegation included. A second implementation of
  "who approves this" would be a second opinion about the most important rule of
  the framework, and the Test button could then show something the engine would
  never do. This is the whole point of the feature.
* Skipped levels are shown, not hidden. Every configured level appears in the
  result, and the ones the rule left out say so. "Level 3 did not run" is usually
  the answer to the question being asked, and a list that only shows what happens
  hides it.
* The result carries its own warnings: no rule matched, nobody would approve, no
  amount was given, or the rules overlap.
* Because delegation is applied, the dry run also shows who would really be asked
  while somebody is on leave, which is exactly the situation where getting it wrong
  is expensive.
* `LevelOutcome` keeps a package private constructor and gains a factory. The only
  way to produce a line of the report is through a factory that documents what each
  field means.
* A negative or non numeric amount is refused with an example in the message,
  rather than being treated as no amount and quietly producing a different answer
  than the one the user asked about.

---

## 10m. Step 16 the approval dashboard

`ApprovalDashboardWindow` answers three questions in one screen, in the order
somebody asks them in the morning:

1. what is waiting for me
2. what is late, whoever it is waiting for
3. what is waiting for nobody, which is always a configuration mistake

The counters at the top are the same three answers in one line, so a manager can
read the state of the whole tenant without scrolling.

Decisions taken while writing it:

* "Waiting for no one" is a first class section, not a log line. An unassigned step
  can only come from a level with a dynamic responsible type, and it stays pending
  forever without anybody noticing. Putting it on the dashboard is the only way it
  gets fixed.
* Approving from the dashboard calls the same `WorkflowEngine.act` the
  configuration window calls. The dashboard only makes it convenient; it has no
  second opinion about whether the user may act and it marks nothing itself.
* The lateness of a row is asked from `SlaMonitor`, the same source the counter
  uses, so the counter and the list can never disagree about which documents are
  late.
* An empty list shows "nothing here" inside the list rather than an empty box. An
  empty list box reads as a form that failed to load.
* The filter is remembered per user in `AD_Preference`, written through the
  iDempiere model rather than with hand written SQL, so the audit columns and the
  tenant rules are filled by iDempiere rather than guessed. `PreferenceFor` is
  mandatory in the database and is set to `W`.
* Failing to remember the filter is only logged. A preference is a convenience and
  must never be able to block the dashboard.

---

## 10n. Step 17 registering the two windows

An `AD_Form` row is what makes a Java web form reachable at all: iDempiere reads
`Classname`, instantiates it and calls `initForm()`. Without the row the class
compiles, exists, and nobody can open it.

| | configuration | dashboard |
|---|---|---|
| `AD_Form_ID` | 1000101 | 1000102 |
| name | Business Process Approval Management | Approval Dashboard |
| `Classname` | `...approval.ui.WProcessApprovalWindow` | `...approval.ui.ApprovalDashboard` |
| `AD_Menu_ID` | 1000017 | 1000018 |
| roles with access | 0, 102, 200001 | 0, 102, 103, 200001 |

Both sit under a new top level group, `AD_Menu` 1000019 "Business Process &
Approval", which exists because hanging an approval configuration under
"Warehouse" - where the earlier plugin forms ended up - would have been quick and
wrong.

Facts about this database that the registration depends on, all verified rather
than assumed:

* **The menu tree is `AD_TreeNodeMM`, not `AD_TreeNode`.** `AD_TreeNode` holds the
  tree *definitions* - tree 10 named "Menu" is one of them - and it is empty for
  that tree, which is what made it look as if the menu tree had been flattened
  away. The hierarchy is in `AD_TreeNodeMM`, and tree 10 has 823 nodes in it.
  An `AD_Menu` row with no matching `AD_TreeNodeMM` row is a row that exists,
  compiles, and can never appear in the Menu window.
* `AD_Menu` has no `Parent_ID` and no `SeqNo`; both live in `AD_TreeNodeMM`. One
  `AD_Menu` row is therefore not a menu entry.
* `AD_Form.AccessLevel` is a **character**, not a number. `7` is full access - the
  level iDempiere's own "Workflow Activities" form uses - and `3` is read only,
  which is what the existing "Meeting Dashboard" uses.
* `AccessLevel` alone grants nothing. `AD_Form_Access` needs a row per role; this
  is what actually made the earlier Quick Product Entry window unreachable before.
* `AD_Form.Classname` is `varchar(60)`. The dashboard class was renamed from
  `ApprovalDashboardWindow` to `ApprovalDashboard` because the longer name is 61
  characters and is rejected. `WProcessApprovalWindow` is exactly 60, so the
  registration has no headroom: renaming a package would break it, and that has to
  be checked before a move rather than discovered afterwards.
* The translation rows are only for `es_CO`, matching the existing plugin forms.
  The English text lives in `AD_Form.Name` itself, which is how iDempiere stores it.

Decisions taken while registering:

* The configuration window is given to administrator roles only, and the dashboard
  to everybody including the plain user role. That is the design's security table
  applied to the two screens rather than a blanket copy of the existing access
  rows: a role that cannot open the dashboard would simply never see what is
  waiting for it, and approving is the whole point of that window.
* The dashboard is `AccessLevel 7`, not `3`, because it performs approve and
  reject. Copying the read-only level of the existing Meeting Dashboard would have
  been the wrong precedent to follow.
* The script is idempotent. It runs inside transactions, uses `ON CONFLICT` on the
  primary key of every table, and was executed repeatedly to prove that a later run
  duplicates nothing. The first attempt failed on the class name length and the
  rollback left the dictionary untouched, which is also worth having confirmed
  rather than assumed.

**What the first run got wrong, and why it is recorded here:** the registration was
written from the dictionary alone, which said the menu tree was `AD_TreeNode` and
empty, and concluded that a single `AD_Menu` row was a complete menu entry. The
Menu window then answered "No Records found" for both windows. The cause was found
by reading `AbstractMenuPanel`, which builds the tree from `MTree`, and `MTree`
hands menu nodes to `MTree_NodeMM`.

**The second thing that was missing, and the more important one:** the windows then
appeared in the menu but answering "Failed to open
org.mycompany.requisition.approval.ui.WProcessApprovalWindow". The cause was
`DefaultFormFactory: Web UI form not implemented for the swing form ...`.

iDempiere does not load an `AD_Form.Classname` by reflection. `Extensions.getForm`
walks every registered `IFormFactory` OSGi service and asks each one to build the
form, and `DefaultFormFactory` can only reach classes it can *derive*: a package
rewrite for `org.compiere.` and `org.adempiere.`, a `V*` to `W*` rename, and one
desperate attempt that appends a `W` to the class name. A plugin class already
named `W...` in a custom package matches none of those, so the factory returns
null and the menu entry opens nothing.

That is why this plugin has `RequisitionFormFactory`, an `IFormFactory`
registered through `OSGI-INF/org.mycompany.requisition.RequisitionFormFactory.xml`
that maps each class name to a `new` instance. Every form of this plugin is
reachable only because it is listed there. **A new form needs three things, not
one: the `AD_Form` row, the `AD_Menu` and `AD_TreeNodeMM` rows, and a line in
`RequisitionFormFactory`.** Missing the third one produces a menu entry that looks
correct and opens nothing, and the error names the class rather than the factory,
which sends you looking in the wrong place.

The lesson worth keeping from both: in iDempiere neither the dictionary nor the
database tells you which table the framework reads a hierarchy from, and neither
tells you which factory instantiates a class. Those two answers only come from the
framework source.

**The third thing, and the reason a compiled form still could not open:** once the
form was being instantiated, ZK refused it with "Unsupported child for grid:
`<Hlayout null>`". A ZK grid accepts only columns and rows, and section 02 had its
button strip appended straight to the grid. This is the class of mistake this
plugin keeps making, and all three instances have the same shape:

* a rows component whose parent is not a grid
* a grid that receives something other than columns or rows
* a container emptied by clearing the list that `getChildren()` returns

The third one does not throw immediately. It drops the children without telling
ZK, they keep pointing at a container that no longer knows them, and the damage
shows much later as a component that will not redraw. All twenty occurrences were
replaced by `UiLayouts.clear`, and a dropdown by `UiLayouts.clearItems`, because a
combobox keeps per item state of its own and has to be emptied through its own
`removeItemAt`. A combobox emptied the generic way reports the old item count
while showing fewer items.

Every remaining structural risk was then checked by hand rather than waited for:
the grid only receives columns and rows, the rows only receive rows, the border
layout only receives north, centre and south, and every group box gets its caption
as its first child followed by exactly one content component.

---

## 10o. Step 18c the native assignment window

The approval level editor was first written as a custom `AD_Form`
(`WApprovalAssignmentWindow`, `AD_Form` 1000103). The user rejected that: a
hand-built ZK form cannot look or behave like a standard iDempiere window, and
the vertical stack of fields was the complaint. The level editor was therefore
rebuilt as a **native window** and the custom form was set aside. Only the two
menu entries were deactivated, so nothing in the engine or the service layer was
touched.

### What the window is

`AD_Window` 1000006 "Business Process Approval Assignment", six single row tabs
over `XX_PROCESS_LEVEL`, one record at a time, exactly like the reference
"Requisition Document Type Wise Supervisor Assignment" window:

| Tab | Fields |
|---|---|
| 01 Organization | Tenant\*, Organization\*, Active, Process Category\* |
| 02 Process | Business Process\*, Workflow Version\* |
| 03 Approval Level | Level Number\*, Level Name\*, Responsible Type\*, Approval Type\*, Responsible User, Responsible Role, Responsible Department, Approver Organization, Required, Final Approver |
| 04 Approval Limit | Lower Limit, Upper Limit |
| 05 Description | Approval Stage, Description |
| 06 SLA and Escalation | SLA Hours, Warning Before Hours, Escalation Enabled, Escalation After Hours, Escalation User, Escalation Role |

### Layout facts taken from the database, not guessed

Read off the existing working window `AD_Window` 200130 / `AD_Tab` 200324
("Label Assignment"), which is the same kind of hand-registered custom window:

* `AD_Window` has **no** table column. The table hangs off `AD_Tab.AD_Table_ID`.
* Two columns are `XPosition` 1 and 4 with `ColumnSpan` 2; a wide field is
  `ColumnSpan` 5. There is no `YPosition` column in this schema, so the row wrap
  comes from `SeqNo` order plus the largest `XPosition` in play.
* The key and the UUID column are kept in the layout with `IsDisplayed='N'`.
* Tabs 02 to 06 set `Included_Tab_ID` = 1000006 so they edit the same record.

### Why the level table grew two columns

A native window can only render real columns, so `XX_PROCESS_LEVEL` now also
carries `XX_PROCESS_ID` and `XX_PROCESS_CATEGORY_ID`. They are mirrors of what
`XX_PROCESS_VERSION_ID` already decides:

* `LevelConfigService.apply()` writes both, read from one join of
  `XX_Process_Version` to `XX_Process`, so the two copies come from the same row.
* Validation rules 200180 and 200181 refuse a record where the version does not
  belong to the process, or the process does not belong to the category. So the
  mirrors cannot drift even if somebody types them by hand.
* In exchange the window gets real lookup boxes and, through
  `AD_Column.AD_Val_Rule_Lookup_ID` rules 200182/200183/200184, a genuine
  category → process → workflow version cascade that a ZK form could not
  produce without hand-written listeners.

### List dropdowns in iDempiere 12

There is **no `AD_List` / `AD_ListValue`** model in this version: neither the
tables nor the classes exist. Every list in the database is an `AD_Ref_List`
row under a custom `AD_Reference` with `ValidationType='L'`, which is exactly
what this plugin already does for `XX_Meeting_Mode` and friends. So:

* `AD_Reference` 1000900 "XX_Process_ResponsibleType" with 6 values, matching
  the `ResponsibleType` enum codes exactly.
* `AD_Reference` 1000901 "XX_Process_ApprovalType" with 5 values, matching the
  `ApprovalType` enum codes exactly.

The codes are the enum names, so `ResponsibleType.of()` and `ApprovalType.of()`
still resolve anything the window stores.

### Column references that were plain Integer or String

The level columns had been registered without usable references, which is why
the custom form had to hand-build every box. Corrected here:

| Column | Was | Now |
|---|---|---|
| XX_PROCESS_LEVEL_ID | 11 Integer | 13 ID, `IsKey='Y'` |
| XX_PROCESS_VERSION_ID | 11 Integer | 19 Table Direct |
| AD_ORG_ID_APPROVER | 11 Integer | 19 Table Direct |
| ESCALATION_USER_ID | 11 Integer | 19 Table Direct |
| ESCALATION_ROLE_ID | 11 Integer | 19 Table Direct |
| RESPONSIBLETYPE | 10 String | 1000900 list |
| APPROVALTYPE | 10 String | 1000901 list |
| LOWERLIMIT / UPPERLIMIT | 11 Integer | 12 Amount |
| APPROVALSTAGE / DESCRIPTION | 10 String | 14 Text |

`HR_DEPARTMENT_ID` deliberately stays reference 11. `hr_department` is not
registered in `AD_Table` and holds no rows, so a Table Direct box there could
only ever show an empty list.

### Validation rules added

`AD_Val_Rule.type` is `'S'` and the code is a **boolean expression**, not a
`SELECT`. 200179 lower limit not above upper limit, 200180 version of process,
200181 process of category, plus the three lookup filters 200182 to 200184.

### Why the menu item was invisible

Worth writing down, because it cost a round trip and it is the same trap that
hid the two `AD_Form` windows earlier.

`MTree.getChildNodes()` asks the role whether it may open a menu target:

```java
else if (X_AD_Menu.ACTION_Window.equals(actionColor))
        access = role.getWindowAccess(AD_Window_ID);
...
if (access != null || m_editable)   // MTree.java line 684
        retValue = new MTreeNode(...);
```

and `MRole.getWindowAccess()` reads **only** `AD_Window_Access`:

```java
String sql = "SELECT AD_Window_ID, IsReadWrite, IsActive FROM AD_Window_Access WHERE AD_Role_ID=?" + ASPFilter;
```

Three consequences, all verified in this installation:

* Access is **not** derived from `AD_Tab`. There is no `AccessLevel` column on
  `AD_Tab` in this schema at all, so a window registered by hand has no access
  until `AD_Window_Access` rows are inserted for it.
* There is **no super-user bypass**. Role 0 gets nothing for free.
* Every other window in the database has `AD_Window_Access` rows. Ours had
  none, so `access` was null and `MTree` silently dropped the node. The parent
  group still appeared, and because the two `AD_Form` menus had just been
  deactivated it appeared **empty**.

`AD_Window_Access` now has window 1000006 for roles 0, 102 and 200001 read/write
and role 103 read only, so the window is visible to every role that needs it
while configuration stays an administrator action. The same table, not
`AD_Tab`, is what a `Menu` window row must be given before a hand-registered
window is reachable.

### A Table Direct column name must end in _ID or _UU

`MLookupFactory` derives the referenced table from the column name by stripping
a trailing `_ID` or `_UU`. A column whose name does not resolve that way gets no
lookup at all:

```
MLookupFactory.getLookup_TableDir: Key does not end with '_ID' or '_UU': AD_ORG_ID_APPROVER
MLookupFactory.getLookupInfo: No SQL - AD_ORG_ID_APPROVER
GridFieldVO.loadLookupInfo: No LookupInfo for ESCALATION_USER_ID
java.lang.NullPointerException: Cannot invoke "MTable.getAD_Window_ID()" because "table" is null
```

An audit over every custom table found four such columns, three of them on the
level and therefore on the tab the user was looking at when the whole tab came
up inert:

| Column | Derived table | Replaced with |
|---|---|---|
| XX_PROCESS_LEVEL.AD_ORG_ID_APPROVER | AD_ORG_ID_APPROVER | 276 AD_Org (all) |
| XX_PROCESS_LEVEL.ESCALATION_USER_ID | ESCALATION_USER | 110 AD_User |
| XX_PROCESS_LEVEL.ESCALATION_ROLE_ID | ESCALATION_ROLE | 53317 AD_Role |
| XX_PROCESS.DOCUMENTTYPE_ID | documenttype | 170 C_DocType |

All four replacements are existing Table references that already carry an
`AD_Ref_Table` row, so the dropdown is a plain lookup with a real name column and
no new dictionary rows are needed. `XX_ManagementMeeting.chairperson_id` and
`XX_ManagementMeeting.secretary_id` have the same defect and are left alone
because they belong to an earlier feature.

The audit is worth repeating after any hand-registered column:

```sql
select t.tablename, c.columnname, c.ad_reference_id
from ad_column c
join ad_table t on t.ad_table_id = c.ad_table_id
left join ad_table t2 on lower(t2.tablename) = lower(
    case when upper(c.columnname) like '%\_ID' then left(c.columnname, length(c.columnname)-3)
         when upper(c.columnname) like '%\_UU' then left(c.columnname, length(c.columnname)-3)
         else c.columnname end)
where c.ad_reference_id = 19 and t.tablename like 'XX\_%' and t2.ad_table_id is null;
```

### Not yet explained

After the lookup repair the window still has to be re-tested. A window of six
tabs over one table where tabs 02 to 06 carry `Included_Tab_ID` was ruled out as
the cause: `GridTab.isDetail()` needs `TableLevel != 0`, and all six tabs are at
level 0, so they are not detail tabs and `m_parentNeedSave` never applies.
`AD_Tab` and `AD_Field` were compared column by column against the working
config window 144 "Product Category" and matched, apart from `Processing`
(NULL, now 'N'). `AD_Table.ReplicationType` is 'L' like everything else, so the
read-only branch at `GridWindowVO.java:215` does not apply either.

The remaining candidate was the role. `GridWindowVO.java:207` sets the whole
window read-only from a single value:

```java
final Boolean windowAccess = role.getWindowAccess(vo.AD_Window_ID);
vo.IsReadWrite = (windowAccess.booleanValue() ? "Y" : "N");
```

and `GridWindowVO.java:277/329` then propagates it to every tab:

```java
if (!tabvo.IsReadOnly && "N".equals(mWindowVO.IsReadWrite))
        tabvo.IsReadOnly = true;
```

`AD_Window_Access.IsReadWrite='N'` therefore greys out the entire window, not
just one tab. Role 103 had been given read-only access on the theory that
configuration is an administrator action, which turned out to be wrong in
practice: this plugin grants role 103 read/write on every other custom window
(1000000 to 1000005), so the anomaly read as a broken window rather than as an
intentional restriction. All four roles now have read/write.

`AD_Menu.IsReadOnly` is a separate and easily confused switch. It is only read
when the window is opened by menu id with no window id
(`GridWindowVO.java:111`), and ours is `'N'`.

### Bisect: six tabs over one table

With every switch that iDempiere actually consults verified identical to a
working config window, the one structural thing that no working window in this
installation has is six tabs pointing at the **same** table, five of them
carrying `Included_Tab_ID`. The screenshot showed no tab bar at all and only
"03 Approval Level", which is what a half-built tab tree looks like.

Tabs 1000007 to 1000011 were therefore deactivated and all 28 fields moved onto
tab 1000006, laid out two per line in reading order: organization, process,
level, limits, description, SLA. The two column layout is untouched, left
`XPosition` 1, right `XPosition` 4, `ColumnSpan` 2, and the two long texts stay
full width at `ColumnSpan` 5. This is a bisect, so it isolates one variable
rather than assuming a cause; the parked tabs remain in the dictionary.

After the collapse the window loads with no errors at all and the new record
initialises correctly, Tenant showing GardenWorld and Active ticked. Table level
access was checked as well and is not a factor: `AD_Table_Access` is a negative
list, and the only row in it is role 103 excluding `RV_BPartner`, so role 102
falls through to full access.

What was actually wrong is that the window had nothing to show. Every approval
table had zero rows, so the single row tab only ever rendered an empty new
record. A starting configuration was seeded: one category (Purchasing), one
process (Purchase Requisition Approval, bound to `M_Requisition`), one active
version, and three levels covering both a role approver and a user approver
with amount bands, SLAs and a final sign off level. Ids come from the table
sequences and the table, roles and approver are resolved by name, so nothing is
hard coded and the script is safe to re-run.

### The actual cause: every row was in the system tenant

This is the one that finally explained an inert window. `GridTable.isRowEditable`
ends in a call to `MRole.canUpdate`:

```java
return MRole.getDefault(m_ctx, false).canUpdate
        (AD_Client_ID, AD_Org_ID, m_AD_Table_ID, Record_ID, false);
```

and `canUpdate` refuses a system level row for a role without system level:

```java
//  System == Client=0 & Org=0
if (AD_Client_ID == 0 && AD_Org_ID == 0 && userLevel.charAt(0) != 'S')
{
        retValue = false;
        whatMissing += "S";
}
```

Role 102 "GardenWorld Admin" has `UserLevel` `' CO'`. Position 0 is a space, not
`S`. Every approval row had `AD_Client_ID = 0`, so `canUpdate` returned false for
every row and the tab was read only from top to bottom. The window could read
and could not write. Tables that behave, `XX_Requisition` and
`M_Product_Category`, both store `AD_Client_ID = 11`.

Why the rows ended up in tenant 0: the `AD_Column` default had been written as
`'@AD_Client_ID@'`. iDempiere's own columns use `'@#AD_Client_ID@'`. The `#`
form reads the global session context, the bare form looks in the current record
context first, finds nothing, and the physical column default of `0` wins. So
every insert landed in the system tenant.

Both halves are fixed: the dictionary defaults on all ten approval tables now
carry the `#`, and the existing rows were moved to the tenant resolved from
`AD_Client` rather than typed in. `AD_Org_ID` stays 0, the `*` organization that
the working custom tables also use and that a client level role may write.

Worth remembering for any hand-registered table: **`@#AD_Client_ID@` and
`@#AD_Org_ID@`, not the versions without the `#`.** A table that gets them wrong
opens, reads and saves from code, yet is read only in the UI, which is the most
misleading failure mode there is.

### Stripping the table back to plain references

A decisive clue then appeared: the **User and Role windows, which are core and
untouched here, also report "No Records found"**, while the custom ZK forms in
this plugin work. The forms read through `PO` and never touch `GridTable`, so
the failure is in the native window layer, not in this window.

To remove every non standard element from the table, the six validation rules
were unlinked and `ResponsibleType` and `ApprovalType` went back to reference
10. `XX_PROCESS_LEVEL` now uses only built in references, 10, 11, 12, 13, 14, 16,
19, 20, 30, 110, 276 and 53317, so it is indistinguishable from a stock table.
The rule definitions themselves are kept in `AD_Val_Rule` and the enum values in
`AD_Ref_List`, ready to be reattached once the window behaves, since
`ResponsibleType.of()` and `ApprovalType.of()` resolve the same codes either way.

### Two tenants, two sessions

The last piece was visible only in `AD_Session`. This installation keeps sessions
of two kinds:

| AD_Client_ID | AD_Role_ID | Created by |
|---|---|---|
| 11 GardenWorld | 102 GardenWorld Admin | 100 |
| 0 System | 0 System Administrator | 10, and 100 |

Choosing **System Administrator** under *Change Role* moves the session to the
System tenant, and a grid then only sees rows whose `AD_Client_ID` is 0. The
newest session row, created at the same moment the menu tree loaded, was
`AD_Client_ID = 0`, so the seeded rows in tenant 11 were invisible and the window
showed nothing at all. No error was logged because nothing failed; the query
simply matched no rows.

The seeded rows were moved to `AD_Client_ID = 0`, which both tenants can read and
the System Administrator session, whose `UserLevel` is `S`, can write. If the
tenant is switched back to GardenWorld Admin the rows have to move again, so the
tenant that owns configuration data is a decision that belongs with the tenant
setup rather than with this script.

---

## 11. Open questions

None blocking. Remaining choices are cosmetic or tenant specific and can be
settled while building the corresponding step.
