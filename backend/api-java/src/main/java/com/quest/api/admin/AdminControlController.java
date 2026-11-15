package com.quest.api.admin;
import com.quest.api.auth.*;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1")
public class AdminControlController {
    private final AdminControlService service;
    private final UserService users;
    private final ModelMeterService meter;
    public AdminControlController(AdminControlService service,UserService users,ModelMeterService meter){this.service=service;this.users=users;this.meter=meter;}
    static SessionIdentity who(HttpServletRequest r){if(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity who)return who;throw UserFailure.invalid();}
    public record Feature(boolean enabled){}
    public record Credit(long amount,UUID requestId,String reason){}
    public record Reconcile(long inputTokens,long outputTokens,String reason){}
    private record ReconcileState(String status,java.time.Instant updated,String outcome){}
    @PostMapping("/admin/users/{id}/usage/{call}/reconcile") public ApiResponse<?> reconcile(@PathVariable String id,@PathVariable UUID call,@RequestBody Reconcile body,HttpServletRequest r){
        service.tx(()->{users.requireAdmin(who(r));service.student(id);
            if(body.reason()==null||body.reason().isBlank()||body.reason().length()>500||body.inputTokens()<0||body.outputTokens()<0||body.inputTokens()>1_000_000_000L||body.outputTokens()>1_000_000_000L)throw new IllegalArgumentException();
            var rows=service.db().query("SELECT status,updated_at,outcome FROM ai_usage WHERE id=? AND user_id=?",(rs,n)->new ReconcileState(rs.getString("status"),rs.getObject("updated_at",java.time.OffsetDateTime.class).toInstant(),rs.getString("outcome")),call,AdminControlService.id(id));
            if(rows.size()!=1)throw new UserFailure(404,40440,"用量记录不存在");var row=rows.getFirst();
            if(row.status().equals("reserved")&&row.updated().isAfter(java.time.Instant.now().minusSeconds(1800)))throw new UserFailure(409,40940,"请求可能仍在执行，请稍后核对");
            meter.settleRecord(call,id,"",body.inputTokens(),body.outputTokens(),row.outcome().equals("ok")?"ok":"failed",true);
            service.log(AdminControlService.id(who(r).user().id()),who(r).user().username(),"user.reconcile","ok",id,"","",call+" input="+body.inputTokens()+" output="+body.outputTokens()+"；"+body.reason(),TraceFilter.traceId(r));return null;});
        return ApiResponse.success(null,TraceFilter.traceId(r));
    }
    @GetMapping("/auth/features") public ApiResponse<?> features(HttpServletRequest r){return ApiResponse.success(service.features(Long.parseLong(who(r).user().id())),TraceFilter.traceId(r));}
    @GetMapping("/admin/events") public ApiResponse<?> events(@RequestParam(defaultValue="")String userId,@RequestParam(defaultValue="")String kind,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest r){return ApiResponse.success(service.events(who(r),userId,kind,page,size),TraceFilter.traceId(r));}
    @GetMapping("/admin/statistics") public ApiResponse<?> stats(@RequestParam(defaultValue="")String userId,@RequestParam String from,@RequestParam String to,HttpServletRequest r){return ApiResponse.success(service.statistics(who(r),userId,from,to),TraceFilter.traceId(r));}
    @GetMapping("/admin/users/{id}/controls") public ApiResponse<?> controls(@PathVariable String id,HttpServletRequest r){return ApiResponse.success(service.controls(who(r),id),TraceFilter.traceId(r));}
    @PatchMapping("/admin/users/{id}/features/{feature}") public ApiResponse<?> feature(@PathVariable String id,@PathVariable String feature,@RequestBody Feature body,HttpServletRequest r){service.feature(who(r),id,feature,body.enabled(),TraceFilter.traceId(r));return ApiResponse.success(null,TraceFilter.traceId(r));}
    @PostMapping("/admin/users/{id}/credits") public ApiResponse<?> credit(@PathVariable String id,@RequestBody Credit body,HttpServletRequest r){service.credit(who(r),id,body.amount(),body.requestId(),body.reason(),TraceFilter.traceId(r));return ApiResponse.success(null,TraceFilter.traceId(r));}
    @PostMapping("/admin/users/{id}/force-logout") public ApiResponse<?> kick(@PathVariable String id,HttpServletRequest r){users.forceLogout(who(r),id,TraceFilter.traceId(r));return ApiResponse.success(null,TraceFilter.traceId(r));}
}
