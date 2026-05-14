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
└── src/main/
    ├── java/com/example/backup/
    │   ├── DatasetBackup.java      # Main entry point
    │   └── BackupConfig.java       # YAML config model
    └── resources/
        └── config.yaml             # Backup job list
```

## Prerequisites

- **Java 11+**
- **Maven 3.6+**
- **IBM z/OS** runtime environment (provides `com.ibm.jzos` at runtime)

## Configuration

Edit `src/main/resources/config.yaml` (bundled in the JAR) or provide an external file:

```yaml
backups:
  - QREF.REPORT.FILE
  - PROD.DATA.MASTER
  - PROD.TRANS.DAILY
```

Each entry is the fully-qualified source dataset name to back up.

## Build

```bash
mvn clean package
```

This produces `target/dataset-backup-1.0.0.jar`.

> **Note:** The `com.ibm.jzos` dependency uses a compile stub from Maven Central (`com.ibm.jzos:ibm.jzos:3.1.3.3`, scope `provided`). It compiles anywhere, but only runs on z/OS where the real JZOS library is provided by the JVM.

## Usage

On z/OS, the JZOS library is already on the JVM classpath, so you only need:

### Using the bundled config (from classpath)

```bash
java -cp dataset-backup-1.0.0.jar com.example.backup.DatasetBackup
```

### Using an external config file

```bash
java -cp dataset-backup-1.0.0.jar com.example.backup.DatasetBackup /path/to/config.yaml
```

The utility searches for the config file in this order:
1. **External file** — the path provided as a command-line argument
2. **Classpath** — the bundled `config.yaml` inside the JAR

## Output

```
=======================================================
  Dataset Backup — 3 job(s) loaded
  Config file: config.yaml
=======================================================

-------------------------------------------------------
Job 1 of 3
-------------------------------------------------------
Backup completed successfully.
  Source DSN : QREF.REPORT.FILE
  Target DSN : QREF.REPORT.FILE.D260514.T143000
  Records    : 1024
  LRECL      : 80

-------------------------------------------------------
Job 2 of 3
-------------------------------------------------------
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

- Each backup job runs independently — a failure in one job does **not** stop the remaining jobs.
- On failure, the utility attempts to **delete the partially-created backup dataset** to avoid leaving incomplete copies.
- All DD names are freed in the `finally` block to prevent resource leaks.

## Constraints

- Only **PS (sequential)** datasets are supported. PDS/PDSE members are not handled.
- The generated backup DSN must not exceed **44 characters** (z/OS limit). Keep source DSN names short enough to accommodate the `.Dyymmdd.Thhmmss` suffix (16 characters).
