package im.arun.pageindex.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI API client with retry logic and async support.
 * Handles communication with OpenAI's chat completion API.
 */
public class OpenAIClient {
    private static final Logger logger = LoggerFactory.getLogger(OpenAIClient.class);
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final int MAX_RETRIES = 10;
    private static final long BASE_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public OpenAIClient(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : System.getenv("CHATGPT_API_KEY");
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            throw new IllegalArgumentException("OpenAI API key must be provided or set in CHATGPT_API_KEY environment variable");
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(32, 5, TimeUnit.MINUTES))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    /**
     * Synchronous chat completion call.
     */
    public String chat(String model, String prompt) {
        return chat(model, prompt, null);
    }

    /**
     * Synchronous chat completion call with chat history.
     */
    public String chat(String model, String prompt, List<Map<String, String>> chatHistory) {
        ChatResponse response = chatWithFinishReason(model, prompt, chatHistory);
        return response.content;
    }

    /**
     * Chat completion that returns both content and finish reason.
     */
    public ChatResponse chatWithFinishReason(String model, String prompt, List<Map<String, String>> chatHistory) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                List<Map<String, String>> messages = buildMessages(prompt, chatHistory);
                String responseContent = executeRequest(model, messages);

                // Parse response to get content and finish reason
                JsonNode jsonResponse = objectMapper.readTree(responseContent);
                JsonNode choice = jsonResponse.get("choices").get(0);
                String content = choice.get("message").get("content").asText();
                String finishReason = choice.get("finish_reason").asText();

                String status = "length".equals(finishReason) ? "max_output_reached" : "finished";
                return new ChatResponse(content, status);

            } catch (Exception e) {
                logger.error("OpenAI API call failed (attempt {}/{}): {}", attempt + 1, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        long backoff = Math.min(BASE_BACKOFF_MS * (1L << attempt), MAX_BACKOFF_MS);
                        logger.debug("Retrying in {}ms", backoff);
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry wait", ie);
                    }
                } else {
                    throw new RuntimeException("Max retries reached for OpenAI API call", e);
                }
            }
        }
        throw new RuntimeException("Unexpected: exceeded max retries without throwing exception");
    }

    private List<Map<String, String>> buildMessages(String prompt, List<Map<String, String>> chatHistory) {
        List<Map<String, String>> messages = new ArrayList<>();

        if (chatHistory != null && !chatHistory.isEmpty()) {
            messages.addAll(chatHistory);
        }

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        return messages;
    }

    private String executeRequest(String model, List<Map<String, String>> messages) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body";
                throw new IOException("OpenAI API error (HTTP " + response.code() + "): " + errorBody);
            }

            return response.body().string();
        }
    }

    /**
     * Response object containing both content and finish reason.
     */
    public static class ChatResponse {
        public final String content;
        public final String finishReason;

        public ChatResponse(String content, String finishReason) {
            this.content = content;
            this.finishReason = finishReason;
        }
    }
}
