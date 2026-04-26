# MySQL Connector/J for Minecraft

Oracle's official [MySQL Connector/J](https://github.com/mysql/mysql-connector-j) repackaged as a Bukkit/Spigot/Paper plugin and a Fabric/Forge/NeoForge mod.

> **Heads up:** CraftBukkit/Spigot/Paper have shipped MySQL Connector/J on the server's parent classloader since 1.4 (the "ebeans" era). For the **default JDBC path** (`Class.forName("com.mysql.cj.jdbc.Driver")` / `DriverManager.getConnection`) the bundled driver wins — installing this mod doesn't change that. This mod is primarily useful on **Fabric**, **NeoForge**, and Bukkit forks that strip the bundled driver. If you specifically want this mod's connector instead of the bundled one (e.g. for a newer connector version), use the public API — see [Using this mod's connector via the public API](#using-this-mods-connector-via-the-public-api) below.

## What's in the jar

`com.mysql:mysql-connector-j:9.1.0` plus minimal loader stubs for Spigot, Forge 1.12, Forge 1.13–1.16, Forge 1.17–1.20, NeoForge 1.21+, and Fabric. No relocation — `com.mysql.cj.*` classes stay at canonical paths so `Class.forName("com.mysql.cj.jdbc.Driver")` finds them. `META-INF/services/java.sql.Driver` is preserved so the driver auto-registers with `DriverManager`.

The `mysql:mysql-connector-java` → `com.mysql:mysql-connector-j` rename happened in connector-j 8.x. The class name `com.mysql.cj.jdbc.Driver` works across both old and new artifact coords, so the runtime probe is stable.

## Compatibility

| Loader | MC versions | Notes |
|---|---|---|
| Bukkit / Spigot / Paper / Folia / Purpur | 1.8 → current | usually unnecessary — driver is server-bundled |
| Fabric | 1.16.1 → current | needs Fabric Loader 0.14+ |
| Forge | 1.12 → 1.20 | universal jar, no Mixins |
| NeoForge | 1.21 → current | drop into `mods/` |

Java 8+ required.

## Connection-string gotchas worth knowing

The 5.1.x and 8+ drivers default to behaviours that almost always need overriding for real deployments:

- `useSSL=true` is the default — fails against any MySQL server without a public CA-signed cert. Set `useSSL=false` for plaintext, or add `verifyServerCertificate=false` if you have a self-signed cert.
- `caching_sha2_password` (MySQL 8 default) needs `allowPublicKeyRetrieval=true` for unencrypted-channel auth, otherwise the handshake fails with `Public Key Retrieval is not allowed`.
- `rewriteBatchedStatements=true` folds JDBC batch INSERTs into multi-row form on the wire. Without it, batch operations are still N round-trips.

Typical URL:
```
jdbc:mysql://host:3306/db?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true
```

MariaDB speaks the MySQL protocol on the wire, so this driver works against MariaDB too. If you want the MariaDB-native connector instead, it's a separate driver and not in scope here.

## Versioning

The jar version tracks connector-j one-to-one. `9.1.0+2026-04-25` ships connector-j 9.1.0; the suffix is the build date. A scheduled GitHub Action checks Maven Central daily and bumps automatically.

## Using this mod's connector via the public API

If you specifically want this mod's connector instead of the bundled one — to upgrade past a stale fork's bundled driver, or to test a newer connector version — your plugin softdepends on this holder and calls `MinecraftMysqlJdbc.connect(url, props)`:

```yaml
# in your plugin.yml
softdepend: [mysql-jdbc]
```

```java
Properties props = new Properties();
props.setProperty("user", "grim");
props.setProperty("password", "...");
Connection c;
try {
    c = MinecraftMysqlJdbc.connect("jdbc:mysql://host:3306/db?useSSL=false", props);
} catch (NoClassDefFoundError | ClassNotFoundException notInstalled) {
    c = DriverManager.getConnection("jdbc:mysql://host:3306/db?useSSL=false", props);
}
```

`MinecraftMysqlJdbc` wraps a child-first `URLClassLoader` pointing at this jar, parented to the platform classloader so `com.mysql.cj.*` must come from this jar (not the bundled chain). The returned `Connection` is a standard `java.sql.Connection` — keep your casts to `java.sql.*` interfaces; the impl class lives in the child-first classloader and won't down-cast across the boundary.

API surface (all static):

| Method | Returns |
|---|---|
| `connect(String url)` | open `Connection` through this driver |
| `connect(String url, Properties props)` | as above with props |
| `driver()` | the `java.sql.Driver` instance |
| `driverVersion()` | connector-j version (e.g. `"9.1"`) |
| `eagerInit()` | warm the classloader at plugin enable |
| `shutdown()` | release file handles on plugin disable |

Grim Anti-Cheat's MySQL backend uses this API automatically when this holder is installed.

## License

GPL-2.0 with the [Universal FOSS Exception 1.0](https://oss.oracle.com/licenses/universal-foss-exception/). The exception makes the driver compatible with any FOSS-licensed Minecraft plugin or mod (it's the same license Oracle ships with the Connector/J binary). Modrinth's license tag shows GPL-2.0-only because the SPDX list doesn't have a single identifier for the GPL-2 + FOSS Exception combo — full text in [`LICENSE`](https://github.com/Axionize/minecraft-mysql-jdbc/blob/main/LICENSE).

---

Issues, source: [GitHub](https://github.com/Axionize/minecraft-mysql-jdbc).
