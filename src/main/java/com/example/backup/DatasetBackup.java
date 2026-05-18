package com.example.backup;

import com.ibm.jzos.ZFile;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class DatasetBackup {

    private static final Logger log = Logger.getLogger(DatasetBackup.class);

    private static final DateTimeFormatter DATE_QUALIFIER_FORMAT = DateTimeFormatter.ofPattern("'D'yyMMdd");

    private static final DateTimeFormatter TIME_QUALIFIER_FORMAT = DateTimeFormatter.ofPattern("'T'HHmmss");

    private static final String DEFAULT_CONFIG = "config.yaml";
    private static final String DEFAULT_LOG4J_CONFIG = "log4j.properties";

    private DatasetBackup() {
    }

    public static void main(String[] args) {
        initLog4j();

        String configPath = (args.length >= 1) ? args[0] : DEFAULT_CONFIG;

        BackupConfig config = loadConfig(configPath);
        List<String> backups = config.getBackups();

        if (backups == null || backups.isEmpty()) {
            log.error("No backup entries found in " + configPath);
            System.exit(8);
        }

        LocalDateTime batchTime = LocalDateTime.now();

        log.info("=======================================================");
        log.info("  Dataset Backup - " + backups.size() + " job(s) loaded");
        log.info("  Config file: " + configPath);
        log.info("  Batch time : " + batchTime);
        log.info("=======================================================");

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < backups.size(); i++) {
            String rawSourceDsn = backups.get(i);
            String sourceDsn = normalizeDsn(rawSourceDsn);

            log.info("-------------------------------------------------------");
            log.info("Job " + (i + 1) + " of " + backups.size());
            log.info("-------------------------------------------------------");

            boolean ok = runBackup(sourceDsn, batchTime);

            if (ok) {
                successCount++;
            } else {
                failCount++;
            }
        }

        log.info("=======================================================");
        log.info("  Summary: " + successCount + " succeeded, " + failCount + " failed");
        log.info("=======================================================");

        System.exit(failCount > 0 ? 12 : 0);
    }

    private static void initLog4j() {
        Path log4jPath = Paths.get(DEFAULT_LOG4J_CONFIG);

        if (Files.exists(log4jPath)) {
            PropertyConfigurator.configure(log4jPath.toAbsolutePath().toString());
        } else {
            org.apache.log4j.BasicConfigurator.configure();
            log.warn("log4j.properties not found at " + log4jPath.toAbsolutePath()
                    + ", using basic console logging");
        }
    }

    private static BackupConfig loadConfig(String configPath) {
        Path externalPath = Paths.get(configPath);

        if (Files.exists(externalPath)) {
            log.info("Loading config from file: " + externalPath.toAbsolutePath());

            try (InputStream in = Files.newInputStream(externalPath)) {
                return parseYaml(in, configPath);
            } catch (Exception ex) {
                log.error("Failed to read config file: " + configPath, ex);
                System.exit(8);
            }
        }

        log.error("Config file not found: " + configPath);
        log.error("Usage: java -cp dataset-backup-1.0.0.jar com.example.backup.DatasetBackup [config.yaml]");
        System.exit(8);

        return null;
    }

    private static BackupConfig parseYaml(InputStream in, String source) {
        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(BackupConfig.class, options));
        BackupConfig config = yaml.load(in);

        if (config == null) {
            throw new IllegalStateException("Empty or invalid YAML: " + source);
        }

        return config;
    }

    private static boolean runBackup(String sourceDsn, LocalDateTime batchTime) {
        String targetDsn;

        try {
            validateDsn(sourceDsn, "sourceDsn");
            targetDsn = buildBackupDsn(sourceDsn, batchTime);
            validateDsn(targetDsn, "targetDsn");
        } catch (Exception ex) {
            log.error("Validation failed for source DSN '" + sourceDsn + "': " + ex.getMessage());
            return false;
        }

        return copyDatasetUsingDynalloc(sourceDsn, targetDsn);
    }

    private static boolean copyDatasetUsingDynalloc(String sourceDsn, String targetDsn) {
        String sourceDd = null;
        String targetDd = null;

        ZFile input = null;
        ZFile output = null;

        try {
            log.info("Starting backup: " + sourceDsn + " -> " + targetDsn);

            sourceDd = ZFile.allocDummyDDName();
            targetDd = ZFile.allocDummyDDName();

            log.debug("Allocated dummy DD names: source=" + sourceDd + ", target=" + targetDd);

            /*
             * This follows the successful DynallocCopyDataset-style approach:
             *
             * 1. Allocate source dataset as SHR.
             * 2. Allocate target dataset as NEW CATALOG using LIKE(source).
             * 3. Open both through //DD:xxx and copy record by record.
             */
            log.info("BPXWDYN allocate source...");
            ZFile.bpxwdyn(
                    "alloc fi(" + sourceDd + ") "
                            + "da('" + sourceDsn + "') "
                            + "shr reuse msg(2)");
            log.info("Source allocated successfully.");

            log.info("BPXWDYN allocate target using LIKE(source)...");
            ZFile.bpxwdyn(
                    "alloc fi(" + targetDd + ") "
                            + "da('" + targetDsn + "') "
                            + "like('" + sourceDsn + "') "
                            + "reuse new catalog msg(2)");
            log.info("Target allocated successfully.");

            input = new ZFile("//DD:" + sourceDd, "rb,type=record,noseek");
            output = new ZFile("//DD:" + targetDd, "wb,type=record,noseek");

            if (input.getDsorg() != ZFile.DSORG_PS) {
                throw new IllegalStateException(
                        "Only PS sequential datasets are supported. Source DSN=" + sourceDsn);
            }

            int lrecl = input.getLrecl();

            if (lrecl <= 0) {
                throw new IllegalStateException(
                        "Invalid source LRECL. Source DSN=" + sourceDsn + ", LRECL=" + lrecl);
            }

            log.info("Source LRECL: " + lrecl);
            log.info("Source DSORG: " + input.getDsorg());

            byte[] buffer = new byte[lrecl];
            long recordCount = 0;
            int bytesRead;

            while ((bytesRead = input.read(buffer)) >= 0) {
                output.write(buffer, 0, bytesRead);
                recordCount++;
            }

            output.close();
            output = null;

            input.close();
            input = null;

            log.info("Backup completed successfully.");
            log.info("  Source DSN : " + sourceDsn);
            log.info("  Target DSN : " + targetDsn);
            log.info("  Records    : " + recordCount);
            log.info("  LRECL      : " + lrecl);

            return true;

        } catch (Exception ex) {
            log.error("Backup failed.", ex);
            log.error("  Source DSN : " + sourceDsn);
            log.error("  Target DSN : " + targetDsn);

            closeQuietly(output);
            output = null;

            closeQuietly(input);
            input = null;

            deleteDatasetQuietly(targetDsn);

            return false;

        } finally {
            closeQuietly(output);
            closeQuietly(input);

            freeDdQuietly(sourceDd);
            freeDdQuietly(targetDd);
        }
    }

    private static String buildBackupDsn(String sourceDsn, LocalDateTime batchTime) {
        String dateQualifier = DATE_QUALIFIER_FORMAT.format(batchTime);
        String timeQualifier = TIME_QUALIFIER_FORMAT.format(batchTime);

        String[] qualifiers = sourceDsn.split("\\.");

        if (qualifiers.length < 2) {
            throw new IllegalArgumentException(
                    "Source DSN must have at least 2 qualifiers to build backup DSN. Source DSN=" + sourceDsn);
        }

        /*
         * Backup DSN rule:
         *
         * Source:
         * BDX53.QW.FREESPCE.MEF
         *
         * Target:
         * BDX53.MEF.D260518.Txxxxxx
         *
         * It keeps:
         * - first qualifier: BDX53
         * - last qualifier : MEF / MF2 / MF6
         * - date qualifier : DyyMMdd
         * - time qualifier : THHmmss
         */
        String firstQualifier = qualifiers[0];
        String lastQualifier = qualifiers[qualifiers.length - 1];

        String targetDsn = firstQualifier
                + "."
                + lastQualifier
                + "."
                + dateQualifier
                + "."
                + timeQualifier;

        if (targetDsn.length() > 44) {
            throw new IllegalArgumentException(
                    "Generated target DSN exceeds 44 characters. Target DSN=" + targetDsn);
        }

        return targetDsn;
    }

    private static String normalizeDsn(String dsn) {
        if (dsn == null) {
            return "";
        }

        String value = dsn.trim();

        if (value.startsWith("//'") && value.endsWith("'")) {
            value = value.substring(3, value.length() - 1);
        } else if (value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        } else if (value.startsWith("//")) {
            value = value.substring(2);
        }

        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }

        return value.toUpperCase();
    }

    private static void validateDsn(String dsn, String fieldName) {
        if (dsn == null || dsn.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is empty.");
        }

        if (dsn.length() > 44) {
            throw new IllegalArgumentException(
                    fieldName + " exceeds 44 characters. DSN=" + dsn);
        }

        String[] qualifiers = dsn.split("\\.");

        for (String qualifier : qualifiers) {
            validateQualifier(qualifier, fieldName, dsn);
        }
    }

    private static void validateQualifier(String qualifier, String fieldName, String fullDsn) {
        if (qualifier == null || qualifier.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " contains empty qualifier. DSN=" + fullDsn);
        }

        if (qualifier.length() > 8) {
            throw new IllegalArgumentException(
                    fieldName + " qualifier exceeds 8 characters. Qualifier=" + qualifier + ", DSN=" + fullDsn);
        }

        char first = qualifier.charAt(0);

        if (!isValidFirstDsnCharacter(first)) {
            throw new IllegalArgumentException(
                    fieldName + " qualifier has invalid first character. Qualifier=" + qualifier + ", DSN=" + fullDsn);
        }

        for (int i = 1; i < qualifier.length(); i++) {
            char ch = qualifier.charAt(i);

            if (!isValidDsnCharacter(ch)) {
                throw new IllegalArgumentException(
                        fieldName + " qualifier contains invalid character '" + ch + "'. "
                                + "Qualifier=" + qualifier + ", DSN=" + fullDsn);
            }
        }
    }

    private static boolean isValidFirstDsnCharacter(char ch) {
        return Character.isLetter(ch) || ch == '@' || ch == '#' || ch == '$';
    }

    private static boolean isValidDsnCharacter(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '@' || ch == '#' || ch == '$';
    }

    private static void closeQuietly(ZFile file) {
        if (file != null) {
            try {
                file.close();
            } catch (Exception ex) {
                log.warn("Failed to close ZFile", ex);
            }
        }
    }

    private static void freeDdQuietly(String ddName) {
        if (ddName != null && !ddName.isEmpty()) {
            try {
                ZFile.bpxwdyn("free fi(" + ddName + ") msg(2)");
            } catch (Exception ex) {
                log.warn("Failed to free DD name: " + ddName, ex);
            }
        }
    }

    private static void deleteDatasetQuietly(String dsn) {
        if (dsn == null || dsn.trim().isEmpty()) {
            return;
        }

        String deleteDd = null;

        try {
            deleteDd = ZFile.allocDummyDDName();

            ZFile.bpxwdyn(
                    "alloc fi(" + deleteDd + ") "
                            + "da('" + dsn + "') "
                            + "old msg(2)");

            ZFile.bpxwdyn(
                    "free fi(" + deleteDd + ") delete msg(2)");

            deleteDd = null;

            log.debug("Deleted target dataset after failure: " + dsn);

        } catch (Exception ex) {
            log.warn("Failed to delete target dataset after failure: " + dsn, ex);
        } finally {
            freeDdQuietly(deleteDd);
        }
    }
}