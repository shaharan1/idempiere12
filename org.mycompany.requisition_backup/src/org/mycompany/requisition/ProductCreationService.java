package org.mycompany.requisition;

import java.util.Properties;
import java.util.logging.Level;

import org.compiere.model.MProduct;
import org.compiere.model.MSequence;
import org.compiere.model.X_M_Product;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Trx;

/**
 * Creates a new iDempiere product through the standard {@link MProduct} model.
 * <p>
 * Only the business name is mandatory for the user. Every other field follows
 * the agreed priority:
 *
 * <pre>
 * 1. value the user entered
 * 2. configured iDempiere default (IsDefault of the master data)
 * 3. standard model default (MProduct.setInitialDefaults)
 * 4. database default
 * 5. leave empty only when the column allows it
 * </pre>
 *
 * A user value is never overwritten by a default, and the product is written
 * with {@link MProduct#save()} so the standard iDempiere validation, audit
 * columns and business rules apply. The result is a normal M_Product record
 * that opens and behaves in the standard Product Window.
 */
public class ProductCreationService {

	/** Custom M_Product column that marks a product as created by this form. */
	public static final String COL_QUICK_ENTRY = "XX_QUICKENTRY";

	private static final CLogger log = CLogger.getCLogger(ProductCreationService.class);

	/** Error that is already phrased for the end user. */
	public static class ProductException extends Exception {
		private static final long serialVersionUID = 1L;

		public ProductException(String message) {
			super(message);
		}

		public ProductException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** Values collected from the form. A null Integer / empty String means "not entered". */
	public static class ProductInput {
		public String name;
		/** Optional product code. Ignored: the code always comes from the sequence. */
	public String value;
		public String description;
		public Integer productCategoryId;
		public Integer uomId;
		public Integer taxCategoryId;
		public Integer gsmId;
		public String productType;
		public Boolean sold;
		public Boolean purchased;
		public Boolean stocked;
		public Boolean active;
	}

	/** What was actually created, for the success message on the form. */
	public static class ProductResult {
		private final int id;
		private final String value;
		private final String name;

		ProductResult(int id, String value, String name) {
			this.id = id;
			this.value = value;
			this.name = name;
		}

		public int getId() {
			return id;
		}

		public String getValue() {
			return value;
		}

		public String getName() {
			return name;
		}
	}
/** Resolves the configured iDempiere master data defaults. */
	private final ProductDefaultService defaults;

	/**
	 * Resolved on every use instead of being captured once, so the record is
	 * written with the tenant and organization of the request that triggered it.
	 */
	private Properties ctx() {
		return Env.getCtx();
	}

	public ProductCreationService(Properties ctx, ProductDefaultService defaults) {
		this.defaults = defaults;
	}

