package org.yourcompany.form;

import org.adempiere.webui.factory.IFormFactory;
import org.adempiere.webui.panel.IFormController;
import org.zkoss.zk.ui.Component;

public class RFQFormFactory implements IFormFactory {

	@Override
	public IFormController newFormInstance(String formName) {
		if ("Create RFQ From PR".equals(formName) || "org.yourcompany.form.WCreateRFQFromPR".equals(formName)) {
			return new WCreateRFQFromPR();
		}
		return null;
	}
}
