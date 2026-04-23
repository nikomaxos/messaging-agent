package com.messagingagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.model.AiAgentConfig;
import com.messagingagent.model.AiMemory;
import com.messagingagent.repository.AiMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiMemoryService {

    private final AiMemoryRepository aiMemoryRepository;
    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;

    /**
     * Asynchronously extracts key takeaways from a conversation and stores them in memory.
     */
    @Async
    public void extractAndStoreMemories(AiAgentConfig config, List<Map<String, String>> conversationHistory) {
        if (conversationHistory.size() < 2) {
            return; // Not enough context to extract memory
        }

        try {
            // Build a prompt that asks the LLM to extract JSON
            String extractionPrompt = """
                    You are an internal AI Memory Manager for the System Admin.
                    Review the following conversation history. If there are any NEW, important key takeaways, 
                    problems solved, configuration changes, or system health observations that should be remembered 
                    long-term, extract them.
                    
                    Return ONLY a JSON array with objects in this format:
                    [
                      {
                        "topic": "Short Topic Name (e.g. Memory Leak, SMPP Config)",
                        "keyPoints": "Detailed explanation of what happened / the solution / the takeaway"
                      }
                    ]
                    
                    If there is nothing new or important to remember, return an empty array: []
                    """;

            // Add the prompt as a system/developer message (using user role for simplicity as some LLMs enforce roles)
            List<Map<String, String>> memoryHistory = new java.util.ArrayList<>(conversationHistory);
            memoryHistory.add(Map.of("role", "user", "content", extractionPrompt));

            log.info("🧠 Triggering background AI Memory extraction...");
            String rawJsonResponse = aiChatService.chat(config, memoryHistory);
            
            // Clean markdown JSON wrapping if present
            rawJsonResponse = rawJsonResponse.replaceAll("```json", "")
                                             .replaceAll("```", "")
                                             .trim();

            List<Map<String, String>> extracted = objectMapper.readValue(rawJsonResponse, new TypeReference<>() {});
            
            if (extracted.isEmpty()) {
                log.info("🧠 No new memories extracted from conversation.");
                return;
            }

            int savedCnt = 0;
            for (Map<String, String> mem : extracted) {
                String topic = mem.get("topic");
                String keyPoints = mem.get("keyPoints");
                if (topic != null && keyPoints != null) {
                    AiMemory memory = AiMemory.builder()
                            .topic(topic)
                            .keyPoints(keyPoints)
                            .build();
                    aiMemoryRepository.save(memory);
                    savedCnt++;
                }
            }
            
            log.info("🧠 Extracted and saved {} new memories.", savedCnt);

        } catch (Exception e) {
            log.error("Failed to extract AI memories: {}", e.getMessage(), e);
        }
    }
}
