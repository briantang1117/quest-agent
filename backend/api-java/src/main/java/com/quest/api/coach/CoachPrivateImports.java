package com.quest.api.coach;
import com.quest.api.auth.*;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.privatebank.PrivateQuestionService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.util.*;
@Component
public class CoachPrivateImports {
    private final PrivateQuestionService service;
    private final ObjectMapper json;
    public CoachPrivateImports(PrivateQuestionService service,ObjectMapper json){this.service=service;this.json=json;}
    public void validate(long user,List<UUID> attachments){service.assets(user,attachments);}
    public ArrayNode jobs(StudentCoachService.Session s){return service.sessionJobs(s.userId(),s.state().path("privateImports"));}
    public Map<String,Object> context(StudentCoachService.Session s){var out=new LinkedHashMap<String,Object>();var assets=attachments(s);if(!assets.isEmpty())out.put("attachments",assets);var refs=s.state().path("privateImports");if(refs.isArray()&&!refs.isEmpty())out.put("private_items",service.contextIssues(s.userId(),UUID.fromString(refs.get(refs.size()-1).asText())));return out;}
    public void hydrate(StudentCoachService.Session s,JsonNode messages){for(var msg:messages){var values=msg.path("attachments");if(values.isArray()){var ids=new ArrayList<UUID>();for(var id:values)ids.add(UUID.fromString(id.asText()));((ObjectNode)msg).set("attachments",service.assets(s.userId(),ids));}}}
    private ArrayNode attachments(StudentCoachService.Session s){var messages=s.state().path("messages");for(int i=messages.size()-1;i>=0;i--){var message=messages.get(i);if(!message.path("role").asText().equals("user"))continue;var values=message.path("attachments");if(!values.isArray()&&(message.path("text").asText().contains("\n")||message.path("text").asText().matches("(?s).*[A-H][.、．].*")))return json.createArrayNode();if(values.isArray()&&!values.isEmpty()){var ids=new ArrayList<UUID>();for(var id:values)ids.add(UUID.fromString(id.asText()));return service.assets(s.userId(),ids);}}return json.createArrayNode();}
    private ObjectNode say(ObjectNode state,String text){var messages=state.withArray("messages");var item=messages.addObject().put("id",UUID.randomUUID().toString()).put("role","assistant").put("text",text);if(messages.size()>100)messages.remove(0);return item;}
    public void create(SessionIdentity who,StudentCoachService.Session s,UUID request,String instruction){
        if(!CoachCapabilities.requestsPrivateImport(instruction))throw new UserFailure(409,40960,"请明确说明要记下或导入哪些题目");
        var assets=attachments(s);String text="";
        if(instruction.contains("\n")||instruction.matches("(?s).*[A-H][.、．].*"))text=instruction;
        if(assets.isEmpty()&&text.isBlank()){
            var messages=s.state().path("messages");for(int i=messages.size()-2;i>=0;i--){var msg=messages.get(i);String prior=msg.path("text").asText();if(msg.path("role").asText().equals("user")&&!CoachCapabilities.requestsPrivateImport(prior)&&(prior.contains("\n")||prior.matches("(?s).*[A-H][.、．].*"))){text=prior;break;}}
        }
        JsonNode job=service.create(who,s.id(),request,s.subject(),instruction,assets,text);var refs=s.state().withArray("privateImports");boolean exists=false;for(var id:refs)if(id.asText().equals(job.path("id").asText()))exists=true;if(!exists)refs.add(job.path("id").asText());if(refs.size()>20)refs.remove(0);
        say(s.state(),"已开始导入到你的私有题库。我会识别题目、校对答案并整理解析和记忆方法；有疑点的题会在这里单独列出。").put("privateImportId",job.path("id").asText());
    }
    public void resolve(SessionIdentity who,StudentCoachService.Session s,UUID item,String action,String text){
        if(item==null||action==null||text.matches("(?s).*(?:不要|别|不能|是否|能否|什么意思).*"))throw new IllegalArgumentException();
        boolean explicit=switch(action){case "use_suggestion"->text.matches("(?s).*(?:采用|按|用).*(?:建议|校对|补全|AI).*");case "keep_original"->text.matches("(?s).*(?:保留|按|用).*原答案.*");case "exclude"->text.matches("(?s).*(?:跳过|排除|不拿来刷|不练|先存着).*");default->false;};if(!explicit)throw new UserFailure(409,40960,"请明确说明要采用建议答案、保留原答案还是排除练习");
        var refs=s.state().path("privateImports");if(refs.isEmpty())throw new IllegalArgumentException();var job=service.job(who,UUID.fromString(refs.get(refs.size()-1).asText()));JsonNode target=null;int count=0;for(var entry:job.path("items"))if(entry.path("status").asText().equals("needs_attention")){count++;if(entry.path("id").asText().equals(item.toString()))target=entry;}
        if(target==null||count>1&&!text.contains("第"+target.path("ordinal").asInt()+"题"))throw new UserFailure(409,40960,"有多道疑点题，请明确本批第几题");
        service.decide(who,s.id(),item,action);say(s.state(),"已按你的选择处理本批第"+target.path("ordinal").asInt()+"题，结果可在导入卡片查看。");
    }
}
