package com.somdiproy.lambda.suggestions.service;

import java.util.*;

/**
 * Template service for generating fallback suggestions
 * Note: No Spring annotations as this runs in Lambda environment
 */
public class TemplateService {
    
    private static final Map<String, String> SECURITY_TEMPLATES = Map.of(
        "SQL_INJECTION", "Use parameterized queries: PreparedStatement stmt = connection.prepareStatement(\"SELECT * FROM users WHERE id = ?\"); stmt.setString(1, userId);",
        "XSS", "Sanitize input: String safe = StringEscapeUtils.escapeHtml4(userInput);",
        "HARDCODED_CREDENTIALS", "Use environment variables: String apiKey = System.getenv(\"API_KEY\");"
    );
    
    private static final Map<String, String> PERFORMANCE_TEMPLATES = Map.of(
        "INEFFICIENT_LOOP", "Use stream operations: list.stream().filter(item -> condition).collect(Collectors.toList());",
        "MEMORY_LEAK", "Close resources: try (FileInputStream fis = new FileInputStream(file)) { /* use fis */ }",
        "DATABASE_N_PLUS_1", "Use batch operations: @Query(\"SELECT u FROM User u JOIN FETCH u.orders\") List<User> findUsersWithOrders();"
    );
    
    public String generateTemplateSuggestion(String issueType, String category) {
        if ("security".equalsIgnoreCase(category)) {
            return SECURITY_TEMPLATES.getOrDefault(issueType, "Apply security best practices for this issue type.");
        } else if ("performance".equalsIgnoreCase(category)) {
            return PERFORMANCE_TEMPLATES.getOrDefault(issueType, "Optimize performance for this issue type.");
        }
        return "Review and fix this " + category + " issue using best practices.";
    }
}