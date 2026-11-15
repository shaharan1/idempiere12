package org.mycompany.requisition;

import org.adempiere.webui.component.Combobox;
import org.adempiere.webui.component.Datebox;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.panel.ADForm;
import org.compiere.util.CLogger;
import org.compiere.util.Env;

import bsh.org.objectweb.asm.Label;

public class WGoodsReceiptForm extends ADForm{

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(WGoodsReceiptForm.class);

	private static final int MAX_PO = 300;
	private static final String CSS_VERSION = "g1";

	private final int clientId = Env.getAD_Client_ID(Env.getCtx());
	private final int orgId = Env.getAD_Org_ID(Env.getCtx());
	
	
	
	// header
		private final Label lblOrg = new Label();
		private Combobox cbDocType;
		private Combobox cbWarehouse;
		private Combobox cbLocator;
		private final Datebox dtMove = new Datebox();
		private final Textbox txtRef = new Textbox();
		private final Textbox txtDesc = new Textbox();
		private final Button btnAttach = new Button("Attachment");
		private final Label lblAttach = new Label();
		private final List<Media> attachments = new ArrayList<Media>();
}
