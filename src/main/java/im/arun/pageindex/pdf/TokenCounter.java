package im.arun.pageindex.pdf;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token counter using JTokkit (Java port of tiktoken).
 * Provides exact token counting compatible with OpenAI models.
 */
public class TokenCounter {
    private static final Logger logger = LoggerFactory.getLogger(TokenCounter.class);
    private final EncodingRegistry registry;

    public TokenCounter() {
        this.registry = Encodings.newDefaultEncodingRegistry();
    }

    /**
     * Count tokens in text for a specific model.
     * 
     * @param text  The text to count tokens for
     * @param model The model name (e.g., "gpt-4o-2024-11-20")
     * @return Number of tokens
     */
    public int countTokens(String text, String model) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        try {
            // Try to get encoding for the specific model
            Encoding encoding = getEncodingForModel(model);
            return encoding.countTokens(text);
        } catch (Exception e) {
            logger.warn("Failed to count tokens for model {}: {}", model, e.getMessage());
            // Fallback to cl100k_base encoding (used by GPT-4 and GPT-3.5-turbo)
            Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);
            return encoding.countTokens(text);
        }
    }

    private Encoding getEncodingForModel(String model) {
        // Map model names to encodings
        if (model == null) {
            return registry.getEncoding(EncodingType.CL100K_BASE);
        }

        // GPT-4 and GPT-4o models use cl100k_base
        if (model.startsWith("gpt-4")) {
            return registry.getEncoding(EncodingType.CL100K_BASE);
        }

        // GPT-3.5-turbo uses cl100k_base
        if (model.startsWith("gpt-3.5")) {
            return registry.getEncoding(EncodingType.CL100K_BASE);
        }

        // Fallback to cl100k_base
        return registry.getEncoding(EncodingType.CL100K_BASE);
    }
}
