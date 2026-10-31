package com.xx.approval.form;

import org.adempiere.webui.factory.IFormFactory;
import org.adempiere.webui.panel.ADForm;

/** Registers POApprovalForm for AD_Form.Classname = com.xx.approval.form.POApprovalForm */
public class POApprovalFormFactory implements IFormFactory {

	@Override
	public ADForm newFormInstance(String formName) {
		if (POApprovalForm.class.getName().equals(formName)) {
			return new POApprovalForm();
		}
		return null;
	}
}