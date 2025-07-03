// src/main/java/com/somdiproy/lambda/suggestions/service/NovaInvokerService.java
package com.somdiproy.lambda.suggestions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced Service for invoking Amazon Nova models via Bedrock with robust
 * retry logic
 */
public class NovaInvokerService {

	private static final Logger log = LoggerFactory.getLogger(NovaInvokerService.class);

	private final BedrockRuntimeClient bedrockClient;
	private final ObjectMapper objectMapper;

	// Nova Premier pricing (per 1M tokens)
	private static final double INPUT_TOKEN_COST = 0.0008; // $0.80 per 1M input tokens
	private static final double OUTPUT_TOKEN_COST = 0.0032; // $3.20 per 1M output tokens

	// Retry configuration with exponential backoff
	private static final int MAX_RETRIES = Integer.parseInt(System.getenv().getOrDefault("MAX_RETRIES", "5"));
	private static final long MIN_RETRY_DELAY_MS = Long
			.parseLong(System.getenv().getOrDefault("RETRY_BASE_DELAY_MS", "1000"));
	private static final long MAX_RETRY_DELAY_MS = Long
			.parseLong(System.getenv().getOrDefault("RETRY_MAX_DELAY_MS", "60000"));
	private static final double JITTER_FACTOR = 0.25; // 25% jitter

	// Rate limiting configuration for Nova Premier
	private static final long MIN_CALL_INTERVAL_MS = 5000; // Reduce to 0.5 calls per second for Nova Premier
	private static final long ADAPTIVE_BACKOFF_MULTIPLIER = 2;
	private volatile long currentBackoffMs = MIN_CALL_INTERVAL_MS;
	private static final int RATE_LIMIT_WINDOW_SIZE = 10; // Track last 10 calls
	private final LinkedList<Long> callTimestamps = new LinkedList<>();
	private final Map<String, Long> lastCallTime = new ConcurrentHashMap<>();

	// Circuit breaker state
	private enum CircuitState {
		CLOSED, OPEN, HALF_OPEN
	}

	private volatile CircuitState circuitState = CircuitState.CLOSED;
	private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
	private final AtomicLong circuitOpenTime = new AtomicLong(0);
	private static final int FAILURE_THRESHOLD = 3;
	private static final long CIRCUIT_RESET_TIMEOUT_MS = 120000; // 2 minutes for Nova Premier
	private static final int ADAPTIVE_BATCH_SIZE = 2; // Reduce batch size when throttled
	private static final boolean CIRCUIT_BREAKER_ENABLED = Boolean
			.parseBoolean(System.getenv().getOrDefault("CIRCUIT_BREAKER_ENABLED", "true"));

	// Metrics tracking
	private final Map<String, AtomicInteger> callCount = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> totalLatency = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> throttleCount = new ConcurrentHashMap<>();

	public NovaInvokerService(String region) {
		this.bedrockClient = BedrockRuntimeClient.builder().region(software.amazon.awssdk.regions.Region.of(region))
				.build();
		this.objectMapper = new ObjectMapper();
	}

