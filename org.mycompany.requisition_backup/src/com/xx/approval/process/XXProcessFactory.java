package com.xx.approval.process;

import java.util.ArrayList;
import java.util.List;

import org.compiere.process.ProcessCall;
import org.compiere.process.SvrProcess;
import org.osgi.service.component.ComponentContext;

import org.adempiere.base.AnnotationBasedProcessFactory;
import org.adempiere.base.IProcessFactory;

/**
 * Makes the {@code @Process} classes of this plugin visible to iDempiere.
 * <p>
 * The annotation based factory that ships with iDempiere scans a fixed list of
 * packages: {@code org.adempiere.process}, {@code org.compiere.process} and so
 * on, all of them iDempiere's own. A plugin's own package is not in that list, so
 * an annotated process in {@code com.xx.approval.process} would simply never be
 * found and {@code AD_Process} would say the class does not exist.
 * <p>
 * This subclass does nothing except name this plugin's package, which is the
 * supported way to extend the list. The scanning, the caching and the lookup of
 * a class by its {@code @Process(name)} are inherited.
 * <p>
 * Registered as an OSGi component through
 * {@code OSGI-INF/com.xx.approval.process.XXProcessFactory.xml}, listed in the
 * {@code Service-Component} header of MANIFEST.MF. The descriptor is used rather
 * than a DS annotation on purpose: this bundle is built as a class folder, and
 * the XML route is the one that is known to work here.
 */
public class XXProcessFactory extends AnnotationBasedProcessFactory implements IProcessFactory {

	@Override
	public void activate(ComponentContext context) throws ClassNotFoundException {
		super.activate(context);
	}

	@Override
	protected String[] getPackages() {
		List<String> list = new ArrayList<String>();
		// this plugin's processes
		list.add("com.xx.approval.process");
		// and iDempiere's own, so that a process moved from this plugin into the
		// distribution keeps working without a second factory
		list.add("org.adempiere.process");
		list.add("org.idempiere.process");
		list.add("org.compiere.process");
		return list.toArray(new String[list.size()]);
	}

	/**
	 * Kept only so the class is referenced from here and the compiler cannot
	 * quietly drop the dependency; the factory resolves the class by name.
	 */
	@SuppressWarnings("unused")
	private static final Class<?> KEEP = SvrProcess.class;
}