	/**
	 * Creates the product inside one transaction. On any failure everything is
	 * rolled back, so no half finished product is left behind.
	 */
	public ProductResult create(ProductInput input) throws ProductException {
		if (input == null)
			throw new ProductException("No product data was entered.");

		String name = trim(input.name);
		if (name == null)
			throw new ProductException("Product Name is required.");

		// iDempiere refuses to store a record without an organization, so resolve
		// one instead of failing with a bare "Organization" fill-mandatory error
		// only when the tenant really has none
		String trxName = Trx.createTrxName("QuickProduct");
		Trx trx = Trx.get(trxName, true);
		try {
			String value = resolveValue(trxName);
			int uomId = resolveUOM(input.uomId, trxName);
			int categoryId = resolveCategory(input.productCategoryId, trxName);
			int taxCategoryId = resolveTaxCategory(input.taxCategoryId, trxName);

			MProduct product = new MProduct(ctx(), 0, trxName);

			// values the user entered
			product.setName(name);
			product.setValue(value);
			if (trim(input.description) != null)
				product.setDescription(trim(input.description));
			product.setC_UOM_ID(uomId);
			product.setM_Product_Category_ID(categoryId);
			product.setC_TaxCategory_ID(taxCategoryId);
			if (input.productType != null && trim(input.productType) != null)
				product.setProductType(trim(input.productType));
			if (input.sold != null)
				product.setIsSold(input.sold.booleanValue());
			if (input.purchased != null)
				product.setIsPurchased(input.purchased.booleanValue());
			if (input.stocked != null)
				product.setIsStocked(input.stocked.booleanValue());
			if (input.active != null)
				product.setIsActive(input.active.booleanValue());

			// custom column: the generated X_M_Product of this installation does
			// not know the custom GSM column, so it is set through the generic
			// public PO API instead of an invented setter
			if (input.gsmId != null && input.gsmId.intValue() > 0) {
				if (!defaults.isValidGSM(input.gsmId.intValue(), trxName))
					throw new ProductException("The selected GSM is not valid.");
				product.set_ValueOfColumnReturningBoolean("XX_GSM_ID", input.gsmId);
			}

			int orgId = resolveOrgId(trx.getTrxName());
			product.setAD_Org_ID(orgId);

			// marks the product as "created by Quick Product Entry" so the form can
			// list exactly its own products later on
			if (defaults.hasQuickEntryMarker())
				product.set_ValueOfColumnReturningBoolean(COL_QUICK_ENTRY, "Y");

			if (!product.save())
				throw new ProductException(errorOr("Unable to create product."));

			ProductResult result = new ProductResult(product.get_ID(), product.getValue(),
					product.getName());
			trx.commit();
			return result;
		} catch (ProductException e) {
			trx.rollback();
			throw e;
		} catch (RuntimeException e) {
			trx.rollback();
			log.log(Level.SEVERE, "Product creation failed", e);
			throw new ProductException("Product creation failed.", e);
		} finally {
			trx.close();
		}
	}

	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
	//  default resolution
	// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	/**
	 * Product code: the user value, otherwise derived from the name. The code is
	 * backed by a unique index on (client, value), so it is always set and the
	 * derived form is checked for uniqueness as well.
	 */
	/**
	 * Updates an existing product with the same field rules that
	 * {@link #create(ProductInput)} uses, so an edit can never store a value the
	 * creation would have rejected.
	 *
	 * @param id    M_Product_ID of the product to change
	 * @param input the values from the form; the same "empty falls back to the
	 *              default" rule applies
	 */
	public ProductResult update(int id, ProductInput input) throws ProductException {
		if (input == null)
			throw new ProductException("No product data was entered.");
		if (id <= 0)
			throw new ProductException("No product is selected for editing.");

		String name = trim(input.name);
		if (name == null)
			throw new ProductException("Product Name is required.");

		String trxName = Trx.createTrxName("QuickProductUpdate");
		Trx trx = Trx.get(trxName, true);
		try {
			MProduct product = new MProduct(ctx(), id, trxName);
			if (!product.load(trxName, null))
				throw new ProductException("Product " + id + " was not found.");
			if (product.getAD_Client_ID() != Env.getAD_Client_ID(ctx()))
				throw new ProductException("Product " + id + " belongs to another tenant.");

			// the code is deliberately left untouched on an update: it is owned by
			// the iDempiere M_Product document sequence and must not drift away
			// from that series
			product.setName(name);
			if (trim(input.description) != null)
				product.setDescription(trim(input.description));
			product.setC_UOM_ID(resolveUOM(input.uomId, trxName));
			product.setM_Product_Category_ID(resolveCategory(input.productCategoryId, trxName));
			product.setC_TaxCategory_ID(resolveTaxCategory(input.taxCategoryId, trxName));
			if (input.productType != null && trim(input.productType) != null)
				product.setProductType(trim(input.productType));
			if (input.sold != null)
				product.setIsSold(input.sold.booleanValue());
			if (input.purchased != null)
				product.setIsPurchased(input.purchased.booleanValue());
			if (input.stocked != null)
				product.setIsStocked(input.stocked.booleanValue());
			if (input.active != null)
				product.setIsActive(input.active.booleanValue());

			if (input.gsmId != null && input.gsmId.intValue() > 0) {
				if (!defaults.isValidGSM(input.gsmId.intValue(), trxName))
					throw new ProductException("The selected GSM is not valid.");
				product.set_ValueOfColumnReturningBoolean("XX_GSM_ID", input.gsmId);
			}

			if (!product.save())
				throw new ProductException(errorOr("Unable to update product."));

			ProductResult result = new ProductResult(product.get_ID(), product.getValue(),
					product.getName());
			trx.commit();
			return result;
		} catch (ProductException e) {
			trx.rollback();
			throw e;
		} catch (RuntimeException e) {
			trx.rollback();
			log.log(Level.SEVERE, "Product update failed", e);
			throw new ProductException("Product update failed.", e);
		} finally {
			trx.close();
		}
	}

