// src/main/java/com/somdiproy/lambda/suggestions/service/NovaInvokerService.java
package com.somdiproy.lambda.suggestions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for invoking Amazon Nova models via Bedrock
 */
public class NovaInvokerService {
    
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NovaInvokerService.class);
    
    private final BedrockRuntimeClient bedrockClient;
    private final ObjectMapper objectMapper;
    
    // Nova Premier pricing (per 1M tokens)
    private static final double INPUT_TOKEN_COST = 0.0008; // $0.80 per 1M input tokens
    private static final double OUTPUT_TOKEN_COST = 0.0032; // $3.20 per 1M output tokens
    
    public NovaInvokerService(String region) {
        this.bedrockClient = BedrockRuntimeClient.builder()
            .region(software.amazon.awssdk.regions.Region.of(region))
            .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Invoke Nova model with text prompt
     */
    public NovaResponse invokeNova(String modelId, String prompt, int maxTokens, 
                                   double temperature, double topP) {
        try {
            // Build request payload for Nova models
            Map<String, Object> requestBody = new HashMap<>();
            
            // Nova-specific message format
            List<Map<String, Object>> messages = List.of(
            	    Map.of(
            	        "role", "user",
            	        "content", List.of(
            	            Map.of("text", prompt)
            	        )
            	    )
            	);
            
            requestBody.put("messages", messages);
            // FIXED: Removed max_tokens and temperature from root level
            
            // Nova-specific inference configuration
            // All model parameters must be inside inferenceConfig only
            Map<String, Object> inferenceConfig = new HashMap<>();
            inferenceConfig.put("max_tokens", maxTokens);
            inferenceConfig.put("temperature", temperature);
            // Note: top_p is not supported in Nova models
            requestBody.put("inferenceConfig", inferenceConfig);
            
            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.debug("Nova API Request: {}", requestJson);
            
            // Create Bedrock request
            InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(modelId)
                .body(SdkBytes.fromUtf8String(requestJson))
                .contentType("application/json")
                .accept("application/json")
                .build();
            
            // Execute request
            InvokeModelResponse response = bedrockClient.invokeModel(request);
            String responseBody = response.body().asUtf8String();
            log.debug("Nova API Response: {}", responseBody);
            
            // Parse Nova response
            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
            
            // Extract content from Nova response format
            String responseText = extractResponseText(responseMap);
            
            // Extract token usage
            Map<String, Object> usage = (Map<String, Object>) responseMap.get("usage");
            int inputTokens = usage != null ? (Integer) usage.getOrDefault("input_tokens", 0) : 0;
            int outputTokens = usage != null ? (Integer) usage.getOrDefault("output_tokens", 0) : 0;
            
            // Calculate cost
            double cost = calculateCost(inputTokens, outputTokens);
            
            log.info("Nova {} invocation successful - Tokens: {}, Cost: ${:.6f}", 
                    modelId, inputTokens + outputTokens, cost);
            
            return NovaResponse.builder()
                .responseText(responseText)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .estimatedCost(cost)
                .modelId(modelId)
                .successful(true)
                .timestamp(System.currentTimeMillis())
                .build();
            
        } catch (Exception e) {
            log.error("Failed to invoke Nova model: " + e.getMessage(), e);
            return NovaResponse.builder()
                .successful(false)
                .errorMessage("Failed to invoke Nova: " + e.getMessage())
                .modelId(modelId)
                .timestamp(System.currentTimeMillis())
                .build();
        }
    }
    
    /**
     * Extract response text from Nova response format
     */
    private String extractResponseText(Map<String, Object> responseMap) {
        // Nova response format: {"output": {"message": {"content": [{"text": "..."}]}}}
        try {
            Map<String, Object> output = (Map<String, Object>) responseMap.get("output");
            if (output != null) {
                Map<String, Object> message = (Map<String, Object>) output.get("message");
                if (message != null) {
                    List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
                    if (content != null && !content.isEmpty()) {
                        return (String) content.get(0).get("text");
                    }
                }
            }
            
            // Fallback: try direct text field
            return (String) responseMap.getOrDefault("text", "No response text found");
            
        } catch (Exception e) {
            log.warn("Error extracting response text, using fallback: " + e.getMessage());
            return responseMap.toString(); // Last resort
        }
    }
    
    /**
     * Calculate estimated cost based on token usage
     */
    private double calculateCost(int inputTokens, int outputTokens) {
        double inputCost = (inputTokens / 1_000_000.0) * INPUT_TOKEN_COST;
        double outputCost = (outputTokens / 1_000_000.0) * OUTPUT_TOKEN_COST;
        return inputCost + outputCost;
    }
    
    /**
     * Response model for Nova invocations
     */
    public static class NovaResponse {
        private String responseText;
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        private double estimatedCost;
        private String modelId;
        private boolean successful;
        private long timestamp;
        private String errorMessage;
        private Map<String, Object> metadata = new HashMap<>();
        
        // Constructors
        public NovaResponse() {}
        
        private NovaResponse(Builder builder) {
            this.responseText = builder.responseText;
            this.inputTokens = builder.inputTokens;
            this.outputTokens = builder.outputTokens;
            this.totalTokens = builder.totalTokens;
            this.estimatedCost = builder.estimatedCost;
            this.modelId = builder.modelId;
            this.successful = builder.successful;
            this.timestamp = builder.timestamp;
            this.errorMessage = builder.errorMessage;
            this.metadata = builder.metadata;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String responseText;
            private int inputTokens;
            private int outputTokens;
            private int totalTokens;
            private double estimatedCost;
            private String modelId;
            private boolean successful;
            private long timestamp;
            private String errorMessage;
            private Map<String, Object> metadata = new HashMap<>();
            
            public Builder responseText(String responseText) { this.responseText = responseText; return this; }
            public Builder inputTokens(int inputTokens) { this.inputTokens = inputTokens; return this; }
            public Builder outputTokens(int outputTokens) { this.outputTokens = outputTokens; return this; }
            public Builder totalTokens(int totalTokens) { this.totalTokens = totalTokens; return this; }
            public Builder estimatedCost(double estimatedCost) { this.estimatedCost = estimatedCost; return this; }
            public Builder modelId(String modelId) { this.modelId = modelId; return this; }
            public Builder successful(boolean successful) { this.successful = successful; return this; }
            public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
            public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
            public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
            
            public NovaResponse build() {
                return new NovaResponse(this);
            }
        }
        
        // Getters
        public String getResponseText() { return responseText; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public int getTotalTokens() { return totalTokens; }
        public double getEstimatedCost() { return estimatedCost; }
        public String getModelId() { return modelId; }
        public boolean isSuccessful() { return successful; }
        public long getTimestamp() { return timestamp; }
        public String getErrorMessage() { return errorMessage; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
}