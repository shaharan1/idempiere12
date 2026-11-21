package org.mycompany.requisition.util;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * Thin helper around the iDempiere DB API (prepared statements only).
 * Used by the DAO classes so that statement / result set handling is written once.
 */
public final class DBUtils {

    private DBUtils() {
    }

    /** Maps the current row of a result set to an object. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** AD_Client_ID of the logged in tenant. */
    public static int clientId() {
        return Env.getAD_Client_ID(Env.getCtx());
    }

    /** Runs a SELECT and maps every row. Never returns null. */
    public static <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
        List<T> out = new ArrayList<T>();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = DB.prepareStatement(sql, null);
            DB.setParameters(ps, params);
            rs = ps.executeQuery();
            while (rs.next())
                out.add(mapper.map(rs));
        } catch (Exception ex) {
            throw new RuntimeException(sql, ex);
        } finally {
            DB.close(rs, ps);
        }
        return out;
    }

    /** Runs a SELECT and maps the first row, or returns null when there is none. */
    public static <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = DB.prepareStatement(sql, null);
            DB.setParameters(ps, params);
            rs = ps.executeQuery();
            return rs.next() ? mapper.map(rs) : null;
        } catch (Exception ex) {
            throw new RuntimeException(sql, ex);
        } finally {
            DB.close(rs, ps);
        }
    }

    /** First column of the first row as text; empty string when no row or NULL. */
    public static String queryString(String sql, Object... params) {
        String v = queryOne(sql, rs -> rs.getString(1), params);
        return v == null ? "" : v;
    }

    /** First column of the first row as number; null when no row or NULL. */
    public static BigDecimal queryBigDecimal(String sql, Object... params) {
        return queryOne(sql, rs -> rs.getBigDecimal(1), params);
    }
}
