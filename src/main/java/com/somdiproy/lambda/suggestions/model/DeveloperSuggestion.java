// src/main/java/com/somdiproy/lambda/suggestions/model/DeveloperSuggestion.java
package com.somdiproy.lambda.suggestions.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Comprehensive developer-friendly suggestion model
 */
public class DeveloperSuggestion {
    
    @JsonProperty("issueId")
    private String issueId;
    
    @JsonProperty("issueType")
    private String issueType;
    
    @JsonProperty("issueCategory")
    private String issueCategory;
    
    @JsonProperty("issueSeverity")
    private String issueSeverity;
    
    @JsonProperty("language")
    private String language;
    
    @JsonProperty("issueDescription")
    private String issueDescription;
    
    @JsonProperty("immediateFix")
    private ImmediateFix immediateFix;
    
    @JsonProperty("bestPractice")
    private BestPractice bestPractice;
    
    @JsonProperty("testing")
    private Testing testing;
    
    @JsonProperty("prevention")
    private Prevention prevention;
    
    @JsonProperty("tokensUsed")
    private Integer tokensUsed;
    
    @JsonProperty("cost")
    private Double cost;
    
    @JsonProperty("timestamp")
    private Long timestamp;
    
    @JsonProperty("modelUsed")
    private String modelUsed;
    
    // Constructors
    public DeveloperSuggestion() {}
    
    private DeveloperSuggestion(Builder builder) {
        this.issueId = builder.issueId;
        this.issueType = builder.issueType;
        this.issueCategory = builder.issueCategory;
        this.issueSeverity = builder.issueSeverity;
        this.language = builder.language;
        this.issueDescription = builder.issueDescription;
        this.immediateFix = builder.immediateFix;
        this.bestPractice = builder.bestPractice;
        this.testing = builder.testing;
        this.prevention = builder.prevention;
        this.tokensUsed = builder.tokensUsed;
        this.cost = builder.cost;
        this.timestamp = builder.timestamp;
        this.modelUsed = builder.modelUsed;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String issueId;
        private String issueType;
        private String issueCategory;
        private String issueSeverity;
        private String language;
        private String issueDescription;
        private ImmediateFix immediateFix;
        private BestPractice bestPractice;
        private Testing testing;
        private Prevention prevention;
        private Integer tokensUsed;
        private Double cost;
        private Long timestamp;
        private String modelUsed;
        
        public Builder issueId(String issueId) { this.issueId = issueId; return this; }
        public Builder issueType(String issueType) { this.issueType = issueType; return this; }
        public Builder issueCategory(String issueCategory) { this.issueCategory = issueCategory; return this; }
        public Builder issueSeverity(String issueSeverity) { this.issueSeverity = issueSeverity; return this; }
        public Builder language(String language) { this.language = language; return this; }
        public Builder issueDescription(String issueDescription) { this.issueDescription = issueDescription; return this; }
        public Builder immediateFix(ImmediateFix immediateFix) { this.immediateFix = immediateFix; return this; }
        public Builder bestPractice(BestPractice bestPractice) { this.bestPractice = bestPractice; return this; }
        public Builder testing(Testing testing) { this.testing = testing; return this; }
        public Builder prevention(Prevention prevention) { this.prevention = prevention; return this; }
        public Builder tokensUsed(Integer tokensUsed) { this.tokensUsed = tokensUsed; return this; }
        public Builder cost(Double cost) { this.cost = cost; return this; }
        public Builder timestamp(Long timestamp) { this.timestamp = timestamp; return this; }
        public Builder modelUsed(String modelUsed) { this.modelUsed = modelUsed; return this; }
        
        public DeveloperSuggestion build() {
            return new DeveloperSuggestion(this);
        }
    }
    
    // Nested classes for suggestion components
    public static class ImmediateFix {
        @JsonProperty("title")
        private String title;
        
        @JsonProperty("searchCode")
        private String searchCode;
        
        @JsonProperty("replaceCode")
        private String replaceCode;
        
        @JsonProperty("explanation")
        private String explanation;
        
        public ImmediateFix() {}
        
        private ImmediateFix(Builder builder) {
            this.title = builder.title;
            this.searchCode = builder.searchCode;
            this.replaceCode = builder.replaceCode;
            this.explanation = builder.explanation;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String title;
            private String searchCode;
            private String replaceCode;
            private String explanation;
            
            public Builder title(String title) { this.title = title; return this; }
            public Builder searchCode(String searchCode) { this.searchCode = searchCode; return this; }
            public Builder replaceCode(String replaceCode) { this.replaceCode = replaceCode; return this; }
            public Builder explanation(String explanation) { this.explanation = explanation; return this; }
            
            public ImmediateFix build() {
                return new ImmediateFix(this);
            }
        }
        
