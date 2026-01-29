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
    private volatile Encoding cachedEncoding;
    private volatile String cachedModel;

    public TokenCounter() {
        this.registry = Encodings.newDefaultEncodingRegistry();
        // Pre-cache the default encoding
        this.cachedEncoding = registry.getEncoding(EncodingType.CL100K_BASE);
        this.cachedModel = null;
    }

    /**
     * Count tokens in text for a specific model.
     * Caches the encoding instance to avoid repeated lookups.
     *
     * @param text  The text to count tokens for
     * @param model The model name (e.g., "gpt-4o-2024-11-20")
     * @return Number of tokens
     */
    public int countTokens(String text, String model) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        Encoding encoding = getEncodingCached(model);
        return encoding.countTokens(text);
    }

    private Encoding getEncodingCached(String model) {
        // Fast path: same model as last call (common case)
        if (model != null && model.equals(cachedModel) && cachedEncoding != null) {
            return cachedEncoding;
        }
        if (model == null && cachedModel == null && cachedEncoding != null) {
            return cachedEncoding;
        }

        Encoding encoding = resolveEncoding(model);
        cachedModel = model;
        cachedEncoding = encoding;
        return encoding;
    }

    private Encoding resolveEncoding(String model) {
        // All current OpenAI models use cl100k_base
        return registry.getEncoding(EncodingType.CL100K_BASE);
    }
}
