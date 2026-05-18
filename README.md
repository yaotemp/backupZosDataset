# Dataset Backup Utility

A z/OS sequential dataset backup utility that copies PS datasets to timestamped backup copies using IBM JZOS.

## How It Works

The utility reads a list of source dataset names from `config.yaml` and creates a backup copy of each one. The backup target DSN is generated automatically by appending a date/time suffix:

```
<sourceDsn>.D<yyMMdd>.T<HHmmss>
```

**Example:**

| Source DSN | Backup DSN |
|---|---|
| `QREF.REPORT.FILE` | `QREF.REPORT.FILE.D260514.T143000` |
| `PROD.DATA.MASTER` | `PROD.DATA.MASTER.D260514.T143000` |

## Project Structure

```
backupzfile/
├── pom.xml
├── README.md
├── config.yaml                 # Backup job list (external, not bundled in JAR)
├── log4j.properties            # Log4j config (external, not bundled in JAR)
└── src/main/java/com/example/backup/
    ├── DatasetBackup.java      # Main entry point
    └── BackupConfig.java       # YAML config model
```

## Prerequisites

- **Java 11+**
- **Maven 3.6+**
- **IBM z/OS** runtime environment (provides `com.ibm.jzos` at runtime)

## Configuration

### config.yaml

Edit `config.yaml` in the same directory as the JAR:

```yaml
backups:
  - QREF.REPORT.FILE
  - PROD.DATA.MASTER
  - PROD.TRANS.DAILY
```

Each entry is the fully-qualified source dataset name to back up.

### log4j.properties

Logging is configured via `log4j.properties` in the same directory as the JAR. By default it logs to both console and a daily rolling file under `./logs/`. Override the log directory at runtime with `-Dlog.dir=/custom/path`.

## Build

```bash
mvn clean package
```

This produces `target/dataset-backup-1.0.0.jar` (fat JAR with all dependencies bundled).

> **Note:** The `com.ibm.jzos` dependency uses a compile stub from Maven Central (`com.ibm.jzos:ibm.jzos:3.1.3.3`, scope `provided`). It compiles anywhere, but only runs on z/OS where the real JZOS library is provided by the JVM.

## Deployment

Copy these three files to your z/OS deployment directory:

```
/deploy/
├── dataset-backup-1.0.0.jar   # from target/
├── config.yaml                # edit to list your datasets
└── log4j.properties           # edit to change log settings
```

## Usage

Run from the directory containing `config.yaml` and `log4j.properties`:

### Using the default config (config.yaml in current directory)

```bash
java -jar dataset-backup-1.0.0.jar
```

### Using a custom config file path

```bash
java -jar dataset-backup-1.0.0.jar /path/to/my-config.yaml
```

> **Encoding:** The utility forces `System.out`/`System.err` to UTF-8 at startup, so it works correctly on z/OS USS terminals without extra JVM flags.

## Output

```
=======================================================
  Dataset Backup - 3 job(s) loaded
  Config file: config.yaml
=======================================================
-------------------------------------------------------
Job 1 of 3
-------------------------------------------------------
Starting backup: QREF.REPORT.FILE -> QREF.REPORT.FILE.D260514.T143000
Backup completed successfully.
  Source DSN : QREF.REPORT.FILE
  Target DSN : QREF.REPORT.FILE.D260514.T143000
  Records    : 1024
  LRECL      : 80
-------------------------------------------------------
Job 2 of 3
-------------------------------------------------------
Starting backup: PROD.DATA.MASTER -> PROD.DATA.MASTER.D260514.T143001
Backup completed successfully.
  Source DSN : PROD.DATA.MASTER
  Target DSN : PROD.DATA.MASTER.D260514.T143001
  Records    : 5000
  LRECL      : 200
=======================================================
  Summary: 2 succeeded, 0 failed
=======================================================
```

## Exit Codes

| Code | Meaning |
|------|---------|
| `0`  | All backup jobs completed successfully |
| `8`  | Configuration error (missing config, no entries, bad arguments) |
| `12` | One or more backup jobs failed |

## Error Handling

- Each backup job runs independently - a failure in one job does **not** stop the remaining jobs.
- On failure, the utility attempts to **delete the partially-created backup dataset** to avoid leaving incomplete copies.
- All DD names are freed in the `finally` block to prevent resource leaks.

## Constraints

- Only **PS (sequential)** datasets are supported. PDS/PDSE members are not handled.
- The generated backup DSN must not exceed **44 characters** (z/OS limit). Keep source DSN names short enough to accommodate the `.Dyymmdd.Thhmmss` suffix (16 characters).
