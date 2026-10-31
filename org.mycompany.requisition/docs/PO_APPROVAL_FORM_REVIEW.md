# PO Document Approval Form — review of the supplied code

Reviewed against this installation rather than accepted on its face. Every claim
below was checked in the database or in the iDempiere sources.

## What was already correct

* `AD_Menu.Action = 'X'` for a form. `X_AD_Menu.java:360` has `ACTION_Form = "X"`,
  `ACTION_Window = "W"`, `ACTION_WorkFlow = "F"`, which is the reverse of what
  the AD_Menu Action list suggests. 45 menus in this database already use `X`.
* The `INSERT` placeholder count: 14 placeholders, 14 arguments, 17 columns with
  three literals. A first pass suggested a mismatch and that was a fault in the
  check, not in the SQL.
* `org.compiere.model.MSequence` is the right import and it exists.
* `Dialog.warn/error`, `AEnv.zoom(int,int)`, `DB.setParameters`,
  `DB.executeUpdateEx` and `DB.getSQLValue` all exist with the signatures used.

## The one compile error

A raw `org.zkoss.zul.Listbox` offers only `appendItem(String, String)`. It has
no `Object` overload, so `orgBox.appendItem("", 0)` does not compile at all, and
even if it did, the value could not be carried.

Fixed by using `org.adempiere.webui.component.Listbox`, which adds
`appendItem(String, Object)` and extends the ZK class, so `getSelectedItem()`
reads exactly as before.

## The logic bugs that mattered

**1. A rejected order could never reappear.** The routing test asked for an
order with *no* log rows at all. The moment a rejection was written that became
false, and so did the forwarding branch, so the order vanished from every queue
for good. The Javadoc claimed a rejection returns the order to the creator's
supervisor, which the query made impossible.

Now the routing decides from the **last** row: a forward to this user shows it,
and only a never-decided order is matched against the mapping. A rejection puts
it back in the queue it came from.

**2. A null `LowerLimit` silently dropped rows.** `o.GrandTotal >= m.LowerLimit`
is null, and null is not true. Now `COALESCE(m.LowerLimit, 0)`.

**3. A failed query looked like an empty queue.** `load()` logged the error and
returned an empty list, so a broken statement and a genuine absence were
indistinguishable on screen. That is the failure mode that costs the most time.
The message is now kept in `getLastError()` so the form can say so.

**4. Reject bypassed the document lifecycle.** `setDocStatus(STATUS_Drafted)`
followed by `saveEx()` writes the status directly, leaving postings, conversions
and history inconsistent. `setDocAction(ACTION_Complete)` followed by
`saveEx()` does nothing at all, since `setDocAction` only prepares a later
`processIt`. Reject now goes through `processIt(ACTION_Prepare)`.

**5. `isFinalApprover` took a `createdBy` it never used.** The signature implied
the decision depended on the document's creator; the query keyed on document type
and approver alone. The parameter was removed because that is what the rule
actually is.

**6. Forwarding to yourself was allowed.** The routing query can never deliver
such a row, so it silently did nothing. It is now refused with a message.

## What the setup.sql had not done

Only the log table exists in this installation. Everything else was missing,
and each omission fails in its own way:

| Missing | Consequence |
|---|---|
| `AD_Sequence` row | `MSequence.getNextID` fails, so **every** Approve, Reject and Forward fails |
| `AD_Table` row | the table is unknown to iDempiere |
| `AD_Form` | nothing to open |
| `AD_Form_Access` | invisible to every role, System Administrator included |
| `AD_TreeNodeMM` | a menu with no tree node is never rendered |

All five are now in place: sequence 1000077, table 1000122, form 1000104,
access for roles 0, 102, 103 and 200001, menu 1000024 with action `X` and a tree
node under the Business Process & Approval group.

The three classes are compiled into the plugin and `RequisitionFormFactory`
returns the form, which is the other half of reachability: a plugin form the
factory does not name is invisible to `DefaultFormFactory`.

## Why the queue is still empty, and why that is not a code fault

| C_Order_ID | DocumentNo | C_DocType_ID | Document Type | GrandTotal |
|---|---|---|---|---|
| 1000000 | 800003 | **0** | ** New ** | 23.75 |
| 1000001 | 800004 | **0** | ** New ** | 48.00 |

`C_DocType_ID` 0 is a placeholder row named "** New **", not a document type.
The mappings in this installation are for document types 127 and 1000000, so
nothing can match. The two orders carry no document type at all, which is a data
defect rather than a routing decision.

`poapproval_02_optional_sample.sql` puts both orders on document type 126
*Purchase Order* and adds the matching mapping. It was run after the decision was
taken to proceed.

| C_Order_ID | DocumentNo | C_DocType_ID | GrandTotal |
|---|---|---|---|
| 1000000 | 800003 | 126 Purchase Order | 23.75 |
| 1000001 | 800004 | 126 Purchase Order | 48.00 |

Both orders were Drafted and neither is posted or processed, so changing their
document type could not disturb postings or conversions.

The mapping created is id 1000002: document type 126, creator 100, supervisor
100, band open, final approver. The approver is SuperUser so the queue can be
exercised; change the approver name in that script and re-run it to hand the
same orders to somebody else.

Afterwards the form's own routing query was run per approver and the routing
discriminates as it should:

| Approver | Documents offered |
|---|---|
| 100 SuperUser | 800003, 800004 |
| 1000001 Shaharan Hossain | none |
| 102 GardenAdmin | none |