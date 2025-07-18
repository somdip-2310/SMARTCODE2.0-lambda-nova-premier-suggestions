// src/main/java/com/somdiproy/lambda/suggestions/service/DynamoDBService.java
package com.somdiproy.lambda.suggestions.service;

import com.somdiproy.lambda.suggestions.model.DeveloperSuggestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for storing suggestions in DynamoDB
 */
public class DynamoDBService {
    
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DynamoDBService.class);
    
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    
    // Table names - fallback to default names if env vars not set
    private static final String ANALYSIS_RESULTS_TABLE = 
        System.getenv("ANALYSIS_RESULTS_TABLE") != null ? 
        System.getenv("ANALYSIS_RESULTS_TABLE") : "smartcode-analysis-results";
    
    private static final String ISSUE_DETAILS_TABLE = 
        System.getenv("ISSUE_DETAILS_TABLE") != null ? 
        System.getenv("ISSUE_DETAILS_TABLE") : "smartcode-issue-details";
    
    public DynamoDBService() {
        this.dynamoDbClient = DynamoDbClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Store suggestions in DynamoDB
     */
    public void storeSuggestions(String analysisId, String sessionId, 
                                List<DeveloperSuggestion> suggestions, 
                                int totalTokens, double totalCost) {
        try {
            // Update analysis results with suggestion summary
            updateAnalysisResults(analysisId, sessionId, suggestions.size(), totalTokens, totalCost);
            
            // Store individual suggestions
            for (DeveloperSuggestion suggestion : suggestions) {
                storeIndividualSuggestion(analysisId, suggestion);
            }
            
            log.info("Successfully stored {} suggestions for analysis {}", suggestions.size(), analysisId);
            
        } catch (Exception e) {
            log.error("Failed to store suggestions for analysis {}: {}", analysisId, e.getMessage(), e);
            throw new RuntimeException("Failed to store suggestions", e);
        }
    }
    
    /**
     * Update analysis progress status
     * Used to track progress during different stages of analysis
     */
    public void updateAnalysisProgress(String analysisId, String status, int suggestionCount) {
        try {
            Map<String, AttributeValue> key = Map.of(
                "analysisId", AttributeValue.builder().s(analysisId).build()
            );
            
            Map<String, AttributeValueUpdate> updates = new HashMap<>();
            
            // Update status
            updates.put("status", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().s(status).build())
                .action(AttributeAction.PUT)
                .build());
            
            // Update progress timestamp
            updates.put("lastUpdatedAt", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().n(String.valueOf(System.currentTimeMillis())).build())
                .action(AttributeAction.PUT)
                .build());
            
            // Update suggestion count if provided
            if (suggestionCount > 0) {
                updates.put("suggestionCount", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().n(String.valueOf(suggestionCount)).build())
                    .action(AttributeAction.PUT)
                    .build());
            }
            
            // Calculate and update progress percentage
            int progressPercentage = calculateProgressPercentage(status);
            updates.put("progressPercentage", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().n(String.valueOf(progressPercentage)).build())
                .action(AttributeAction.PUT)
                .build());
            
            UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(ANALYSIS_RESULTS_TABLE)
                .key(key)
                .attributeUpdates(updates)
                .build();
            
            dynamoDbClient.updateItem(request);
            log.info("Updated analysis progress for analysisId: {} to status: {}", analysisId, status);
            
        } catch (Exception e) {
            log.error("Failed to update analysis progress: {}", e.getMessage(), e);
            // Don't throw exception to prevent Lambda failure
        }
    }
    
    /**
     * Update analysis results table with suggestion summary
     */
    private void updateAnalysisResults(String analysisId, String sessionId, 
                                     int suggestionCount, int totalTokens, double totalCost) {
        try {
            Map<String, AttributeValue> key = Map.of(
                "analysisId", AttributeValue.builder().s(analysisId).build()
            );
            
            Map<String, AttributeValueUpdate> updates = new HashMap<>();
            updates.put("suggestionCount", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().n(String.valueOf(suggestionCount)).build())
                .action(AttributeAction.PUT)
                .build());
            
            updates.put("suggestionTokens", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().n(String.valueOf(totalTokens)).build())
                .action(AttributeAction.PUT)
                .build());
            
            updates.put("suggestionCost", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().n(String.valueOf(totalCost)).build())
                .action(AttributeAction.PUT)
                .build());
            
            updates.put("status", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().s("completed").build())
                .action(AttributeAction.PUT)
                .build());
            
            updates.put("completedAt", AttributeValueUpdate.builder()
                .value(AttributeValue.builder().n(String.valueOf(System.currentTimeMillis())).build())
                .action(AttributeAction.PUT)
                .build());
            
            UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(ANALYSIS_RESULTS_TABLE)
                .key(key)
                .attributeUpdates(updates)
                .build();
            
            dynamoDbClient.updateItem(request);
            log.info("Updated analysis results for analysisId: {}", analysisId);
            
        } catch (Exception e) {
            log.error("Failed to update analysis results: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Store individual suggestion in issue details table
     */
    private void storeIndividualSuggestion(String analysisId, DeveloperSuggestion suggestion) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("analysisId", AttributeValue.builder().s(analysisId).build());
            item.put("issueId", AttributeValue.builder().s(suggestion.getIssueId()).build());
            
            // Store issue metadata
            if (suggestion.getIssueType() != null) {
                item.put("type", AttributeValue.builder().s(suggestion.getIssueType()).build());
            }
            if (suggestion.getIssueSeverity() != null) {
                item.put("severity", AttributeValue.builder().s(suggestion.getIssueSeverity()).build());
            }
            if (suggestion.getIssueCategory() != null) {
                item.put("category", AttributeValue.builder().s(suggestion.getIssueCategory()).build());
            }
            
            // Generate title from type if not present
            String title = suggestion.getIssueType() != null ? 
                suggestion.getIssueType().replace("_", " ") : "Unknown Issue";
            item.put("title", AttributeValue.builder().s(title).build());
            
            // Store suggestion data in the format expected by main app
            Map<String, AttributeValue> suggestionMap = new HashMap<>();
            Map<String, AttributeValue> immediateFix = new HashMap<>();
            if (suggestion.getImmediateFix() != null) {
                immediateFix.put("title", AttributeValue.builder().s(
                    suggestion.getImmediateFix().getTitle() != null ? 
                    suggestion.getImmediateFix().getTitle() : "Fix Required").build());
                immediateFix.put("searchCode", AttributeValue.builder().s(
                    suggestion.getImmediateFix().getSearchCode() != null ? 
                    suggestion.getImmediateFix().getSearchCode() : "Review code").build());
                immediateFix.put("replaceCode", AttributeValue.builder().s(
                    suggestion.getImmediateFix().getReplaceCode() != null ? 
                    suggestion.getImmediateFix().getReplaceCode() : "Apply fix").build());
                immediateFix.put("explanation", AttributeValue.builder().s(
                    suggestion.getImmediateFix().getExplanation() != null ? 
                    suggestion.getImmediateFix().getExplanation() : "Security fix required").build());
                
                item.put("suggestion", AttributeValue.builder().m(Map.of(
                    "immediateFix", AttributeValue.builder().m(immediateFix).build()
                )).build());
            } else {
                // Create basic fallback suggestion structure
                Map<String, AttributeValue> basicFix = new HashMap<>();
                basicFix.put("title", AttributeValue.builder().s("Manual Review Required").build());
                basicFix.put("searchCode", AttributeValue.builder().s("Review the identified issue").build());
                basicFix.put("replaceCode", AttributeValue.builder().s("Apply appropriate security measures").build());
                basicFix.put("explanation", AttributeValue.builder().s("This issue requires manual review and security implementation.").build());
                
                item.put("suggestion", AttributeValue.builder().m(Map.of(
                    "immediateFix", AttributeValue.builder().m(basicFix).build()
                )).build());
            }
            
            // Add metadata
            item.put("tokensUsed", AttributeValue.builder().n(String.valueOf(suggestion.getTokensUsed())).build());
            item.put("cost", AttributeValue.builder().n(String.valueOf(suggestion.getCost())).build());
            item.put("modelUsed", AttributeValue.builder().s(suggestion.getModelUsed() != null ? 
                suggestion.getModelUsed() : "nova-lite").build());
            item.put("timestamp", AttributeValue.builder().n(String.valueOf(System.currentTimeMillis())).build());
            
            PutItemRequest request = PutItemRequest.builder()
                .tableName(ISSUE_DETAILS_TABLE)
                .item(item)
                .build();
            
            dynamoDbClient.putItem(request);
            log.debug("Stored suggestion for issue: {}", suggestion.getIssueId());
            
        } catch (Exception e) {
            log.error("Failed to store suggestion for issue {}: {}", suggestion.getIssueId(), e.getMessage());
        }
    }
    
    /**
     * Calculate progress percentage based on status
     */
    private int calculateProgressPercentage(String status) {
        switch (status.toLowerCase()) {
            case "suggestions_started":
                return 70;
            case "suggestions_in_progress":
                return 80;
            case "suggestions_complete":
            case "completed":
                return 100;
            default:
                return 75; // Default for unknown status
        }
    }
}