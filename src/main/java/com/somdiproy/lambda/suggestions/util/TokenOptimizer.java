package com.somdiproy.lambda.suggestions.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for token optimization and estimation
 */
public class TokenOptimizer {
    
    // Rough estimation: 1 token ≈ 4 characters for English text
    private static final double CHARS_PER_TOKEN = 4.0;
    
    /**
     * Estimate the number of tokens in a text
     * This is a rough approximation - actual tokens may vary
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        // Simple estimation based on character count
        // In practice, tokenization is more complex
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
    
    /**
     * Truncate code snippet to stay within token limits
     * Preserves code structure when possible
     */
    public static String truncateCode(String code, int maxChars) {
        if (code == null || code.length() <= maxChars) {
            return code;
        }
        
        // Try to truncate at a reasonable boundary
        String truncated = code.substring(0, maxChars);
        
        // Find last complete line
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > maxChars * 0.8) {
            truncated = truncated.substring(0, lastNewline);
        }
        
        // Add truncation indicator
        return truncated + "\n// ... (truncated)";
    }
    
    /**
     * Optimize prompt by removing unnecessary whitespace and comments
     */
    public static String optimizePrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        
        // Remove multiple consecutive blank lines
        prompt = prompt.replaceAll("\\n{3,}", "\n\n");
        
        // Remove trailing whitespace from each line
        prompt = prompt.replaceAll("[ \\t]+$", "");
        
        // Remove leading whitespace (but preserve indentation structure)
        prompt = prompt.replaceAll("^[ \\t]+$", "");
        
        return prompt.trim();
    }
    
    /**
     * Split large code into analyzable chunks
     */
    public static List<String> splitCode(String code, int maxCharsPerChunk) {
        List<String> chunks = new ArrayList<>();
        
        if (code == null || code.length() <= maxCharsPerChunk) {
            chunks.add(code);
            return chunks;
        }
        
        // Split by logical boundaries (methods, classes)
        String[] lines = code.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        
        for (String line : lines) {
            if (currentChunk.length() + line.length() + 1 > maxCharsPerChunk && 
                currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(line).append("\n");
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        
        return chunks;
    }
}