	/**
	 * Deletes a product of this tenant that was created by this form.
	 *
	 * @param id M_Product_ID to delete
	 * @return true when the record was removed
	 * @throws ProductException when the record is missing, belongs to another
	 *                          tenant, or is still referenced somewhere
	 */
	public boolean delete(int id) throws ProductException {
		if (id <= 0)
			throw new ProductException("No product is selected for deleting.");

		String trxName = Trx.createTrxName("QuickProductDelete");
		Trx trx = Trx.get(trxName, true);
		try {
			MProduct product = new MProduct(ctx(), id, trxName);
			if (!product.load(trxName, null))
				throw new ProductException("Product " + id + " was not found.");
			if (product.getAD_Client_ID() != Env.getAD_Client_ID(ctx()))
				throw new ProductException("Product " + id + " belongs to another tenant.");
			if (!defaults.hasQuickEntryMarker() || !"Y".equals(product.get_ValueAsString(COL_QUICK_ENTRY)))
				throw new ProductException("Product " + id + " was not created by this form, "
						+ "so it cannot be deleted here.");

			if (!product.delete(false))
				throw new ProductException(errorOr("Unable to delete product " + id + "."));

			trx.commit();
			return true;
		} catch (ProductException e) {
			trx.rollback();
			throw e;
		} catch (RuntimeException e) {
			trx.rollback();
			log.log(Level.SEVERE, "Product delete failed", e);
			throw new ProductException("Unable to delete the product.", e);
		} finally {
			trx.close();
		}
	}

	/**
	 * Determines the organization the new record belongs to.
	 * <p>
	 * The order follows what the user themselves chose:
	 *
	 * <ol>
	 * <li>the organization of the running session</li>
	 * <li>the organization stored in the user preference of the login screen</li>
	 * <li>the first organization the current role is allowed to access</li>
	 * <li>the first active organization of the current tenant</li>
	 * </ol>
	 *
	 * Only when the tenant has no organization at all - the System tenant - does
	 * this fail, because such a record can never be stored.
	 */
	private int resolveOrgId(String trxName) throws ProductException {
		int sessionOrg = Env.getAD_Org_ID(ctx());
		if (sessionOrg > 0)
			return sessionOrg;

		int clientId = Env.getAD_Client_ID(ctx());

		Integer preferred = preferenceOrg();
		if (preferred != null && preferred.intValue() > 0 && orgBelongsToClient(preferred.intValue(), clientId))
			return preferred.intValue();

		int roleOrg = firstOrgForRole();
		if (roleOrg > 0)
			return roleOrg;

		int tenantOrg = DB.getSQLValue(trxName,
				"SELECT MIN(o.AD_Org_ID) FROM AD_Org o WHERE o.AD_Client_ID=? AND o.IsActive='Y'",
				Integer.valueOf(clientId));
		if (tenantOrg > 0)
			return tenantOrg;

		throw new ProductException("No organization is available. This is the System tenant, which has no "
				+ "organization. Switch to a tenant such as GardenWorld and create the product again.");
	}

