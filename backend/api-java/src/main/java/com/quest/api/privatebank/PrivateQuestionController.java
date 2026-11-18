package com.quest.api.privatebank;
import com.quest.api.auth.*;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1")
public class PrivateQuestionController {
    private final PrivateQuestionService service;
    public PrivateQuestionController(PrivateQuestionService service){this.service=service;}
    private SessionIdentity who(HttpServletRequest r){if(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity who)return who;throw UserFailure.denied();}
    private ApiResponse<?> ok(Object value,HttpServletRequest r){return ApiResponse.success(value,TraceFilter.traceId(r));}
    @PostMapping(value="/coach/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<?> upload(@RequestParam UUID requestId,@RequestPart MultipartFile file,HttpServletRequest r)throws java.io.IOException{return ok(service.upload(who(r),requestId,file.getOriginalFilename(),file.getContentType(),file.getBytes()),r);}
    @GetMapping("/coach/attachments/{id}/file") public ResponseEntity<byte[]> file(@PathVariable UUID id,HttpServletRequest r){var value=service.file(who(r),id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.get("mime").toString())).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(value.get("filename").toString(),StandardCharsets.UTF_8).build().toString()).body((byte[])value.get("file_bytes"));}
    @GetMapping("/study/private-imports/{id}") public ApiResponse<?> job(@PathVariable UUID id,HttpServletRequest r){return ok(service.job(who(r),id),r);}
    public record Action(String action){}
    public record Decision(UUID sessionId,String action){}
    @PostMapping("/study/private-imports/{id}/actions") public ApiResponse<?> control(@PathVariable UUID id,@RequestBody Action body,HttpServletRequest r){return ok(service.controlJob(who(r),id,body.action()),r);}
    @PostMapping("/study/private-items/{id}/decision") public ApiResponse<?> decision(@PathVariable UUID id,@RequestBody Decision body,HttpServletRequest r){return ok(service.decide(who(r),body.sessionId(),id,body.action()),r);}
    @GetMapping("/study/private-questions") public ApiResponse<?> questions(@RequestParam(defaultValue="")String subject,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="1")int page,HttpServletRequest r){return ok(service.questions(who(r),subject,status,q,page),r);}
}
