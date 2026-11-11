package com.quest.api.coach;

import com.quest.api.auth.UserFailure;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 来源仅保留白名单元数据，随成功消息存入已有 state_json。 */
public final class CoachSources {
    private CoachSources(){}
    public static JsonNode validate(JsonNode items,ObjectMapper json){
        if(!items.isArray()||items.size()>18||json.writeValueAsBytes(items).length>24576)throw invalid();
        var clean=json.createArrayNode();
        for(var item:items){
            if(!Set.of("search_web","fetch_page").contains(item.path("tool").asText())||!item.path("title").isString()||item.path("title").asText().length()>200)throw invalid();
            String url=item.path("url").asText(),stamp=item.path("retrieved_at").asText();
            try{
                URI uri=URI.create(url);String host=uri.getHost();
                if(url.length()>2048||!Set.of("http","https").contains(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw invalid();
                String lower=host.toLowerCase(java.util.Locale.ROOT);
                if(lower.equals("localhost")||lower.startsWith("127.")||lower.startsWith("10.")||lower.equals("[::1]")||lower.endsWith(".internal")||lower.endsWith(".local"))throw invalid();
                if(!lower.contains(".")||lower.contains(":"))throw invalid();
                if(lower.endsWith(".localhost")||lower.endsWith(".lan")||lower.endsWith(".home"))throw invalid();
                if(lower.matches("[0-9.]+")){
                    String[] parts=lower.split("\\.");if(parts.length!=4)throw invalid();
                    int a=Integer.parseInt(parts[0]),b=Integer.parseInt(parts[1]);
                    if(a==0||a==10||a==127||a>=224||(a==100&&b>=64&&b<=127)||(a==169&&b==254)||(a==172&&b>=16&&b<=31)||(a==192&&(b==168||b==0)))throw invalid();
                    for(String part:parts)if(Integer.parseInt(part)>255)throw invalid();
                }
                Instant.parse(stamp);
            }catch(RuntimeException failure){throw invalid();}
            clean.add(json.createObjectNode().put("tool",item.path("tool").asText()).put("title",item.path("title").asText()).put("url",url).put("retrieved_at",stamp));
        }
        return clean;
    }
    private static UserFailure invalid(){return new UserFailure(503,50204,"AI来源格式错误");}
}
