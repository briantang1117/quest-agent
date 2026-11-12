package com.quest.api.coach;

import com.quest.api.auth.UserFailure;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public final class CoachWorkflowInternalController {
    private final StudentCoachService coach;
    public CoachWorkflowInternalController(StudentCoachService coach){this.coach=coach;}
    @PostMapping("/internal/coach/workflow-step")
    public ApiResponse<?> step(@RequestBody StudentCoachService.WorkflowStep input,HttpServletRequest request){
        if(!Boolean.TRUE.equals(request.getAttribute(CoachWorkflowInternalFilter.VERIFIED)))throw UserFailure.denied();
        return ApiResponse.success(coach.workflowStep(input),TraceFilter.traceId(request));
    }
}
