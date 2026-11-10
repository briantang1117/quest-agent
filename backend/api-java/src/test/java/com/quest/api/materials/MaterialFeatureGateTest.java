package com.quest.api.materials;

import com.quest.api.auth.UserFailure;
import com.quest.api.publicbank.PublicAiClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MaterialFeatureGateTest {
    @Test void disabledExecuteRejectsBeforeClaimOrPaidModelCall() {
        var materials = mock(LearningMaterialService.class);
        var ai = mock(PublicAiClient.class);
        doThrow(new UserFailure(503, 50341, "学习资料生成尚未启用")).when(materials).ensureEnabled();
        var executor = new MaterialTaskExecutor(materials, JsonMapper.builder().build(), ai);
        var event = new MaterialTaskExecutor.Event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> executor.execute(event, "gate-test"))
                .isInstanceOfSatisfying(UserFailure.class, failure -> {
                    org.assertj.core.api.Assertions.assertThat(failure.status()).isEqualTo(503);
                    org.assertj.core.api.Assertions.assertThat(failure.code()).isEqualTo(50341);
                });
        verify(materials).ensureEnabled();
        verifyNoMoreInteractions(materials);
        verifyNoInteractions(ai);
    }

    @Test void disabledClaimRejectsBeforeLeasingOrMaintainingDatabaseTasks() {
        var materials = mock(LearningMaterialService.class);
        var ai = mock(PublicAiClient.class);
        doThrow(new UserFailure(503, 50341, "学习资料生成尚未启用")).when(materials).ensureEnabled();
        var executor = new MaterialTaskExecutor(materials, JsonMapper.builder().build(), ai);

        assertThatThrownBy(executor::claimOutbox).isInstanceOfSatisfying(UserFailure.class,
                failure -> org.assertj.core.api.Assertions.assertThat(failure.status()).isEqualTo(503));
        verify(materials).ensureEnabled();
        verifyNoMoreInteractions(materials);
        verifyNoInteractions(ai);
    }

    @Test void realFeatureGateRejectsDisabledAndAcceptsEnabledWithoutAnyStorage() {
        var disabled = new LearningMaterialService(null, null, null, JsonMapper.builder().build(), false, 1000000, 8388608);
        var enabled = new LearningMaterialService(null, null, null, JsonMapper.builder().build(), true, 1000000, 8388608);
        assertThatThrownBy(disabled::ensureEnabled).isInstanceOfSatisfying(UserFailure.class,
                failure -> org.assertj.core.api.Assertions.assertThat(failure.status()).isEqualTo(503));
        org.assertj.core.api.Assertions.assertThatCode(enabled::ensureEnabled).doesNotThrowAnyException();
    }
}
