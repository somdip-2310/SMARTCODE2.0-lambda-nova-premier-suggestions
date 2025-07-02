// src/main/java/com/somdiproy/lambda/suggestions/model/SuggestionResponse.java
package com.somdiproy.lambda.suggestions.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Response model for Nova Premier suggestion generation
 */
public class SuggestionResponse {
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("analysisId")
    private String analysisId;
    
    @JsonProperty("sessionId")
    private String sessionId;
    
    @JsonProperty("suggestions")
    private List<DeveloperSuggestion> suggestions;
    
    @JsonProperty("summary")
    private Summary summary;
    
    @JsonProperty("metadata")
    private Map<String, Object> metadata;
    
    @JsonProperty("processingTime")
    private ProcessingTime processingTime;
    
    @JsonProperty("errors")
    private List<String> errors;
    
    @JsonProperty("warnings")
    private List<String> warnings;
    
    // Constructors
    public SuggestionResponse() {}
    
    private SuggestionResponse(Builder builder) {
        this.status = builder.status;
        this.analysisId = builder.analysisId;
        this.sessionId = builder.sessionId;
        this.suggestions = builder.suggestions;
        this.summary = builder.summary;
        this.metadata = builder.metadata;
        this.processingTime = builder.processingTime;
        this.errors = builder.errors;
        this.warnings = builder.warnings;
    }
    
    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String status;
        private String analysisId;
        private String sessionId;
        private List<DeveloperSuggestion> suggestions;
        private Summary summary;
        private Map<String, Object> metadata;
        private ProcessingTime processingTime;
        private List<String> errors;
        private List<String> warnings;
        
        public Builder status(String status) { this.status = status; return this; }
        public Builder analysisId(String analysisId) { this.analysisId = analysisId; return this; }
        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder suggestions(List<DeveloperSuggestion> suggestions) { this.suggestions = suggestions; return this; }
        public Builder summary(Summary summary) { this.summary = summary; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder processingTime(ProcessingTime processingTime) { this.processingTime = processingTime; return this; }
        public Builder errors(List<String> errors) { this.errors = errors; return this; }
        public Builder warnings(List<String> warnings) { this.warnings = warnings; return this; }
        
        public SuggestionResponse build() {
            return new SuggestionResponse(this);
        }
    }
    
    // Factory methods
    public static SuggestionResponse error(String analysisId, String sessionId, String errorMessage) {
        return builder()
            .status("error")
            .analysisId(analysisId)
            .sessionId(sessionId)
            .errors(List.of(errorMessage))
            .build();
    }
    
    // Summary class
    public static class Summary {
        @JsonProperty("totalSuggestions")
        private Integer totalSuggestions;
        
        @JsonProperty("bySeverity")
        private Map<String, Long> bySeverity;
        
        @JsonProperty("byCategory")
        private Map<String, Long> byCategory;
        
        @JsonProperty("tokensUsed")
        private Integer tokensUsed;
        
        @JsonProperty("estimatedCost")
        private Double estimatedCost;
        
        // Constructors
        public Summary() {}
        
        private Summary(Builder builder) {
            this.totalSuggestions = builder.totalSuggestions;
            this.bySeverity = builder.bySeverity;
            this.byCategory = builder.byCategory;
            this.tokensUsed = builder.tokensUsed;
            this.estimatedCost = builder.estimatedCost;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private Integer totalSuggestions;
            private Map<String, Long> bySeverity;
            private Map<String, Long> byCategory;
            private Integer tokensUsed;
            private Double estimatedCost;
            
            public Builder totalSuggestions(Integer totalSuggestions) { this.totalSuggestions = totalSuggestions; return this; }
            public Builder bySeverity(Map<String, Long> bySeverity) { this.bySeverity = bySeverity; return this; }
            public Builder byCategory(Map<String, Long> byCategory) { this.byCategory = byCategory; return this; }
            public Builder tokensUsed(Integer tokensUsed) { this.tokensUsed = tokensUsed; return this; }
            public Builder estimatedCost(Double estimatedCost) { this.estimatedCost = estimatedCost; return this; }
            
            public Summary build() {
                return new Summary(this);
            }
        }
        
        // Getters
        public Integer getTotalSuggestions() { return totalSuggestions; }
        public Map<String, Long> getBySeverity() { return bySeverity; }
        public Map<String, Long> getByCategory() { return byCategory; }
        public Integer getTokensUsed() { return tokensUsed; }
        public Double getEstimatedCost() { return estimatedCost; }
    }
    
    // ProcessingTime class
    public static class ProcessingTime {
        @JsonProperty("startTime")
        public Long startTime;
        
        @JsonProperty("endTime")
        public Long endTime;
        
        @JsonProperty("totalProcessingTime")
        public Long totalProcessingTime;
        
        public ProcessingTime() {}
    }
    
    // Getters and Setters
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }
    
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    
    public List<DeveloperSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<DeveloperSuggestion> suggestions) { this.suggestions = suggestions; }
    
    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }
    
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    
    public ProcessingTime getProcessingTime() { return processingTime; }
    public void setProcessingTime(ProcessingTime processingTime) { this.processingTime = processingTime; }
    
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
    
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
}