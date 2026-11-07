package com.quest.common.question;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

/** Exact set matching; partial credit is an explicit examination policy, never a hidden default. */
public final class ChoiceGrader {
    private ChoiceGrader() {}
    public record Result(boolean correct, BigDecimal score) {}

    public static Result grade(Question question, List<String> selected, BigDecimal fullScore, BigDecimal partialScore) {
        Question.require(question != null && selected != null, "Question and selection are required");
        Question.require(fullScore != null && fullScore.signum() > 0, "Full score must be positive");
        Question.require(partialScore != null && partialScore.signum() >= 0 && partialScore.compareTo(fullScore) <= 0, "Partial score must be between zero and full score");
        Question.require(selected.stream().allMatch(key -> key != null && !key.isBlank()), "Selection keys must not be blank");
        var selection = new HashSet<>(selected);
        Question.require(selection.size() == selected.size(), "Duplicate selection keys");
        Question.require(question.options().stream().map(Question.Option::key).toList().containsAll(selection), "Unknown selection key");
        Question.require(question.type() != Question.Type.SINGLE_CHOICE || selected.size() <= 1, "Single choice accepts at most one selection");
        var answers = new HashSet<>(question.answerKeys());
        if (answers.equals(selection)) return new Result(true, fullScore);
        if (question.type() == Question.Type.MULTIPLE_CHOICE && !selection.isEmpty() && answers.containsAll(selection)) return new Result(false, partialScore);
        return new Result(false, BigDecimal.ZERO);
    }
}
