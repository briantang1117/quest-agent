package com.quest.api.materials;

import com.quest.api.auth.UserController;
import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class LearningMaterialController {
    private final LearningMaterialService service;
    private final com.quest.api.publicbank.PublicAiClient ai;
    public LearningMaterialController(LearningMaterialService service,com.quest.api.publicbank.PublicAiClient ai){this.service=service;this.ai=ai;}
    public record Retry(UUID requestId){}
    @PostMapping("/coach/sessions/{id}/materials") public ApiResponse<?> create(@PathVariable String id,@RequestBody LearningMaterialService.Create body,HttpServletRequest r){return ok(service.create(who(r),id,body),r);}
    @GetMapping("/study/materials") public ApiResponse<?> list(HttpServletRequest r){return ok(service.list(who(r)),r);}
    @GetMapping("/study/materials/{id}") public ApiResponse<?> get(@PathVariable String id,HttpServletRequest r){return ok(service.get(who(r),id),r);}
    @PostMapping("/study/materials/{id}/retry") public ApiResponse<?> retry(@PathVariable String id,@RequestBody Retry body,HttpServletRequest r){return ok(service.retry(who(r),id,body.requestId()),r);}
    @GetMapping("/study/materials/{id}/download") public ResponseEntity<byte[]> download(@PathVariable String id,@RequestParam(defaultValue="markdown") String format,HttpServletRequest r){
        if(!java.util.Set.of("markdown","pdf").contains(format))throw new IllegalArgumentException();
        var value=service.get(who(r),id);if(!"ready".equals(value.get("status")))throw LearningMaterialService.conflict();
        String title=((String)value.get("title")).replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]","_");if(title.length()>100)title=title.substring(0,100);
        boolean pdf=format.equals("pdf");
        byte[] body=pdf?ai.materialPdf((String)value.get("title"),(String)value.get("markdown"),new com.quest.api.publicbank.PublicAiClient.Caller(Long.valueOf(who(r).user().id()),TraceFilter.traceId(r))):((String)value.get("markdown")).getBytes(StandardCharsets.UTF_8);
        // 转换完成后再次校验登录与所有者，避免导出期间登录失效后仍返回正文。
        if(pdf)service.get(who(r),id);
        return ResponseEntity.ok().contentType(pdf?MediaType.APPLICATION_PDF:MediaType.parseMediaType("text/markdown;charset=UTF-8")).header(HttpHeaders.CACHE_CONTROL,"private, no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(title+(pdf?".pdf":".md"),StandardCharsets.UTF_8).build().toString()).body(body);
    }
    private SessionIdentity who(HttpServletRequest r){if(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity identity)return identity;throw UserFailure.denied();}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.success(data,TraceFilter.traceId(r));}
}
