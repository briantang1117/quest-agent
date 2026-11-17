package com.quest.api.coach;
import com.quest.api.auth.UserController;
import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/coach/topics")
public class LearningTopicController {
    private final CoachMemoryService memory;
    public LearningTopicController(CoachMemoryService memory){this.memory=memory;}
    private SessionIdentity who(HttpServletRequest r){if(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity who)return who;throw UserFailure.denied();}
    @GetMapping public ApiResponse<?> list(HttpServletRequest r){return ApiResponse.success(memory.list(who(r)),TraceFilter.traceId(r));}
    @PostMapping public ApiResponse<?> create(@RequestBody CoachMemoryService.TopicInput body,HttpServletRequest r){if(body.requestId()==null)throw new IllegalArgumentException();return ApiResponse.success(memory.save(who(r),body.requestId(),body,true),TraceFilter.traceId(r));}
    @PutMapping("/{id}") public ApiResponse<?> edit(@PathVariable UUID id,@RequestBody CoachMemoryService.TopicInput body,HttpServletRequest r){return ApiResponse.success(memory.save(who(r),id,body,false),TraceFilter.traceId(r));}
    @DeleteMapping("/{id}") public ApiResponse<?> delete(@PathVariable UUID id,HttpServletRequest r){memory.delete(who(r),id);return ApiResponse.success(null,TraceFilter.traceId(r));}
}
