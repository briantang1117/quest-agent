package com.quest.api.admin;
import com.quest.api.common.TraceFilter;
import com.quest.api.auth.UserFailure;
import com.quest.api.materials.MaterialInternalFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController
public class ModelMeterController {
    private final ModelMeterService meter;
    public ModelMeterController(ModelMeterService meter){this.meter=meter;}
    private void verified(HttpServletRequest r){if(!Boolean.TRUE.equals(r.getAttribute(MaterialInternalFilter.VERIFIED)))throw UserFailure.denied();}
    @PostMapping("/internal/meter/reserve") public ApiResponse<?> reserve(@RequestBody ModelMeterService.Reserve input,HttpServletRequest r){verified(r);return ApiResponse.success(meter.reserve(input),TraceFilter.traceId(r));}
    @PostMapping("/internal/meter/settle") public ApiResponse<?> settle(@RequestBody ModelMeterService.Settle input,HttpServletRequest r){verified(r);meter.settle(input);return ApiResponse.success(null,TraceFilter.traceId(r));}
}
