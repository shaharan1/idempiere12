# Document Approval Form — Deployment

## What was built

An iDempiere 12 OSGi plugin form, `AD_Form` 1000103, that lists the requisitions
waiting for the signed-in user and lets that user approve, reject or forward
them.

```
org.mycompany.requisition
└── docapproval
    ├── DocumentApprovalForm.java      ADForm, the three panels
    ├── DocumentApprovalService.java   all SQL and every approval rule
    ├── ApprovalDocument.java          one grid row
    ├── ApprovalHistoryLine.java       one history entry
    └── ApprovalException.java         a decision that cannot be carried out
```

`RequisitionFormFactory` was extended with one line, which is what makes the
form reachable at all.

## What was applied to the database

| Change | Object |
|---|---|
| 5 new columns on the history table | `documenttable`, `documentrecord_id`, `documentno`, `c_doctype_id`, `to_user_id` |
| `xx_process_transaction_id` made optional | was `NOT NULL`, a document approved here has no transaction |
| access level raised to `7` | `AD_Table` 1000117 `XX_Approval_History`, was `3` |
| 3 new `AD_Element` + 5 new `AD_Column` | `DOCUMENTTABLE`, `DOCUMENTRECORD_ID`, `TO_USER_ID` |
| `AD_Form` | 1000103, classname 58 characters of the 60 allowed |
| `AD_Form_Access` | roles 0, 102, 103, 200001, all read/write |
| `AD_Menu` | 1000023, action `F`, under group 1000019 |
| `AD_TreeNodeMM` | tree 10, node 1000023, parent 1000019, seqno 13 |
| sample supervisor mapping | `XX_ReqDocType_Supervisor` id 1000001 |

Scripts, all idempotent, in `C:\Users\Shaharan\AppData\Local\Temp\opencode\sofix`:

```
docapproval_01_history_columns.sql
docapproval_02_form_registration.sql
docapproval_03_sample_mapping.sql
```

## Two facts the form depends on, found in the database

**`M_Requisition` has no amount and no business partner.** The amount is
`SUM(M_RequisitionLine.LineNetAmt)` per requisition and the partner is the
`C_BPartner` on its lines. The form computes both in the query rather than
expecting columns that do not exist.

**The document date column is `DateDoc`, not `DatedDoc`.** Writing `DatedDoc`
makes the query fail outright; the form uses `DateDoc`.

## The sample mapping, and when to drop it

The requisitions in this installation use `C_DocType_ID` 127 *Purchase
Requisition*, while the only mapping row was for 1000000 *Requsition *. The form
would have been correct and permanently empty, which reads as a fault. The
sample mapping fixes that:

```
C_DocType_ID 127, AD_User_ID 100, Supervisor_ID 100,
LowerLimit 0, UpperLimit 0, IsFinalApprover 'Y'
```

`UpperLimit` of 0 means "no upper bound" and a null `LowerLimit` means zero, so
every amount matches. The supervisor is SuperUser so the form can be exercised
immediately; change that one value to hand the same requisitions to somebody
else.

With that in place the form's own query returns 6 requisitions.

## Deploy

### From Eclipse

1. The classes are already compiled into `bin\`. If you edit anything, rebuild
   the project only.
2. Run the iDempiere server from Eclipse. **A restart is required**, because
   `bin` is loaded by the OSGi bundle and the menu tree is cached per session.
3. Log out and back in, so the menu tree is rebuilt.

### Without Eclipse

Drop `org.mycompany.requisition` into the Felix console:

```
bundle install -s file:<path>/org.mycompany.requisition
```

or install the packaged jar through the web UI, then restart the bundle.

### Reset the cache

If a menu item does not appear after a restart, clear the dictionary cache from
**System Admin → Maintenance → Cache** and log out and back in. The menu tree
lives in `AD_TreeNodeMM` and is cached, and `AD_Form` rows are cached by
`MForm`, so both have to be refreshed.

## Use

1. **Menu → Business Process & Approval → Document Approval Form**
2. Set the date range; it opens on the current month. **Reload** applies the
   filters and updates *Total Document*.
3. Tick a document. The Detail, Print, Attachment and Approval History buttons
   work on exactly one ticked document.
4. Choose **Approve** or **Reject**, type a message, optionally pick a
   **Forward To**, and **Submit**.
   * Reject requires a message.
   * Forward is one document at a time.
   * Approve completes the document through the normal iDempiere complete
     action when the mapping marks the approver as final, and otherwise leaves
     it pending for the next supervisor.

## Extending to C_Order and other tables

The service already keys the history on `DocumentTable` plus
`DocumentRecord_ID`, so a second document type needs:

1. A source of documents in the same shape. Add a `listPending` method for
   `C_Order`, whose amount is `C_Order.GrandTotal` and whose partner is
   `C_Order.C_BPartner_ID`, both real columns.
2. A `complete` branch calling `MOrder.processIt(DocAction.ACTION_Complete)`.

Nothing in the form changes: it asks the service, not the table.

## Two traps worth remembering

**`AD_Form.Classname` is `varchar(60)`.** The class name here is 58 characters.
Anything longer will not register.

**A menu without an `AD_TreeNodeMM` row shows nothing**, and a window without
`AD_Window_Access` rows is invisible to every role including System
Administrator. Both were hit in this installation while building the earlier
approval window.