package com.quest.api.materials;

import com.quest.api.auth.UserFailure;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/materials")
public class MaterialInternalController {
    private final MaterialTaskExecutor tasks;
    public MaterialInternalController(MaterialTaskExecutor tasks){this.tasks=tasks;}
    @PostMapping("/outbox/claim") public ApiResponse<?> claim(HttpServletRequest r){verified(r);return ApiResponse.success(tasks.claimOutbox(),TraceFilter.traceId(r));}
    @PostMapping("/outbox/ack") public ApiResponse<?> ack(@RequestBody MaterialTaskExecutor.Ack body,HttpServletRequest r){verified(r);tasks.ack(body);return ApiResponse.success(null,TraceFilter.traceId(r));}
    @PostMapping("/execute") public ApiResponse<?> execute(@RequestBody MaterialTaskExecutor.Event body,HttpServletRequest r){verified(r);return ApiResponse.success(tasks.execute(body,TraceFilter.traceId(r)),TraceFilter.traceId(r));}
    private void verified(HttpServletRequest r){if(!Boolean.TRUE.equals(r.getAttribute(MaterialInternalFilter.VERIFIED)))throw UserFailure.denied();}
}
