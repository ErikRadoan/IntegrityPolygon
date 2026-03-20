package dev.erikradovan.integritypolygon.core;

import com.google.gson.Gson;
import dev.erikradovan.integritypolygon.api.ModuleStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.regex.Pattern;

/**
 * SQLite-backed module storage implementation.
 */
public class ModuleStorageImpl implements ModuleStorage {

    private static final Pattern TABLE_PATTERN = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final SqliteModuleDatabase db;
    private final String moduleId;
    private final Gson gson = new Gson();

    public ModuleStorageImpl(SqliteModuleDatabase db, String moduleId) {
        this.db = db;
        this.moduleId = moduleId;
    }

    @Override
    public String qualifyTable(String tableName) {
        if (tableName == null || !TABLE_PATTERN.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
        return moduleId.replace('-', '_') + "__" + tableName;
    }

    @Override
    public void ensureTable(String tableName, String tableDefinitionSql) {
        String fullTable = qualifyTable(tableName);
        String sql = "CREATE TABLE IF NOT EXISTS " + fullTable + " " + tableDefinitionSql;
        withConnection(conn -> {
            try (var st = conn.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }

    @Override
    public int update(String sql, Object... params) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void query(String sql, ResultSetHandler handler, Object... params) {
        withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (var rs = ps.executeQuery()) {
                    handler.handle(rs);
                }
            }
            return null;
        });
    }

    @Override
    public <T> T withConnection(SqlFunction<T> function) {
        try (Connection conn = db.openConnection()) {
            return function.apply(conn);
        } catch (Exception e) {
            throw new RuntimeException("Module storage operation failed for " + moduleId, e);
        }
    }

    private void bind(PreparedStatement ps, Object... params) throws Exception {
        if (params == null) return;
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            if (p == null) {
                ps.setNull(idx, Types.NULL);
            } else if (p instanceof Integer v) {
                ps.setInt(idx, v);
            } else if (p instanceof Long v) {
                ps.setLong(idx, v);
            } else if (p instanceof Double v) {
                ps.setDouble(idx, v);
            } else if (p instanceof Float v) {
                ps.setFloat(idx, v);
            } else if (p instanceof Boolean v) {
                ps.setInt(idx, v ? 1 : 0);
            } else if (p instanceof String v) {
                ps.setString(idx, v);
            } else {
                ps.setString(idx, gson.toJson(p));
            }
        }
    }
}
