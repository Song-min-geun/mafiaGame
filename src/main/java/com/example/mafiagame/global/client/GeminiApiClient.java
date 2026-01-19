package com.example.mafiagame.global.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class GeminiApiClient {

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent}")
    private String apiUrl;

    private final RestClient restClient = RestClient.create();

    public String generateContent(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API Key is missing. Please set 'gemini.api-key' in application.properties.");
            return null;
        }

        try {
            GeminiRequest request = new GeminiRequest(List.of(
                    new GeminiContent(List.of(new GeminiPart(prompt)))));

            GeminiResponse response = restClient.post()
                    .uri(apiUrl + "?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GeminiResponse.class);

            if (response != null && !response.candidates().isEmpty()) {
                return response.candidates().get(0).content().parts().get(0).text();
            } else {
                log.warn("Gemini API returned empty response: {}", response);
            }
        } catch (Exception e) {
            log.error("Failed to call Gemini API. Start server with details to investigate: {}", e.getMessage());
        }
        return null;
    }

    // DTOs
    public record GeminiRequest(List<GeminiContent> contents) {
    }

    public record GeminiContent(List<GeminiPart> parts) {
    }

    public record GeminiPart(String text) {
    }

    public record GeminiResponse(List<Candidate> candidates) {
    }

    public record Candidate(GeminiContent content) {
    }
}
