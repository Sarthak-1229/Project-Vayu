package com.vayu.app.ai;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GeminiService {
    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "llama-3.3-70b-versatile";

    private final String apiKey;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Chat history stored as OpenAI-format message objects
    private final List<JsonObject> chatHistory = new ArrayList<>();
    
    // Latest analysis context for enriching chat responses
    private String analysisContext = "";

    public interface OutputListener {
        void onResponse(String result);
        void onError(String error);
    }

    public GeminiService(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Set the latest analysis context so the chatbot can reference it.
     */
    public void setAnalysisContext(String context) {
        this.analysisContext = (context != null) ? context : "";
    }

    /**
     * Sends a chat message, keeping history for context.
     */
    public void sendChat(String text, OutputListener listener) {
        // Add user message to history
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", text);
        chatHistory.add(userMsg);

        String systemPrompt = "You are Vayu, a friendly AI air quality assistant. Keep answers short, fun, and helpful. "
                + "When answering health or air quality questions, use the user's actual data if available. "
                + "Focus on actionable respiratory health advice.";

        // Inject analysis context if available
        if (!analysisContext.isEmpty()) {
            systemPrompt += "\n\nThe user's latest air quality analysis:\n" + analysisContext;
        }

        execute(buildPayload(systemPrompt, chatHistory), new OutputListener() {
            @Override
            public void onResponse(String result) {
                // Save assistant response to history
                JsonObject assistantMsg = new JsonObject();
                assistantMsg.addProperty("role", "assistant");
                assistantMsg.addProperty("content", result);
                chatHistory.add(assistantMsg);

                listener.onResponse(result);
            }

            @Override
            public void onError(String error) {
                // Rollback user msg on error
                chatHistory.remove(chatHistory.size() - 1);
                listener.onError(error);
            }
        });
    }

    /**
     * One-shot request for analyzing air quality trends.
     * Now receives structured stats from AqiAnalysis rather than raw JSON.
     */
    public void analyzeTrends(String structuredPrompt, OutputListener listener) {
        String systemPrompt = "You are an expert health and environmental AI specializing in respiratory health. "
                + "Analyze the provided air quality data and give a clear, actionable health assessment. "
                + "Don't use markdown formatting, just plain text. Be specific about health risks and protective measures.";

        List<JsonObject> messages = new ArrayList<>();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", structuredPrompt);
        messages.add(userMsg);

        execute(buildPayload(systemPrompt, messages), listener);
    }

    private JsonObject buildPayload(String systemPrompt, List<JsonObject> messages) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", MODEL);

        JsonArray messagesArray = new JsonArray();

        // System message first
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messagesArray.add(sysMsg);

        // Then all conversation messages
        for (JsonObject msg : messages) {
            messagesArray.add(msg);
        }

        payload.add("messages", messagesArray);
        payload.addProperty("temperature", 0.7);
        payload.addProperty("max_tokens", 1024);

        return payload;
    }

    private void execute(JsonObject jsonPayload, OutputListener listener) {
        RequestBody body = RequestBody.create(jsonPayload.toString(), MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handler.post(() -> listener.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String resBody = response.body().string();
                if (response.isSuccessful()) {
                    try {
                        JsonObject root = gson.fromJson(resBody, JsonObject.class);
                        String text = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString();
                        handler.post(() -> listener.onResponse(text));
                    } catch (Exception e) {
                        handler.post(() -> listener.onError("Failed to parse AI response."));
                    }
                } else {
                    // Parse the actual error message from Groq's API response
                    String errorMsg;
                    try {
                        JsonObject errRoot = gson.fromJson(resBody, JsonObject.class);
                        String apiMessage = errRoot.getAsJsonObject("error").get("message").getAsString();
                        errorMsg = "API Error " + response.code() + ": " + apiMessage;
                    } catch (Exception e) {
                        errorMsg = "API Error: " + response.code();
                    }
                    final String msg = errorMsg;
                    handler.post(() -> listener.onError(msg));
                }
            }
        });
    }
}