        public String getTitle() { return title; }
        public String getSearchCode() { return searchCode; }
        public String getReplaceCode() { return replaceCode; }
        public String getExplanation() { return explanation; }
    }
    
    public static class BestPractice {
        @JsonProperty("title")
        private String title;
        
        @JsonProperty("code")
        private String code;
        
        @JsonProperty("benefits")
        private List<String> benefits;
        
        public BestPractice() {}
        
        private BestPractice(Builder builder) {
            this.title = builder.title;
            this.code = builder.code;
            this.benefits = builder.benefits;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String title;
            private String code;
            private List<String> benefits;
            
            public Builder title(String title) { this.title = title; return this; }
            public Builder code(String code) { this.code = code; return this; }
            public Builder benefits(List<String> benefits) { this.benefits = benefits; return this; }
            
            public BestPractice build() {
                return new BestPractice(this);
            }
        }
        
        public String getTitle() { return title; }
        public String getCode() { return code; }
        public List<String> getBenefits() { return benefits; }
    }
    
    public static class Testing {
        @JsonProperty("testCase")
        private String testCase;
        
        @JsonProperty("validationSteps")
        private List<String> validationSteps;
        
        public Testing() {}
        
        private Testing(Builder builder) {
            this.testCase = builder.testCase;
            this.validationSteps = builder.validationSteps;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String testCase;
            private List<String> validationSteps;
            
            public Builder testCase(String testCase) { this.testCase = testCase; return this; }
            public Builder validationSteps(List<String> validationSteps) { this.validationSteps = validationSteps; return this; }
            
            public Testing build() {
                return new Testing(this);
            }
        }
        
        public String getTestCase() { return testCase; }
        public List<String> getValidationSteps() { return validationSteps; }
    }
    
    public static class Prevention {
        @JsonProperty("guidelines")
        private List<String> guidelines;
        
        @JsonProperty("tools")
        private List<Tool> tools;
        
        @JsonProperty("codeReviewChecklist")
        private List<String> codeReviewChecklist;
        
        public Prevention() {}
        
        private Prevention(Builder builder) {
            this.guidelines = builder.guidelines;
            this.tools = builder.tools;
            this.codeReviewChecklist = builder.codeReviewChecklist;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private List<String> guidelines;
            private List<Tool> tools;
            private List<String> codeReviewChecklist;
            
            public Builder guidelines(List<String> guidelines) { this.guidelines = guidelines; return this; }
            public Builder tools(List<Tool> tools) { this.tools = tools; return this; }
            public Builder codeReviewChecklist(List<String> codeReviewChecklist) { this.codeReviewChecklist = codeReviewChecklist; return this; }
            
            public Prevention build() {
                return new Prevention(this);
            }
        }
        
        public List<String> getGuidelines() { return guidelines; }
        public List<Tool> getTools() { return tools; }
        public List<String> getCodeReviewChecklist() { return codeReviewChecklist; }
    }
    
    public static class Tool {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("description")
        private String description;
        
        public Tool() {}
        
        private Tool(Builder builder) {
            this.name = builder.name;
            this.description = builder.description;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String name;
            private String description;
            
            public Builder name(String name) { this.name = name; return this; }
            public Builder description(String description) { this.description = description; return this; }
            
            public Tool build() {
                return new Tool(this);
            }
        }
        
        public String getName() { return name; }
        public String getDescription() { return description; }
    }
    
    // Main class getters
    public String getIssueId() { return issueId; }
    public String getIssueType() { return issueType; }
    public String getIssueCategory() { return issueCategory; }
    public String getIssueSeverity() { return issueSeverity; }
    public String getLanguage() { return language; }
    public String getIssueDescription() { return issueDescription; }
    public ImmediateFix getImmediateFix() { return immediateFix; }
    public BestPractice getBestPractice() { return bestPractice; }
    public Testing getTesting() { return testing; }
    public Prevention getPrevention() { return prevention; }
    public Integer getTokensUsed() { return tokensUsed; }
    public Double getCost() { return cost; }
    public Long getTimestamp() { return timestamp; }
    public String getModelUsed() { return modelUsed; }
}