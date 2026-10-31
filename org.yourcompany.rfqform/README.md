# Create RFQ From PR - iDempiere 12 Plugin

## Step-by-Step Instructions

### 1. Eclipse Import
1. Open Eclipse with iDempiere workspace
2. File → Import → Existing Projects into Workspace
3. Select D:\idempiere\org.yourcompany.rfqform
4. Click Finish

### 2. Build
1. Project → Clean → Build All
2. Verify no compilation errors

### 3. Deploy
1. Run → Run Configurations → OSGi Framework
2. Add org.yourcompany.rfqform bundle
3. Apply → Run

### 4. Application Dictionary Registration

#### a. Create Form record
- Menu: Application Dictionary → Form
- New record:
  - Name: Create RFQ From PR
  - Classname: org.yourcompany.form.WCreateRFQFromPR
  - IsMultiRecord: N

#### b. Create Menu entry
- Menu: Application Dictionary → Menu
- New record:
  - Name: Create RFQ From PR
  - Action: Form
  - Form: Create RFQ From PR (from step a)
  - SeqNo: (appropriate)

#### c. Role Form Access
- Menu: Application Dictionary → Role
- Select target role
- Form Access tab → Add "Create RFQ From PR"

#### d. Create custom term table (optional)
```sql
CREATE TABLE XX_RFQ_Term (
    XX_RFQ_Term_ID NUMERIC(10) PRIMARY KEY,
    AD_Client_ID NUMERIC(10) NOT NULL,
    AD_Org_ID NUMERIC(10) NOT NULL,
    IsActive CHAR(1) DEFAULT 'Y',
    Created TIMESTAMP DEFAULT NOW(),
    CreatedBy NUMERIC(10),
    Updated TIMESTAMP DEFAULT NOW(),
    UpdatedBy NUMERIC(10),
    Name VARCHAR(60),
    TermsText TEXT
);
```

### 5. Test
1. Login to iDempiere
2. Navigate to Create RFQ From PR form
3. Select Requisition → lines load
4. Select RFQ Topic, Date, Subject (mandatory)
5. Select RFQ Term → Terms and Conditions auto-fill
6. Add lines → OK → RFQ created
7. Standard RfQ window opens on new record

## iDempiere 12 API Assumptions
- WTableDirEditor has setTable(), setColumnName(), getValue(), addValueChangeListener()
- WDateEditor has getValue() returning Date
- SessionManager.getAppDesktop().closeActiveWindow() works
- AEnv.zoom(tableId, recordId) opens standard window
- MAttachment.get(ctx, tableId, recordId) and addEntry(name, InputStream) exist
- ConfirmPanel and Dialog are available in org.adempiere.webui component
- ListModelTable and IDColumn may need org.adempiere.webui.grid package