# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build fat JAR (includes all dependencies except JZOS, which is provided at runtime)
mvn clean package
# Produces: target/dataset-backup-1.0.0.jar

# Run on z/OS (JZOS library provided by the z/OS JVM).
# -Dfile.encoding=UTF-8 is REQUIRED — the z/OS JVM defaults to EBCDIC (Cp1047),
# which produces garbled console output on USS terminals that expect UTF-8.
java -Dfile.encoding=UTF-8 -cp dataset-backup-1.0.0.jar com.example.backup.DatasetBackup [path/to/config.yaml]
```

There are no tests yet — the project has no test source directory.

## Architecture

Two classes in `com.example.backup`:

- **`DatasetBackup`** (`src/main/java/.../DatasetBackup.java`) — Main entry point. Orchestrates the full workflow: load YAML config, iterate datasets, copy each via JZOS ZFile record-by-record. Each backup job is independent — failure in one does not stop the rest. On failure, attempts to delete the partially-created backup dataset.

- **`BackupConfig`** (`src/main/java/.../BackupConfig.java`) — Simple POJO with a `List<String> backups` field, deserialized from YAML by SnakeYAML.

## Config loading order

1. External file path (CLI argument)
2. Classpath resource (`config.yaml` bundled in JAR)

## DSN naming convention

Backup target DSNs are generated as: `<sourceDsn>.D<yyMMdd>.T<HHmmss>`

Critical constraint: the generated DSN must not exceed **44 characters** (z/OS limit). The suffix adds 16 characters, so source DSNs must be ≤ 28 characters.

## Key dependencies

| Dependency | Scope | Purpose |
|---|---|---|
| `com.ibm.jzos:ibm.jzos:3.1.3.3` | provided | JZOS library — compile stub from Maven Central; real impl provided by z/OS JVM at runtime |
| `org.yaml:snakeyaml:2.2` | compile | YAML config parsing |
| `ch.qos.logback:logback-classic` | compile | Logging (console + rolling file in `logs/` dir) |

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | All backups succeeded |
| 8 | Config error (missing file, no entries, bad args) |
| 12 | One or more backups failed |

## Runtime constraints

- Only **PS (sequential)** datasets are supported — PDS/PDSE not handled.
- The utility compiles anywhere but **only runs on z/OS** where the JZOS library is available on the JVM classpath.
