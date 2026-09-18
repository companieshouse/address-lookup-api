package uk.gov.companieshouse.addresslookup.releasecore.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.*;

/** Small JDBC binding helpers; callers own the transaction. */
public final class Jdbc {
    public static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            bind(p, args);
            p.execute();
        }
    }

    public static void bind(PreparedStatement p, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
    }

    public static Map<String, Object> one(Connection c, String sql, Object... args) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            bind(p, args);
            try (var r = p.executeQuery()) {
                if (!r.next()) return null;
                var m = new HashMap<String, Object>();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                    m.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
                return m;
            }
        }
    }

    public static long count(Connection c, String sql, Object... args) throws SQLException {
        return ((Number) one(c, sql, args).values().iterator().next()).longValue();
    }

}
