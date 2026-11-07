package com.quest.common.question;

import java.util.HashSet;
import java.util.List;

/** Pure, immutable validated objective-question contract. No persistence or publishing side effects. */
public record Question(Type type, String stemMd, List<Option> options, List<String> answerKeys,
                       List<String> knowledgePoints, int difficulty) {
    public enum Type { SINGLE_CHOICE, MULTIPLE_CHOICE }
    public record Option(String key, String textMd) {}

    public Question {
        require(type != null, "Question type is required");
        require(stemMd != null && !stemMd.isBlank() && stemMd.length() <= 5000, "Stem must contain 1–5000 characters");
        require(difficulty >= 1 && difficulty <= 5, "Difficulty must be 1–5");
        require(options != null && options.size() >= 2 && options.size() <= 8, "Question requires 2–8 options");
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            require(option != null && String.valueOf((char) ('A' + i)).equals(option.key()), "Options must have unique consecutive A–H keys");
            require(option.textMd() != null && !option.textMd().isBlank(), "Option text is required");
        }
        require(answerKeys != null && !answerKeys.isEmpty(), "Answer is required");
        require(answerKeys.stream().noneMatch(key -> key == null || key.isBlank()), "Answer keys must not be blank");
        require(new HashSet<>(answerKeys).size() == answerKeys.size(), "Duplicate answer keys");
        var available = options.stream().map(Option::key).toList();
        require(available.containsAll(answerKeys), "Answer must be an option subset");
        require(type == Type.SINGLE_CHOICE ? answerKeys.size() == 1 : answerKeys.size() >= 2, "Answer cardinality does not match question type");
        require(knowledgePoints != null && !knowledgePoints.isEmpty(), "At least one knowledge point is required");
        require(knowledgePoints.stream().allMatch(code -> code != null && code.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")), "Knowledge points must be codes, not free text");
        require(new HashSet<>(knowledgePoints).size() == knowledgePoints.size(), "Duplicate knowledge points");
        options = List.copyOf(options);
        answerKeys = List.copyOf(answerKeys);
        knowledgePoints = List.copyOf(knowledgePoints);
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
