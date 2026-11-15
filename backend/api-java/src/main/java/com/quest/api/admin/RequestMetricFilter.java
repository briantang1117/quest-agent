package com.quest.api.admin;
import com.quest.api.auth.UserController;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class RequestMetricFilter extends OncePerRequestFilter {
    private final AdminControlService admin;
    public RequestMetricFilter(AdminControlService admin){this.admin=admin;}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        long start=System.nanoTime();
        try{chain.doFilter(r,response);}finally{
            if(r.getRequestURI().contains("/api/v1/")){
                Object pattern=r.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route=pattern==null?"unmatched":pattern.toString();
                var who=r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity s?s:null;
                String target=null;
                if(r.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof java.util.Map<?,?> variables&&variables.get("id") instanceof String id)target=id;
                try{
                    admin.metric(who==null?null:Long.valueOf(who.user().id()),r.getMethod(),route,response.getStatus(),(System.nanoTime()-start)/1_000_000);
                    if(who!=null&&!r.getMethod().equals("GET")&&!route.equals("unmatched")&&!route.contains("/auth/login")&&!route.contains("/auth/refresh"))admin.log(Long.valueOf(who.user().id()),who.user().username(),r.getMethod()+" "+route,response.getStatus()<400?"ok":"failed",target,r.getRemoteAddr(),r.getHeader("User-Agent"),"",TraceFilter.traceId(r));
                }catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("请求元数据记录失败：type={}",failure.getClass().getSimpleName());}
            }
        }
    }
}
