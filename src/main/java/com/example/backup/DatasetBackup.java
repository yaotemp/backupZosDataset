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

    private static final DateTimeFormatter DATE_QUALIFIER_FORMAT =
            DateTimeFormatter.ofPattern("'D'yyMMdd");

    private static final DateTimeFormatter TIME_QUALIFIER_FORMAT =
            DateTimeFormatter.ofPattern("'T'HHmmss");

    private static final String DEFAULT_CONFIG = "config.yaml";
    private static final String DEFAULT_LOG4J_CONFIG = "log4j.properties";

    private DatasetBackup() {
    }

    public static void main(String[] args) {
        // Load log4j config from external file (same directory as the JAR)
        initLog4j();

        String configPath = (args.length >= 1) ? args[0] : DEFAULT_CONFIG;

        BackupConfig config = loadConfig(configPath);
        List<String> backups = config.getBackups();

        if (backups == null || backups.isEmpty()) {
            log.error("No backup entries found in " + configPath);
            System.exit(8);
        }

        log.info("=======================================================");
        log.info("  Dataset Backup - " + backups.size() + " job(s) loaded");
        log.info("  Config file: " + configPath);
        log.info("=======================================================");

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < backups.size(); i++) {
            String dsn = backups.get(i);
            log.info("-------------------------------------------------------");
            log.info("Job " + (i + 1) + " of " + backups.size());
            log.info("-------------------------------------------------------");

            boolean ok = runBackup(dsn);
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

    // ---------------------------------------------------------------
    //  Log4j initialization
    // ---------------------------------------------------------------

    private static void initLog4j() {
        Path log4jPath = Paths.get(DEFAULT_LOG4J_CONFIG);
        if (Files.exists(log4jPath)) {
            PropertyConfigurator.configure(log4jPath.toAbsolutePath().toString());
        } else {
            // Minimal fallback: console only
            org.apache.log4j.BasicConfigurator.configure();
            log.warn("log4j.properties not found at " + log4jPath.toAbsolutePath()
                    + ", using basic console logging");
        }
    }

    // ---------------------------------------------------------------
    //  Config loading
    // ---------------------------------------------------------------

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
        log.error("Usage: java -jar dataset-backup.jar [config.yaml]");
        System.exit(8);

        return null; // unreachable
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

    // ---------------------------------------------------------------
    //  Single backup execution
    // ---------------------------------------------------------------

    private static boolean runBackup(String rawSourceDsn) {
        String sourceDsn = normalizeDsn(rawSourceDsn);
        String targetDsn = null;

        try {
            validateDsn(sourceDsn, "sourceDsn");
            targetDsn = buildTimestampBackupDsn(sourceDsn);
            validateDsn(targetDsn, "targetDsn");
        } catch (Exception ex) {
            log.error("Validation failed for DSN '" + rawSourceDsn + "': " + ex.getMessage());
            return false;
        }

        log.info("Starting backup: " + sourceDsn + " -> " + targetDsn);

        String sourceDd = null;
        String targetDd = null;

        ZFile input = null;
        ZFile output = null;

        try {
            sourceDd = ZFile.allocDummyDDName();
            targetDd = ZFile.allocDummyDDName();

            if (log.isDebugEnabled()) {
                log.debug("Allocated DD names: source=" + sourceDd + ", target=" + targetDd);
            }

            ZFile.bpxwdyn(
                    "alloc fi(" + sourceDd + ") da('" + sourceDsn + "') shr reuse msg(wtp)"
            );
            log.debug("Allocated source dataset: " + sourceDsn);

            ZFile.bpxwdyn(
                    "alloc fi(" + targetDd + ") da('" + targetDsn + "') new catalog msg(wtp) " +
                    "like('" + sourceDsn + "')"
            );
            log.debug("Allocated target dataset: " + targetDsn);

            input = new ZFile("//DD:" + sourceDd, "rb,type=record,noseek");

            if (input.getDsorg() != ZFile.DSORG_PS) {
                throw new IllegalStateException(
                        "Only PS sequential datasets are supported. Source DSN=" + sourceDsn
                );
            }

            int lrecl = input.getLrecl();

            if (lrecl <= 0) {
                throw new IllegalStateException(
                        "Invalid LRECL from source dataset. Source DSN=" + sourceDsn + ", LRECL=" + lrecl
                );
            }

            log.debug("Source LRECL=" + lrecl);

            output = new ZFile("//DD:" + targetDd, "wb,type=record,noseek");

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
            log.error("Backup failed for source DSN: " + sourceDsn, ex);
            log.error("  Source DSN : " + sourceDsn);
            log.error("  Target DSN : " + targetDsn);

            closeQuietly(input);
            closeQuietly(output);

            freeDdQuietly(sourceDd);
            freeDdQuietly(targetDd);

            deleteDatasetQuietly(targetDsn);

            return false;

        } finally {
            closeQuietly(input);
            closeQuietly(output);

            freeDdQuietly(sourceDd);
            freeDdQuietly(targetDd);
        }
    }

    // ---------------------------------------------------------------
    //  DSN helpers
    // ---------------------------------------------------------------

    private static String buildTimestampBackupDsn(String sourceDsn) {
        LocalDateTime now = LocalDateTime.now();

        String dateQualifier = DATE_QUALIFIER_FORMAT.format(now);
        String timeQualifier = TIME_QUALIFIER_FORMAT.format(now);

        String targetDsn = sourceDsn + "." + dateQualifier + "." + timeQualifier;

        if (targetDsn.length() > 44) {
            throw new IllegalArgumentException(
                    "Generated target DSN exceeds 44 characters. Target DSN=" + targetDsn
            );
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
                    fieldName + " exceeds 44 characters. DSN=" + dsn
            );
        }

        String[] qualifiers = dsn.split("\\.");

        for (String qualifier : qualifiers) {
            validateQualifier(qualifier, fieldName, dsn);
        }
    }

    private static void validateQualifier(String qualifier, String fieldName, String fullDsn) {
        if (qualifier == null || qualifier.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " contains empty qualifier. DSN=" + fullDsn
            );
        }

        if (qualifier.length() > 8) {
            throw new IllegalArgumentException(
                    fieldName + " qualifier exceeds 8 characters. Qualifier=" + qualifier + ", DSN=" + fullDsn
            );
        }

        char first = qualifier.charAt(0);

        if (!isValidFirstDsnCharacter(first)) {
            throw new IllegalArgumentException(
                    fieldName + " qualifier has invalid first character. Qualifier=" + qualifier + ", DSN=" + fullDsn
            );
        }

        for (int i = 1; i < qualifier.length(); i++) {
            char ch = qualifier.charAt(i);

            if (!isValidDsnCharacter(ch)) {
                throw new IllegalArgumentException(
                        fieldName + " qualifier contains invalid character '" + ch + "'. " +
                        "Qualifier=" + qualifier + ", DSN=" + fullDsn
                );
            }
        }
    }

    private static boolean isValidFirstDsnCharacter(char ch) {
        return Character.isLetter(ch) || ch == '@' || ch == '#' || ch == '$';
    }

    private static boolean isValidDsnCharacter(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '@' || ch == '#' || ch == '$';
    }

    // ---------------------------------------------------------------
    //  Cleanup helpers
    // ---------------------------------------------------------------

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
                ZFile.bpxwdyn("free fi(" + ddName + ") msg(wtp)");
            } catch (Exception ex) {
                log.warn("Failed to free DD name: " + ddName, ex);
            }
        }
    }

    private static void deleteDatasetQuietly(String dsn) {
        String deleteDd = null;

        try {
            deleteDd = ZFile.allocDummyDDName();

            ZFile.bpxwdyn(
                    "alloc fi(" + deleteDd + ") da('" + dsn + "') old reuse msg(wtp)"
            );

            ZFile.bpxwdyn(
                    "free fi(" + deleteDd + ") delete msg(wtp)"
            );

            log.debug("Deleted partial backup dataset: " + dsn);

        } catch (Exception ex) {
            log.warn("Failed to delete partial backup dataset: " + dsn, ex);
        } finally {
            freeDdQuietly(deleteDd);
        }
    }
}
