package main.java.com.djrapitops.plan.database.databases;

import main.java.com.djrapitops.plan.Log;
import main.java.com.djrapitops.plan.api.IPlan;
import main.java.com.djrapitops.plan.api.exceptions.DatabaseInitException;
import main.java.com.djrapitops.plan.database.Database;
import main.java.com.djrapitops.plan.database.tables.*;
import main.java.com.djrapitops.plan.utilities.Benchmark;
import org.apache.commons.dbcp2.BasicDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Class containing main logic for different data related save & load functionality.
 *
 * @author Rsl1122
 * @since 2.0.0
 */
public abstract class SQLDB extends Database {

    private final boolean usingMySQL;

    /**
     * @param plugin
     */
    public SQLDB(IPlan plugin) {
        super(plugin);
        usingMySQL = getName().equals("MySQL");

        versionTable = new VersionTable(this, usingMySQL);
        serverTable = new ServerTable(this, usingMySQL);
        securityTable = new SecurityTable(this, usingMySQL);

        commandUseTable = new CommandUseTable(this, usingMySQL);
        tpsTable = new TPSTable(this, usingMySQL);

        usersTable = new UsersTable(this, usingMySQL);
        userInfoTable = new UserInfoTable(this, usingMySQL);
        actionsTable = new ActionsTable(this, usingMySQL);
        ipsTable = new IPsTable(this, usingMySQL);
        nicknamesTable = new NicknamesTable(this, usingMySQL);
        sessionsTable = new SessionsTable(this, usingMySQL);
        killsTable = new KillsTable(this, usingMySQL);
        worldTable = new WorldTable(this, usingMySQL);
        worldTimesTable = new WorldTimesTable(this, usingMySQL);
    }

    /**
     * Initializes the Database.
     * <p>
     * All tables exist in the database after call to this.
     * Updates Schema to latest version.
     * Converts Unsaved Bukkit player files to database data.
     * Cleans the database.
     *
     * @return Was the Initialization successful.
     */
    @Override
    public void init() throws DatabaseInitException {
        setStatus("Init");
        String benchName = "Init " + getConfigName();
        Benchmark.start(benchName);
        try {
            setupDataSource();
            setupDatabase();
            clean();
        } finally {
            Benchmark.stop("Database", benchName);
            Log.logDebug("Database");
        }
    }

    /**
     * Ensures connection functions correctly and all tables exist.
     * <p>
     * Updates to latest schema.
     *
     * @throws DatabaseInitException if something goes wrong.
     */
    public void setupDatabase() throws DatabaseInitException {
        try {
            boolean newDatabase = isNewDatabase();

            versionTable.createTable();
            createTables();

            if (newDatabase) {
                Log.info("New Database created.");
            }

            int version = getVersion();
            boolean newVersion = version < 8;

            if (newDatabase || newVersion) {
                setVersion(8);
            }

        } catch (SQLException e) {
            throw new DatabaseInitException("Failed to set-up Database", e);
        }
    }

    /**
     * Creates the tables that contain data.
     * <p>
     * Updates table columns to latest schema.
     *
     * @return true if successful.
     */
    private void createTables() throws DatabaseInitException {
        Benchmark.start("Create tables");
        for (Table table : getAllTables()) {
            table.createTable();
        }
        Benchmark.stop("Database", "Create tables");
    }

    /**
     * @return
     */
    public Table[] getAllTables() {
        return new Table[]{
                serverTable, usersTable, userInfoTable, ipsTable,
                nicknamesTable, sessionsTable, killsTable,
                commandUseTable, actionsTable, tpsTable,
                worldTable, worldTimesTable, securityTable
        };
    }

    /**
     * Get all tables except securityTable for removal of user data.
     *
     * @return Tables in the order the data should be removed in.
     */
    public Table[] getAllTablesInRemoveOrder() {
        return new Table[]{
                ipsTable, nicknamesTable, killsTable,
                worldTimesTable, sessionsTable, actionsTable,
                worldTable, userInfoTable, usersTable,
                commandUseTable, tpsTable, securityTable,
                serverTable
        };
    }

    /**
     * Setups the {@link BasicDataSource}
     */
    public abstract void setupDataSource() throws DatabaseInitException;

    /**
     * @throws SQLException
     */
    @Override
    public void close() throws SQLException {
        dataSource.close();
        setStatus("Closed");
        Log.logDebug("Database"); // Log remaining Debug info if present
    }

    /**
     * @return @throws SQLException
     */
    @Override
    public int getVersion() throws SQLException {
        return versionTable.getVersion();
    }

    /**
     * @param version
     * @throws SQLException
     */
    @Override
    public void setVersion(int version) throws SQLException {
        versionTable.setVersion(version);
    }

    @Override
    public boolean isNewDatabase() throws SQLException {
        return versionTable.isNewDatabase();
    }

    /**
     * @param uuid
     * @return
     */
    @Override
    public boolean wasSeenBefore(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            return usersTable.isRegistered(uuid);
        } catch (SQLException e) {
            Log.toLog(this.getClass().getName(), e);
            return false;
        } finally {
            setAvailable();
        }
    }

    public void removeAccount(UUID uuid) throws SQLException {
        if (uuid == null) {
            return;
        }

        try {
            Benchmark.start("Remove Account");
            Log.debug("Database", "Removing Account: " + uuid);

            for (Table t : getAllTablesInRemoveOrder()) {
                if (!(t instanceof UserIDTable)) {
                    continue;
                }

                UserIDTable table = (UserIDTable) t;
                table.removeUser(uuid);
            }
        } finally {
            Benchmark.stop("Database", "Remove Account");
            setAvailable();
        }
    }

    private void clean() throws DatabaseInitException {
        Log.info("Cleaning the database.");
        try {
            tpsTable.clean();
            Log.info("Clean complete.");
        } catch (SQLException e) {
            throw new DatabaseInitException("Database Clean failed", e);
        }
    }

    /**
     * @return
     */
    @Override
    public void removeAllData() throws SQLException {
        setStatus("Clearing all data");
        try {
            for (Table table : getAllTablesInRemoveOrder()) {
                table.removeAllData();
            }
        } finally {
            setAvailable();
        }
    }

    private void setStatus(String status) {
        Log.debug("Database", status);
    }

    public void setAvailable() {
        Log.logDebug("Database");
    }

    public Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    /**
     * Commits changes to the .db file when using SQLite Database.
     * <p>
     * MySQL has Auto Commit enabled.
     */
    @Override
    public void commit(Connection connection) throws SQLException {
        try {
            if (!usingMySQL) {
                connection.commit();
            }
        } finally {
            endTransaction(connection);
        }
    }

    /**
     * Reverts transaction when using SQLite Database.
     * <p>
     * MySQL has Auto Commit enabled.
     */
    public void rollback(Connection connection) throws SQLException {
        try {
            if (!usingMySQL) {
                connection.rollback();
            }
        } finally {
            endTransaction(connection);
        }
    }

    public void endTransaction(Connection connection) throws SQLException {
        connection.close();
    }
}
