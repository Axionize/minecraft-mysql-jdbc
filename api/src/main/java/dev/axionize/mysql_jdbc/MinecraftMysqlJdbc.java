package dev.axionize.mysql_jdbc;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Public API for accessing the MySQL Connector/J driver bundled with this
 * holder mod, bypassing parent-first classloader delegation that would
 * otherwise resolve to the server's bundled connector-j.
 *
 * <h2>Default path (use this 99% of the time)</h2>
 *
 * On Bukkit/Spigot/Paper, mysql-connector-j lives on the server's parent
 * classloader, so the standard JDBC entry points just work:
 *
 * <pre>{@code
 * try (Connection c = DriverManager.getConnection("jdbc:mysql://host/db", user, pw)) {
 *     // ... uses the SERVER's bundled driver ...
 * }
 * }</pre>
 *
 * Bundled connector-j is generally fine for typical workloads. Install
 * this holder only on Fabric/NeoForge (where vanilla MC ships no JDBC
 * drivers) or on Bukkit forks that have stripped the bundled driver.
 *
 * <h2>Workaround path: explicitly use this holder's driver</h2>
 *
 * You only need the API on this class when you want a connector-j version
 * NEWER than what the server bundles. Paper 1.21.11 bundles connector-j
 * 9.2.0; this holder ships 9.1.0+ (or whatever the latest auto-bump is).
 * For features only in newer connector versions, or to avoid bugs in older
 * bundled versions, use this API.
 *
 * <p>Your plugin softdepends on this holder so its classes are on your
 * plugin's classloader graph:
 *
 * <pre>
 * # in plugin.yml
 * softdepend: [minecraft-mysql-jdbc]
 * </pre>
 *
 * Then call directly:
 *
 * <pre>{@code
 * Connection c;
 * try {
 *     c = MinecraftMysqlJdbc.connect("jdbc:mysql://host/db?useSSL=false", props);
 * } catch (NoClassDefFoundError | ClassNotFoundException notInstalled) {
 *     c = DriverManager.getConnection("jdbc:mysql://host/db?useSSL=false", props);
 * }
 * }</pre>
 *
 * <h2>Caveats</h2>
 *
 * <ul>
 * <li>Standard {@code java.sql.*} interfaces only. Down-casting to
 *     {@code com.mysql.cj.jdbc.JdbcConnection} or other connector-internal
 *     types will throw {@link ClassCastException} because the impl class
 *     lives in this holder's child-first classloader.</li>
 * <li>{@link java.sql.DriverManager#getConnection} returns the bundled
 *     driver, not this one. Use {@link #connect} directly.</li>
 * <li>Same {@code allowPublicKeyRetrieval=true} / {@code useSSL=false} /
 *     {@code rewriteBatchedStatements=true} URL params apply as for any
 *     connector-j use against MySQL 8 servers.</li>
 * </ul>
 */
public final class MinecraftMysqlJdbc {

    private static final Logger LOG = Logger.getLogger("MinecraftMysqlJdbc");
    private static final Object LOCK = new Object();

    private static volatile URLClassLoader childFirst;
    private static volatile Driver driver;
    private static volatile String driverVersion;

    private MinecraftMysqlJdbc() {}

    /** Open a connection through this holder's bundled driver. */
    public static Connection connect(String url) throws SQLException {
        return connect(url, new Properties());
    }

    /** Open a connection through this holder's bundled driver. */
    public static Connection connect(String url, Properties properties) throws SQLException {
        Driver d = driver();
        Connection c = d.connect(url, properties == null ? new Properties() : properties);
        if (c == null) {
            throw new SQLException("MinecraftMysqlJdbc: driver did not accept URL: " + url);
        }
        return c;
    }

    /** The {@link Driver} bundled with this holder, child-first loaded. */
    public static Driver driver() throws SQLException {
        Driver d = driver;
        if (d != null) return d;
        synchronized (LOCK) {
            if (driver != null) return driver;
            initLocked();
            return driver;
        }
    }

    /**
     * Connector/J version this holder ships (e.g. {@code "9.1.0"}).
     * Memoized after the first call.
     */
    public static String driverVersion() throws SQLException {
        String v = driverVersion;
        if (v != null) return v;
        synchronized (LOCK) {
            if (driverVersion != null) return driverVersion;
            Driver d = driver();
            driverVersion = d.getMajorVersion() + "." + d.getMinorVersion();
            return driverVersion;
        }
    }

    /** Eagerly construct the holder's classloader + driver so failures
     *  surface at plugin enable rather than the first JDBC call. */
    public static void eagerInit() {
        try {
            driver();
        } catch (Throwable t) {
            LOG.severe("eagerInit failed: " + t);
        }
    }

    /** Release the child-first classloader and forget the cached Driver.
     *  Optional — call from plugin/mod disable to release file handles. */
    public static void shutdown() {
        synchronized (LOCK) {
            driver = null;
            driverVersion = null;
            URLClassLoader cl = childFirst;
            childFirst = null;
            if (cl != null) {
                try { cl.close(); } catch (IOException ignore) {}
            }
        }
    }

    // ---------- internals ----------

    private static void initLocked() throws SQLException {
        try {
            URL ourJar = MinecraftMysqlJdbc.class
                    .getProtectionDomain().getCodeSource().getLocation();
            if (ourJar == null) {
                throw new SQLException(
                        "MinecraftMysqlJdbc: cannot locate this holder's jar URL");
            }
            ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
            URLClassLoader cf = new ChildFirstURLClassLoader(new URL[]{ourJar}, parent);
            Class<?> dc = Class.forName("com.mysql.cj.jdbc.Driver", true, cf);
            Driver d = (Driver) dc.getDeclaredConstructor().newInstance();
            childFirst = cf;
            driver = d;
            LOG.info("MinecraftMysqlJdbc: initialised driver from " + ourJar);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw new SQLException("MinecraftMysqlJdbc: init failed: " + t, t);
        }
    }

    private static final class ChildFirstURLClassLoader extends URLClassLoader {
        ChildFirstURLClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (name.startsWith("java.") || name.startsWith("javax.")
                            || name.startsWith("sun.") || name.startsWith("jdk.")) {
                        c = super.loadClass(name, false);
                    } else {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            c = super.loadClass(name, false);
                        }
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
