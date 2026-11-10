package com.quest.api.coach;

import com.quest.api.auth.UserController;
import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1")
public class StudentCoachController {
    private final StudentCoachService service;
    // 不排队无限付费生成；领取容量后才认领数据库轮次。
    private final Semaphore capacity=new Semaphore(8);
    private final ExecutorService push=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"coach-turn-deadline");t.setDaemon(true);return t;});
    public StudentCoachController(StudentCoachService service){this.service=service;}
    @PreDestroy void close(){push.shutdownNow();clock.shutdownNow();}
    @GetMapping("/me/study-preference") public ApiResponse<?> preference(HttpServletRequest r){return ok(service.preference(who(r)),r);}
    @PutMapping("/me/study-preference") public ApiResponse<?> preference(@RequestBody StudentCoachService.Preference body,HttpServletRequest r){return ok(service.preference(who(r),body.subject(),body.examYear()),r);}
    @GetMapping("/coach/sessions") public ApiResponse<?> list(HttpServletRequest r){return ok(service.list(who(r)),r);}
    @PostMapping("/coach/sessions") public ApiResponse<?> create(@RequestBody StudentCoachService.Create body,HttpServletRequest r){return ok(service.create(who(r),body),r);}
    @GetMapping("/coach/sessions/{id}") public ApiResponse<?> get(@PathVariable String id,HttpServletRequest r){return ok(service.get(who(r),id),r);}
    @PostMapping("/coach/sessions/{id}/turns") public ApiResponse<?> turn(@PathVariable String id,@RequestBody StudentCoachService.Turn body,HttpServletRequest r){return ok(service.turn(who(r),id,body,TraceFilter.traceId(r)),r);}

    /** 单一幂等轮次：先保存判分，再流式回答，提交完整状态后才发送 done。 */
    @PostMapping(value="/coach/sessions/{id}/turns/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,@RequestBody StudentCoachService.Turn body,HttpServletRequest r,HttpServletResponse response){
        SessionIdentity identity=who(r);String traceId=TraceFilter.traceId(r);
        if(!capacity.tryAcquire())throw new UserFailure(503,50333,"当前生成较多，请稍后再试");
        StudentCoachService.Pending pending;
        try{pending=service.claim(identity,id,body);}catch(RuntimeException failure){capacity.release();throw failure;}
        response.setHeader("Cache-Control","no-cache");response.setHeader("X-Accel-Buffering","no");response.setCharacterEncoding("UTF-8");
        SseEmitter emitter=new SseEmitter(145_000L);CoachStreamControl control=new CoachStreamControl();
        emitter.onTimeout(control::close);emitter.onError(failure->control.close());emitter.onCompletion(control::close);
        AtomicBoolean started=new AtomicBoolean();
        try{
            push.execute(()->{
                started.set(true);
                ScheduledFuture<?> timeout=null;boolean processing=false;
                try{
                    timeout=clock.schedule(control::close,140,TimeUnit.SECONDS);
                    control.attachThread();processing=true;
                    var result=service.streamTurn(identity,pending,traceId,(event,data)->{
                        control.check();
                        try{emitter.send(SseEmitter.event().name(event).data(data,MediaType.APPLICATION_JSON));}
                        catch(IOException|IllegalStateException failure){control.close();throw new ClientGone(failure);}
                    },control);
                    // 完整结果已落库，即使最后 done 没送到，刷新/同 requestId 重放也不会二次计费。
                    control.check();emitter.send(SseEmitter.event().name("done").data(result,MediaType.APPLICATION_JSON));emitter.complete();
                }catch(Exception failure){
                    // 连接已断时无法回写，但仍必须释放数据库认领，否则会话永久卡在"处理中"。
                    if(!control.cancelled()){
                        int code=failure instanceof UserFailure uf?uf.code():50331;
                        try{emitter.send(SseEmitter.event().name("error").data(Map.of("code",code,"message","本次回答未完成，请重新打开会话查看已保存状态","traceId",traceId)));emitter.complete();}
                        catch(IOException|IllegalStateException gone){emitter.completeWithError(gone);}
                    }else emitter.complete();
                    if(!(failure instanceof ClientGone))try{service.abandon(identity,pending);}catch(RuntimeException cleanup){org.slf4j.LoggerFactory.getLogger(StudentCoachController.class).warn("Coach claim cleanup deferred: type={}",cleanup.getClass().getName());}
                }finally{
                    if(timeout!=null)timeout.cancel(false);
                    control.close();capacity.release();
                }
            });
        }catch(RejectedExecutionException failure){
            if(!started.get()){service.abandon(identity,pending);capacity.release();}
            throw new UserFailure(503,50333,"服务正在停止，请稍后再试");
        }
        return emitter;
    }
    private static final class ClientGone extends RuntimeException{ClientGone(Throwable cause){super(cause);}}
    private SessionIdentity who(HttpServletRequest r){if(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity identity)return identity;throw UserFailure.denied();}
    private <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.success(data,TraceFilter.traceId(r));}
}
