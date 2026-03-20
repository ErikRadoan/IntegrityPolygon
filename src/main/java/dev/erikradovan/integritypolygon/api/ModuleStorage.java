package dev.erikradovan.integritypolygon.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Module-scoped data storage API on top of the shared SQLite database.
 */
public interface ModuleStorage {

    @FunctionalInterface
    interface SqlFunction<T> {
        T apply(Connection connection) throws Exception;
    }

    @FunctionalInterface
    interface ResultSetHandler {
        void handle(ResultSet rs) throws Exception;
    }

    String qualifyTable(String tableName);

    void ensureTable(String tableName, String tableDefinitionSql);

    int update(String sql, Object... params);

    void query(String sql, ResultSetHandler handler, Object... params);

    <T> T withConnection(SqlFunction<T> function);
}
