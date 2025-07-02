// src/main/java/com/somdiproy/lambda/suggestions/util/TokenOptimizer.java
package com.somdiproy.lambda.suggestions.util;

import java.util.*;

/**
 * Utility for optimizing token usage in Nova Premier suggestions
 */
public class TokenOptimizer {
    
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TokenOptimizer.class);
    
    // Language-specific patterns for optimization
    private static final Map<String, LanguageConfig> LANGUAGE_CONFIGS = Map.of(
        "java", new LanguageConfig(
            List.of("//.*$", "/\\*.*?\\*/"),
            List.of("public", "private", "protected", "class", "interface", "method")
        ),
        "python", new LanguageConfig(
            List.of("#.*$", "\"\"\".*?\"\"\"", "'''.*?'''"),
            List.of("def", "class", "import", "from")
        ),
        "javascript", new LanguageConfig(
            List.of("//.*$", "/\\*.*?\\*/"),
            List.of("function", "class", "const", "let", "var")
        ),
        "typescript", new LanguageConfig(
            List.of("//.*$", "/\\*.*?\\*/"),
            List.of("function", "class", "interface", "type", "const", "let")
        )
    );
    
    /**
     * Optimize issue context for Nova Premier suggestions
     */
    public static Map<String, Object> optimizeForSuggestions(String issueCode, String context, 
                                                            String language, String issueType) {
        Map<String, Object> optimized = new HashMap<>();
        
        try {
            // Stage 1: Extract focused code (limit to 1000 chars for token efficiency)
            String focusedCode = extractFocusedCode(issueCode, 1000);
            
            // Stage 2: Provide minimal context (limit to 500 chars)
            String optimizedContext = extractContext(context, 500);
            
            // Stage 3: Add language-specific optimization hints
            List<String> hints = generateOptimizationHints(language, issueType);
            
            optimized.put("focusedCode", focusedCode);
            optimized.put("context", optimizedContext);
            optimized.put("language", language);
            optimized.put("issueType", issueType);
            optimized.put("hints", hints);
            optimized.put("tokenEstimate", estimateTokens(focusedCode + optimizedContext));
            
        } catch (Exception e) {
            log.warn("Error optimizing code for suggestions: " + e.getMessage());
            // Fallback to basic optimization
            optimized.put("focusedCode", issueCode != null ? issueCode.substring(0, Math.min(issueCode.length(), 1000)) : "");
            optimized.put("context", context != null ? context.substring(0, Math.min(context.length(), 500)) : "");
            optimized.put("language", language);
            optimized.put("issueType", issueType);
            optimized.put("hints", List.of());
            optimized.put("tokenEstimate", 500); // Conservative estimate
        }
        
        return optimized;
    }
    
    /**
     * Extract focused code with length limitation
     */
    private static String extractFocusedCode(String code, int maxLength) {
        if (code == null || code.trim().isEmpty()) {
            return "";
        }
        
        // Remove excessive whitespace
        String cleaned = code.replaceAll("\\s+", " ").trim();
        
        // Truncate if too long
        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength) + "...";
        }
        
        return cleaned;
    }
    
    /**
     * Extract context with comment removal and length limitation
     */
    private static String extractContext(String context, int maxLength) {
        if (context == null || context.trim().isEmpty()) {
            return "";
        }
        
        // Remove comments and extra whitespace
        String cleaned = context.replaceAll("//.*$", "")
                               .replaceAll("/\\*.*?\\*/", "")
                               .replaceAll("\\s+", " ")
                               .trim();
        
        // Truncate if too long
        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength) + "...";
        }
        
        return cleaned;
    }
    
    /**
     * Generate optimization hints based on language and issue type
     */
    private static List<String> generateOptimizationHints(String language, String issueType) {
        List<String> hints = new ArrayList<>();
        
        // Language-specific hints
        LanguageConfig config = LANGUAGE_CONFIGS.get(language.toLowerCase());
        if (config != null) {
            hints.addAll(config.keywords);
        }
        
        // Issue-type specific hints
        switch (issueType.toLowerCase()) {
            case "security":
                hints.addAll(List.of("input validation", "sanitization", "authentication", "authorization"));
                break;
            case "performance":
                hints.addAll(List.of("optimization", "caching", "algorithm complexity", "memory usage"));
                break;
            case "quality":
                hints.addAll(List.of("refactoring", "code clarity", "maintainability", "design patterns"));
                break;
            default:
                hints.addAll(List.of("best practices", "code review", "testing"));
                break;
        }
        
        return hints;
    }
    
    /**
     * Estimate token count from text
     */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Rough estimation: 1 token ≈ 4 characters
        return text.length() / 4;
    }
    
    /**
     * Language-specific configuration
     */
    private static class LanguageConfig {
        final List<String> commentPatterns;
        final List<String> keywords;
        
        LanguageConfig(List<String> commentPatterns, List<String> keywords) {
            this.commentPatterns = commentPatterns;
            this.keywords = keywords;
        }
    }
}