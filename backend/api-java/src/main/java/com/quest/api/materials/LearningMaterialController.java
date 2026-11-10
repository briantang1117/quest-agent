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
    public LearningMaterialController(LearningMaterialService service){this.service=service;}
    public record Retry(UUID requestId){}
    @PostMapping("/coach/sessions/{id}/materials") public ApiResponse<?> create(@PathVariable String id,@RequestBody LearningMaterialService.Create body,HttpServletRequest r){return ok(service.create(who(r),id,body),r);}
    @GetMapping("/study/materials") public ApiResponse<?> list(HttpServletRequest r){return ok(service.list(who(r)),r);}
    @GetMapping("/study/materials/{id}") public ApiResponse<?> get(@PathVariable String id,HttpServletRequest r){return ok(service.get(who(r),id),r);}
    @PostMapping("/study/materials/{id}/retry") public ApiResponse<?> retry(@PathVariable String id,@RequestBody Retry body,HttpServletRequest r){return ok(service.retry(who(r),id,body.requestId()),r);}
    @GetMapping("/study/materials/{id}/download") public ResponseEntity<byte[]> download(@PathVariable String id,HttpServletRequest r){
        var value=service.get(who(r),id);if(!"ready".equals(value.get("status")))throw LearningMaterialService.conflict();
        String title=((String)value.get("title")).replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]","_");if(title.length()>100)title=title.substring(0,100);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).header(HttpHeaders.CACHE_CONTROL,"private, no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(title+".md",StandardCharsets.UTF_8).build().toString()).body(((String)value.get("markdown")).getBytes(StandardCharsets.UTF_8));
    }
    private SessionIdentity who(HttpServletRequest r){if(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity identity)return identity;throw UserFailure.denied();}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.success(data,TraceFilter.traceId(r));}
}
