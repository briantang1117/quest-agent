package com.quest.api.coach;

import com.quest.api.publicbank.PublicAiClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.function.Consumer;

@Component
public class CoachAiClient {
    private final PublicAiClient client;
    public CoachAiClient(PublicAiClient client){this.client=client;}
    public JsonNode turn(Object payload){return client.coachTurn(payload);}
    public JsonNode turn(Object payload,String userId,String traceId){return client.coachTurn(payload,caller(userId,traceId));}
    public JsonNode route(Object payload,String userId,String traceId){return client.coachRoute(payload,caller(userId,traceId));}
    /** 正文仅生成一次，末尾返回已校验的建议；状态与持久化由 Service 负责。 */
    public JsonNode explain(Object payload,String userId,String traceId,Consumer<String> onDelta,CoachStreamControl control){
        return client.coachExplain(payload,caller(userId,traceId),onDelta,control);
    }
    private PublicAiClient.Caller caller(String userId,String traceId){return new PublicAiClient.Caller(Long.valueOf(userId),traceId);}
}
