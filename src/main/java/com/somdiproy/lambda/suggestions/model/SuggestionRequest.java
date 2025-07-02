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
    
    // Constructors
    public SuggestionRequest() {}
    
    // Validation
    public boolean isValid() {
        return sessionId != null && !sessionId.trim().isEmpty() &&
               analysisId != null && !analysisId.trim().isEmpty() &&
               issues != null && !issues.isEmpty();
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