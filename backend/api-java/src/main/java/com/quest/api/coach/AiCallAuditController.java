package com.quest.api.coach;

import com.quest.api.auth.UserController;
import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.common.api.ApiResponse;
import com.quest.api.common.TraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Admin 只读查询 AI 调用审计；不提供写入/修改入口。 */
@RestController
@RequestMapping("/api/v1/admin/ai-calls")
public class AiCallAuditController {
    private final AiCallAuditQuery query;
    public AiCallAuditController(AiCallAuditQuery query){this.query=query;}
    @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
                                           @RequestParam(defaultValue="") String action,@RequestParam(defaultValue="") String outcome,HttpServletRequest r){
        admin(r);
        return ApiResponse.success(query.list(action,outcome,page,size),TraceFilter.traceId(r));
    }
    @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable String id,HttpServletRequest r){
        admin(r);
        return ApiResponse.success(query.detail(id),TraceFilter.traceId(r));
    }
    private void admin(HttpServletRequest r){
        if(!(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity identity)||!identity.user().role().equals("admin"))throw UserFailure.denied();
    }
}
