package com.quest.api.auth;

import com.quest.api.coach.CoachSources;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class CoachSourcesTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void stripsAllNonMetadataFields(){
        var items=json.valueToTree(List.of(Map.of("tool","search_web","title","资料","url","https://example.com/reference","retrieved_at","2026-11-11T00:00:00Z","text","PRIVATE_BODY")));
        assertThat(CoachSources.validate(items,json).toString()).doesNotContain("PRIVATE_BODY","text");
    }
    @Test void rejectsUnsafeLinksAndInvalidDates(){
        for(String url:List.of("javascript:alert(1)","http://127.0.0.1/","http://172.16.0.1/","http://169.254.169.254/","http://user:pass@example.com/","http://localhost/")){
            var items=json.valueToTree(List.of(Map.of("tool","search_web","title","资料","url",url,"retrieved_at","2026-11-11T00:00:00Z")));
            assertThatThrownBy(()->CoachSources.validate(items,json)).isInstanceOf(UserFailure.class);
        }
    }
    @Test void acceptsFetchSourcesAndPublicIpv4(){
        for(String url:List.of("https://example.com/page","https://93.184.216.34/page")){
            var items=json.valueToTree(List.of(Map.of("tool","fetch_page","title","网页","url",url,"retrieved_at","2026-11-11T00:00:00Z")));
            assertThat(CoachSources.validate(items,json).get(0).path("tool").asText()).isEqualTo("fetch_page");
        }
    }
    @Test void acceptsExpandedSourceListAndKeepsTransportLimit(){
        var items=json.createArrayNode();
        for(int i=0;i<36;i++)items.add(json.valueToTree(Map.of("tool","search_web","title","资料","url","https://example.com/"+i,"retrieved_at","2026-11-11T00:00:00Z")));
        assertThat(CoachSources.validate(items,json).size()).isEqualTo(36);
        for(int i=36;i<97;i++)items.add(items.get(0).deepCopy());
        assertThatThrownBy(()->CoachSources.validate(items,json)).isInstanceOf(UserFailure.class);
    }
}
