package im.arun.pageindex.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final PageIndexConfig defaultConfig;

    public ConfigLoader() {
        this(null);
    }

    public ConfigLoader(String configPath) {
        this.defaultConfig = loadDefaultConfig(configPath);
    }

    private PageIndexConfig loadDefaultConfig(String configPath) {
        try {
            // Try to load from classpath first
            InputStream resourceStream = getClass().getClassLoader().getResourceAsStream("config.yaml");
            if (resourceStream != null) {
                return yamlMapper.readValue(resourceStream, PageIndexConfig.class);
            }

            // Try to load from file system
            if (configPath != null) {
                Path path = Paths.get(configPath);
                if (Files.exists(path)) {
                    return yamlMapper.readValue(path.toFile(), PageIndexConfig.class);
                }
            }

            // Return default configuration
            logger.warn("No config.yaml found, using default configuration");
            return new PageIndexConfig();
        } catch (IOException e) {
            logger.warn("Failed to load configuration, using defaults: {}", e.getMessage());
            return new PageIndexConfig();
        }
    }

    public PageIndexConfig load(Map<String, Object> userOptions) {
        PageIndexConfig config = copyConfig(defaultConfig);

        if (userOptions == null || userOptions.isEmpty()) {
            return config;
        }

        // Merge user options into config
        userOptions.forEach((key, value) -> {
            try {
                switch (key) {
                    case "model":
                        if (value instanceof String) config.setModel((String) value);
                        break;
                    case "toc_check_page_num":
                    case "tocCheckPageNum":
                        if (value instanceof Integer) config.setTocCheckPageNum((Integer) value);
                        break;
                    case "max_page_num_each_node":
                    case "maxPageNumEachNode":
                        if (value instanceof Integer) config.setMaxPageNumEachNode((Integer) value);
                        break;
                    case "max_token_num_each_node":
                    case "maxTokenNumEachNode":
                        if (value instanceof Integer) config.setMaxTokenNumEachNode((Integer) value);
                        break;
                    case "if_add_node_id":
                    case "addNodeId":
                        config.setAddNodeId(parseBoolean(value));
                        break;
                    case "if_add_node_summary":
                    case "addNodeSummary":
                        config.setAddNodeSummary(parseBoolean(value));
                        break;
                    case "if_add_doc_description":
                    case "addDocDescription":
                        config.setAddDocDescription(parseBoolean(value));
                        break;
                    case "if_add_node_text":
                    case "addNodeText":
                        config.setAddNodeText(parseBoolean(value));
                        break;
                    default:
                        logger.warn("Unknown configuration key: {}", key);
                }
            } catch (Exception e) {
                logger.error("Error setting config key {}: {}", key, e.getMessage());
            }
        });

        return config;
    }

    private boolean parseBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return "yes".equalsIgnoreCase((String) value) || "true".equalsIgnoreCase((String) value);
        }
        return false;
    }

    private PageIndexConfig copyConfig(PageIndexConfig source) {
        PageIndexConfig copy = new PageIndexConfig();
        copy.setModel(source.getModel());
        copy.setTocCheckPageNum(source.getTocCheckPageNum());
        copy.setMaxPageNumEachNode(source.getMaxPageNumEachNode());
        copy.setMaxTokenNumEachNode(source.getMaxTokenNumEachNode());
        copy.setAddNodeId(source.isAddNodeId());
        copy.setAddNodeSummary(source.isAddNodeSummary());
        copy.setAddDocDescription(source.isAddDocDescription());
        copy.setAddNodeText(source.isAddNodeText());
        return copy;
    }
}
