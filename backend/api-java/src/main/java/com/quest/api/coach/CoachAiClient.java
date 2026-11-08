package com.quest.api.coach;

import com.quest.api.publicbank.PublicAiClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class CoachAiClient {
    private final PublicAiClient client;
    public CoachAiClient(PublicAiClient client){this.client=client;}
    public JsonNode turn(Object payload){return client.coachTurn(payload);}
    /** 带调用者归因的调用，审计里能看出是谁触发的、对应哪条 trace。 */
    public JsonNode turn(Object payload,String userId,String traceId){
        Long actor;try{actor=Long.parseLong(userId);}catch(RuntimeException failure){actor=null;}
        return client.coachTurn(payload,new PublicAiClient.Caller(actor,traceId));
    }
}
