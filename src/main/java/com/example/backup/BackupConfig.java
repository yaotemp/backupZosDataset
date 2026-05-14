package com.example.backup;

import java.util.List;

/**
 * Root configuration model mapped from config.yaml.
 */
public class BackupConfig {

    private List<String> backups;

    public BackupConfig() {
    }

    public List<String> getBackups() {
        return backups;
    }

    public void setBackups(List<String> backups) {
        this.backups = backups;
    }
}
