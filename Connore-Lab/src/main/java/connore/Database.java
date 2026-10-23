package connore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** JDBC assignments 19-23: open, prepare, execute, read, and close. No pool. */
public final class Database {
    private static Properties settings;

    private Database() { }

    public static void configure(Properties values) {
        settings = values;
    }

    public static Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(
            settings.getProperty("DB_URL"), settings.getProperty("DB_USER"),
            settings.getProperty("DB_PASSWORD"));
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("SET time_zone='+00:00'");
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return connection;
        } catch (SQLException exception) {
            close(connection);
            throw exception;
        } finally {
            if (statement != null) statement.close();
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... values)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        try {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        } catch (SQLException exception) {
            statement.close();
            throw exception;
        }
    }

    public static ArrayList<Map<String, Object>> query(Connection connection, String sql, Object... values)
            throws SQLException {
        PreparedStatement statement = null;
        ResultSet result = null;
        try {
            statement = prepare(connection, sql, values);
            result = statement.executeQuery();
            ResultSetMetaData columns = result.getMetaData();
            ArrayList<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (int index = 1; index <= columns.getColumnCount(); index++) {
                    row.put(columns.getColumnLabel(index), result.getObject(index));
                }
                rows.add(row);
            }
            return rows;
        } finally {
            try {
                if (result != null) result.close();
            } finally {
                if (statement != null) statement.close();
            }
        }
    }

    // Method overloading: callers may supply a transaction or request one connection.
    public static ArrayList<Map<String, Object>> query(String sql, Object... values) throws SQLException {
        Connection connection = open();
        try {
            return query(connection, sql, values);
        } finally {
            close(connection);
        }
    }

    public static int update(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = null;
        try {
            statement = prepare(connection, sql, values);
            return statement.executeUpdate();
        } finally {
            if (statement != null) statement.close();
        }
    }

    public static int update(String sql, Object... values) throws SQLException {
        Connection connection = open();
        try {
            return update(connection, sql, values);
        } finally {
            close(connection);
        }
    }

    public static int insert(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = null;
        ResultSet keys = null;
        try {
            statement = prepare(connection, sql, values);
            statement.executeUpdate();
            keys = statement.getGeneratedKeys();
            if (!keys.next()) throw new SQLException("Missing generated ID");
            return keys.getInt(1);
        } finally {
            try {
                if (keys != null) keys.close();
            } finally {
                if (statement != null) statement.close();
            }
        }
    }

    public static int insert(String sql, Object... values) throws SQLException {
        Connection connection = open();
        try {
            return insert(connection, sql, values);
        } finally {
            close(connection);
        }
    }

    public static void close(Connection connection) {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) { }
        }
    }

    public static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    public static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number && ((Number) value).intValue() != 0;
    }

    public static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public static String iso(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant().toString();
        if (value instanceof LocalDateTime) return ((LocalDateTime) value).toInstant(ZoneOffset.UTC).toString();
        return value.toString();
    }
}