	/**
	 * Invoke Nova model with comprehensive retry logic and throttling protection
	 */
	public NovaResponse invokeNova(String modelId, String prompt, int maxTokens, double temperature, double topP)
			throws NovaInvokerException {

// Handle template mode before processing
		if ("TEMPLATE_MODE".equals(modelId)) {
			return createTemplateResponse(prompt, maxTokens);
		}

// Continue with your existing implementation...
		String callKey = modelId + "-" + Thread.currentThread().getName();
		long startTime = System.currentTimeMillis();

		// Check circuit breaker state
		if (CIRCUIT_BREAKER_ENABLED) {
			checkCircuitBreaker();
		}

		Exception lastException = null;

		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				// Enforce rate limiting before each attempt
				enforceRateLimit(callKey);

				// Update call metrics
				updateCallMetrics(callKey);

				// Build request payload for Nova models
				Map<String, Object> requestBody = buildRequestBody(prompt, maxTokens, temperature);
				String requestJson = objectMapper.writeValueAsString(requestBody);

				if (attempt > 1) {
					log.info("Retry attempt {}/{} for Nova {} invocation", attempt, MAX_RETRIES, modelId);
				}

				log.debug("Nova API Request (attempt {}): {}", attempt, requestJson);

				// Create Bedrock request
				InvokeModelRequest request = InvokeModelRequest.builder().modelId(modelId)
						.body(SdkBytes.fromUtf8String(requestJson)).contentType("application/json")
						.accept("application/json").build();

				// Execute request
				InvokeModelResponse response = bedrockClient.invokeModel(request);
				String responseBody = response.body().asUtf8String();
				log.debug("Nova API Response: {}", responseBody);

				// Parse successful response
				NovaResponse novaResponse = parseResponse(responseBody, modelId);

				// Reset circuit breaker on success
				if (CIRCUIT_BREAKER_ENABLED && circuitState != CircuitState.CLOSED) {
					log.info("Circuit breaker: resetting to CLOSED state after successful call");
					circuitState = CircuitState.CLOSED;
					consecutiveFailures.set(0);
				}

				// Log success metrics
				long latency = System.currentTimeMillis() - startTime;
				logMetrics(modelId, novaResponse.getTotalTokens(), novaResponse.getEstimatedCost(), latency,
						attempt - 1, true);

				return novaResponse;

			} catch (ThrottlingException e) {
				// Rate limit exceeded - retry with exponential backoff
				log.warn("Rate limit exceeded for {}, attempt {}/{}: {}", modelId, attempt, MAX_RETRIES,
						e.getMessage());
				lastException = e;
				throttleCount.computeIfAbsent(modelId, k -> new AtomicInteger()).incrementAndGet();

				if (attempt < MAX_RETRIES) {
					long delay = calculateExponentialBackoffDelay(attempt);
					log.info("Throttled - waiting {}ms before retry attempt {}", delay, attempt + 1);

					try {
						Thread.sleep(delay);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new NovaInvokerException("Interrupted during retry", ie);
					}
				}

			} catch (ModelTimeoutException e) {
				// Model timeout - retry with backoff
				log.warn("Model timeout for {}, attempt {}/{}", modelId, attempt, MAX_RETRIES);
				lastException = e;

				if (attempt < MAX_RETRIES) {
					long delay = calculateExponentialBackoffDelay(attempt);
					try {
						Thread.sleep(delay);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new NovaInvokerException("Interrupted during retry", ie);
					}
				}

			} catch (BedrockRuntimeException e) {
				// Check if it's a retryable error
				if (isRetryableError(e) && attempt < MAX_RETRIES) {
					log.warn("Bedrock service error for {}, attempt {}/{}: {}", modelId, attempt, MAX_RETRIES,
							e.getMessage());
					lastException = e;

					long delay = calculateExponentialBackoffDelay(attempt);
					try {
						Thread.sleep(delay);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new NovaInvokerException("Interrupted during retry", ie);
					}
				} else {
					// Non-retryable error
					handleCircuitBreakerFailure();
					throw new NovaInvokerException("Bedrock service error: " + e.getMessage(), e);
				}

			} catch (SdkServiceException e) {
				// AWS service error - check if retryable
				if (e.statusCode() >= 500 && attempt < MAX_RETRIES) {
					log.warn("AWS service error (5xx) for {}, attempt {}/{}", modelId, attempt, MAX_RETRIES);
					lastException = e;

					long delay = calculateExponentialBackoffDelay(attempt);
					try {
						Thread.sleep(delay);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new NovaInvokerException("Interrupted during retry", ie);
					}
				} else {
					handleCircuitBreakerFailure();
					throw new NovaInvokerException("AWS service error: " + e.getMessage(), e);
				}

			} catch (SdkClientException e) {
				// Client-side error (network, config, etc.) - retry for network issues
				log.error("Client error for {}, attempt {}/{}: {}", modelId, attempt, MAX_RETRIES, e.getMessage());
				lastException = e;

				if (attempt < MAX_RETRIES && isNetworkError(e)) {
					long delay = calculateExponentialBackoffDelay(attempt);
					try {
						Thread.sleep(delay);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new NovaInvokerException("Interrupted during retry", ie);
					}
				} else {
					handleCircuitBreakerFailure();
					throw new NovaInvokerException("Client error: " + e.getMessage(), e);
				}

			} catch (Exception e) {
				// Unexpected error
				log.error("Unexpected error for {}: {}", modelId, e.getMessage(), e);
				handleCircuitBreakerFailure();
				throw new NovaInvokerException("Unexpected error: " + e.getMessage(), e);
			}
		}

		// All retries exhausted
		handleCircuitBreakerFailure();
		log.error("All {} retry attempts failed for Nova {}", MAX_RETRIES, modelId);

		// Log failure metrics
		long latency = System.currentTimeMillis() - startTime;
		logMetrics(modelId, 0, 0.0, latency, MAX_RETRIES, false);

		throw new NovaInvokerException("All retry attempts failed", lastException);
	}
	
	/**
	 * Create template-based response for fallback scenarios
	 */
	private NovaResponse createTemplateResponse(String prompt, int maxTokens) {
	    log.info("Using template mode for fallback suggestion");
	    
	    // Extract issue type from prompt for better template matching
	    String issueType = extractIssueType(prompt);
	    String templateResponse = generateTemplateResponse(issueType, prompt);
	    
	 // Create response object using builder pattern
	    return NovaResponse.builder()
	        .responseText(templateResponse)
	        .inputTokens(Math.min(prompt.length() / 4, 100))
	        .outputTokens(Math.min(templateResponse.length() / 4, 50))
	        .totalTokens(Math.min(prompt.length() / 4, 100) + Math.min(templateResponse.length() / 4, 50))
	        .estimatedCost(0.0001)
	        .modelId("TEMPLATE_MODE")
	        .successful(true)
	        .timestamp(System.currentTimeMillis())
	        .build();
	    
	}

	/**
	 * Extract issue type from prompt for template matching
	 */
	private String extractIssueType(String prompt) {
	    String lowerPrompt = prompt.toLowerCase();
	    
	    if (lowerPrompt.contains("sql injection") || lowerPrompt.contains("sqli")) {
	        return "SQL_INJECTION";
	    } else if (lowerPrompt.contains("xss") || lowerPrompt.contains("cross-site scripting")) {
	        return "XSS";
	    } else if (lowerPrompt.contains("hardcoded") && lowerPrompt.contains("credential")) {
	        return "HARDCODED_CREDENTIALS";
	    } else if (lowerPrompt.contains("loop") || lowerPrompt.contains("iteration")) {
	        return "INEFFICIENT_LOOP";
	    } else if (lowerPrompt.contains("memory leak") || lowerPrompt.contains("resource")) {
	        return "MEMORY_LEAK";
	    } else if (lowerPrompt.contains("database") || lowerPrompt.contains("query")) {
	        return "DATABASE_N_PLUS_1";
	    }
	    
	    return "GENERIC";
	}

	/**
	 * Generate template-based suggestion from prompt analysis
	 */
	private String generateTemplateResponse(String issueType, String prompt) {
	    Map<String, String> templates = Map.of(
	        "SQL_INJECTION", """
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
	            "testCase": "@Test public void testSqlInjectionPrevention() { String maliciousInput = \\"'; DROP TABLE users; --\\"; }",
	            "validationSteps": ["Test with malicious input", "Verify database integrity", "Check query logs"]
	          },
	          "prevention": {
	            "guidelines": ["Always use parameterized queries", "Validate input length and format", "Use least privilege database accounts"],
	            "tools": [{"name": "SonarQube", "description": "Static analysis for SQL injection detection"}],
	            "codeReviewChecklist": ["Check for string concatenation in SQL", "Verify parameterized queries usage", "Review input validation"]
	          }
	        }
	        """,
	        
	        "XSS", """
	        {
	          "immediateFix": {
	            "title": "Sanitize Input and Encode Output",
	            "searchCode": "response.getWriter().println(\\"<p>\\" + userInput + \\"</p>\\");",
	            "replaceCode": "String safeInput = StringEscapeUtils.escapeHtml4(userInput); response.getWriter().println(\\"<p>\\" + safeInput + \\"</p>\\");",
	            "explanation": "HTML escaping converts dangerous characters to safe entities."
	          },
	          "bestPractice": {
	            "title": "Input Validation and Output Encoding",
	            "code": "String safeOutput = StringEscapeUtils.escapeHtml4(userInput);",
	            "benefits": ["Prevents XSS attacks", "Secure data handling", "User safety"]
	          },
	          "testing": {
	            "testCase": "@Test public void testXssPrevention() { String maliciousInput = \\"<script>alert('xss')</script>\\"; }",
	            "validationSteps": ["Test with malicious scripts", "Verify output encoding", "Check CSP headers"]
	          },
	          "prevention": {
	            "guidelines": ["Always encode output", "Validate input format", "Use Content Security Policy"],
	            "tools": [{"name": "OWASP ZAP", "description": "Security testing for XSS vulnerabilities"}],
	            "codeReviewChecklist": ["Check output encoding", "Verify input validation", "Review CSP implementation"]
	          }
	        }
	        """
	    );
	    
	    return templates.getOrDefault(issueType, 
	        """
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
	        """
	    );
	}
	/**
	 * Build request body for Nova models
	 */
	private Map<String, Object> buildRequestBody(String prompt, int maxTokens, double temperature) {
		Map<String, Object> requestBody = new HashMap<>();

		// Nova-specific message format
		List<Map<String, Object>> messages = List
				.of(Map.of("role", "user", "content", List.of(Map.of("text", prompt))));

		requestBody.put("messages", messages);

		// Nova-specific inference configuration
		Map<String, Object> inferenceConfig = new HashMap<>();
		inferenceConfig.put("maxTokens", maxTokens);
		inferenceConfig.put("temperature", temperature);
		// Note: top_p is not supported in Nova models
		requestBody.put("inferenceConfig", inferenceConfig);

		return requestBody;
	}

	/**
	 * Parse Nova response
	 */
	private NovaResponse parseResponse(String responseBody, String modelId) throws Exception {
	    Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

	    // Extract content from Nova response format
	    String responseText = extractResponseText(responseMap);

	    // Extract token usage with enhanced extraction
	    Map<String, Object> usage = (Map<String, Object>) responseMap.get("usage");
	    int inputTokens = 0;
	    int outputTokens = 0;
	    int totalTokens = 0;

	    if (usage != null) {
	        // Try multiple possible field names for token counts
	        inputTokens = getIntegerValue(usage, "input_tokens", "inputTokens");
	        outputTokens = getIntegerValue(usage, "output_tokens", "outputTokens");
	        totalTokens = getIntegerValue(usage, "total_tokens", "totalTokens");
	        
	        // If individual tokens are 0 but total exists, estimate split
	        if (inputTokens == 0 && outputTokens == 0 && totalTokens > 0) {
	            inputTokens = (int) (totalTokens * 0.6); // Typical input ratio
	            outputTokens = (int) (totalTokens * 0.4); // Typical output ratio
	        }
	        
	        // Calculate total if not provided but individuals are
	        if (totalTokens == 0 && (inputTokens > 0 || outputTokens > 0)) {
	            totalTokens = inputTokens + outputTokens;
	        }
	    }
	    
	    // Fallback estimation if no usage data available
	    if (inputTokens == 0 && outputTokens == 0) {
	        // Estimate based on content length as last resort
	        inputTokens = Math.max(100, estimateTokensFromContent(responseText));
	        outputTokens = Math.max(50, responseText.length() / 4);
	        totalTokens = inputTokens + outputTokens;
	        log.warn("No token usage data found for model {}, using estimation: input={}, output={}", 
	                modelId, inputTokens, outputTokens);
	    }

	    // Calculate cost
	    double cost = calculateCost(inputTokens, outputTokens);

	    log.info("Nova {} invocation successful - Input: {}, Output: {}, Total: {}, Cost: ${:.6f}", 
	            modelId, inputTokens, outputTokens, totalTokens, cost);

	    return NovaResponse.builder()
	            .responseText(responseText)
	            .inputTokens(inputTokens)
	            .outputTokens(outputTokens)
	            .totalTokens(totalTokens)
	            .estimatedCost(cost)
	            .modelId(modelId)
	            .successful(true)
	            .timestamp(System.currentTimeMillis())
	            .build();
	}

	/**
	 * Get integer value from usage map with multiple possible field names
	 */
	private int getIntegerValue(Map<String, Object> usage, String... fieldNames) {
	    for (String fieldName : fieldNames) {
	        Object value = usage.get(fieldName);
	        if (value instanceof Number) {
	            return ((Number) value).intValue();
	        }
	        if (value instanceof String) {
	            try {
	                return Integer.parseInt((String) value);
	            } catch (NumberFormatException e) {
	                // Continue to next field name
	            }
	        }
	    }
	    return 0;
	}

	/**
	 * Estimate token count from content length (rough approximation)
	 */
	private int estimateTokensFromContent(String content) {
	    if (content == null || content.trim().isEmpty()) {
	        return 0;
	    }
	    // Rough estimation: 1 token per 4 characters for English text
	    // This is a fallback when actual token counts aren't available
	    return Math.max(50, content.length() / 4);
	}
	
	private int getIntegerValue(Object usage, String... fieldNames) {
	    if (usage instanceof Map) {
	        Map<String, Object> usageMap = (Map<String, Object>) usage;
	        for (String fieldName : fieldNames) {
	            Object value = usageMap.get(fieldName);
	            if (value instanceof Number) {
	                return ((Number) value).intValue();
	            }
	        }
	    }
	    return 0;
	}
	
	/**
	 * Extract response text from Nova response format
	 */
	private String extractResponseText(Object response) {
	    try {
	        // Handle proper ConverseResponse object
	        if (response instanceof software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse) {
	            software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse converseResponse = 
	                (software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse) response;
	            
	            if (converseResponse.output() != null && converseResponse.output().message() != null) {
	                var content = converseResponse.output().message().content();
	                if (!content.isEmpty() && content.get(0).text() != null) {
	                    return content.get(0).text();
	                }
	            }
	        }
	        
	        // Fallback for Map-based response (legacy)
	        if (response instanceof Map) {
	            Map<String, Object> responseMap = (Map<String, Object>) response;
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
	            return (String) responseMap.getOrDefault("text", "No response text found");
	        }
	        
	        return response.toString();
	        
	    } catch (Exception e) {
	        log.warn("Error extracting response text, using fallback: " + e.getMessage());
	        return response.toString();
	    }
	}

	/**
	 * Calculate exponential backoff delay with jitter
	 */
	private long calculateExponentialBackoffDelay(int attempt) {
		// Exponential backoff: 2^(attempt-1) * base delay
		long exponentialDelay = (long) Math.pow(2, attempt - 1) * MIN_RETRY_DELAY_MS;

		// Cap at maximum delay
		long delay = Math.min(exponentialDelay, MAX_RETRY_DELAY_MS);

		// Add jitter (0-25% of delay) to prevent thundering herd
		long jitter = (long) (delay * JITTER_FACTOR * Math.random());

		return delay + jitter;
	}

	/**
	 * Enforce rate limiting with sliding window
	 */
	private void enforceRateLimit(String callKey) throws InterruptedException {
		synchronized (callTimestamps) {
			long currentTime = System.currentTimeMillis();

			// Remove timestamps older than the window
			while (!callTimestamps.isEmpty()
					&& currentTime - callTimestamps.getFirst() > RATE_LIMIT_WINDOW_SIZE * MIN_CALL_INTERVAL_MS) {
				callTimestamps.removeFirst();
			}

			// If we've made too many calls recently, wait
			if (callTimestamps.size() >= RATE_LIMIT_WINDOW_SIZE) {
				long oldestCall = callTimestamps.getFirst();
				long waitTime = (oldestCall + RATE_LIMIT_WINDOW_SIZE * MIN_CALL_INTERVAL_MS) - currentTime;
				if (waitTime > 0) {
					log.info("Rate limiting: waiting {}ms before next call", waitTime);
					Thread.sleep(waitTime);
					// Recursive call to recheck after waiting
					enforceRateLimit(callKey);
					return;
				}
			}

			// Add current timestamp
			callTimestamps.addLast(currentTime);

			// Also enforce minimum interval between consecutive calls
			Long lastCall = lastCallTime.get(callKey);
			if (lastCall != null) {
				long timeSinceLastCall = currentTime - lastCall;
				if (timeSinceLastCall < MIN_CALL_INTERVAL_MS) {
					Thread.sleep(MIN_CALL_INTERVAL_MS - timeSinceLastCall);
				}
			}
			lastCallTime.put(callKey, System.currentTimeMillis());
		}
	}

	/**
	 * Check circuit breaker state
	 */
	private void checkCircuitBreaker() throws NovaInvokerException {
		if (circuitState == CircuitState.OPEN) {
			long timeSinceOpen = System.currentTimeMillis() - circuitOpenTime.get();
			if (timeSinceOpen > CIRCUIT_RESET_TIMEOUT_MS) {
				log.info("Circuit breaker: transitioning to HALF_OPEN state");
				circuitState = CircuitState.HALF_OPEN;
			} else {
				throw new NovaInvokerException(String.format("Circuit breaker is OPEN. Service unavailable for %d ms",
						CIRCUIT_RESET_TIMEOUT_MS - timeSinceOpen));
			}
		}
	}

	/**
	 * Handle circuit breaker failure
	 */
	private void handleCircuitBreakerFailure() {
		if (!CIRCUIT_BREAKER_ENABLED) {
			return;
		}

		int failures = consecutiveFailures.incrementAndGet();

		if (failures >= FAILURE_THRESHOLD && circuitState != CircuitState.OPEN) {
			log.warn("Circuit breaker: opening circuit after {} consecutive failures", failures);
			circuitState = CircuitState.OPEN;
			circuitOpenTime.set(System.currentTimeMillis());
		}
	}

	/**
	 * Check if error is retryable
	 */
	private boolean isRetryableError(BedrockRuntimeException e) {
		// Retry on throttling, timeouts, and 5xx errors
		return e instanceof ThrottlingException || e instanceof ModelTimeoutException
				|| (e.statusCode() >= 500 && e.statusCode() < 600) || e.statusCode() == 429; // Too Many Requests
	}

	/**
	 * Check if it's a network error
	 */
	private boolean isNetworkError(SdkClientException e) {
		String message = e.getMessage().toLowerCase();
		return message.contains("timeout") || message.contains("connection") || message.contains("network");
	}

	/**
	 * Update call metrics for monitoring
	 */
	private void updateCallMetrics(String callKey) {
		callCount.computeIfAbsent(callKey, k -> new AtomicInteger()).incrementAndGet();
	}

	/**
	 * Log metrics for monitoring
	 */
	private void logMetrics(String modelId, int tokens, double cost, long latency, int retryCount, boolean success) {
		log.info("MONITORING_METRIC|ModelId:{}|Tokens:{}|Cost:{}|Latency:{}|RetryCount:{}|Success:{}|CircuitState:{}",
				modelId, tokens, cost, latency, retryCount, success, circuitState);

		// Update latency tracking
		totalLatency.computeIfAbsent(modelId, k -> new AtomicLong()).addAndGet(latency);
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
	 * Get call statistics for monitoring
	 */
	public Map<String, Object> getStatistics() {
		Map<String, Object> stats = new HashMap<>();

		// Convert AtomicInteger maps to Integer maps
		Map<String, Integer> callCountSnapshot = new HashMap<>();
		callCount.forEach((key, atomicValue) -> callCountSnapshot.put(key, atomicValue.get()));
		stats.put("callCounts", callCountSnapshot);

		Map<String, Integer> throttleCountSnapshot = new HashMap<>();
		throttleCount.forEach((key, atomicValue) -> throttleCountSnapshot.put(key, atomicValue.get()));
		stats.put("throttleCounts", throttleCountSnapshot);

		stats.put("circuitState", circuitState.toString());
		stats.put("consecutiveFailures", consecutiveFailures.get());

		// Calculate average latencies
		Map<String, Double> avgLatencies = new HashMap<>();
		for (Map.Entry<String, AtomicLong> entry : totalLatency.entrySet()) {
			String model = entry.getKey();
			long totalLat = entry.getValue().get();
			int calls = callCount.getOrDefault(model, new AtomicInteger(0)).get();
			if (calls > 0) {
				avgLatencies.put(model, (double) totalLat / calls);
			}
		}
		stats.put("averageLatencies", avgLatencies);

		return stats;
	}

	/**
	 * Reset statistics
	 */
	public void resetStatistics() {
		callCount.clear();
		throttleCount.clear();
		totalLatency.clear();
		consecutiveFailures.set(0);
		callTimestamps.clear();
		lastCallTime.clear();
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
		public NovaResponse() {
		}

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

			public Builder responseText(String responseText) {
				this.responseText = responseText;
				return this;
			}

			public Builder inputTokens(int inputTokens) {
				this.inputTokens = inputTokens;
				return this;
			}

			public Builder outputTokens(int outputTokens) {
				this.outputTokens = outputTokens;
				return this;
			}

			public Builder totalTokens(int totalTokens) {
				this.totalTokens = totalTokens;
				return this;
			}

			public Builder estimatedCost(double estimatedCost) {
				this.estimatedCost = estimatedCost;
				return this;
			}

			public Builder modelId(String modelId) {
				this.modelId = modelId;
				return this;
			}

			public Builder successful(boolean successful) {
				this.successful = successful;
				return this;
			}

			public Builder timestamp(long timestamp) {
				this.timestamp = timestamp;
				return this;
			}

			public Builder errorMessage(String errorMessage) {
				this.errorMessage = errorMessage;
				return this;
			}

			public Builder metadata(Map<String, Object> metadata) {
				this.metadata = metadata;
				return this;
			}

			public NovaResponse build() {
				return new NovaResponse(this);
			}
		}

		// Getters
		public String getResponseText() {
			return responseText;
		}

		public int getInputTokens() {
			return inputTokens;
		}

		public int getOutputTokens() {
			return outputTokens;
		}

		public int getTotalTokens() {
			return totalTokens;
		}

		public double getEstimatedCost() {
			return estimatedCost;
		}

		public String getModelId() {
			return modelId;
		}

		public boolean isSuccessful() {
			return successful;
		}

		public long getTimestamp() {
			return timestamp;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

		public Map<String, Object> getMetadata() {
			return metadata;
		}
	}

	/**
	 * Custom exception for Nova invocation errors
	 */
	public static class NovaInvokerException extends Exception {
		public NovaInvokerException(String message) {
			super(message);
		}

		public NovaInvokerException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}