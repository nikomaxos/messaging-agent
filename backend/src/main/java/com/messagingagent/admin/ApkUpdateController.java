package com.messagingagent.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@Slf4j
public class ApkUpdateController {

    private static final String APK_DIR = "/tmp/updates";
    private static final String APK_FILE_NAME = "update.apk";

    public ApkUpdateController() {
        File dir = new File(APK_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    @PostMapping("/api/apk/upload")
    public ResponseEntity<String> uploadApk(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File is empty");
        }
        if (!file.getOriginalFilename().endsWith(".apk")) {
            return ResponseEntity.badRequest().body("File must be an APK");
        }
        try {
            Path path = Paths.get(APK_DIR, APK_FILE_NAME);
            Files.write(path, file.getBytes());
            // Save the original filename so we can extract the version from it
            String origName = file.getOriginalFilename();
            Files.writeString(Paths.get(APK_DIR, "apk-meta.txt"), origName != null ? origName : "unknown");
            log.info("New APK uploaded successfully: {}", origName);
            return ResponseEntity.ok("APK uploaded successfully");
        } catch (IOException e) {
            log.error("Failed to upload APK", e);
            return ResponseEntity.internalServerError().body("Failed to save APK");
        }
    }

    @GetMapping("/api/apk/info")
    public ResponseEntity<?> getApkInfo() {
        File file = new File(APK_DIR, APK_FILE_NAME);
        if (!file.exists()) {
            return ResponseEntity.ok(java.util.Map.of("exists", false));
        }
        String filename = "unknown";
        try {
            Path metaPath = Paths.get(APK_DIR, "apk-meta.txt");
            if (metaPath.toFile().exists()) {
                filename = Files.readString(metaPath).trim();
            }
        } catch (IOException ignored) {}

        return ResponseEntity.ok(java.util.Map.of(
            "exists", true,
            "filename", filename,
            "sizeBytes", file.length(),
            "lastModified", new java.util.Date(file.lastModified()).toString()
        ));
    }

    @GetMapping("/api/public/apk/version")
    public ResponseEntity<?> getApkVersion() {
        try {
            Path metaPath = Paths.get(APK_DIR, "apk-meta.txt");
            if (!metaPath.toFile().exists()) {
                return ResponseEntity.ok(java.util.Map.of("available", false));
            }
            String filename = Files.readString(metaPath).trim();
            // Parse version from filename like "MessagingAgent-1.0.74.apk"
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+)")
                .matcher(filename);
            String version = m.find() ? m.group(1) : null;
            if (version == null) {
                return ResponseEntity.ok(java.util.Map.of("available", false));
            }
            return ResponseEntity.ok(java.util.Map.of(
                "available", true,
                "versionName", version,
                "filename", filename
            ));
        } catch (IOException e) {
            return ResponseEntity.ok(java.util.Map.of("available", false));
        }
    }

    @GetMapping("/api/public/apk/download")
    public ResponseEntity<Resource> downloadApk() {
        File file = new File(APK_DIR, APK_FILE_NAME);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"update.apk\"")
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .contentLength(file.length())
                .body(resource);
    }

    private static final String GUARDIAN_FILE_NAME = "guardian.apk";

    @PostMapping("/api/apk/upload-guardian")
    public ResponseEntity<String> uploadGuardian(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty() || !file.getOriginalFilename().endsWith(".apk")) {
            return ResponseEntity.badRequest().body("File must be an APK and not empty");
        }
        try {
            Path path = Paths.get(APK_DIR, GUARDIAN_FILE_NAME);
            Files.write(path, file.getBytes());
            String origName = file.getOriginalFilename();
            Files.writeString(Paths.get(APK_DIR, "guardian-meta.txt"), origName != null ? origName : "unknown");
            log.info("New Guardian APK uploaded successfully: {}", origName);
            return ResponseEntity.ok("Guardian APK uploaded successfully");
        } catch (IOException e) {
            log.error("Failed to upload Guardian APK", e);
            return ResponseEntity.internalServerError().body("Failed to save Guardian APK");
        }
    }

    @GetMapping("/api/public/guardian/version")
    public ResponseEntity<?> getGuardianVersion() {
        try {
            Path metaPath = Paths.get(APK_DIR, "guardian-meta.txt");
            if (!metaPath.toFile().exists()) {
                return ResponseEntity.ok(java.util.Map.of("available", false));
            }
            String filename = Files.readString(metaPath).trim();
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+)")
                .matcher(filename);
            String version = m.find() ? m.group(1) : null;
            if (version == null) {
                return ResponseEntity.ok(java.util.Map.of("available", false));
            }
            return ResponseEntity.ok(java.util.Map.of(
                "available", true,
                "versionName", version,
                "filename", filename
            ));
        } catch (IOException e) {
            return ResponseEntity.ok(java.util.Map.of("available", false));
        }
    }

    @GetMapping("/api/public/guardian/download")
    public ResponseEntity<Resource> downloadGuardian() {
        File file = new File(APK_DIR, GUARDIAN_FILE_NAME);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"guardian.apk\"")
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .contentLength(file.length())
                .body(resource);
    }
}
