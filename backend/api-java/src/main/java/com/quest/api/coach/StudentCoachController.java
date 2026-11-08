package com.quest.api.coach;

import com.quest.api.auth.UserController;
import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StudentCoachController {
    private final StudentCoachService service;
    public StudentCoachController(StudentCoachService service){this.service=service;}
    @GetMapping("/me/study-preference") public ApiResponse<?> preference(HttpServletRequest r){return ok(service.preference(who(r)),r);}
    @PutMapping("/me/study-preference") public ApiResponse<?> preference(@RequestBody StudentCoachService.Preference body,HttpServletRequest r){return ok(service.preference(who(r),body.subject()),r);}
    @GetMapping("/coach/sessions") public ApiResponse<?> list(HttpServletRequest r){return ok(service.list(who(r)),r);}
    @PostMapping("/coach/sessions") public ApiResponse<?> create(@RequestBody StudentCoachService.Create body,HttpServletRequest r){return ok(service.create(who(r),body),r);}
    @GetMapping("/coach/sessions/{id}") public ApiResponse<?> get(@PathVariable String id,HttpServletRequest r){return ok(service.get(who(r),id),r);}
    @PostMapping("/coach/sessions/{id}/turns") public ApiResponse<?> turn(@PathVariable String id,@RequestBody StudentCoachService.Turn body,HttpServletRequest r){return ok(service.turn(who(r),id,body,TraceFilter.traceId(r)),r);}
    private SessionIdentity who(HttpServletRequest r){if(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity identity)return identity;throw UserFailure.denied();}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.success(data,TraceFilter.traceId(r));}
}
