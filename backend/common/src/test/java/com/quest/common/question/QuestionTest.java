package com.quest.common.question;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QuestionTest {
    private final List<Question.Option> options = List.of(new Question.Option("A", "甲"), new Question.Option("B", "乙"), new Question.Option("C", "丙"));
    private Question question(Question.Type type, List<String> answers, int difficulty) {
        return new Question(type, "题干", options, answers, List.of("GK/FL/MF/0003"), difficulty);
    }
    @Test void validSingleAndMultipleChoice() {
        assertDoesNotThrow(() -> question(Question.Type.SINGLE_CHOICE, List.of("A"), 1));
        assertDoesNotThrow(() -> question(Question.Type.MULTIPLE_CHOICE, List.of("A", "B"), 5));
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 6}) void difficultyMustBeOneToFive(int value) {
        assertThrows(IllegalArgumentException.class, () -> question(Question.Type.SINGLE_CHOICE, List.of("A"), value));
    }
    @Test void rejectsMissingOrExcessiveStem() {
        for (String stem : Arrays.asList(null, " ", "题".repeat(5001))) {
            assertThrows(IllegalArgumentException.class, () -> new Question(Question.Type.SINGLE_CHOICE, stem, options, List.of("A"), List.of("GK/FL"), 3));
        }
    }
    @Test void acceptsMaximumStemLength() {
        assertDoesNotThrow(() -> new Question(Question.Type.SINGLE_CHOICE, "题".repeat(5000), options, List.of("A"), List.of("GK/FL"), 3));
    }
    @Test void rejectsInvalidOptions() {
        var badOptions = List.of(List.of(new Question.Option("A", "a")),
            List.of(new Question.Option("A", "a"), new Question.Option("A", "b")),
            List.of(new Question.Option("B", "a"), new Question.Option("A", "b")),
            List.of(new Question.Option("A", " "), new Question.Option("B", "b")),
            List.of(new Question.Option("A", "a"), new Question.Option("I", "b")));
        for (var value : badOptions) assertThrows(IllegalArgumentException.class, () -> new Question(Question.Type.SINGLE_CHOICE, "题", value, List.of("A"), List.of("GK/FL"), 3));
        var nine = new ArrayList<Question.Option>();
        for (int i = 0; i < 9; i++) nine.add(new Question.Option(String.valueOf((char)('A' + i)), "选项"));
        assertThrows(IllegalArgumentException.class, () -> new Question(Question.Type.SINGLE_CHOICE, "题", nine, List.of("A"), List.of("GK/FL"), 3));
    }
    @Test void rejectsMissingFieldsAndNullElements() {
        assertThrows(IllegalArgumentException.class, () -> new Question(null, "题", options, List.of("A"), List.of("GK/FL"), 3));
        assertThrows(IllegalArgumentException.class, () -> new Question(Question.Type.SINGLE_CHOICE, "题", null, List.of("A"), List.of("GK/FL"), 3));
        assertThrows(IllegalArgumentException.class, () -> new Question(Question.Type.SINGLE_CHOICE, "题", Arrays.asList(options.get(0), null), List.of("A"), List.of("GK/FL"), 3));
        assertThrows(IllegalArgumentException.class, () -> question(Question.Type.SINGLE_CHOICE, null, 3));
        assertThrows(IllegalArgumentException.class, () -> question(Question.Type.SINGLE_CHOICE, Arrays.asList((String) null), 3));
    }
    @Test void requiresKnowledgeCodes() {
        for (var codes : List.of(List.<String>of(), List.of(" "), List.of("GK//MF"), List.of("GK/FL", "GK/FL"))) {
            assertThrows(IllegalArgumentException.class, () -> new Question(Question.Type.SINGLE_CHOICE, "题", options, List.of("A"), codes, 3));
        }
    }
    @Test void enforcesAnswerCardinalityAndMembership() {
        for (var keys : List.of(List.<String>of(), List.of("A", "B"), List.of("D"), List.of("A", "A")))
            assertThrows(IllegalArgumentException.class, () -> question(Question.Type.SINGLE_CHOICE, keys, 3));
        for (var keys : List.of(List.<String>of(), List.of("A"), List.of("A", "D"), List.of("A", "A")))
            assertThrows(IllegalArgumentException.class, () -> question(Question.Type.MULTIPLE_CHOICE, keys, 3));
    }
    @Test void defensiveCopyPreventsMutation() {
        var mutable = new ArrayList<>(List.of("A"));
        var question = question(Question.Type.SINGLE_CHOICE, mutable, 3);
        mutable.add("B");
        assertEquals(List.of("A"), question.answerKeys());
        assertThrows(UnsupportedOperationException.class, () -> question.answerKeys().add("C"));
    }
    @Test void unorderedMultipleAnswersEarnFullCredit() {
        var result = ChoiceGrader.grade(question(Question.Type.MULTIPLE_CHOICE, List.of("A", "B"), 3), List.of("B", "A"), new BigDecimal("2"), new BigDecimal("1"));
        assertTrue(result.correct()); assertEquals(new BigDecimal("2"), result.score());
    }
    @Test void partialAndWrongAndUnansweredAreDistinct() {
        var q = question(Question.Type.MULTIPLE_CHOICE, List.of("A", "B"), 3);
        assertEquals(BigDecimal.ONE, ChoiceGrader.grade(q, List.of("A"), BigDecimal.TEN, BigDecimal.ONE).score());
        assertEquals(BigDecimal.ZERO, ChoiceGrader.grade(q, List.of("A", "C"), BigDecimal.TEN, BigDecimal.ONE).score());
        assertEquals(BigDecimal.ZERO, ChoiceGrader.grade(q, List.of(), BigDecimal.TEN, BigDecimal.ONE).score());
        assertFalse(ChoiceGrader.grade(q, List.of("A"), BigDecimal.TEN, BigDecimal.ONE).correct());
    }
    @Test void singleChoiceIsAllOrNothing() {
        var q = question(Question.Type.SINGLE_CHOICE, List.of("A"), 3);
        assertTrue(ChoiceGrader.grade(q, List.of("A"), BigDecimal.ONE, BigDecimal.ZERO).correct());
        assertEquals(BigDecimal.ZERO, ChoiceGrader.grade(q, List.of("B"), BigDecimal.ONE, BigDecimal.ZERO).score());
        assertThrows(IllegalArgumentException.class, () -> ChoiceGrader.grade(q, List.of("A", "B"), BigDecimal.ONE, BigDecimal.ZERO));
    }
    @Test void rejectsDuplicateUnknownNullSelectionsAndInvalidScoring() {
        var q = question(Question.Type.MULTIPLE_CHOICE, List.of("A", "B"), 3);
        for (var selection : List.of(List.of("A", "A"), List.of("D"), Arrays.asList((String)null)))
            assertThrows(IllegalArgumentException.class, () -> ChoiceGrader.grade(q, selection, BigDecimal.ONE, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ChoiceGrader.grade(q, null, BigDecimal.ONE, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ChoiceGrader.grade(q, List.of("A"), BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ChoiceGrader.grade(q, List.of("A"), BigDecimal.ONE, BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class, () -> ChoiceGrader.grade(q, List.of("A"), BigDecimal.ONE, new BigDecimal("-1")));
    }
}
