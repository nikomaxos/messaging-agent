package com.messagingagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Executes File I/O tools safely for the AI Agent.
 */
@Service
@Slf4j
public class AiToolService {

    private static final String REPO_ROOT = "/repo";

    /**
     * Executes the requested tool and returns the string output.
     */
    public String executeTool(String action, String targetPath, String content) {
        if (targetPath == null || targetPath.isBlank()) {
            return "Error: Path is required.";
        }

        try {
            Path safePath = getSafePath(targetPath);

            return switch (action.toLowerCase()) {
                case "view_file" -> viewFile(safePath);
                case "edit_file" -> editFile(safePath, content);
                case "delete_file" -> deleteFile(safePath);
                case "list_dir" -> listDir(safePath);
                default -> "Error: Unknown action '" + action + "'. Supported: view_file, edit_file, delete_file, list_dir.";
            };

        } catch (SecurityException e) {
            return "Security Error: " + e.getMessage();
        } catch (Exception e) {
            return "Execution Error: " + e.getMessage();
        }
    }

    private Path getSafePath(String targetPath) throws SecurityException {
        // Strip out leading windows drives if the AI is confused
        if (targetPath.matches("^[a-zA-Z]:.*")) {
            targetPath = targetPath.substring(2);
        }
        targetPath = targetPath.replace('\\', '/');

        // Resolve against /repo if not already there
        Path path = Paths.get(targetPath);
        if (!path.isAbsolute() || !path.startsWith(REPO_ROOT)) {
            // Strip leading slashes to prevent root injection
            if (targetPath.startsWith("/")) {
                targetPath = targetPath.substring(1);
            }
            path = Paths.get(REPO_ROOT).resolve(targetPath);
        }

        path = path.normalize();

        // Enforce jail
        if (!path.startsWith(REPO_ROOT)) {
            throw new SecurityException("Access denied. Path must be inside " + REPO_ROOT);
        }

        return path;
    }

    private String viewFile(Path path) throws IOException {
        if (!Files.exists(path)) return "Error: File does not exist.";
        if (Files.isDirectory(path)) return "Error: Path is a directory, use list_dir instead.";
        
        long sizeBytes = Files.size(path);
        if (sizeBytes > 500_000) {
            return "Error: File is too large to read (" + sizeBytes + " bytes).";
        }
        
        return Files.readString(path);
    }

    private String editFile(Path path, String content) throws IOException {
        if (content == null) return "Error: Content is required for edit_file.";
        
        // Ensure parent directories exist
        if (path.getParent() != null && !Files.exists(path.getParent())) {
            Files.createDirectories(path.getParent());
        }
        
        Files.writeString(path, content);
        return "Successfully wrote " + content.length() + " characters to " + path.toString();
    }

    private String deleteFile(Path path) throws IOException {
        if (!Files.exists(path)) return "Error: File does not exist.";
        if (Files.isDirectory(path)) return "Error: Cannot delete directories entirely via this tool.";
        
        Files.delete(path);
        return "Successfully deleted " + path.toString();
    }

    private String listDir(Path path) throws IOException {
        if (!Files.exists(path)) return "Error: Directory does not exist.";
        if (!Files.isDirectory(path)) return "Error: Path is a file, use view_file instead.";
        
        try (Stream<Path> stream = Files.list(path)) {
            String files = stream
                    .map(p -> (Files.isDirectory(p) ? "[DIR] " : "      ") + p.getFileName().toString())
                    .collect(Collectors.joining("\n"));
            
            if (files.isEmpty()) return "Directory is empty.";
            return files;
        }
    }
}
