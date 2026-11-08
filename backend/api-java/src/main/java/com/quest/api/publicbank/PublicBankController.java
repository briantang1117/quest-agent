package com.quest.api.publicbank;

import com.quest.api.auth.UserController;
import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/admin")
public class PublicBankController {
    private final PublicBankService service;
    private final PublicAiClient ai;
    private final PublicBatchExecutor batches;
    public PublicBankController(PublicBankService service,PublicAiClient ai,PublicBatchExecutor batches){this.service=service;this.ai=ai;this.batches=batches;}
    public record Create(String name,String examType,String subject) {}
    public record ImportText(String text) { @Override public String toString(){return "ImportText[redacted]";} }
    public record Version(int version) {}
    public record BatchPublish(java.util.List<PublicBankService.PublishItem> items) {}
    public record Edit(int version,JsonNode draft) { @Override public String toString(){return "Edit[redacted]";} }
    @GetMapping("/public-banks") public ApiResponse<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String examType,HttpServletRequest r){return ok(service.list(who(r),page,size,keyword,examType),r);}
    @GetMapping("/public-banks/{id}") public ApiResponse<?> detail(@PathVariable String id,HttpServletRequest r){return ok(service.detail(who(r),id),r);}
    @PostMapping("/public-banks") public ApiResponse<?> create(@RequestBody Create body,HttpServletRequest r){return ok(service.create(who(r),body.name(),body.examType(),body.subject()),r);}
    @PostMapping("/public-banks/{id}/imports") public ApiResponse<?> submit(@PathVariable String id,@RequestBody ImportText body,HttpServletRequest r){var identity=who(r);return ok(batches.startText(()->service.submit(identity,id,body.text())),r);}
    @GetMapping("/public-banks/{id}/imports") public ApiResponse<?> tasks(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="all") String source,HttpServletRequest r){return ok(service.tasks(who(r),id,page,size,source),r);}
    @PostMapping(value="/public-banks/{id}/pdf-imports",consumes="multipart/form-data") public ApiResponse<?> pdf(@PathVariable String id,@RequestParam("file") org.springframework.web.multipart.MultipartFile file,HttpServletRequest r) throws java.io.IOException {if(file.isEmpty()||file.getSize()>20*1024*1024)throw new IllegalArgumentException();try(var stream=file.getInputStream()){byte[] bytes=stream.readNBytes(20*1024*1024+1);return ok(service.importPdf(who(r),id,file.getOriginalFilename(),bytes,ai),r);}}
    @GetMapping("/public-banks/{id}/documents") public ApiResponse<?> documents(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest r){return ok(service.documents(who(r),id,page,size),r);}
    @GetMapping("/public-banks/{id}/questions") public ApiResponse<?> questions(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String status,HttpServletRequest r){return ok(service.questions(who(r),id,page,size,keyword,status),r);}
    @GetMapping("/public-banks/{id}/question-summary") public ApiResponse<?> questionSummary(@PathVariable String id,HttpServletRequest r){return ok(service.questionSummary(who(r),id),r);}
    @PostMapping("/public-questions/{id}/withdraw") public ApiResponse<?> withdraw(@PathVariable String id,@RequestBody Version body,HttpServletRequest r){return ok(service.withdraw(who(r),id,body.version()),r);}
    @DeleteMapping("/public-questions/{id}") public ApiResponse<?> delete(@PathVariable String id,@RequestBody Version body,HttpServletRequest r){service.deleteQuestion(who(r),id,body.version());return ok(null,r);}
    @PostMapping("/public-banks/{id}/publish") public ApiResponse<?> publishBatch(@PathVariable String id,@RequestBody BatchPublish body,HttpServletRequest r){return ok(service.publishBatch(who(r),id,body.items()),r);}
    @PostMapping("/public-documents/{id}/start") public ApiResponse<?> start(@PathVariable String id,HttpServletRequest r){var identity=who(r);return ok(batches.start(PublicBankService.uuid(id),()->service.startDocument(identity,id)),r);}
    @GetMapping("/public-documents/{id}/file") public org.springframework.http.ResponseEntity<byte[]> file(@PathVariable String id,HttpServletRequest r){return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=source.pdf").header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").contentType(org.springframework.http.MediaType.APPLICATION_PDF).body(service.sourceFile(who(r),id).bytes());}
    @GetMapping("/public-imports/{id}") public ApiResponse<?> task(@PathVariable String id,HttpServletRequest r){return ok(service.task(who(r),id),r);}
    @GetMapping("/public-questions/{id}") public ApiResponse<?> review(@PathVariable String id,HttpServletRequest r){return ok(service.review(who(r),id),r);}
    @PutMapping("/public-questions/{id}") public ApiResponse<?> edit(@PathVariable String id,@RequestBody Edit body,HttpServletRequest r){return ok(service.edit(who(r),id,body.version(),body.draft()),r);}
    @PostMapping("/public-questions/{id}/confirm") public ApiResponse<?> confirm(@PathVariable String id,@RequestBody Version body,HttpServletRequest r){return ok(service.confirm(who(r),id,body.version()),r);}
    @PostMapping("/public-questions/{id}/publish") public ApiResponse<?> publish(@PathVariable String id,@RequestBody Version body,HttpServletRequest r){return ok(service.publish(who(r),id,body.version()),r);}
    private SessionIdentity who(HttpServletRequest request){if(request.getAttribute(UserController.IDENTITY) instanceof SessionIdentity identity)return identity;throw UserFailure.denied();}
    private <T> ApiResponse<T> ok(T body,HttpServletRequest r){return ApiResponse.success(body,TraceFilter.traceId(r));}
}
