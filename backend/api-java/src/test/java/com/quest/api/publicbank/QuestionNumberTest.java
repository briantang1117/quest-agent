package com.quest.api.publicbank;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class QuestionNumberTest {
    @Test void originalNumberWinsOverModelAndMissingOriginalCannotBeInvented() {
        var json=new ObjectMapper();
        var draft=json.readTree("{\"source\":{\"question_no\":99}}");
        var withOriginal=PublicBankService.numberedDraft(draft,json.readTree("{\"provenance\":{\"question_no\":12}}"));
        assertThat(withOriginal.path("source").path("question_no").asInt()).isEqualTo(12);
        var withoutOriginal=PublicBankService.numberedDraft(draft,json.readTree("{\"provenance\":{\"question_no\":null}}"));
        assertThat(withoutOriginal.path("source").path("question_no").isNull()).isTrue();
    }
}
