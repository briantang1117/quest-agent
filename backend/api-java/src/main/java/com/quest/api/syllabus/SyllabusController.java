package com.quest.api.syllabus;

import com.quest.api.auth.UserController;
import com.quest.api.auth.UserFailure;
import com.quest.api.auth.UserModels.SessionIdentity;
import com.quest.api.common.TraceFilter;
import com.quest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
public class SyllabusController {
    private final SyllabusService service;
    public SyllabusController(SyllabusService service){this.service=service;}
    public record Revision(int revision) {}
    @GetMapping("/admin/syllabi") public ApiResponse<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String examType,@RequestParam(required=false) Integer examYear,@RequestParam(defaultValue="") String status,HttpServletRequest r){return ok(service.list(who(r),page,size,keyword,examType,examYear,status),r);}
    @PostMapping(value="/admin/syllabi",consumes="multipart/form-data") public ApiResponse<?> upload(@RequestParam MultipartFile file,@RequestParam String title,@RequestParam String examType,@RequestParam int examYear,@RequestParam String versionLabel,@RequestParam String issuer,@RequestParam(required=false) LocalDate publishedDate,@RequestParam(defaultValue="") String sourceUrl,HttpServletRequest r)throws java.io.IOException{
        if(file.isEmpty()||file.getSize()>20*1024*1024)throw new IllegalArgumentException();
        try(var input=file.getInputStream()){return ok(service.upload(who(r),new SyllabusService.Metadata(title,examType,examYear,versionLabel,issuer,publishedDate,sourceUrl),file.getOriginalFilename(),input.readNBytes(20*1024*1024+1)),r);}
    }
    @GetMapping("/admin/syllabi/{id}") public ApiResponse<?> detail(@PathVariable String id,HttpServletRequest r){return ok(service.detail(who(r),id),r);}
    @PutMapping("/admin/syllabi/{id}") public ApiResponse<?> edit(@PathVariable String id,@RequestBody SyllabusService.Edit body,HttpServletRequest r){return ok(service.edit(who(r),id,body),r);}
    @PostMapping("/admin/syllabi/{id}/publish") public ApiResponse<?> publish(@PathVariable String id,@RequestBody Revision body,HttpServletRequest r){return ok(service.publish(who(r),id,body.revision()),r);}
    @PostMapping("/admin/syllabi/{id}/archive") public ApiResponse<?> archive(@PathVariable String id,@RequestBody Revision body,HttpServletRequest r){return ok(service.archive(who(r),id,body.revision()),r);}
    @PostMapping("/admin/syllabi/{id}/retry-review") public ApiResponse<?> retryReview(@PathVariable String id,@RequestBody Revision body,HttpServletRequest r){return ok(service.retry(who(r),id,body.revision(),"review"),r);}
    @PostMapping("/admin/syllabi/{id}/rebuild-index") public ApiResponse<?> rebuildIndex(@PathVariable String id,@RequestBody Revision body,HttpServletRequest r){return ok(service.retry(who(r),id,body.revision(),"index"),r);}
    @GetMapping("/admin/syllabi/{id}/file") public ResponseEntity<byte[]> file(@PathVariable String id,HttpServletRequest r){return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Content-Disposition","attachment; filename=syllabus.pdf").header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").body(service.file(who(r),id));}
    @GetMapping("/study/syllabus") public ApiResponse<?> study(@RequestParam String subject,@RequestParam int examYear,@RequestParam(defaultValue="") String keyword,HttpServletRequest r){return ok(service.study(who(r),subject,examYear,keyword),r);}
    private SessionIdentity who(HttpServletRequest r){if(r.getAttribute(UserController.IDENTITY) instanceof SessionIdentity identity)return identity;throw UserFailure.denied();}
    private <T>ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.success(data,TraceFilter.traceId(r));}
}
