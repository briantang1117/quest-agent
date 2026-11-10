package com.quest.api.materials;

import com.quest.api.auth.UserFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** 从公开的轮次视图重建历史；不读取 current.snapshot 中的隐藏答案。 */
final class MaterialSnapshot {
    static ArrayNode collect(JdbcTemplate db,ObjectMapper json,UUID session,JsonNode current,String excludedMessage){
        var messages=new LinkedHashMap<String,JsonNode>();
        db.query("SELECT response_json FROM student_coach_turn WHERE session_id=? AND status='done' AND response_json IS NOT NULL ORDER BY created_at,id",rs->{
            JsonNode response=json.readTree(rs.getString(1));
            merge(messages,response.path("messages"),excludedMessage,json);
            mergeGrade(messages,response.path("currentQuestion").path("id").asText(),response.path("result"));
        },session);
        merge(messages,current.path("messages"),excludedMessage,json);
        // state.current 只取题目ID；公开判分仅来自已持久化的state.result，不读内部snapshot。
        mergeGrade(messages,current.path("current").path("id").asText(),current.path("result"));
        ArrayNode out=json.createArrayNode();messages.values().forEach(out::add);
        if(out.isEmpty()||messages.values().stream().noneMatch(m->!m.path("text").asText().isBlank()))throw new UserFailure(409,40940,"对话中还没有可整理的学习内容");
        return out;
    }
    private static void mergeGrade(Map<String,JsonNode> messages,String questionId,JsonNode result){
        if(questionId.isBlank()||!result.isObject())return;
        for(JsonNode message:messages.values())if(questionId.equals(message.path("question").path("id").asText()))((ObjectNode)message).set("result",result.deepCopy());
    }
    private static void merge(Map<String,JsonNode> target,JsonNode input,String excluded,ObjectMapper json){
        for(JsonNode item:input){
            String id=item.path("id").asText();if(id.isBlank()||id.equals(excluded))continue;
            if(!Set.of("user","assistant").contains(item.path("role").asText()))continue;
            ObjectNode clean=json.createObjectNode();
            for(String field:List.of("id","role","text","question","result"))if(item.hasNonNull(field))clean.set(field,item.get(field).deepCopy());
            // 非学习状态通知不进入整理；卡片始终独立，不经过消息快照。
            if(item.path("materialNotice").asBoolean())continue;
            target.put(id,clean);
        }
    }
}
