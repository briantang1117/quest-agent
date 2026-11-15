package com.quest.api.auth;
import com.quest.api.coach.CoachCapabilities;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class CoachCapabilitiesTest{
    @Test void tableAndOutlineAreChatWhileExplicitFilesRemainDocumentRequests(){
        for(String text:java.util.List.of("帮我梳理城市运行管理服务平台相关的常考提法，做成对比表。","总结一下财政政策和货币政策的区别","整理这段考纲的重点和复习提纲","只在聊天里输出，不用文档，列个表格"))assertThat(CoachCapabilities.requestsDocument(text)).as(text).isFalse();
        for(String text:java.util.List.of("把我们聊的总结成文档","把本次对话整理成复习材料","生成一份可下载的PDF","整理学习资料","把这份对比表导出为Markdown文件"))assertThat(CoachCapabilities.requestsDocument(text)).as(text).isTrue();
    }
    @Test void unavailableSuggestionsAreRejectedEvenWhenModelOmitsFeatureTag(){
        var json=new ObjectMapper();var off=Map.of("materials",false,"search",false);
        assertThat(CoachCapabilities.allowsSuggestion(json.readTree("{\"label\":\"梳理考点\",\"text\":\"梳理常考提法，做成对比表。\"}"),off)).isTrue();
        var file=json.readTree("{\"label\":\"生成文档\",\"text\":\"把本次对话总结成文档\"}");
        assertThat(CoachCapabilities.allowsSuggestion(file,off)).isFalse();
        assertThat(CoachCapabilities.allowsSuggestion(file,Map.of("materials",true))).isTrue();
        assertThat(CoachCapabilities.allowsSuggestion(json.readTree("{\"label\":\"联网查证\",\"text\":\"查证\",\"required_feature\":\"search\"}"),off)).isFalse();
    }
}
