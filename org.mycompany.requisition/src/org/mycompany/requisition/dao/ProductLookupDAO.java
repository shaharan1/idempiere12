package org.mycompany.requisition.dao;

import java.util.List;

import org.compiere.util.Env;
import org.mycompany.requisition.service.ProductDefaultService.QuickEntryProduct;
import org.mycompany.requisition.util.DBUtils;

/**
 * Database access for Product defaults and Quick Entry listings.
 */
public class ProductLookupDAO {

    /**
     * Default product category for the tenant.
     */
    public int findDefaultProductCategoryId(int clientId) {
        return DBUtils.queryOne(
                "SELECT M_Product_Category_ID FROM M_Product_Category "
                        + "WHERE AD_Client_ID IN (0,?) AND IsActive='Y' AND IsDefault='Y' "
                        + "ORDER BY AD_Client_ID DESC FETCH FIRST 1 ROW ONLY",
                rs -> rs.getInt(1),
                clientId);
    }

    /**
     * Default UOM for the tenant.
     */
    public int findDefaultUomId(int clientId) {
        return DBUtils.queryOne(
                "SELECT C_UOM_ID FROM C_UOM "
                        + "WHERE AD_Client_ID IN (0,?) AND IsActive='Y' AND IsDefault='Y' "
                        + "ORDER BY AD_Client_ID DESC FETCH FIRST 1 ROW ONLY",
                rs -> rs.getInt(1),
                clientId);
    }

    /**
     * Default Tax Category for the tenant.
     */
    public int findDefaultTaxCategoryId(int clientId) {
        return DBUtils.queryOne(
                "SELECT C_TaxCategory_ID FROM C_TaxCategory "
                        + "WHERE AD_Client_ID IN (0,?) AND IsActive='Y' AND IsDefault='Y' "
                        + "ORDER BY AD_Client_ID DESC FETCH FIRST 1 ROW ONLY",
                rs -> rs.getInt(1),
                clientId);
    }
}
