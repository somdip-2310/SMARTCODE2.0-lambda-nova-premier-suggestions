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

import software.amazon.awssdk.utils.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Enhanced Lambda function for Nova Premier suggestion generation with batch
 * processing and hybrid model support
 * Handler: com.somdiproy.lambda.suggestions.SuggestionHandler::handleRequest
 */
public class SuggestionHandler implements RequestHandler<SuggestionRequest, SuggestionResponse> {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SuggestionHandler.class);

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final NovaInvokerService novaInvoker;
	private final DynamoDBService dynamoDBService;

	// Configuration from environment variables with hybrid model support
	private static final String DEFAULT_MODEL_ID = System.getenv("MODEL_ID"); // amazon.nova-pro-v1:0
	private static final String NOVA_LITE_MODEL_ID = "amazon.nova-lite-v1:0";
	private static final boolean TEMPLATE_MODE_ENABLED = Boolean.parseBoolean(System.getenv("TEMPLATE_MODE_ENABLED"));
	private static final String MODEL_ID = DEFAULT_MODEL_ID; // Primary model reference
	
	private static final String BEDROCK_REGION = System.getenv("BEDROCK_REGION"); // us-east-1
	private static final int MAX_TOKENS = Integer.parseInt(System.getenv("MAX_TOKENS")); // 8000

	// Batch processing configuration
	private static final int BATCH_SIZE = Integer.parseInt(System.getenv().getOrDefault("BATCH_SIZE", "1")); // Single issue per batch for Nova stability
	private static final int MAX_ISSUES_PER_ANALYSIS = 25; // Limit total issues to prevent timeout
	private static final long BATCH_DELAY_MS = Long.parseLong(System.getenv().getOrDefault("BATCH_DELAY_MS", "2000"));
	private static final int MAX_CONCURRENT_CALLS = Integer
			.parseInt(System.getenv().getOrDefault("MAX_CONCURRENT_CALLS", "2"));

	// Token budget management
	private static final int TOKEN_BUDGET = Integer.parseInt(System.getenv().getOrDefault("TOKEN_BUDGET", "40000"));
	private static final int TOKEN_BUFFER = 5000; // Reserve tokens for safety

	// Timeout management
	private static final long TIMEOUT_BUFFER_MS = 30000; // 30 seconds buffer before Lambda timeout

	// Executor service for parallel processing within batches
	private static ExecutorService executorService;
	private static final Object executorLock = new Object();

	public SuggestionHandler() {
		this.novaInvoker = new NovaInvokerService(BEDROCK_REGION);
		this.dynamoDBService = new DynamoDBService();
		initializeExecutorService();
	}

	/**
	 * Model selection strategy for hybrid approach
	 */
	private String selectModel(SuggestionRequest request) {
		// Check if hybrid mode is enabled via request
		if (request != null && request.isHybridMode()) {
			return request.getEffectiveModelId();
		}
		
		// Default to request-based selection if available
		String requestedModel = request != null ? request.getModelId() : null;
		if (requestedModel != null) {
			return requestedModel;
		}
		
		// Check if template mode is enabled globally
		// Check if template mode is enabled globally
		if (TEMPLATE_MODE_ENABLED) {
		    // Use deterministic selection based on session from request
		    String sessionId = request != null ? request.getSessionId() : "default";
		    int sessionHash = Math.abs((sessionId != null ? sessionId : "default").hashCode()) % 100;
		    if (sessionHash < 9) {
		        return "TEMPLATE_MODE";
		    }
		}

		// Default to Nova Lite for most cases (90%)
		String sessionId = request != null ? request.getSessionId() : "default";
		int sessionHash = Math.abs((sessionId != null ? sessionId : "default").hashCode()) % 100;
		return sessionHash < 90 ? NOVA_LITE_MODEL_ID : DEFAULT_MODEL_ID;
	}

	/**
	 * Determine which model to use for a specific issue based on hybrid strategy
	 * Uses deterministic selection based on issue characteristics for consistency
	 */
	private String determineModelForIssue(Map<String, Object> issue) {
	    String severity = (String) issue.getOrDefault("severity", "MEDIUM");
	    String category = (String) issue.getOrDefault("category", "quality");
	    
	    // Create deterministic hash from issue characteristics
	    String issueKey = String.format("%s_%s_%s_%s", 
	        issue.getOrDefault("id", "unknown"),
	        issue.getOrDefault("type", "unknown"),
	        issue.getOrDefault("file", "unknown"),
	        issue.getOrDefault("line", "0")
	    );
	    
	    int hash = Math.abs(issueKey.hashCode()) % 100;
	    
	    // 1% Nova Premier for CRITICAL security issues only
	    if ("CRITICAL".equalsIgnoreCase(severity) && "security".equalsIgnoreCase(category)) {
	        return hash < 1 ? DEFAULT_MODEL_ID : NOVA_LITE_MODEL_ID;
	    }
	    
	    // 90% Nova Lite for most issues
	    if (hash < 90) {
	        return NOVA_LITE_MODEL_ID;
	    }
	    
	    // 9% Enhanced Templates for fallback (90-98)
	    if (hash < 99) {
	        return "TEMPLATE_MODE";
	    }
	    
	    // Skip remaining 1% (99)
	    return NOVA_LITE_MODEL_ID; // Changed to Nova Lite instead of skipping
	}

	private void initializeExecutorService() {
		synchronized (executorLock) {
			if (executorService == null || executorService.isShutdown()) {
				executorService = new ThreadPoolExecutor(MAX_CONCURRENT_CALLS, MAX_CONCURRENT_CALLS, 60L,
						TimeUnit.SECONDS, new LinkedBlockingQueue<>(100), r -> {
							Thread t = new Thread(r);
							t.setDaemon(true);
							t.setName("nova-suggestion-worker-" + t.getId());
							return t;
						}, new ThreadPoolExecutor.CallerRunsPolicy());
			}
		}
	}

	@Override
	public SuggestionResponse handleRequest(SuggestionRequest request, Context context) {
		LambdaLogger logger = context.getLogger();
		logger.log("🚀 Starting hybrid suggestion generation for analysis: " + request.getAnalysisId());

		SuggestionResponse.ProcessingTime processingTime = new SuggestionResponse.ProcessingTime();
		processingTime.startTime = System.currentTimeMillis();

		try {
			// Validate input
			if (!request.isValid()) {
				return SuggestionResponse.error(request.getAnalysisId(), request.getSessionId(),
						"Invalid request: missing required fields");
			}

			List<Map<String, Object>> issues = request.getIssues();
			logger.log(String.format("🎯 Processing %d issues for suggestions in batches of %d", issues.size(),
					BATCH_SIZE));

			// Create batches for processing
			List<List<Map<String, Object>>> batches = createBatches(issues, BATCH_SIZE);
			logger.log(String.format("📦 Created %d batches for processing", batches.size()));

			// Process batches with throttling prevention
			List<DeveloperSuggestion> allSuggestions = new ArrayList<>();
			int totalTokensUsed = 0;
			double totalCost = 0.0;

			for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
				// Check timeout
				if (context.getRemainingTimeInMillis() < TIMEOUT_BUFFER_MS) {
					logger.log("⏰ Approaching Lambda timeout, stopping batch processing");
					break;
				}

				// Check token budget
				if (totalTokensUsed > (TOKEN_BUDGET - TOKEN_BUFFER)) {
					logger.log(String.format("🪙 Token budget limit approaching (%d/%d), stopping processing",
							totalTokensUsed, TOKEN_BUDGET));
					break;
				}

				List<Map<String, Object>> batch = batches.get(batchIndex);
				logger.log(String.format("📋 Processing batch %d/%d with %d issues", batchIndex + 1, batches.size(),
						batch.size()));

				// Add delay between batches to prevent throttling (except for first batch)
				// Enhanced delay strategy: always wait between batches for Nova API stability
				long delay = calculateAdaptiveBatchDelay(batchIndex, totalTokensUsed);
				if (batchIndex > 0 || totalTokensUsed > 0) { // Always delay after first request
				    logger.log(String.format("⏸️ Waiting %dms before processing next batch (adaptive Nova delay)", delay));
				    Thread.sleep(delay);
				}

				// Check if issues are already categorized (from balanced allocation)
				boolean hasCategoryInfo = batch.stream().anyMatch(issue -> issue.containsKey("category"));

				BatchResult batchResult;
				if (hasCategoryInfo) {
				    // Use category-aware processing for balanced allocation
				    List<DeveloperSuggestion> suggestions = processCategoryAwareIssues(batch, context, logger);
				    batchResult = new BatchResult(suggestions, 
				                                 suggestions.stream().mapToInt(DeveloperSuggestion::getTokensUsed).sum(),
				                                 suggestions.stream().mapToDouble(DeveloperSuggestion::getCost).sum());
				} else {
				    // Fallback to sequential processing
				    batchResult = processIssuesSequentially(batch, context, logger);
				}

				// Aggregate results
				allSuggestions.addAll(batchResult.suggestions);
				totalTokensUsed += batchResult.tokensUsed;
				totalCost += batchResult.cost;

				// Log batch statistics
				logger.log(String.format("✅ Batch %d complete: %d suggestions, %d tokens, $%.4f", batchIndex + 1,
						batchResult.suggestions.size(), batchResult.tokensUsed, batchResult.cost));

				// Check Nova service statistics and adapt if needed
				Map<String, Object> stats = novaInvoker.getStatistics();
				if (shouldSlowDown(stats)) {
					logger.log("⚠️ High throttle rate detected, increasing batch delays");
					Thread.sleep(BATCH_DELAY_MS * 2); // Extra delay
				}
			}

			// Store results in DynamoDB
			try {
				// Update analysis status
				dynamoDBService.updateAnalysisProgress(request.getAnalysisId(), "suggestions_complete",
						allSuggestions.size());

				// Store suggestions
				dynamoDBService.storeSuggestions(request.getAnalysisId(), request.getSessionId(), allSuggestions,
						totalTokensUsed, totalCost);
				logger.log("💾 Successfully stored suggestions in DynamoDB");
			} catch (Exception e) {
				logger.log("❌ Failed to store suggestions: " + e.getMessage());
				// Continue - don't fail the entire operation
			}

			// Build response
			processingTime.endTime = System.currentTimeMillis();
			processingTime.totalProcessingTime = processingTime.endTime - processingTime.startTime;

			// Log final statistics
			Map<String, Object> finalStats = novaInvoker.getStatistics();
			logger.log(String.format("📊 Final statistics: %s", objectMapper.writeValueAsString(finalStats)));

			SuggestionResponse response = SuggestionResponse.builder().status("success")
					.analysisId(request.getAnalysisId()).sessionId(request.getSessionId()).suggestions(allSuggestions)
					.summary(buildSummary(allSuggestions, totalTokensUsed, totalCost))
					.metadata(buildMetadata(totalTokensUsed, totalCost, processingTime)).processingTime(processingTime)
					.build();

			logger.log(String.format("✅ Generated %d suggestions using %d tokens (Cost: $%.4f) in %.2f seconds",
					allSuggestions.size(), totalTokensUsed, totalCost, processingTime.totalProcessingTime / 1000.0));

			return response;

		} catch (Exception e) {
			logger.log("❌ Fatal error in suggestion generation: " + e.getMessage());
			log.error("Fatal error details:", e);

			processingTime.endTime = System.currentTimeMillis();
			return SuggestionResponse.error(request.getAnalysisId(), request.getSessionId(),
					"Failed to generate suggestions: " + e.getMessage());
		}
	}
	
	/**
	 * Process issues with category-aware prioritization
	 * Ensures balanced token usage across Security, Performance, and Quality
	 */
	private List<DeveloperSuggestion> processCategoryAwareIssues(List<Map<String, Object>> issues, 
	                                                           Context context, LambdaLogger logger) {
	    logger.log("🎯 Starting category-aware processing for " + issues.size() + " issues");
	    
	    // Group issues by category for balanced processing
	    Map<String, List<Map<String, Object>>> categorizedIssues = issues.stream()
	            .collect(Collectors.groupingBy(issue -> 
	                    (String) issue.getOrDefault("category", "quality")));
	    
	    List<DeveloperSuggestion> allSuggestions = new ArrayList<>();
	    int totalTokensUsed = 0;
	    double totalCost = 0.0;
	    
	    // Process each category with appropriate token allocation
	    for (Map.Entry<String, List<Map<String, Object>>> entry : categorizedIssues.entrySet()) {
	        String category = entry.getKey();
	        List<Map<String, Object>> categoryIssues = entry.getValue();
	        
	        logger.log(String.format("📋 Processing %s category: %d issues", category, categoryIssues.size()));
	        
	        // Calculate token budget for this category
	        int categoryTokenBudget = calculateCategoryTokenBudget(category, categoryIssues.size());
	        
	        // Process category issues sequentially with budget control
	        CategoryResult categoryResult = processCategoryIssues(categoryIssues, category, 
	                categoryTokenBudget, context, logger);
	        
	        allSuggestions.addAll(categoryResult.suggestions);
	        totalTokensUsed += categoryResult.tokensUsed;
	        totalCost += categoryResult.cost;
	        
	        logger.log(String.format("✅ %s category complete: %d suggestions, %d tokens, $%.4f", 
	                category, categoryResult.suggestions.size(), categoryResult.tokensUsed, categoryResult.cost));
	        
	        // Check overall budget
	        if (totalTokensUsed > (TOKEN_BUDGET - TOKEN_BUFFER)) {
	            logger.log(String.format("🪙 Token budget limit reached (%d/%d), stopping processing", 
	                    totalTokensUsed, TOKEN_BUDGET));
	            break;
	        }
	    }
	    
	    logger.log(String.format("🎉 Category-aware processing complete: %d suggestions, %d tokens, $%.4f", 
	            allSuggestions.size(), totalTokensUsed, totalCost));
	    
	    return allSuggestions;
	}

	/**
	 * Calculate token budget allocation per category
	 */
	private int calculateCategoryTokenBudget(String category, int issueCount) {
	    // Base allocation percentages
	    double allocation = switch (category.toLowerCase()) {
	        case "security" -> 0.50;      // 50% of budget
	        case "performance" -> 0.30;   // 30% of budget  
	        case "quality" -> 0.20;       // 20% of budget
	        default -> 0.20;              // Default to quality allocation
	    };
	    
	    int categoryBudget = (int) ((TOKEN_BUDGET - TOKEN_BUFFER) * allocation);
	    
	    // Ensure minimum budget per issue (at least 2000 tokens per suggestion)
	    int minBudgetNeeded = issueCount * 2000;
	    
	    return Math.max(categoryBudget, Math.min(minBudgetNeeded, TOKEN_BUDGET / 3));
	}

	/**
	 * Process issues within a single category
	 */
	private CategoryResult processCategoryIssues(List<Map<String, Object>> issues, String category,
	                                           int tokenBudget, Context context, LambdaLogger logger) {
	    List<DeveloperSuggestion> suggestions = new ArrayList<>();
	    int tokensUsed = 0;
	    double cost = 0.0;
	    
	    // Sort issues by priority within category (CRITICAL/HIGH first)
	    List<Map<String, Object>> prioritizedIssues = issues.stream()
	            .sorted((a, b) -> {
	                String severityA = (String) a.getOrDefault("severity", "LOW");
	                String severityB = (String) b.getOrDefault("severity", "LOW");
	                return getSeverityPriority(severityB) - getSeverityPriority(severityA);
	            })
	            .collect(Collectors.toList());
	    
	    for (int i = 0; i < prioritizedIssues.size(); i++) {
	        Map<String, Object> issue = prioritizedIssues.get(i);
	        
	        // Check token budget
	        if (tokensUsed >= tokenBudget) {
	            logger.log(String.format("💰 %s category budget exhausted (%d/%d tokens)", 
	                    category, tokensUsed, tokenBudget));
	            break;
	        }
	        
	        try {
	            // Generate suggestion with category-specific optimization
	            DeveloperSuggestion suggestion = generateCategoryOptimizedSuggestion(issue, category, logger);
	            
	            if (suggestion != null) {
	                suggestions.add(suggestion);
	                tokensUsed += suggestion.getTokensUsed();
	                cost += suggestion.getCost();
	                
	                logger.log(String.format("✅ %s suggestion generated: %s (tokens: %d)", 
	                        category, issue.get("id"), suggestion.getTokensUsed()));
	            }
	            
	        } catch (Exception e) {
	            logger.log(String.format("❌ Error generating %s suggestion for %s: %s", 
	                    category, issue.get("id"), e.getMessage()));
	        }
	        
	        // Add delay between suggestions to prevent throttling
	        if (i < prioritizedIssues.size() - 1) {
	            try {
	                Thread.sleep(1000); // 1 second delay
	            } catch (InterruptedException e) {
	                Thread.currentThread().interrupt();
	                break;
	            }
	        }
	    }
	    
	    return new CategoryResult(suggestions, tokensUsed, cost);
	}

	/**
	 * Generate category-optimized suggestion
	 */
	private DeveloperSuggestion generateCategoryOptimizedSuggestion(Map<String, Object> issue, 
	                                                              String category, LambdaLogger logger) {
	    try {
	        String issueId = (String) issue.get("id");
	        String severity = (String) issue.getOrDefault("severity", "MEDIUM");
	        
	        // Determine model based on category and severity
	        String selectedModel = determineCategoryAwareModel(category, severity);
	        
	        // Build category-specific prompt
	        String prompt = buildCategoryOptimizedPrompt(issue, category);
	        
	        // Optimize token usage for category
	        int maxTokens = calculateCategoryMaxTokens(category, severity);
	        
	        logger.log(String.format("🔍 Generating %s suggestion for %s using %s (max tokens: %d)", 
	                category, issueId, selectedModel, maxTokens));
	        
	        // Generate suggestion with correct method signature
	        NovaInvokerService.NovaResponse novaResponse;
	        if ("TEMPLATE_MODE".equals(selectedModel)) {
	            novaResponse = createCategoryTemplateResponse(prompt, category, maxTokens);
	        } else {
	            // Use the correct method signature
	            novaResponse = novaInvoker.invokeNova(selectedModel, prompt, maxTokens, 0.3, 0.9);
	        }
	        
	        // Parse and return suggestion
	        return parseSuggestionResponse(novaResponse, issue, category);
	        
	    } catch (Exception e) {
	        logger.log("❌ Error in category-optimized suggestion generation: " + e.getMessage());
	        return null;
	    }
	}

	/**
	 * Determine model based on category and severity
	 */
	private String determineCategoryAwareModel(String category, String severity) {
		boolean isHighPriority = "CRITICAL".equalsIgnoreCase(severity) || "HIGH".equalsIgnoreCase(severity);

		return switch (category.toLowerCase()) {
		case "security" -> isHighPriority ? "amazon.nova-pro-v1:0" : "amazon.nova-lite-v1:0";
		case "performance" -> isHighPriority ? "amazon.nova-pro-v1:0" : "amazon.nova-lite-v1:0";
		case "quality" -> isHighPriority ? "amazon.nova-lite-v1:0" : "TEMPLATE_MODE";
		default -> "TEMPLATE_MODE";
		};
	}

	/**
	 * Calculate max tokens per category
	 */
	private int calculateCategoryMaxTokens(String category, String severity) {
	    boolean isHighPriority = "CRITICAL".equalsIgnoreCase(severity) || "HIGH".equalsIgnoreCase(severity);
	    
	    return switch (category.toLowerCase()) {
	        case "security" -> isHighPriority ? 4000 : 3000;
	        case "performance" -> isHighPriority ? 3500 : 2500;  
	        case "quality" -> isHighPriority ? 2500 : 1500;
	        default -> 1500;
	    };
	}

	/**
	 * Build category-optimized prompt for specific categories
	 */
	private String buildCategoryOptimizedPrompt(Map<String, Object> issue, String category) {
	    StringBuilder prompt = new StringBuilder();
	    
	    String language = (String) issue.get("language");
	    String type = (String) issue.get("type");
	    String severity = (String) issue.get("severity");
	    String codeSnippet = (String) issue.get("codeSnippet");
	    String description = (String) issue.get("description");
	    
	    // Category-specific prompt optimization
	    switch (category.toLowerCase()) {
	        case "security":
	            prompt.append("# SECURITY FIX REQUIRED for ").append(type).append(" (").append(severity).append(")\n\n");
	            prompt.append("🔒 PRIORITY: Immediate security remediation needed\n");
	            prompt.append("Language: ").append(language).append("\n");
	            prompt.append("Vulnerability: ").append(description).append("\n\n");
	            break;
	            
	        case "performance":
	            prompt.append("# PERFORMANCE OPTIMIZATION for ").append(type).append(" (").append(severity).append(")\n\n");
	            prompt.append("⚡ PRIORITY: Performance improvement required\n");
	            prompt.append("Language: ").append(language).append("\n");
	            prompt.append("Issue: ").append(description).append("\n\n");
	            break;
	            
	        case "quality":
	            prompt.append("# CODE QUALITY IMPROVEMENT for ").append(type).append(" (").append(severity).append(")\n\n");
	            prompt.append("✨ PRIORITY: Code maintainability enhancement\n");
	            prompt.append("Language: ").append(language).append("\n");
	            prompt.append("Issue: ").append(description).append("\n\n");
	            break;
	            
	        default:
	            return buildSuggestionPrompt(issue); // Fallback to existing method
	    }
	    
	    prompt.append("Code:\n```").append(language != null ? language.toLowerCase() : "text").append("\n");
	    prompt.append(TokenOptimizer.truncateCode(codeSnippet, 500)).append("\n");
	    prompt.append("```\n\n");
	    
	    // Category-specific JSON structure request with issueDescription
	    if ("security".equals(category.toLowerCase())) {
	        prompt.append("Generate SECURITY-FOCUSED fix as JSON with emphasis on vulnerability mitigation.\n");
	        prompt.append("IMPORTANT: Start with a detailed 'issueDescription' that explains what this vulnerability is and how it can be exploited (2-3 sentences):\n");
	    } else if ("performance".equals(category.toLowerCase())) {
	        prompt.append("Generate PERFORMANCE-FOCUSED optimization as JSON with metrics and benchmarks.\n");
	        prompt.append("IMPORTANT: Start with a detailed 'issueDescription' that explains what this performance issue is and how it impacts the application (2-3 sentences):\n");
	    } else {
	        prompt.append("Generate QUALITY-FOCUSED improvement as JSON with maintainability focus.\n");
	        prompt.append("IMPORTANT: Start with a detailed 'issueDescription' that explains what this code quality issue is and why it matters (2-3 sentences):\n");
	    }
	    
	    prompt.append("```json\n{\n");
	    prompt.append("  \"issueDescription\": \"Detailed explanation of what this ").append(type).append(" issue is, how it can be exploited/cause problems, and its potential impact (2-3 sentences)\",\n");
	    prompt.append("  \"immediateFix\": {\n");
	    prompt.append("    \"title\": \"Brief description\",\n");
	    prompt.append("    \"searchCode\": \"Exact problematic code\",\n");
	    prompt.append("    \"replaceCode\": \"Fixed code\",\n");
	    prompt.append("    \"explanation\": \"Why this fixes the issue\"\n");
	    prompt.append("  },\n");
	    prompt.append("  \"bestPractice\": {\n");
	    prompt.append("    \"title\": \"Best practice name\",\n");
	    prompt.append("    \"code\": \"Example implementation\",\n");
	    prompt.append("    \"benefits\": [\"Benefit 1\", \"Benefit 2\"]\n");
	    prompt.append("  },\n");
	    prompt.append("  \"testing\": {\n");
	    prompt.append("    \"testCase\": \"Unit test code\",\n");
	    prompt.append("    \"validationSteps\": [\"Step 1\", \"Step 2\"]\n");
	    prompt.append("  },\n");
	    prompt.append("  \"prevention\": {\n");
	    prompt.append("    \"guidelines\": [\"Guideline 1\", \"Guideline 2\"],\n");
	    prompt.append("    \"tools\": [{\"name\": \"Tool\", \"description\": \"How it helps\"}],\n");
	    prompt.append("    \"codeReviewChecklist\": [\"Check 1\", \"Check 2\"]\n");
	    prompt.append("  }\n");
	    prompt.append("}\n```");
	    
	    return prompt.toString();
	}

	/**
	 * Create category-specific template response
	 */
	private NovaInvokerService.NovaResponse createCategoryTemplateResponse(String prompt, String category, int maxTokens) {
	    String templateResponse = generateCategoryTemplateBasedSuggestion(prompt, category);
	    
	    // Create response object using builder pattern
	    return NovaInvokerService.NovaResponse.builder()
	        .responseText(templateResponse)
	        .inputTokens(50)
	        .outputTokens(150)
	        .totalTokens(200)
	        .estimatedCost(0.0002)
	        .modelId("TEMPLATE_MODE_" + category.toUpperCase())
	        .successful(true)
	        .timestamp(System.currentTimeMillis())
	        .build();
	}

	/**
	 * Generate category-specific template-based suggestion
	 */
	private String generateCategoryTemplateBasedSuggestion(String prompt, String category) {
	    String lowerPrompt = prompt.toLowerCase();
	    
	    // Security category templates
	    if ("security".equals(category.toLowerCase())) {
	        if (lowerPrompt.contains("sql injection") || lowerPrompt.contains("sqli")) {
	            return """
	            {
	              "immediateFix": {
	                "title": "Use Parameterized Queries",
	                "searchCode": "String query = \\"SELECT * FROM users WHERE id = '\\" + userId + \\"'\\";",
	                "replaceCode": "String query = \\"SELECT * FROM users WHERE id = ?\\"; PreparedStatement stmt = connection.prepareStatement(query); stmt.setString(1, userId);",
	                "explanation": "Parameterized queries prevent SQL injection by separating code from data."
	              },
	              "bestPractice": {
	                "title": "Always Use Prepared Statements",
	                "code": "PreparedStatement stmt = connection.prepareStatement(\\"SELECT * FROM users WHERE id = ?\\"); stmt.setString(1, userId);",
	                "benefits": ["Prevents SQL injection", "Better performance", "Cleaner code"]
	              },
	              "testing": {
	                "testCase": "@Test public void testSqlInjectionPrevention() { String maliciousInput = \\"'; DROP TABLE users; --\\"; /* Test should not affect database */ }",
	                "validationSteps": ["Test with malicious input", "Verify database integrity", "Check query logs"]
	              },
	              "prevention": {
	                "guidelines": ["Always use parameterized queries", "Validate input length and format", "Use least privilege database accounts"],
	                "tools": [{"name": "SonarQube", "description": "Static analysis for SQL injection detection"}],
	                "codeReviewChecklist": ["Check for string concatenation in SQL", "Verify parameterized queries usage", "Review input validation"]
	              }
	            }
	            """;
	        } else if (lowerPrompt.contains("xss") || lowerPrompt.contains("cross-site")) {
	            return """
	            {
	              "immediateFix": {
	                "title": "Implement Input Validation and Output Encoding",
	                "searchCode": "output.innerHTML = userInput;",
	                "replaceCode": "output.textContent = sanitizeInput(userInput);",
	                "explanation": "Use textContent instead of innerHTML and sanitize all user inputs to prevent XSS attacks."
	              },
	              "bestPractice": {
	                "title": "Content Security Policy and Input Sanitization",
	                "code": "response.setHeader(\\"Content-Security-Policy\\", \\"default-src 'self'\\"); String safeOutput = StringEscapeUtils.escapeHtml4(userInput);",
	                "benefits": ["Prevents XSS attacks", "Better security posture", "Compliance with security standards"]
	              },
	              "testing": {
	                "testCase": "@Test public void testXSSPrevention() { String maliciousScript = \\"<script>alert('XSS')</script>\\"; /* Test should not execute script */ }",
	                "validationSteps": ["Test with script tags", "Verify output encoding", "Check CSP headers"]
	              },
	              "prevention": {
	                "guidelines": ["Always encode output", "Validate and sanitize input", "Use Content Security Policy"],
	                "tools": [{"name": "OWASP ZAP", "description": "Security testing for XSS vulnerabilities"}],
	                "codeReviewChecklist": ["Check for innerHTML usage", "Verify input sanitization", "Review CSP implementation"]
	              }
	            }
	            """;
	        }
	        // Default security template
	        return createDefaultSecurityTemplate();
	    }
	    
	    // Performance category templates
	    else if ("performance".equals(category.toLowerCase())) {
	        if (lowerPrompt.contains("loop") || lowerPrompt.contains("complexity")) {
	            return """
	            {
	              "immediateFix": {
	                "title": "Optimize Algorithm Complexity",
	                "searchCode": "for(int i=0; i<n; i++) { for(int j=0; j<n; j++) { /* O(n²) operation */ } }",
	                "replaceCode": "// Use HashMap for O(1) lookup instead of nested loops\\nMap<String, Object> lookupMap = new HashMap<>();",
	                "explanation": "Replace nested loops with more efficient data structures to reduce time complexity from O(n²) to O(n)."
	              },
	              "bestPractice": {
	                "title": "Choose Appropriate Data Structures",
	                "code": "// Use HashSet for O(1) contains() instead of ArrayList O(n)\\nSet<String> items = new HashSet<>(Arrays.asList(data));",
	                "benefits": ["Faster execution", "Better scalability", "Reduced CPU usage"]
	              },
	              "testing": {
	                "testCase": "@Test public void testPerformanceImprovement() { /* Benchmark before and after optimization */ }",
	                "validationSteps": ["Measure execution time", "Profile memory usage", "Test with large datasets"]
	              },
	              "prevention": {
	                "guidelines": ["Analyze algorithm complexity", "Use profiling tools", "Consider data structure efficiency"],
	                "tools": [{"name": "JProfiler", "description": "Performance profiling and optimization"}],
	                "codeReviewChecklist": ["Check algorithm complexity", "Review data structure choices", "Verify performance tests"]
	              }
	            }
	            """;
	        }
	        // Default performance template
	        return createDefaultPerformanceTemplate();
	    }
	    
	    // Quality category templates
	    else if ("quality".equals(category.toLowerCase())) {
	        if (lowerPrompt.contains("cyclomatic") || lowerPrompt.contains("complexity")) {
	            return """
	            {
	              "immediateFix": {
	                "title": "Extract Method to Reduce Complexity",
	                "searchCode": "public void complexMethod() { /* 20+ lines of complex logic */ }",
	                "replaceCode": "public void complexMethod() { validateInput(); processData(); generateOutput(); }\\nprivate void validateInput() { /* validation logic */ }",
	                "explanation": "Break down complex methods into smaller, focused methods to improve readability and maintainability."
	              },
	              "bestPractice": {
	                "title": "Single Responsibility Principle",
	                "code": "// Each method should have one clear responsibility\\npublic class UserService { public void createUser() { /* only user creation */ } }",
	                "benefits": ["Better maintainability", "Easier testing", "Improved code clarity"]
	              },
	              "testing": {
	                "testCase": "@Test public void testEachMethodSeparately() { /* Test individual methods */ }",
	                "validationSteps": ["Test each extracted method", "Verify overall functionality", "Check code coverage"]
	              },
	              "prevention": {
	                "guidelines": ["Keep methods focused", "Limit cyclomatic complexity", "Use design patterns appropriately"],
	                "tools": [{"name": "SonarQube", "description": "Code quality and complexity analysis"}],
	                "codeReviewChecklist": ["Check method length", "Verify single responsibility", "Review complexity metrics"]
	              }
	            }
	            """;
	        }
	        // Default quality template
	        return createDefaultQualityTemplate();
	    }
	    
	    // Fallback template
	    return createFallbackJsonResponse("Template mode for " + category);
	}

	/**
	 * Parse suggestion response with corrected signature matching existing project
	 */
	private DeveloperSuggestion parseSuggestionResponse(NovaInvokerService.NovaResponse novaResponse, 
	                                                   Map<String, Object> originalIssue, String category) {
	    try {
	        String issueId = (String) originalIssue.get("id");
	        String response = novaResponse.getResponseText();
	        int tokensUsed = novaResponse.getTotalTokens();
	        double cost = novaResponse.getEstimatedCost();
	        String modelUsed = novaResponse.getModelId();
	        
	        // Extract JSON from response
	        String jsonContent = extractJsonFromResponse(response);
	        Map<String, Object> suggestionData = objectMapper.readValue(jsonContent, Map.class);

	        return DeveloperSuggestion.builder()
	                .issueId(issueId)
	                .issueType((String) originalIssue.get("type"))
	                .issueCategory(category) // Use the category parameter
	                .issueSeverity((String) originalIssue.get("severity"))
	                .language((String) originalIssue.get("language"))
	                .immediateFix(parseImmediateFix(suggestionData))
	                .bestPractice(parseBestPractice(suggestionData))
	                .testing(parseTesting(suggestionData))
	                .prevention(parsePrevention(suggestionData))
	                .tokensUsed(tokensUsed)
	                .cost(cost)
	                .timestamp(System.currentTimeMillis())
	                .modelUsed(modelUsed)
	                .build();

	    } catch (Exception e) {
	        log.error("❌ Error parsing suggestion response for issue {}: {}", originalIssue.get("id"), e.getMessage());
	        return createFallbackSuggestion((String) originalIssue.get("id"), originalIssue, 
	                                      novaResponse.getTotalTokens(), novaResponse.getEstimatedCost());
	    }
	}

	// Helper methods for default templates
	private String createDefaultSecurityTemplate() {
	    return """
	    {
	      "immediateFix": {
	        "title": "Security Review Required",
	        "searchCode": "Review the identified security vulnerability",
	        "replaceCode": "Apply appropriate security measures according to OWASP guidelines",
	        "explanation": "This security issue requires manual review and implementation of appropriate security controls."
	      },
	      "bestPractice": {
	        "title": "Follow OWASP Security Guidelines",
	        "code": "// Implement security controls according to OWASP Top 10\\n// Use security frameworks and libraries",
	        "benefits": ["Improved security posture", "Compliance with standards", "Reduced vulnerability risk"]
	      },
	      "testing": {
	        "testCase": "// Add security-focused unit tests and integration tests",
	        "validationSteps": ["Security code review", "Penetration testing", "Vulnerability scanning"]
	      },
	      "prevention": {
	        "guidelines": ["Follow secure coding practices", "Regular security training", "Use security linters"],
	        "tools": [{"name": "OWASP ZAP", "description": "Security vulnerability scanner"}],
	        "codeReviewChecklist": ["Security implications", "Input validation", "Authentication and authorization"]
	      }
	    }
	    """;
	}

	private String createDefaultPerformanceTemplate() {
	    return """
	    {
	      "immediateFix": {
	        "title": "Performance Optimization Required",
	        "searchCode": "Review the identified performance bottleneck",
	        "replaceCode": "Apply performance optimization techniques",
	        "explanation": "This performance issue requires analysis and optimization of the identified code section."
	      },
	      "bestPractice": {
	        "title": "Performance Best Practices",
	        "code": "// Use efficient algorithms and data structures\\n// Profile and measure performance improvements",
	        "benefits": ["Faster execution", "Better user experience", "Reduced resource consumption"]
	      },
	      "testing": {
	        "testCase": "// Add performance benchmarks and load tests",
	        "validationSteps": ["Performance profiling", "Load testing", "Memory usage analysis"]
	      },
	      "prevention": {
	        "guidelines": ["Profile regularly", "Choose efficient algorithms", "Monitor performance metrics"],
	        "tools": [{"name": "JProfiler", "description": "Performance profiling and analysis"}],
	        "codeReviewChecklist": ["Algorithm complexity", "Memory usage", "Performance impact"]
	      }
	    }
	    """;
	}

	private String createDefaultQualityTemplate() {
	    return """
	    {
	      "immediateFix": {
	        "title": "Code Quality Improvement Required",
	        "searchCode": "Review the identified code quality issue",
	        "replaceCode": "Apply code quality best practices and refactoring",
	        "explanation": "This code quality issue requires refactoring to improve maintainability and readability."
	      },
	      "bestPractice": {
	        "title": "Code Quality Best Practices",
	        "code": "// Follow SOLID principles\\n// Use meaningful names and clear structure",
	        "benefits": ["Better maintainability", "Easier debugging", "Improved team productivity"]
	      },
	      "testing": {
	        "testCase": "// Add comprehensive unit tests for refactored code",
	        "validationSteps": ["Code review", "Test coverage analysis", "Static code analysis"]
	      },
	      "prevention": {
	        "guidelines": ["Follow coding standards", "Regular refactoring", "Use static analysis tools"],
	        "tools": [{"name": "SonarQube", "description": "Code quality and maintainability analysis"}],
	        "codeReviewChecklist": ["Code complexity", "Naming conventions", "Design patterns usage"]
	      }
	    }
	    """;
	}

	/**
	 * Helper class for category processing results
	 */
	private static class CategoryResult {
	    final List<DeveloperSuggestion> suggestions;
	    final int tokensUsed;
	    final double cost;
	    
	    CategoryResult(List<DeveloperSuggestion> suggestions, int tokensUsed, double cost) {
	        this.suggestions = suggestions;
	        this.tokensUsed = tokensUsed;
	        this.cost = cost;
	    }
	}

	/**
	 * Get severity priority for sorting
	 */
	private int getSeverityPriority(String severity) {
	    return switch (severity.toUpperCase()) {
	        case "CRITICAL" -> 4;
	        case "HIGH" -> 3;
	        case "MEDIUM" -> 2;
	        case "LOW" -> 1;
	        default -> 0;
	    };
	}
	
	/**
	 * Process issues sequentially with proper delays for Nova API
	 */
	private BatchResult processIssuesSequentially(List<Map<String, Object>> issues, Context context, LambdaLogger logger)
	        throws InterruptedException {
	    List<DeveloperSuggestion> suggestions = new ArrayList<>();
	    int tokensUsed = 0;
	    double cost = 0.0;
	    int successCount = 0;
	    int failureCount = 0;
	    long baseDelay = 1000L; // 1 second base delay - 5x faster

	    for (int i = 0; i < issues.size(); i++) {
	        try {
	            Map<String, Object> issue = issues.get(i);
	            logger.log(String.format("🔍 Processing issue %d/%d: %s", 
	                i + 1, issues.size(), issue.get("id")));

	            // Check remaining time
	            if (context.getRemainingTimeInMillis() < TIMEOUT_BUFFER_MS + baseDelay) {
	                logger.log("⏰ Insufficient time remaining, stopping processing");
	                break;
	            }

	            // Generate suggestion with enhanced error handling
	            DeveloperSuggestion suggestion = generateSuggestionForIssue(issue, logger);
	            
	            if (suggestion != null) {
	                suggestions.add(suggestion);
	                tokensUsed += suggestion.getTokensUsed();
	                cost += suggestion.getCost();
	                
	                // Check if this is a real suggestion or fallback
	                if (suggestion.getModelUsed().contains("fallback")) {
	                    failureCount++;
	                    logger.log("⚠️ Fallback suggestion created for issue: " + issue.get("id"));
	                } else {
	                    successCount++;
	                    logger.log("✅ Successful suggestion generated for issue: " + issue.get("id"));
	                }
	            }

	            // Progressive delay - but cap maximum delay for speed
	            if (i < issues.size() - 1) {
	                long adaptiveDelay = calculateSequentialDelay(baseDelay, failureCount, successCount);
	                // Cap maximum delay at 3 seconds for speed
	                adaptiveDelay = Math.min(adaptiveDelay, 3000L);
	                logger.log(String.format("⏸️ Waiting %dms before next request (adaptive delay, capped)", adaptiveDelay));
	                Thread.sleep(adaptiveDelay);
	            }

	        } catch (Exception e) {
	            logger.log("❌ Error processing issue " + i + ": " + e.getMessage());
	            failureCount++;
	        }
	    }

	    logger.log(String.format("📊 Sequential processing complete: %d success, %d failures, %d total tokens, $%.4f cost",
	        successCount, failureCount, tokensUsed, cost));

	    return new BatchResult(suggestions, tokensUsed, cost);
	}

	/**
	 * Calculate adaptive delay based on success/failure rate
	 */
	private long calculateSequentialDelay(long baseDelay, int failures, int successes) {
	    // Much more aggressive delay reduction for speed
	    if (failures == 0) {
	        return 500L; // Only 0.5 seconds when everything works
	    }
	    
	    double failureRate = (double) failures / (failures + successes);
	    if (failureRate > 0.5) {
	        return baseDelay * 2; // 2 seconds for high failure rate
	    } else if (failureRate > 0.25) {
	        return (long) (baseDelay * 1.5); // 1.5 seconds for medium failure rate
	    } else {
	        return baseDelay; // 1 second for low failure rate
	    }
	}
	
	/**
	 * Create batches from issues list
	 */
	private List<List<Map<String, Object>>> createBatches(List<Map<String, Object>> issues, int batchSize) {
		List<List<Map<String, Object>>> batches = new ArrayList<>();

		for (int i = 0; i < issues.size(); i += batchSize) {
			batches.add(issues.subList(i, Math.min(i + batchSize, issues.size())));
		}

		return batches;
	}

	/**
	 * Calculate delay between batches with progressive backoff
	 */
	private long calculateBatchDelay(int batchIndex) {
		// Progressive delay: increases slightly with each batch
		long baseDelay = BATCH_DELAY_MS;
		long progressiveDelay = baseDelay + (batchIndex * 500L); // Add 500ms per batch
		return Math.min(progressiveDelay, 10000L); // Cap at 10 seconds
	}
	
	/**
	 * Calculate adaptive batch delay based on current load and Nova API performance
	 */
	private long calculateAdaptiveBatchDelay(int batchIndex, int tokensUsed) {
	    // Base delay for Nova Premier stability
	    long baseDelay = BATCH_DELAY_MS;
	    
	    // Increase delay based on batch number (cumulative throttling protection)
	    long scalingDelay = baseDelay * Math.min(batchIndex, 5); // Cap scaling at 5x
	    
	    // Additional delay based on token usage (API load indicator)
	    long tokenBasedDelay = (tokensUsed / 1000) * 500L; // 500ms per 1K tokens
	    
	    // Progressive delay: starts high, stays high for Nova
	    long progressiveDelay = Math.max(scalingDelay + tokenBasedDelay, 5000L); // Minimum 5s
	    
	    return Math.min(progressiveDelay, 30000L); // Cap at 30s
	}
	
	/**
	 * Check if we should slow down based on statistics
	 */
	private boolean shouldSlowDown(Map<String, Object> stats) {
		Map<String, Integer> throttleCounts = (Map<String, Integer>) stats.get("throttleCounts");
		if (throttleCounts != null && !throttleCounts.isEmpty()) {
			int totalThrottles = throttleCounts.values().stream().mapToInt(Integer::intValue).sum();
			return totalThrottles > 2; // Slow down if we've seen more than 2 throttles
		}

		String circuitState = (String) stats.get("circuitState");
		return "HALF_OPEN".equals(circuitState) || "OPEN".equals(circuitState);
	}

	/**
	 * Process a batch of issues with controlled parallelism
	 */
	private BatchResult processBatch(List<Map<String, Object>> batch, Context context, LambdaLogger logger)
			throws InterruptedException, ExecutionException {

		List<Future<DeveloperSuggestion>> futures = new ArrayList<>();

		// Submit tasks for parallel processing
		for (Map<String, Object> issue : batch) {
			Future<DeveloperSuggestion> future = executorService
					.submit(() -> generateSuggestionForIssue(issue, logger));
			futures.add(future);

			// Small delay between submissions to prevent burst
			Thread.sleep(100);
		}

		// Collect results
		List<DeveloperSuggestion> suggestions = new ArrayList<>();
		int tokensUsed = 0;
		double cost = 0.0;

		for (int i = 0; i < futures.size(); i++) {
			try {
				// Use timeout to prevent hanging
				long remainingTime = context.getRemainingTimeInMillis() - TIMEOUT_BUFFER_MS;
				DeveloperSuggestion suggestion = futures.get(i).get(Math.min(remainingTime, 60000L),
						TimeUnit.MILLISECONDS);

				if (suggestion != null) {
					suggestions.add(suggestion);
					tokensUsed += suggestion.getTokensUsed();
					cost += suggestion.getCost();
				}
			} catch (TimeoutException e) {
				logger.log("⏰ Timeout waiting for suggestion generation");
				futures.get(i).cancel(true);
			} catch (Exception e) {
				logger.log("❌ Error collecting suggestion result: " + e.getMessage());
			}
		}

		return new BatchResult(suggestions, tokensUsed, cost);
	}

	/**
	 * Generate suggestion for a single issue with hybrid model selection
	 */
	private DeveloperSuggestion generateSuggestionForIssue(Map<String, Object> issue, LambdaLogger logger) {
		try {
			String issueId = (String) issue.get("id");
			logger.log("🔍 Generating suggestion for issue: " + issueId);

			// Determine which model to use for this issue
			String selectedModel = determineModelForIssue(issue);
			logger.log("🎯 Using model: " + selectedModel + " for issue: " + issueId);

			// Build optimized prompt
			String prompt = buildSuggestionPrompt(issue);

			// Add token optimization to prevent exceeding limits
			int estimatedTokens = TokenOptimizer.estimateTokens(prompt);
			int adjustedMaxTokens = Math.min(MAX_TOKENS, TOKEN_BUDGET / 10); // Limit per issue

			// Call appropriate Nova model or template with hybrid strategy
			NovaInvokerService.NovaResponse novaResponse;
			if ("TEMPLATE_MODE".equals(selectedModel)) {
				// Use template-based response
				novaResponse = createSimpleTemplateResponse(prompt, adjustedMaxTokens);
			} else {
				// Use regular Nova model
				novaResponse = novaInvoker.invokeNova(selectedModel, prompt, adjustedMaxTokens, 0.3, 0.9);
			}

			if (!novaResponse.isSuccessful()) {
				logger.log("❌ Model call failed for issue " + issueId + ": " + novaResponse.getErrorMessage());
				return createFallbackSuggestion(issueId, issue, 0, 0.0);
			}

			// Parse response
			return parseSuggestionResponse(issueId, novaResponse.getResponseText(), novaResponse.getTotalTokens(),
					novaResponse.getEstimatedCost(), issue, logger, selectedModel);

		} catch (NovaInvokerService.NovaInvokerException e) {
			// Handle circuit breaker or other critical errors
			logger.log("🚫 Nova invoker error: " + e.getMessage());
			return createFallbackSuggestion((String) issue.get("id"), issue, 0, 0.0);
		} catch (Exception e) {
			logger.log("❌ Error generating suggestion: " + e.getMessage());
			log.error("Suggestion generation error details:", e);
			return createFallbackSuggestion((String) issue.get("id"), issue, 0, 0.0);
		}
	}

	/**
	 * Create a simple template response when TEMPLATE_MODE is selected
	 */
	private NovaInvokerService.NovaResponse createSimpleTemplateResponse(String prompt, int maxTokens) {
		String templateResponse = generateTemplateBasedSuggestion(prompt);
		
		// Create a simple response object using builder pattern
		return NovaInvokerService.NovaResponse.builder()
		    .responseText(templateResponse)
		    .inputTokens(50)
		    .outputTokens(100)
		    .totalTokens(150)
		    .estimatedCost(0.0001)
		    .modelId("TEMPLATE_MODE")
		    .successful(true)
		    .timestamp(System.currentTimeMillis())
		    .build();
	}

	/**
	 * Generate template-based suggestion from prompt analysis
	 */
	private String generateTemplateBasedSuggestion(String prompt) {
		String lowerPrompt = prompt.toLowerCase();
		
		if (lowerPrompt.contains("sql injection") || lowerPrompt.contains("sqli")) {
			return """
			{
			  "immediateFix": {
			    "title": "Use Parameterized Queries",
			    "searchCode": "String query = \\"SELECT * FROM users WHERE id = '\\" + userId + \\"'\\";",
			    "replaceCode": "String query = \\"SELECT * FROM users WHERE id = ?\\"; PreparedStatement stmt = connection.prepareStatement(query); stmt.setString(1, userId);",
			    "explanation": "Parameterized queries prevent SQL injection by separating code from data."
			  },
			  "bestPractice": {
			    "title": "Always Use Prepared Statements",
			    "code": "PreparedStatement stmt = connection.prepareStatement(\\"SELECT * FROM users WHERE id = ?\\"); stmt.setString(1, userId);",
			    "benefits": ["Prevents SQL injection", "Better performance", "Cleaner code"]
			  },
			  "testing": {
			    "testCase": "@Test public void testSqlInjectionPrevention() { String maliciousInput = \\"'; DROP TABLE users; --\\"; /* Test should not affect database */ }",
			    "validationSteps": ["Test with malicious input", "Verify database integrity", "Check query logs"]
			  },
			  "prevention": {
			    "guidelines": ["Always use parameterized queries", "Validate input length and format", "Use least privilege database accounts"],
			    "tools": [{"name": "SonarQube", "description": "Static analysis for SQL injection detection"}],
			    "codeReviewChecklist": ["Check for string concatenation in SQL", "Verify parameterized queries usage", "Review input validation"]
			  }
			}
			""";
		}
		
		// Default template for other issues
		return """
		{
		  "immediateFix": {
		    "title": "Review and Apply Best Practices",
		    "searchCode": "// Review the identified code section",
		    "replaceCode": "// Apply appropriate security measures and best practices",
		    "explanation": "This issue requires manual review and application of security best practices."
		  },
		  "bestPractice": {
		    "title": "Follow Security Guidelines",
		    "code": "// Implement according to OWASP guidelines",
		    "benefits": ["Improved security", "Better maintainability", "Reduced vulnerabilities"]
		  },
		  "testing": {
		    "testCase": "// Add appropriate unit tests",
		    "validationSteps": ["Review code changes", "Test functionality", "Verify security measures"]
		  },
		  "prevention": {
		    "guidelines": ["Follow OWASP guidelines", "Regular security reviews", "Use static analysis tools"],
		    "tools": [{"name": "Static Analysis", "description": "Automated security scanning"}],
		    "codeReviewChecklist": ["Security implications", "Best practices compliance", "Test coverage"]
		  }
		}
		""";
	}

	private String buildSuggestionPrompt(Map<String, Object> issue) {
		StringBuilder prompt = new StringBuilder();

		String language = (String) issue.get("language");
		String type = (String) issue.get("type");
		String severity = (String) issue.get("severity");
		String codeSnippet = (String) issue.get("codeSnippet");
		String description = (String) issue.get("description");

		// Use concise prompt to optimize token usage
		prompt.append("# Fix Required for ").append(type).append(" (").append(severity).append(")\n\n");
		prompt.append("Language: ").append(language).append("\n");
		prompt.append("Issue: ").append(description).append("\n\n");

		prompt.append("Code:\n```").append(language.toLowerCase()).append("\n");
		prompt.append(TokenOptimizer.truncateCode(codeSnippet, 500)).append("\n");
		prompt.append("```\n\n");

		prompt.append("Generate comprehensive fix as JSON:\n");
		prompt.append("```json\n{\n");
		prompt.append("  \"immediateFix\": {\n");
		prompt.append("    \"title\": \"Brief description\",\n");
		prompt.append("    \"searchCode\": \"Exact problematic code\",\n");
		prompt.append("    \"replaceCode\": \"Fixed code\",\n");
		prompt.append("    \"explanation\": \"Why this fixes the issue\"\n");
		prompt.append("  },\n");
		prompt.append("  \"bestPractice\": {\n");
		prompt.append("    \"title\": \"Best practice name\",\n");
		prompt.append("    \"code\": \"Example implementation\",\n");
		prompt.append("    \"benefits\": [\"Benefit 1\", \"Benefit 2\"]\n");
		prompt.append("  },\n");
		prompt.append("  \"testing\": {\n");
		prompt.append("    \"testCase\": \"Unit test code\",\n");
		prompt.append("    \"validationSteps\": [\"Step 1\", \"Step 2\"]\n");
		prompt.append("  },\n");
		prompt.append("  \"prevention\": {\n");
		prompt.append("    \"guidelines\": [\"Guideline 1\", \"Guideline 2\"],\n");
		prompt.append("    \"tools\": [{\"name\": \"Tool\", \"description\": \"How it helps\"}],\n");
		prompt.append("    \"codeReviewChecklist\": [\"Check 1\", \"Check 2\"]\n");
		prompt.append("  }\n");
		prompt.append("}\n```");

		return prompt.toString();
	}

	private DeveloperSuggestion parseSuggestionResponse(String issueId, String response, int tokensUsed, double cost,
			Map<String, Object> originalIssue, LambdaLogger logger, String modelUsed) {
		try {
			// Extract JSON from response
			String jsonContent = extractJsonFromResponse(response);
			Map<String, Object> suggestionData = objectMapper.readValue(jsonContent, Map.class);

			return DeveloperSuggestion.builder().issueId(issueId).issueType((String) originalIssue.get("type"))
					.issueCategory((String) originalIssue.get("category"))
					.issueSeverity((String) originalIssue.get("severity"))
					.language((String) originalIssue.get("language"))
					.issueDescription((String) suggestionData.getOrDefault("issueDescription", generateDefaultIssueDescription(originalIssue)))
					.immediateFix(parseImmediateFix(suggestionData))
					.bestPractice(parseBestPractice(suggestionData)).testing(parseTesting(suggestionData))
					.prevention(parsePrevention(suggestionData)).tokensUsed(tokensUsed).cost(cost)
					.timestamp(System.currentTimeMillis()).modelUsed(modelUsed).build();

		} catch (Exception e) {
			logger.log("❌ Error parsing suggestion response for issue " + issueId + ": " + e.getMessage());
			return createFallbackSuggestion(issueId, originalIssue, tokensUsed, cost);
		}
	}

	private String extractJsonFromResponse(String response) {
		// Try to find JSON within code blocks first
		int jsonStart = response.indexOf("```json");
		int jsonEnd = response.lastIndexOf("```");

		if (jsonStart != -1 && jsonEnd != -1 && jsonStart < jsonEnd) {
			String jsonContent = response.substring(jsonStart + 7, jsonEnd).trim();
			return sanitizeJsonString(jsonContent);
		}

		// Fallback: try to find JSON without code blocks
		jsonStart = response.indexOf("{");
		jsonEnd = response.lastIndexOf("}") + 1;

		if (jsonStart != -1 && jsonEnd > jsonStart) {
			String jsonContent = response.substring(jsonStart, jsonEnd);
			return sanitizeJsonString(jsonContent);
		}

		throw new IllegalArgumentException("No valid JSON found in response");
	}

	/**
	 * Sanitize JSON string to handle character escaping issues
	 */
	/**
	 * Enhanced JSON sanitization with proper escape sequence handling
	 */
	private String sanitizeJsonString(String jsonContent) {
	    try {
	        // Pre-validate JSON structure
	        if (jsonContent == null || jsonContent.trim().isEmpty()) {
	            return createEnhancedFallbackJsonResponse("Empty content");
	        }
	        
	        String sanitized = jsonContent;
	        
	        sanitized = sanitized
	            .replaceAll("\\{\\s+\"", "{\"")  // Remove extra spaces after opening brace
	            .replaceAll("\"\\s*:\\s*\\{\\s+\"", "\":{\"")  // Clean up object formatting
	            .replace("\\\\n", "\\n")      // Fix double-escaped newlines
	            .replace("\\\\t", "\\t")      // Fix double-escaped tabs  
	            .replace("\\\\r", "\\r")      // Fix double-escaped carriage returns
	            .replace("\\\\\"", "\\\"")    // Fix double-escaped quotes
	            .replace("\\\\\\\\", "\\\\"); // Fix double-escaped backslashes // Fix double-escaped backslashes
	        
	        // Step 2: Handle unescaped control characters in strings
	        sanitized = sanitized
	            .replaceAll("\"([^\"]*?)\\n([^\"]*?)\"", "\"$1\\\\n$2\"")  // Escape unescaped newlines in strings
	            .replaceAll("\"([^\"]*?)\\t([^\"]*?)\"", "\"$1\\\\t$2\"")  // Escape unescaped tabs in strings
	            .replaceAll("\"([^\"]*?)\\r([^\"]*?)\"", "\"$1\\\\r$2\""); // Escape unescaped carriage returns in strings
	        
	        // Step 3: Remove actual control characters that shouldn't be in JSON
	        sanitized = sanitized.replaceAll("[\\x00-\\x1F\\x7F]", "");
	        
	        // Step 4: Fix trailing commas
	        sanitized = sanitized.replaceAll(",\\s*([}\\]])", "$1");
	        
	        // Step 5: Ensure proper JSON structure
	        sanitized = sanitized.trim();
	        if (!sanitized.startsWith("{") || !sanitized.endsWith("}")) {
	            log.warn("⚠️ Invalid JSON structure, wrapping content");
	            return createEnhancedFallbackJsonResponse(sanitized);
	        }
	        
	        // Step 6: Test parse to ensure validity
	        try {
	            objectMapper.readTree(sanitized);
	            log.debug("✅ JSON sanitization successful");
	            return sanitized;
	        } catch (Exception parseTest) {
	            log.warn("⚠️ JSON validation failed after sanitization: {}", parseTest.getMessage());
	            
	            // Try one more aggressive fix for common issues
	            String aggressiveFix = performAggressiveJsonFix(sanitized);
	            try {
	                objectMapper.readTree(aggressiveFix);
	                log.info("✅ Aggressive JSON fix successful");
	                return aggressiveFix;
	            } catch (Exception e) {
	                log.warn("❌ Aggressive fix also failed, using enhanced fallback");
	                return createEnhancedFallbackJsonResponse(jsonContent);
	            }
	        }
	        
	    } catch (Exception e) {
	        log.error("❌ JSON sanitization failed: {}", e.getMessage());
	        return createEnhancedFallbackJsonResponse(jsonContent);
	    }
	}

	/**
	 * Aggressive JSON fix for severely malformed JSON
	 */
	private String performAggressiveJsonFix(String jsonContent) {
	    try {
	        // Remove problematic escape sequences and rebuild clean JSON
	        String content = jsonContent;
	        
	        // Extract key content using regex patterns
	        String title = extractJsonValue(content, "title");
	        String searchCode = extractJsonValue(content, "searchCode");
	        String replaceCode = extractJsonValue(content, "replaceCode");
	        String explanation = extractJsonValue(content, "explanation");
	        
	        // Try to extract issue context from the content
	        String issueType = extractContextualInfo(content, "type");
	        String category = extractContextualInfo(content, "category");
	        String severity = extractContextualInfo(content, "severity");
	        
	        // Build clean JSON manually with category-aware responses
	        StringBuilder cleanJson = new StringBuilder();
	        cleanJson.append("{\n");
	        cleanJson.append("  \"immediateFix\": {\n");
	        cleanJson.append("    \"title\": \"").append(cleanString(title.isEmpty() ? generateContextualTitle(issueType, category, severity) : title)).append("\",\n");
	        cleanJson.append("    \"searchCode\": \"").append(cleanString(searchCode.isEmpty() ? "Review the identified code section" : searchCode)).append("\",\n");
	        cleanJson.append("    \"replaceCode\": \"").append(cleanString(replaceCode.isEmpty() ? generateContextualFix(issueType, category) : replaceCode)).append("\",\n");
	        cleanJson.append("    \"explanation\": \"").append(cleanString(explanation.isEmpty() ? generateContextualExplanation(issueType, category, severity) : explanation)).append("\"\n");
	        cleanJson.append("  },\n");
	        cleanJson.append("  \"bestPractice\": {\n");
	        cleanJson.append("    \"title\": \"").append(generateBestPracticeTitle(category)).append("\",\n");
	        cleanJson.append("    \"code\": \"").append(generateBestPracticeCode(issueType, category)).append("\",\n");
	        cleanJson.append("    \"benefits\": ").append(generateBenefits(category)).append("\n");
	        cleanJson.append("  },\n");
	        cleanJson.append("  \"testing\": {\n");
	        cleanJson.append("    \"testCase\": \"").append(generateTestCase(issueType, category)).append("\",\n");
	        cleanJson.append("    \"validationSteps\": ").append(generateValidationSteps(category)).append("\n");
	        cleanJson.append("  },\n");
	        cleanJson.append("  \"prevention\": {\n");
	        cleanJson.append("    \"guidelines\": ").append(generateGuidelines(category)).append(",\n");
	        cleanJson.append("    \"tools\": ").append(generateTools(category)).append(",\n");
	        cleanJson.append("    \"codeReviewChecklist\": ").append(generateChecklist(category)).append("\n");
	        cleanJson.append("  }\n");
	        cleanJson.append("}");
	        
	        return cleanJson.toString();
	        
	    } catch (Exception e) {
	        log.error("Aggressive fix failed: {}", e.getMessage());
	        throw e;
	    }
	}
	
	private String extractContextualInfo(String content, String key) {
	    try {
	        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"";
	        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
	        java.util.regex.Matcher m = p.matcher(content);
	        return m.find() ? m.group(1) : "";
	    } catch (Exception e) {
	        return "";
	    }
	}
	
	private String generateContextualTitle(String issueType, String category, String severity) {
	    if ("security".equalsIgnoreCase(category)) {
	        return severity.equalsIgnoreCase("critical") ? "Critical Security Vulnerability" : "Security Enhancement Required";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "Performance Optimization for " + (issueType.isEmpty() ? "Code" : issueType);
	    }
	    return "Code Quality Improvement Required";
	}
	
	private String generateContextualFix(String issueType, String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "Apply appropriate security controls and input validation";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "Optimize the code for better performance and resource utilization";
	    }
	    return "Refactor code following best practices and coding standards";
	}
	
	private String generateContextualExplanation(String issueType, String category, String severity) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "This " + severity + " security issue requires immediate attention to prevent potential vulnerabilities.";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "This performance issue can impact application responsiveness and should be optimized.";
	    }
	    return "This code quality issue should be addressed to improve maintainability and reliability.";
	}
	
	private String generateBestPracticeTitle(String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "Follow OWASP Security Guidelines";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "Apply Performance Best Practices";
	    }
	    return "Follow Clean Code Principles";
	}
	
	private String generateBestPracticeCode(String issueType, String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "// Validate all inputs\\n// Use parameterized queries\\n// Implement proper authentication";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "// Use efficient algorithms\\n// Implement caching where appropriate\\n// Optimize database queries";
	    }
	    return "// Follow SOLID principles\\n// Write self-documenting code\\n// Maintain consistent style";
	}
	
	private String generateBenefits(String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "[\"Enhanced security posture\", \"Compliance with standards\", \"Reduced vulnerability risk\"]";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "[\"Improved response times\", \"Better resource utilization\", \"Enhanced scalability\"]";
	    }
	    return "[\"Better maintainability\", \"Improved code quality\", \"Reduced technical debt\"]";
	}
	
	private String generateTestCase(String issueType, String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "// Test with malicious inputs\\n// Verify security controls\\n// Check access restrictions";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "// Benchmark performance\\n// Test with load\\n// Monitor resource usage";
	    }
	    return "// Unit test coverage\\n// Integration tests\\n// Edge case validation";
	}
	
	private String generateValidationSteps(String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "[\"Security code review\", \"Penetration testing\", \"Vulnerability scanning\"]";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "[\"Performance profiling\", \"Load testing\", \"Resource monitoring\"]";
	    }
	    return "[\"Code review\", \"Unit testing\", \"Integration testing\"]";
	}
	
	private String generateGuidelines(String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "[\"Follow secure coding practices\", \"Regular security training\", \"Use security tools\"]";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "[\"Profile before optimizing\", \"Focus on bottlenecks\", \"Monitor performance metrics\"]";
	    }
	    return "[\"Follow coding standards\", \"Write clean code\", \"Regular refactoring\"]";
	}
	
	private String generateTools(String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "[{\"name\": \"OWASP ZAP\", \"description\": \"Security vulnerability scanner\"}, {\"name\": \"SonarQube\", \"description\": \"Code security analysis\"}]";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "[{\"name\": \"JProfiler\", \"description\": \"Performance profiling\"}, {\"name\": \"Apache JMeter\", \"description\": \"Load testing\"}]";
	    }
	    return "[{\"name\": \"SonarQube\", \"description\": \"Code quality analysis\"}, {\"name\": \"CheckStyle\", \"description\": \"Code style checker\"}]";
	}
	
	private String generateChecklist(String category) {
	    if ("security".equalsIgnoreCase(category)) {
	        return "[\"Input validation\", \"Authentication checks\", \"Authorization verification\"]";
	    } else if ("performance".equalsIgnoreCase(category)) {
	        return "[\"Algorithm efficiency\", \"Resource usage\", \"Caching opportunities\"]";
	    }
	    return "[\"Code clarity\", \"Documentation\", \"Test coverage\"]";
	}

	/**
	 * Extract JSON value with fallback
	 */
	private String extractJsonValue(String content, String key) {
	    try {
	        // Try to find the value after the key
	        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"";
	        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL);
	        java.util.regex.Matcher m = p.matcher(content);
	        
	        if (m.find()) {
	            return m.group(1);
	        }
	        
	        // Fallback based on key type
	        return switch (key) {
	            case "title" -> "Security Fix Required";
	            case "searchCode" -> "Review the identified code section";
	            case "replaceCode" -> "Apply appropriate security measures";
	            case "explanation" -> "This issue requires security review and implementation.";
	            default -> "Manual review required";
	        };
	        
	    } catch (Exception e) {
	        return "Manual review required";
	    }
	}

	/**
	 * Clean string for JSON inclusion
	 */
	private String cleanString(String input) {
	    if (input == null) return "";
	    
	    return input
	        .replace("\\", "\\\\")    // Escape backslashes
	        .replace("\"", "\\\"")    // Escape quotes
	        .replace("\n", "\\n")     // Escape newlines
	        .replace("\r", "\\r")     // Escape carriage returns
	        .replace("\t", "\\t")     // Escape tabs
	        .replaceAll("[\\x00-\\x1F\\x7F]", "") // Remove control characters
	        .trim();
	}

	// Helper methods for parsing suggestion components
	private DeveloperSuggestion.ImmediateFix parseImmediateFix(Map<String, Object> data) {
		Map<String, Object> fixData = (Map<String, Object>) data.get("immediateFix");
		if (fixData == null)
			return null;

		return DeveloperSuggestion.ImmediateFix.builder().title((String) fixData.get("title"))
				.searchCode((String) fixData.get("searchCode")).replaceCode((String) fixData.get("replaceCode"))
				.explanation((String) fixData.get("explanation")).build();
	}

	private DeveloperSuggestion.BestPractice parseBestPractice(Map<String, Object> data) {
		Map<String, Object> practiceData = (Map<String, Object>) data.get("bestPractice");
		if (practiceData == null)
			return null;

		return DeveloperSuggestion.BestPractice.builder().title((String) practiceData.get("title"))
				.code((String) practiceData.get("code")).benefits((List<String>) practiceData.get("benefits")).build();
	}

	private DeveloperSuggestion.Testing parseTesting(Map<String, Object> data) {
		Map<String, Object> testingData = (Map<String, Object>) data.get("testing");
		if (testingData == null)
			return null;

		return DeveloperSuggestion.Testing.builder().testCase((String) testingData.get("testCase"))
				.validationSteps((List<String>) testingData.get("validationSteps")).build();
	}

	private DeveloperSuggestion.Prevention parsePrevention(Map<String, Object> data) {
		Map<String, Object> preventionData = (Map<String, Object>) data.get("prevention");
		if (preventionData == null)
			return null;

		List<DeveloperSuggestion.Tool> tools = new ArrayList<>();
		Object toolsObj = preventionData.get("tools");

		if (toolsObj instanceof List) {
			List<Object> toolsList = (List<Object>) toolsObj;
			for (Object toolObj : toolsList) {
				if (toolObj instanceof Map) {
					Map<String, String> toolData = (Map<String, String>) toolObj;
					tools.add(DeveloperSuggestion.Tool.builder().name(toolData.get("name"))
							.description(toolData.get("description")).build());
				}
			}
		}

		return DeveloperSuggestion.Prevention.builder().guidelines((List<String>) preventionData.get("guidelines"))
				.tools(tools).codeReviewChecklist((List<String>) preventionData.get("codeReviewChecklist")).build();
	}
	
	private String createEnhancedFallbackJsonResponse(String originalContent) {
	    // Try to extract key information from malformed JSON
	    String title = "Code Review Required";
	    String explanation = "This issue requires manual review and implementation of security best practices.";
	    
	    // Try to extract issue type from content
	    if (originalContent != null) {
	        if (originalContent.toLowerCase().contains("sql")) {
	            title = "SQL Injection Fix Required";
	            explanation = "Use parameterized queries to prevent SQL injection vulnerabilities.";
	        } else if (originalContent.toLowerCase().contains("xss")) {
	            title = "XSS Prevention Required";
	            explanation = "Implement input validation and output encoding to prevent XSS attacks.";
	        } else if (originalContent.toLowerCase().contains("security")) {
	            title = "Security Issue Fix Required";
	            explanation = "Review and implement appropriate security measures for this vulnerability.";
	        }
	    }
	    
	    return String.format("""
	        {
	          "immediateFix": {
	            "title": "%s",
	            "searchCode": "Review the identified code section",
	            "replaceCode": "Apply appropriate security measures and best practices",
	            "explanation": "%s"
	          },
	          "bestPractice": {
	            "title": "Follow Security Guidelines",
	            "code": "// Implement according to security best practices\\n// Refer to OWASP guidelines",
	            "benefits": ["Improved security", "Better maintainability", "Reduced vulnerabilities"]
	          },
	          "testing": {
	            "testCase": "// Add comprehensive unit tests for security",
	            "validationSteps": ["Review code changes", "Test with security tools", "Verify mitigation"]
	          },
	          "prevention": {
	            "guidelines": ["Follow secure coding practices", "Regular security reviews", "Use static analysis tools"],
	            "tools": [{"name": "Security Scanner", "description": "Automated security vulnerability scanning"}],
	            "codeReviewChecklist": ["Security implications", "Best practices compliance", "Vulnerability mitigation"]
	          }
	        }
	        """, title, explanation);
	}
	
	private DeveloperSuggestion createFallbackSuggestion(String issueId, Map<String, Object> issue, int tokensUsed,
			double cost) {
		// Create basic fix guidance based on issue type
		String issueType = (String) issue.get("type");
		String codeSnippet = (String) issue.getOrDefault("codeSnippet", "");

		DeveloperSuggestion.ImmediateFix fallbackFix = DeveloperSuggestion.ImmediateFix.builder()
				.title("Manual Review Required for " + issueType)
				.searchCode(codeSnippet.isEmpty() ? "// Review the code at line " + issue.get("line") : codeSnippet)
				.replaceCode("// TODO: Apply " + generateBasicFixGuidance(issueType)
						+ "\n// Refer to OWASP guidelines for " + issueType)
				.explanation("This issue requires manual review. " + getIssueTypeExplanation(issueType)).build();

		DeveloperSuggestion.BestPractice fallbackPractice = DeveloperSuggestion.BestPractice.builder()
				.title("Best Practice for " + issueType).code(generateBestPracticeExample(issueType))
				.benefits(List.of("Improved security", "Better maintainability", "Reduced risk")).build();

		return DeveloperSuggestion.builder().issueId(issueId).issueType(issueType)
				.issueCategory((String) issue.get("category")).issueSeverity((String) issue.get("severity"))
				.language((String) issue.get("language")).immediateFix(fallbackFix).bestPractice(fallbackPractice)
				.tokensUsed(tokensUsed).cost(cost).timestamp(System.currentTimeMillis())
				.modelUsed(DEFAULT_MODEL_ID + "-fallback").build();
	}

	private String generateBasicFixGuidance(String issueType) {
		return switch (issueType.toUpperCase()) {
		case "SQL_INJECTION" -> "parameterized queries instead of string concatenation";
		case "XSS", "CROSS_SITE_SCRIPTING" -> "input validation and output encoding";
		case "HARDCODED_CREDENTIALS" -> "environment variables or secure configuration";
		case "INSECURE_DESERIALIZATION" -> "safe deserialization libraries and validation";
		case "INEFFICIENT_LOOP" -> "optimized algorithm or data structure";
		case "MEMORY_LEAK" -> "proper resource disposal and memory management";
		default -> "secure coding practices according to industry standards";
		};
	}
	private String generateDefaultIssueDescription(Map<String, Object> issue) {
		String issueType = (String) issue.get("type");
		String severity = (String) issue.get("severity");
		String category = (String) issue.get("category");
		
		if ("security".equalsIgnoreCase(category)) {
			return switch (issueType.toUpperCase()) {
				case "SQL_INJECTION" -> "SQL Injection occurs when untrusted data is concatenated directly into SQL queries, allowing attackers to modify the query structure. This can lead to unauthorized data access, data manipulation, or even complete database compromise.";
				case "XSS", "CROSS_SITE_SCRIPTING" -> "Cross-Site Scripting (XSS) vulnerabilities occur when user input is rendered in HTML without proper encoding, allowing attackers to inject malicious scripts. These scripts can steal user credentials, hijack sessions, or perform actions on behalf of the victim.";
				case "HARDCODED_CREDENTIALS" -> "Hardcoded credentials in source code pose a serious security risk as they can be easily discovered by anyone with access to the codebase. This vulnerability can lead to unauthorized system access and data breaches, especially if the code is shared or becomes public.";
				case "PATH_TRAVERSAL" -> "Path Traversal vulnerabilities allow attackers to access files outside the intended directory by manipulating file paths. This can expose sensitive configuration files, source code, or system files, leading to information disclosure or system compromise.";
				case "INSECURE_DESERIALIZATION" -> "Insecure deserialization occurs when untrusted data is deserialized without proper validation, potentially allowing arbitrary code execution. Attackers can craft malicious serialized objects that execute commands when deserialized by the application.";
				case "AUTHENTICATION_FLAW" -> "Authentication flaws allow attackers to bypass login mechanisms or impersonate other users. This can occur due to weak session management, predictable tokens, or improper credential validation, leading to unauthorized access to user accounts.";
				case "CRYPTOGRAPHIC_WEAKNESS" -> "Weak cryptographic implementations, such as using outdated algorithms or improper key management, can be exploited to decrypt sensitive data. This vulnerability compromises data confidentiality and can lead to exposure of passwords, personal information, or business secrets.";
				default -> "This security vulnerability in the code can be exploited by attackers to compromise application security. It requires immediate attention to prevent potential security breaches and protect sensitive data.";
			};
		} else if ("performance".equalsIgnoreCase(category)) {
			return switch (issueType.toUpperCase()) {
				case "INEFFICIENT_LOOP" -> "This loop implementation has performance issues that can cause excessive CPU usage and slow response times. The inefficiency typically stems from nested loops with high complexity or unnecessary repeated operations that could be optimized.";
				case "BLOCKING_IO", "BLOCKING_IO_OPERATION" -> "Blocking I/O operations can freeze the application thread while waiting for external resources, leading to poor responsiveness. This impacts user experience and reduces the application's ability to handle concurrent requests efficiently.";
				case "MEMORY_LEAK", "POTENTIAL_MEMORY_LEAK" -> "Memory leaks occur when objects are retained in memory unnecessarily, causing gradual memory exhaustion. This can lead to application crashes, degraded performance over time, and increased infrastructure costs.";
				case "INEFFICIENT_DATABASE_QUERY" -> "Inefficient database queries can cause significant performance bottlenecks, especially with large datasets. This results in slow page loads, increased database server load, and poor application scalability.";
				case "UNNECESSARY_LOOP" -> "Unnecessary loops perform redundant iterations that waste computational resources. This inefficiency impacts application performance and can be eliminated through better algorithm design or data structure choices.";
				default -> "This performance issue impacts application efficiency and user experience. Optimizing this code will improve response times, reduce resource consumption, and enhance overall application scalability.";
			};
		} else {
			return switch (issueType.toUpperCase()) {
				case "CODE_DUPLICATION" -> "Code duplication violates the DRY (Don't Repeat Yourself) principle, making maintenance difficult and error-prone. When the same logic exists in multiple places, bugs must be fixed in each location, increasing the risk of inconsistencies.";
				case "HIGH_CYCLOMATIC_COMPLEXITY" -> "High cyclomatic complexity indicates overly complex code with many decision paths, making it difficult to understand and test. This complexity increases the likelihood of bugs and makes the code harder to maintain or modify.";
				case "MISSING_ERROR_HANDLING" -> "Missing error handling can cause applications to crash unexpectedly or behave unpredictably when errors occur. Proper error handling ensures graceful degradation and provides meaningful feedback for debugging and user experience.";
				case "CODE_SMELL" -> "Code smells indicate deeper problems in the code structure that, while not bugs, suggest areas needing refactoring. These issues make the code harder to understand, modify, and maintain over time.";
				case "LACK_OF_DOCUMENTATION" -> "Lack of documentation makes code difficult to understand and maintain, especially for new team members. Well-documented code reduces onboarding time and prevents misunderstandings about code functionality and design decisions.";
				default -> "This code quality issue affects maintainability and reliability of the application. Addressing it will improve code readability, reduce technical debt, and make future development more efficient.";
			};
		}
	}
	
	private String getIssueTypeExplanation(String issueType) {
		return switch (issueType.toUpperCase()) {
		case "SQL_INJECTION" -> "Use prepared statements to prevent SQL injection attacks.";
		case "XSS" -> "Sanitize user input and encode output to prevent XSS vulnerabilities.";
		case "HARDCODED_CREDENTIALS" -> "Store sensitive information in secure configuration files.";
		default -> "Follow security best practices for this type of vulnerability.";
		};
	}

	private String generateBestPracticeExample(String issueType) {
		return switch (issueType.toUpperCase()) {
		case "SQL_INJECTION" -> """
				// Use PreparedStatement instead of string concatenation
				String sql = "SELECT * FROM users WHERE id = ?";
				PreparedStatement stmt = connection.prepareStatement(sql);
				stmt.setInt(1, userId);
				ResultSet rs = stmt.executeQuery();
				""";
		case "XSS" -> """
				// Encode output and validate input
				String safeOutput = StringEscapeUtils.escapeHtml4(userInput);
				// Use Content Security Policy headers
				response.setHeader("Content-Security-Policy", "default-src 'self'");
				""";
		default -> "// Refer to language-specific security guidelines\n// Implement appropriate security measures";
		};
	}

	private String truncateString(String str, int maxLength) {
		if (str == null)
			return "";
		return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
	}

	private SuggestionResponse.Summary buildSummary(List<DeveloperSuggestion> suggestions, int totalTokens,
			double totalCost) {
		Map<String, Long> bySeverity = suggestions.stream().collect(Collectors.groupingBy(
				s -> s.getIssueSeverity() != null ? s.getIssueSeverity() : "unknown", Collectors.counting()));

		Map<String, Long> byCategory = suggestions.stream().collect(Collectors.groupingBy(
				s -> s.getIssueCategory() != null ? s.getIssueCategory() : "unknown", Collectors.counting()));

		return SuggestionResponse.Summary.builder().totalSuggestions(suggestions.size()).bySeverity(bySeverity)
				.byCategory(byCategory).tokensUsed(totalTokens).estimatedCost(totalCost).build();
	}

	private Map<String, Object> buildMetadata(int totalTokens, double totalCost,
			SuggestionResponse.ProcessingTime processingTime) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("modelUsed", DEFAULT_MODEL_ID);
		metadata.put("hybridModeEnabled", TEMPLATE_MODE_ENABLED);
		metadata.put("totalTokensUsed", totalTokens);
		metadata.put("totalCost", totalCost);
		metadata.put("timestamp", System.currentTimeMillis());
		metadata.put("batchSize", BATCH_SIZE);
		metadata.put("batchDelayMs", BATCH_DELAY_MS);
		metadata.put("processingTimeMs", processingTime.totalProcessingTime);

		// Add Nova service statistics
		try {
			metadata.put("novaStatistics", novaInvoker.getStatistics());
		} catch (Exception e) {
			log.warn("Failed to get Nova statistics", e);
		}

		return metadata;
	}

	/**
	 * Result container for batch processing
	 */
	private static class BatchResult {
		final List<DeveloperSuggestion> suggestions;
		final int tokensUsed;
		final double cost;

		BatchResult(List<DeveloperSuggestion> suggestions, int tokensUsed, double cost) {
			this.suggestions = suggestions;
			this.tokensUsed = tokensUsed;
			this.cost = cost;
		}
	}

	/**
	 * Cleanup resources on Lambda container shutdown
	 */
	public void cleanup() {
		if (executorService != null && !executorService.isShutdown()) {
			executorService.shutdown();
			try {
				if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
					executorService.shutdownNow();
				}
			} catch (InterruptedException e) {
				executorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}
	
	/**
	 * Create a fallback JSON response when sanitization fails
	 */
	private String createFallbackJsonResponse(String originalContent) {
	    return String.format("""
	        {
	          "immediateFix": {
	            "title": "Code Review Required",
	            "searchCode": "Review the identified code section",
	            "replaceCode": "Apply appropriate security measures and best practices",
	            "explanation": "This issue requires manual review and implementation of security best practices."
	          },
	          "bestPractice": {
	            "title": "Follow Security Guidelines",
	            "code": "// Implement according to security best practices",
	            "benefits": ["Improved security", "Better maintainability", "Reduced vulnerabilities"]
	          },
	          "testing": {
	            "testCase": "// Add comprehensive unit tests",
	            "validationSteps": ["Review code changes", "Test functionality", "Verify security measures"]
	          },
	          "prevention": {
	            "guidelines": ["Follow security guidelines", "Regular code reviews", "Use static analysis tools"],
	            "tools": [{"name": "Security Scanner", "description": "Automated security scanning"}],
	            "codeReviewChecklist": ["Security implications", "Best practices compliance", "Test coverage"]
	          }
	        }
	        """);
	}
}