// src/main/java/com/somdiproy/lambda/suggestions/SuggestionHandler.java
package com.somdiproy.lambda.suggestions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.somdiproy.lambda.suggestions.model.SuggestionRequest;
import com.somdiproy.lambda.suggestions.model.SuggestionResponse;
import com.somdiproy.lambda.suggestions.model.DeveloperSuggestion;
import com.somdiproy.lambda.suggestions.service.NovaInvokerService;
import com.somdiproy.lambda.suggestions.service.DynamoDBService;
import com.somdiproy.lambda.suggestions.util.TokenOptimizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Lambda function for Nova Premier suggestion generation
 * Handler: com.somdiproy.lambda.suggestions.SuggestionHandler::handleRequest
 */
public class SuggestionHandler implements RequestHandler<SuggestionRequest, SuggestionResponse> {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SuggestionHandler.class);
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NovaInvokerService novaInvoker;
    private final DynamoDBService dynamoDBService;
    
    // Configuration from environment variables
    private static final String MODEL_ID = System.getenv("MODEL_ID"); // amazon.nova-pro-v1:0
    private static final String BEDROCK_REGION = System.getenv("BEDROCK_REGION"); // us-east-1
    private static final int MAX_TOKENS = Integer.parseInt(System.getenv("MAX_TOKENS")); // 8000
    
    public SuggestionHandler() {
        this.novaInvoker = new NovaInvokerService(BEDROCK_REGION);
        this.dynamoDBService = new DynamoDBService();
    }
    
    @Override
    public SuggestionResponse handleRequest(SuggestionRequest request, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("🚀 Starting Nova Premier suggestion generation for analysis: " + request.getAnalysisId());
        
        SuggestionResponse.ProcessingTime processingTime = new SuggestionResponse.ProcessingTime();
        processingTime.startTime = System.currentTimeMillis();
        
        try {
            // Validate input
            if (!request.isValid()) {
                return SuggestionResponse.error(request.getAnalysisId(), request.getSessionId(),
                                               "Invalid request: missing required fields");
            }
            
            List<Map<String, Object>> issues = request.getIssues();
            logger.log(String.format("🎯 Processing %d issues for suggestions", issues.size()));
            
            // Process issues and generate suggestions
            List<DeveloperSuggestion> suggestions = new ArrayList<>();
            int totalTokensUsed = 0;
            double totalCost = 0.0;
            
            for (Map<String, Object> issue : issues) {
                if (context.getRemainingTimeInMillis() < 30000) { // 30 seconds buffer
                    logger.log("⏰ Approaching timeout, stopping processing");
                    break;
                }
                
                try {
                    DeveloperSuggestion suggestion = generateSuggestionForIssue(issue, logger);
                    if (suggestion != null) {
                        suggestions.add(suggestion);
                        totalTokensUsed += suggestion.getTokensUsed();
                        totalCost += suggestion.getCost();
                    }
                } catch (Exception e) {
                    logger.log("❌ Error processing issue " + issue.get("id") + ": " + e.getMessage());
                }
                
                // Stop if we reach token budget
                if (totalTokensUsed > 35000) {
                    logger.log("🪙 Token budget reached, stopping processing");
                    break;
                }
            }
            
            // Store results in DynamoDB
            try {
                dynamoDBService.storeSuggestions(request.getAnalysisId(), request.getSessionId(), 
                                               suggestions, totalTokensUsed, totalCost);
                logger.log("💾 Successfully stored suggestions in DynamoDB");
            } catch (Exception e) {
                logger.log("❌ Failed to store suggestions: " + e.getMessage());
            }
            
            // Build response
            processingTime.endTime = System.currentTimeMillis();
            processingTime.totalProcessingTime = processingTime.endTime - processingTime.startTime;
            
            SuggestionResponse response = SuggestionResponse.builder()
                .status("success")
                .analysisId(request.getAnalysisId())
                .sessionId(request.getSessionId())
                .suggestions(suggestions)
                .summary(buildSummary(suggestions, totalTokensUsed, totalCost))
                .metadata(buildMetadata(totalTokensUsed, totalCost))
                .processingTime(processingTime)
                .build();
            
            logger.log(String.format("✅ Generated %d suggestions using %d tokens (Cost: $%.4f)", 
                       suggestions.size(), totalTokensUsed, totalCost));
            
            return response;
            
        } catch (Exception e) {
            logger.log("❌ Fatal error in suggestion generation: " + e.getMessage());
            processingTime.endTime = System.currentTimeMillis();
            return SuggestionResponse.error(request.getAnalysisId(), request.getSessionId(),
                                           "Failed to generate suggestions: " + e.getMessage());
        }
    }
    
    private DeveloperSuggestion generateSuggestionForIssue(Map<String, Object> issue, LambdaLogger logger) {
        try {
            String issueId = (String) issue.get("id");
            logger.log("🔍 Generating suggestion for issue: " + issueId);
            
            // Build optimized prompt
            String prompt = buildSuggestionPrompt(issue);
            
            // Call Nova Premier
            NovaInvokerService.NovaResponse novaResponse = novaInvoker.invokeNova(
                MODEL_ID, prompt, MAX_TOKENS, 0.3, 0.9);
            
            if (!novaResponse.isSuccessful()) {
                logger.log("❌ Nova Premier call failed for issue " + issueId + ": " + novaResponse.getErrorMessage());
                return createFallbackSuggestion(issueId, issue, 0, 0.0);
            }
            
            // Parse response
            return parseSuggestionResponse(issueId, novaResponse.getResponseText(), 
                                         novaResponse.getTotalTokens(), novaResponse.getEstimatedCost(),
                                         issue, logger);
            
        } catch (Exception e) {
            logger.log("❌ Error generating suggestion: " + e.getMessage());
            return createFallbackSuggestion((String) issue.get("id"), issue, 0, 0.0);
        }
    }
    
    private String buildSuggestionPrompt(Map<String, Object> issue) {
        StringBuilder prompt = new StringBuilder();
        
        String language = (String) issue.get("language");
        String type = (String) issue.get("type");
        String severity = (String) issue.get("severity");
        String codeSnippet = (String) issue.get("codeSnippet");
        String description = (String) issue.get("description");
        
        prompt.append("# Code Analysis Expert - Developer-Friendly Fix Generation\n\n");
        prompt.append("You are an expert software engineer specializing in ").append(language)
              .append(" development. Generate a comprehensive, actionable fix for this code issue.\n\n");
        
        prompt.append("## Issue Details\n");
        prompt.append("- **Type**: ").append(type).append("\n");
        prompt.append("- **Severity**: ").append(severity).append("\n");
        prompt.append("- **Language**: ").append(language).append("\n");
        prompt.append("- **Description**: ").append(description).append("\n\n");
        
        prompt.append("## Problematic Code\n");
        prompt.append("```").append(language.toLowerCase()).append("\n");
        prompt.append(codeSnippet).append("\n");
        prompt.append("```\n\n");
        
        prompt.append("## Required Response Format\n");
        prompt.append("Provide a comprehensive fix in this exact JSON format:\n\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"immediateFix\": {\n");
        prompt.append("    \"title\": \"Brief fix description\",\n");
        prompt.append("    \"searchCode\": \"Exact code to find and replace\",\n");
        prompt.append("    \"replaceCode\": \"Exact replacement code\",\n");
        prompt.append("    \"explanation\": \"Detailed explanation of the fix\"\n");
        prompt.append("  },\n");
        prompt.append("  \"bestPractice\": {\n");
        prompt.append("    \"title\": \"Best practice recommendation\",\n");
        prompt.append("    \"code\": \"Example of industry best practice\",\n");
        prompt.append("    \"benefits\": [\"List of specific benefits\"]\n");
        prompt.append("  },\n");
        prompt.append("  \"testing\": {\n");
        prompt.append("    \"testCase\": \"Complete unit test code\",\n");
        prompt.append("    \"validationSteps\": [\"Step-by-step validation instructions\"]\n");
        prompt.append("  },\n");
        prompt.append("  \"prevention\": {\n");
        prompt.append("    \"guidelines\": [\"Prevention guidelines\"],\n");
        prompt.append("    \"tools\": [{\n");
        prompt.append("      \"name\": \"Tool name\",\n");
        prompt.append("      \"description\": \"How it helps prevent this issue\"\n");
        prompt.append("    }],\n");
        prompt.append("    \"codeReviewChecklist\": [\"Code review checklist items\"]\n");
        prompt.append("  }\n");
        prompt.append("}\n");
        prompt.append("```\n\n");
        
        prompt.append("Generate the comprehensive fix now:");
        
        return prompt.toString();
    }
    
    private DeveloperSuggestion parseSuggestionResponse(String issueId, String response, 
                                                       int tokensUsed, double cost, 
                                                       Map<String, Object> originalIssue,
                                                       LambdaLogger logger) {
        try {
            // Extract JSON from response
            String jsonContent = extractJsonFromResponse(response);
            Map<String, Object> suggestionData = objectMapper.readValue(jsonContent, Map.class);
            
            return DeveloperSuggestion.builder()
                .issueId(issueId)
                .issueType((String) originalIssue.get("type"))
                .issueCategory((String) originalIssue.get("category"))
                .issueSeverity((String) originalIssue.get("severity"))
                .language((String) originalIssue.get("language"))
                .immediateFix(parseImmediateFix(suggestionData))
                .bestPractice(parseBestPractice(suggestionData))
                .testing(parseTesting(suggestionData))
                .prevention(parsePrevention(suggestionData))
                .tokensUsed(tokensUsed)
                .cost(cost)
                .timestamp(System.currentTimeMillis())
                .modelUsed(MODEL_ID)
                .build();
            
        } catch (Exception e) {
            logger.log("❌ Error parsing suggestion response for issue " + issueId + ": " + e.getMessage());
            return createFallbackSuggestion(issueId, originalIssue, tokensUsed, cost);
        }
    }
    
    private String extractJsonFromResponse(String response) {
        int jsonStart = response.indexOf("```json");
        int jsonEnd = response.lastIndexOf("```");
        
        if (jsonStart != -1 && jsonEnd != -1 && jsonStart < jsonEnd) {
            return response.substring(jsonStart + 7, jsonEnd).trim();
        }
        
        // Fallback: try to find JSON without code blocks
        jsonStart = response.indexOf("{");
        jsonEnd = response.lastIndexOf("}") + 1;
        
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd);
        }
        
        throw new IllegalArgumentException("No valid JSON found in response");
    }
    
    // Helper methods for parsing suggestion components
    private DeveloperSuggestion.ImmediateFix parseImmediateFix(Map<String, Object> data) {
        Map<String, Object> fixData = (Map<String, Object>) data.get("immediateFix");
        if (fixData == null) return null;
        
        return DeveloperSuggestion.ImmediateFix.builder()
            .title((String) fixData.get("title"))
            .searchCode((String) fixData.get("searchCode"))
            .replaceCode((String) fixData.get("replaceCode"))
            .explanation((String) fixData.get("explanation"))
            .build();
    }
    
    private DeveloperSuggestion.BestPractice parseBestPractice(Map<String, Object> data) {
        Map<String, Object> practiceData = (Map<String, Object>) data.get("bestPractice");
        if (practiceData == null) return null;
        
        return DeveloperSuggestion.BestPractice.builder()
            .title((String) practiceData.get("title"))
            .code((String) practiceData.get("code"))
            .benefits((List<String>) practiceData.get("benefits"))
            .build();
    }
    
    private DeveloperSuggestion.Testing parseTesting(Map<String, Object> data) {
        Map<String, Object> testingData = (Map<String, Object>) data.get("testing");
        if (testingData == null) return null;
        
        return DeveloperSuggestion.Testing.builder()
            .testCase((String) testingData.get("testCase"))
            .validationSteps((List<String>) testingData.get("validationSteps"))
            .build();
    }
    
    private DeveloperSuggestion.Prevention parsePrevention(Map<String, Object> data) {
        Map<String, Object> preventionData = (Map<String, Object>) data.get("prevention");
        if (preventionData == null) return null;
        
        List<DeveloperSuggestion.Tool> tools = new ArrayList<>();
        List<Map<String, String>> toolsData = (List<Map<String, String>>) preventionData.get("tools");
        if (toolsData != null) {
            for (Map<String, String> toolData : toolsData) {
                tools.add(DeveloperSuggestion.Tool.builder()
                    .name(toolData.get("name"))
                    .description(toolData.get("description"))
                    .build());
            }
        }
        
        return DeveloperSuggestion.Prevention.builder()
            .guidelines((List<String>) preventionData.get("guidelines"))
            .tools(tools)
            .codeReviewChecklist((List<String>) preventionData.get("codeReviewChecklist"))
            .build();
    }
    
    private DeveloperSuggestion createFallbackSuggestion(String issueId, Map<String, Object> issue, 
                                                        int tokensUsed, double cost) {
        return DeveloperSuggestion.builder()
            .issueId(issueId)
            .issueType((String) issue.get("type"))
            .issueCategory((String) issue.get("category"))
            .issueSeverity((String) issue.get("severity"))
            .language((String) issue.get("language"))
            .immediateFix(DeveloperSuggestion.ImmediateFix.builder()
                .title("Manual Review Required")
                .explanation("This issue requires manual review and custom fixing approach.")
                .searchCode((String) issue.get("codeSnippet"))
                .replaceCode("// TODO: Implement fix based on issue description")
                .build())
            .tokensUsed(tokensUsed)
            .cost(cost)
            .timestamp(System.currentTimeMillis())
            .modelUsed(MODEL_ID)
            .build();
    }
    
    private SuggestionResponse.Summary buildSummary(List<DeveloperSuggestion> suggestions, 
                                                   int totalTokens, double totalCost) {
        Map<String, Long> bySeverity = suggestions.stream()
            .collect(Collectors.groupingBy(
                s -> s.getIssueSeverity() != null ? s.getIssueSeverity() : "unknown",
                Collectors.counting()
            ));
        
        Map<String, Long> byCategory = suggestions.stream()
            .collect(Collectors.groupingBy(
                s -> s.getIssueCategory() != null ? s.getIssueCategory() : "unknown",
                Collectors.counting()
            ));
        
        return SuggestionResponse.Summary.builder()
            .totalSuggestions(suggestions.size())
            .bySeverity(bySeverity)
            .byCategory(byCategory)
            .tokensUsed(totalTokens)
            .estimatedCost(totalCost)
            .build();
    }
    
    private Map<String, Object> buildMetadata(int totalTokens, double totalCost) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("modelUsed", MODEL_ID);
        metadata.put("totalTokensUsed", totalTokens);
        metadata.put("totalCost", totalCost);
        metadata.put("timestamp", System.currentTimeMillis());
        return metadata;
    }
}