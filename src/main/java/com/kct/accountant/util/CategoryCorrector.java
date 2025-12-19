package com.kct.accountant.util;

import java.util.*;
import java.util.stream.Collectors;

public class CategoryCorrector {
    private static final int MAX_DISTANCE = 2;

    public static int levenshteinDistance(String s1, String s2) {
        s1 = s1.toLowerCase();
        s2 = s2.toLowerCase();
        
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else {
                    if (j > 0) {
                        int newValue = costs[j - 1];
                        if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                        }
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) {
                costs[s2.length()] = lastValue;
            }
        }
        return costs[s2.length()];
    }

    public static List<String> findSimilarCategories(String input, List<String> existingCategories) {
        if (input == null || input.isEmpty() || existingCategories.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Integer> distances = new HashMap<>();
        
        for (String existing : existingCategories) {
            int distance = levenshteinDistance(input, existing);
            if (distance > 0 && distance <= MAX_DISTANCE) {
                distances.put(existing, distance);
            }
        }

        return distances.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public static List<String> extractCategories(List<String> notes) {
        Set<String> categories = new HashSet<>();
        
        for (String note : notes) {

            if (note.contains(":")) {
                String[] parts = note.split(":", 2);
                if (parts.length == 2) {
                    String category = parts[1].trim();
                    if (!category.isEmpty()) {
                        categories.add(category.toLowerCase());
                    }
                }
            }
        }
        
        return new ArrayList<>(categories);
    }

    public static String createCorrectionSuggestion(String input, List<String> suggestions) {
        if (suggestions.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("💡 Возможно, вы имели в виду:\n");
        
        for (int i = 0; i < suggestions.size(); i++) {
            sb.append("   ").append(i + 1).append(". ").append(suggestions.get(i)).append("\n");
        }
        
        return sb.toString();
    }
}

