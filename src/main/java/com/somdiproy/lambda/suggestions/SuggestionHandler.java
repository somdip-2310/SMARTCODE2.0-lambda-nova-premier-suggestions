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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Enhanced Lambda function for Nova Premier suggestion generation with batch
 * processing Handler:
 * com.somdiproy.lambda.suggestions.SuggestionHandler::handleRequest
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

				// Switch to sequential processing for Nova API stability
				BatchResult batchResult = processIssuesSequentially(batch, context, logger);

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
	 * Process issues sequentially with proper delays for Nova API
	 */
	private BatchResult processIssuesSequentially(List<Map<String, Object>> issues, Context context, LambdaLogger logger)
	        throws InterruptedException {
	    List<DeveloperSuggestion> suggestions = new ArrayList<>();
	    int tokensUsed = 0;
	    double cost = 0.0;
	    int successCount = 0;
	    int failureCount = 0;
	    long baseDelay = 5000L; // 5 second base delay between requests

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

	            // Progressive delay - longer delays after failures
	            if (i < issues.size() - 1) {
	                long adaptiveDelay = calculateSequentialDelay(baseDelay, failureCount, successCount);
	                logger.log(String.format("⏸️ Waiting %dms before next request (adaptive delay)", adaptiveDelay));
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
	    if (failures == 0) {
	        return baseDelay; // Standard delay when everything works
	    }
	    
	    double failureRate = (double) failures / (failures + successes);
	    if (failureRate > 0.5) {
	        return baseDelay * 3; // Triple delay if >50% failure rate
	    } else if (failureRate > 0.25) {
	        return baseDelay * 2; // Double delay if >25% failure rate  
	    } else {
	        return (long) (baseDelay * 1.5); // 50% increase for any failures
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
	 * Generate suggestion for a single issue with error handling
	 */
	private DeveloperSuggestion generateSuggestionForIssue(Map<String, Object> issue, LambdaLogger logger) {
		try {
			String issueId = (String) issue.get("id");
			logger.log("🔍 Generating suggestion for issue: " + issueId);

			// Build optimized prompt
			String prompt = buildSuggestionPrompt(issue);

			// Add token optimization to prevent exceeding limits
			int estimatedTokens = TokenOptimizer.estimateTokens(prompt);
			int adjustedMaxTokens = Math.min(MAX_TOKENS, TOKEN_BUDGET / 10); // Limit per issue

			// Call Nova Premier with retry logic handled by NovaInvokerService
			NovaInvokerService.NovaResponse novaResponse = novaInvoker.invokeNova(MODEL_ID, prompt, adjustedMaxTokens,
					0.3, 0.9);

			if (!novaResponse.isSuccessful()) {
				logger.log("❌ Nova Premier call failed for issue " + issueId + ": " + novaResponse.getErrorMessage());
				return createFallbackSuggestion(issueId, issue, 0, 0.0);
			}

			// Parse response
			return parseSuggestionResponse(issueId, novaResponse.getResponseText(), novaResponse.getTotalTokens(),
					novaResponse.getEstimatedCost(), issue, logger);

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
			Map<String, Object> originalIssue, LambdaLogger logger) {
		try {
			// Extract JSON from response
			String jsonContent = extractJsonFromResponse(response);
			Map<String, Object> suggestionData = objectMapper.readValue(jsonContent, Map.class);

			return DeveloperSuggestion.builder().issueId(issueId).issueType((String) originalIssue.get("type"))
					.issueCategory((String) originalIssue.get("category"))
					.issueSeverity((String) originalIssue.get("severity"))
					.language((String) originalIssue.get("language")).immediateFix(parseImmediateFix(suggestionData))
					.bestPractice(parseBestPractice(suggestionData)).testing(parseTesting(suggestionData))
					.prevention(parsePrevention(suggestionData)).tokensUsed(tokensUsed).cost(cost)
					.timestamp(System.currentTimeMillis()).modelUsed(MODEL_ID).build();

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
	private String sanitizeJsonString(String jsonContent) {
		return jsonContent
			// Fix unescaped single quotes in strings
			.replaceAll("(?<!\\\\)'", "\\\\'")
			// Fix unescaped double quotes in strings  
			.replaceAll("(?<!\\\\)\"([^\"]*?)(?<!\\\\)\"([^,}\\]]*?)(?<!\\\\)\"", "\\\"$1\\\"$2\\\"")
			// Fix newlines in strings
			.replaceAll("\\n", "\\\\n")
			.replaceAll("\\r", "\\\\r")
			// Fix tabs in strings
			.replaceAll("\\t", "\\\\t")
			// Remove any trailing commas
			.replaceAll(",\\s*}", "}")
			.replaceAll(",\\s*]", "]");
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
				.modelUsed(MODEL_ID + "-fallback").build();
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
		metadata.put("modelUsed", MODEL_ID);
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
}