	/** Organization picked on the login screen of this user, if any. */
	private Integer preferenceOrg() {
		int userId = Env.getAD_User_ID(ctx());
		if (userId <= 0)
			return null;
		try {
			String value = DB.getSQLValueString(null,
					"SELECT MAX(p.value) FROM AD_Preference p WHERE p.AD_User_ID=? AND p.Attribute='Organization' "
							+ "AND p.AD_Window_ID IS NULL AND p.PreferenceFor='W'",
					Integer.valueOf(userId));
			if (value == null || value.trim().length() == 0)
				return null;
			return Integer.valueOf(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private boolean orgBelongsToClient(int orgId, int clientId) {
		return orgId == clientId || DB.getSQLValue(null,
				"SELECT COUNT(*) FROM AD_Org o WHERE o.AD_Org_ID=? AND o.AD_Client_ID=? AND o.IsActive='Y'",
				Integer.valueOf(orgId), Integer.valueOf(clientId)) > 0;
	}

	/** First organization the role of the session is allowed to work in. */
	private int firstOrgForRole() {
		int roleId = Env.getAD_Role_ID(ctx());
		int clientId = Env.getAD_Client_ID(ctx());
		if (roleId <= 0)
			return 0;
		// AD_Org_ID=0 is the virtual system organization, it can never own a record
		return DB.getSQLValue(null,
				"SELECT MIN(o.AD_Org_ID) FROM AD_Role_OrgAccess ra "
						+ "INNER JOIN AD_Org o ON o.AD_Org_ID=ra.AD_Org_ID AND o.IsActive='Y' "
						+ "WHERE ra.AD_Role_ID=? AND ra.IsActive='Y' AND o.AD_Client_ID IN (0,?) AND o.AD_Org_ID>0",
				Integer.valueOf(roleId), Integer.valueOf(clientId));
	}

	/**
	 * Product code of a new product. It always comes from the iDempiere document
	 * sequence of the tenant (DocumentNo_M_Product) - the same place the standard
	 * Product Window takes its codes from - so every code is a unique number and
	 * never collides with the record id.
	 */
	private String resolveValue(String trxName) throws ProductException {
		String generated = nextSequenceValue(trxName);
		if (generated == null || generated.trim().length() == 0)
			throw new ProductException("Could not generate a Product Code from the M_Product sequence. "
					+ "Please create the product in the standard Product Window.");

		if (valueExists(generated, trxName))
			throw new ProductException("Product Code " + generated + " is already used. "
					+ "Please create the product in the standard Product Window.");
		return generated;
	}

	/**
	 * Next value of the iDempiere document sequence of M_Product for the current
	 * tenant. Falls back to nothing when the sequence cannot be used, so the
	 * caller can ask the user for a code instead of writing a broken one.
	 */
	private String nextSequenceValue(String trxName) {
		try {
			String value = MSequence.getDocumentNo(Env.getAD_Client_ID(ctx()), "M_Product", trxName);
			return value == null ? null : value.trim();
		} catch (RuntimeException e) {
			log.log(Level.WARNING, "Cannot read the M_Product document sequence", e);
			return null;
		}
	}

	private int resolveUOM(Integer entered, String trxName) throws ProductException {
		if (entered != null && entered.intValue() > 0) {
			if (!defaults.isValidUOM(entered.intValue(), trxName))
				throw new ProductException("UOM is invalid.");
			return entered.intValue();
		}
		int fallback = defaults.getDefaultUOM(trxName);
		if (fallback <= 0)
			throw new ProductException("No UOM available. Please select a UOM.");
		return fallback;
	}

	private int resolveCategory(Integer entered, String trxName) throws ProductException {
		if (entered != null && entered.intValue() > 0) {
			if (!defaults.isValidProductCategory(entered.intValue(), trxName))
				throw new ProductException("Product Category is invalid.");
			return entered.intValue();
		}
		int fallback = defaults.getDefaultProductCategory(trxName);
		if (fallback <= 0)
			throw new ProductException("No Product Category available. Please select a category.");
		return fallback;
	}

	private int resolveTaxCategory(Integer entered, String trxName) throws ProductException {
		if (entered != null && entered.intValue() > 0) {
			if (!defaults.isValidTaxCategory(entered.intValue(), trxName))
				throw new ProductException("Tax Category is invalid.");
			return entered.intValue();
		}
		int fallback = defaults.getDefaultTaxCategory(trxName);
		if (fallback <= 0)
			throw new ProductException("No Tax Category available. Please select a tax category.");
		return fallback;
	}

	/** Standard iDempiere master data convention for a key derived from a name. */
	/** True when the value is already used by a product of this client. */
	private boolean valueExists(String value, String trxName) {
		return DB.getSQLValue(trxName,
				"SELECT COUNT(*) FROM M_Product WHERE AD_Client_ID=? AND UPPER(Value)=UPPER(?)",
				Integer.valueOf(Env.getAD_Client_ID(ctx())), value) > 0;
	}

	private String errorOr(String fallback) {
		String error = CLogger.retrieveErrorString(null);
		if (error == null || error.trim().length() == 0)
			return fallback;
		return error;
	}

	private static String trim(String value) {
		if (value == null)
			return null;
		String trimmed = value.trim();
		return trimmed.length() == 0 ? null : trimmed;
	}
}