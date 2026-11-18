package com.quest.api.coach;

import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** 聊天中的表格/总结不产生文件；仅收紧模型动作，不用关键词主动创建任务。 */
public final class CoachCapabilities {
    private CoachCapabilities(){}
    private static final String ARTIFACT="(?:文件|文档|PDF|Markdown|MD文件|学习资料|复习材料)";
    private static final Pattern DOCUMENT=Pattern.compile(
        "(?:生成|创建|导出|下载|保存|制作|打包|提供|给我|输出).{0,24}"+ARTIFACT
        +"|(?:整理|总结|汇总|转换|转|做|写|归纳).{0,40}(?:成|为|一份|一个|一篇).{0,8}"+ARTIFACT
        +"|(?:整理|生成|制作)(?:一份)?(?:学习资料|复习材料)",Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE=Pattern.compile("(?:只在|只要|只需).{0,8}(?:聊天|对话|这里).{0,8}(?:回答|展示|输出)|(?:不要|不用|无需).{0,8}(?:文件|文档|PDF|Markdown)",Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_IMPORT=Pattern.compile("(?:记下|记住|收录|保存|导入|加入).{0,24}(?:这道题|这些题|题目|错题|题库)|(?:帮我|请).{0,8}导入.{0,12}题");
    public static boolean requestsPrivateImport(String text){return text!=null&&!Pattern.compile("(?:不要|不必|不用|别).{0,12}(?:导入|记下|保存)|(?:导入|记下).{0,12}(?:什么意思|怎么用|可以吗|能否)|(?:能不能|能否|如何|怎么).{0,12}(?:导入|记下)").matcher(text).find()&&PRIVATE_IMPORT.matcher(text).find();}
    public static boolean requestsDocument(String text){return text!=null&&!INLINE.matcher(text).find()&&DOCUMENT.matcher(text).find();}
    public static boolean allowsSuggestion(JsonNode item,Map<String,Boolean> capabilities){
        String required=item.path("required_feature").asText("");
        if(!required.isEmpty()&&!Boolean.TRUE.equals(capabilities.get(required)))return false;
        return !requestsDocument(item.path("label").asText("")+" "+item.path("text").asText(""))||Boolean.TRUE.equals(capabilities.get("materials"));
    }
}
