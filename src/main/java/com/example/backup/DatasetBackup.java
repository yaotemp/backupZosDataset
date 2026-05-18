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

    private static final String BACKUP_SECOND_QUALIFIER = "BKUP";

    /*
     * QuickRef report output attributes from JCL:
     *
     * DCB=(RECFM=VBA,LRECL=450,BLKSIZE=6000),
     * SPACE=(CYL,(5,5),RLSE),UNIT=SYSDA
     *
     * In BPXWDYN, we start with the minimal required attributes:
     * RECFM=VBA, LRECL=450, CYL SPACE(5,5), UNIT=SYSDA.
     */
    private static final int QUICKREF_LRECL = 450;

    /*
     * Set this to true if you only want to test allocation and exit before backup
     * jobs.
     * Set this to false to run allocation tests first, then continue with backup
     * jobs.
     */
    private static final boolean ALLOCATION_SELF_TEST_ONLY = false;

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

        log.info("=======================================================");
        log.info("  Dataset Backup - " + backups.size() + " job(s) loaded");
        log.info("  Config file: " + configPath);
        log.info("=======================================================");

        runAllocationSelfTest(backups);

        if (ALLOCATION_SELF_TEST_ONLY) {
            log.info("ALLOCATION_SELF_TEST_ONLY is true. Exiting before backup jobs.");
            System.exit(0);
        }

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

    private static void runAllocationSelfTest(List<String> backups) {
        log.info("=======================================================");
        log.info("  Allocation Self-Test");
        log.info("=======================================================");

        testAllocateAndDelete("BDX53.BKUP.TEST1");

        if (backups != null && !backups.isEmpty()) {
            try {
                String firstSourceDsn = normalizeDsn(backups.get(0));
                String generatedTargetDsn = buildBackupDsn(firstSourceDsn);
                testAllocateAndDelete(generatedTargetDsn);
            } catch (Exception ex) {
                log.error("Allocation self-test failed while building generated target DSN", ex);
            }
        }

        log.info("=======================================================");
        log.info("  Allocation Self-Test Completed");
        log.info("=======================================================");
    }

    private static boolean testAllocateAndDelete(String testDsn) {
        String ddName = null;

        log.info("Testing allocation for DSN: " + testDsn);

        try {
            validateDsn(testDsn, "testDsn");

            ddName = ZFile.allocDummyDDName();

            ZFile.bpxwdyn(
                    "alloc fi(" + ddName + ") "
                            + "da('" + testDsn + "') "
                            + "new catalog msg(2) "
                            + "recfm(VBA) "
                            + "lrecl(" + QUICKREF_LRECL + ") "
                            + "cyl space(5,5) "
                            + "unit(SYSDA)");

            log.info("Allocation self-test succeeded: " + testDsn);

            ZFile.bpxwdyn("free fi(" + ddName + ") delete msg(2)");
            ddName = null;

            log.info("Allocation self-test dataset deleted: " + testDsn);

            return true;

        } catch (Exception ex) {
            log.error("Allocation self-test failed: " + testDsn, ex);
            return false;

        } finally {
            freeDdQuietly(ddName);
        }
    }

    private static boolean runBackup(String rawSourceDsn) {
        String sourceDsn = normalizeDsn(rawSourceDsn);
        String targetDsn;

        try {
            validateDsn(sourceDsn, "sourceDsn");
            targetDsn = buildBackupDsn(sourceDsn);
            validateDsn(targetDsn, "targetDsn");
        } catch (Exception ex) {
            log.error("Validation failed for DSN '" + rawSourceDsn + "': " + ex.getMessage());
            return false;
        }

        log.info("Starting backup: " + sourceDsn + " -> " + targetDsn);

        String targetDd = null;
        boolean targetAllocated = false;

        ZFile input = null;
        ZFile output = null;

        try {
            /*
             * Step 1:
             * Allocate target first.
             * Do not open source before target allocation.
             */
            targetDd = ZFile.allocDummyDDName();

            ZFile.bpxwdyn(
                    "alloc fi(" + targetDd + ") "
                            + "da('" + targetDsn + "') "
                            + "new catalog msg(2) "
                            + "recfm(VBA) "
                            + "lrecl(" + QUICKREF_LRECL + ") "
                            + "cyl space(5,5) "
                            + "unit(SYSDA)");

            targetAllocated = true;
            log.debug("Allocated target dataset: " + targetDsn);

            /*
             * Step 2:
             * Open source after target allocation.
             */
            input = new ZFile("//'" + sourceDsn + "'", "rb,type=record,noseek");
            log.debug("Opened source dataset: " + sourceDsn);

            if (input.getDsorg() != ZFile.DSORG_PS) {
                throw new IllegalStateException(
                        "Only PS sequential datasets are supported. Source DSN=" + sourceDsn);
            }

            int sourceLrecl = input.getLrecl();

            if (sourceLrecl <= 0) {
                throw new IllegalStateException(
                        "Invalid LRECL from source dataset. Source DSN=" + sourceDsn
                                + ", LRECL=" + sourceLrecl);
            }

            if (sourceLrecl != QUICKREF_LRECL) {
                log.warn("Source LRECL is " + sourceLrecl
                        + ", expected QuickRef LRECL=" + QUICKREF_LRECL
                        + ". Source DSN=" + sourceDsn);
            }

            /*
             * Step 3:
             * Open target and copy records.
             */
            output = new ZFile("//DD:" + targetDd, "wb,type=record,noseek");

            byte[] buffer = new byte[sourceLrecl];
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
            log.info("  Source LRECL: " + sourceLrecl);
            log.info("  Target LRECL: " + QUICKREF_LRECL);
            log.info("  Target RECFM: VBA");

            return true;

        } catch (Exception ex) {
            log.error("Backup failed for source DSN: " + sourceDsn, ex);
            log.error("  Source DSN : " + sourceDsn);
            log.error("  Target DSN : " + targetDsn);

            closeQuietly(output);
            output = null;

            closeQuietly(input);
            input = null;

            if (targetAllocated) {
                deleteDatasetQuietly(targetDsn);
            }

            return false;

        } finally {
            closeQuietly(output);
            closeQuietly(input);
            freeDdQuietly(targetDd);
        }
    }

    private static String buildBackupDsn(String sourceDsn) {
        LocalDateTime now = LocalDateTime.now();

        String dateQualifier = DATE_QUALIFIER_FORMAT.format(now);
        String timeQualifier = TIME_QUALIFIER_FORMAT.format(now);

        String[] qualifiers = sourceDsn.split("\\.");

        if (qualifiers.length < 3) {
            throw new IllegalArgumentException(
                    "Source DSN must have at least 3 qualifiers to build backup DSN. Source DSN=" + sourceDsn);
        }

        /*
         * Current backup DSN rule:
         *
         * Source:
         * BDX53.QW.FREESPCE.MEF
         *
         * Target:
         * BDX53.BKUP.FREESPCE.MEF.D260518.Txxxxxx
         */
        StringBuilder builder = new StringBuilder();

        builder.append(qualifiers[0]);
        builder.append(".");
        builder.append(BACKUP_SECOND_QUALIFIER);

        for (int i = 2; i < qualifiers.length; i++) {
            builder.append(".");
            builder.append(qualifiers[i]);
        }

        builder.append(".");
        builder.append(dateQualifier);
        builder.append(".");
        builder.append(timeQualifier);

        String targetDsn = builder.toString();

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
                ZFile.bpxwdyn("free fi(" + ddName + ") msg(wtp)");
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
                    "alloc fi(" + deleteDd + ") da('" + dsn + "') old msg(2)");

            ZFile.bpxwdyn(
                    "free fi(" + deleteDd + ") delete msg(2)");

            deleteDd = null;

            log.debug("Deleted partial backup dataset: " + dsn);

        } catch (Exception ex) {
            log.warn("Failed to delete partial backup dataset: " + dsn, ex);
        } finally {
            freeDdQuietly(deleteDd);
        }
    }
}