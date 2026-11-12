package com.quest.api.auth;

import com.quest.api.coach.CoachWorkflowGrant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CoachWorkflowGrantTest {
    final ObjectMapper json=new ObjectMapper();
    final String key="only-java-knows-this-signing-key-32-bytes";
    CoachWorkflowGrant.Claims claims(long expiry){return new CoachWorkflowGrant.Claims("1","login",UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),"answer",UUID.randomUUID().toString(),List.of("B"),expiry);}
    @Test void expiredTamperedOversizedAndOtherSecretGrantsAreRejected(){
        var grants=new CoachWorkflowGrant(json,key);var c=claims(Instant.now().getEpochSecond()+120);String token=grants.issue(c);
        assertThat(grants.verify(token)).isEqualTo(c);
        assertThatThrownBy(()->grants.verify(grants.issue(claims(Instant.now().getEpochSecond()-1)))).isInstanceOf(UserFailure.class);
        assertThatThrownBy(()->grants.verify(token+"x")).isInstanceOf(UserFailure.class);
        assertThatThrownBy(()->grants.verify("x".repeat(8193))).isInstanceOf(UserFailure.class);
        assertThatThrownBy(()->new CoachWorkflowGrant(json,"worker-shared-hmac-secret-32-bytes-long").verify(token)).isInstanceOf(UserFailure.class);
        assertThatThrownBy(()->grants.verify(grants.issue(claims(Instant.now().getEpochSecond()+600)))).isInstanceOf(UserFailure.class);
    }
}
