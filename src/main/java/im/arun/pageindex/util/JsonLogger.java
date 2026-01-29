package im.arun.pageindex.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-based logger that accumulates log messages and writes them to a JSON file.
 * Equivalent to Python's JsonLogger class.
 */
public class JsonLogger {
    private static final Logger systemLogger = LoggerFactory.getLogger(JsonLogger.class);
    private final Path logPath;
    private final List<Object> logData = new ArrayList<>();
    private final ObjectMapper objectMapper;

    public JsonLogger() {
        this("document");
    }

    public JsonLogger(String documentPath) {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // Extract document name
        String docName = extractDocumentName(documentPath);

        // Create log file name with timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String logFileName = String.format("%s_%s.json", docName, timestamp);

        // Ensure logs directory exists
        try {
            Files.createDirectories(Paths.get("./logs"));
        } catch (IOException e) {
            systemLogger.error("Failed to create logs directory", e);
        }

        this.logPath = Paths.get("./logs", logFileName);
    }

    private String extractDocumentName(String documentPath) {
        if (documentPath == null) {
            return "Untitled";
        }

        Path path = Paths.get(documentPath);
        String filename = path.getFileName().toString();

        // Remove extension
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            filename = filename.substring(0, dotIndex);
        }

        // Sanitize filename (replace invalid characters)
        return filename.replaceAll("[/\\\\]", "-");
    }

    public void info(Object message) {
        log(message);
    }

    public void info(String message, Object... args) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("message", String.format(message, args));
        log(logEntry);
    }

    public void error(Object message) {
        log(message);
    }

    public void error(String message, Object... args) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("level", "ERROR");
        logEntry.put("message", String.format(message, args));
        log(logEntry);
    }

    public void warn(String message) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("level", "WARNING");
        logEntry.put("message", message);
        log(logEntry);
    }

    private void log(Object message) {
        if (message instanceof String) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("message", message);
            logData.add(entry);
        } else if (message instanceof Map) {
            logData.add(message);
        } else {
            logData.add(message);
        }

        // Write to file
        writeToFile();
    }

    private void writeToFile() {
        try {
            objectMapper.writeValue(logPath.toFile(), logData);
        } catch (IOException e) {
            systemLogger.error("Failed to write log file: {}", logPath, e);
        }
    }

    public Path getLogPath() {
        return logPath;
    }
}
