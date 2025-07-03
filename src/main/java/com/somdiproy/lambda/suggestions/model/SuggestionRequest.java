// src/main/java/com/somdiproy/lambda/suggestions/model/SuggestionRequest.java
package com.somdiproy.lambda.suggestions.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Request model for Nova Premier suggestion generation
 */
public class SuggestionRequest {
    
    @JsonProperty("sessionId")
    private String sessionId;
    
    @JsonProperty("analysisId")
    private String analysisId;
    
    @JsonProperty("repository")
    private String repository;
    
    @JsonProperty("branch")
    private String branch;
    
    @JsonProperty("issues")
    private List<Map<String, Object>> issues;
    
    @JsonProperty("stage")
    private String stage;
    
    @JsonProperty("scanNumber")
    private Integer scanNumber;
    
    @JsonProperty("timestamp")
    private Long timestamp;
    
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    // New hybrid strategy fields
    @JsonProperty("modelId")
    private String modelId; // "nova-lite", "nova-premier", or "template"

    @JsonProperty("issueSeverity") 
    private String issueSeverity; // "LOW", "MEDIUM", "HIGH", "CRITICAL"

    @JsonProperty("strategy")
    private String strategy = "hybrid"; // Strategy identifier

    @JsonProperty("processingMode")
    private String processingMode; // "standard", "fallback", "template"
    
    // Constructors
    public SuggestionRequest() {}
    
    public String getModelId() {
		return modelId;
	}

	public void setModelId(String modelId) {
		this.modelId = modelId;
	}

	public String getIssueSeverity() {
		return issueSeverity;
	}

	public void setIssueSeverity(String issueSeverity) {
		this.issueSeverity = issueSeverity;
	}

	public String getStrategy() {
		return strategy;
	}

	public void setStrategy(String strategy) {
		this.strategy = strategy;
	}

	public String getProcessingMode() {
		return processingMode;
	}

	public void setProcessingMode(String processingMode) {
		this.processingMode = processingMode;
	}

	// Validation
    public boolean isValid() {
        return sessionId != null && !sessionId.trim().isEmpty() &&
               analysisId != null && !analysisId.trim().isEmpty() &&
               issues != null && !issues.isEmpty();
    }

    // Helper method to determine if hybrid strategy should be used
    public boolean isHybridMode() {
        return "hybrid".equals(strategy) || modelId != null;
    }

    // Helper method to get effective model ID with fallback
    public String getEffectiveModelId() {
        if (modelId != null && !modelId.trim().isEmpty()) {
            return modelId;
        }
        
        // Determine based on issue severity if not explicitly set
        if (issueSeverity != null) {
            if ("CRITICAL".equalsIgnoreCase(issueSeverity)) {
                return "nova-premier";
            } else {
                return "nova-lite";
            }
        }
        
        return "nova-lite"; // Default fallback
    }
    
    // Getters and Setters
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    
    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }
    
    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }
    
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    
    public List<Map<String, Object>> getIssues() { return issues; }
    public void setIssues(List<Map<String, Object>> issues) { this.issues = issues; }
    
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    
    public Integer getScanNumber() { return scanNumber; }
    public void setScanNumber(Integer scanNumber) { this.scanNumber = scanNumber; }
    
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}