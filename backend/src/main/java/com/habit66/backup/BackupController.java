package com.habit66.backup;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.habit66.common.ApiException;

/** 관리자: 지금 백업 만들기(자동 백업은 매일 새벽 BackupService 가 한다). */
@RestController
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    @PostMapping("/api/v1/admin/backup")
    public BackupService.Result backup() {
        try {
            return backups.backupNow();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BACKUP_FAILED", "백업을 만들지 못했어요: " + e.getMessage());
        }
    }
}
