package com.exe.morphia3d.controller;
import com.exe.morphia3d.dto.request.*;
import com.exe.morphia3d.dto.response.*;
import com.exe.morphia3d.enums.*;
import com.exe.morphia3d.security.UserPrincipal;
import com.exe.morphia3d.service.TaskManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController @RequestMapping("/api/admin/tasks") @RequiredArgsConstructor @Validated
public class AdminTaskController {
 private final TaskManagementService service;
 @GetMapping public TaskPageResponse<TaskSummaryResponse> list(@RequestParam(required=false) TaskStatus status,@RequestParam(required=false) TaskPriority priority,@RequestParam(required=false) @Positive Long assigneeStaffProfileId,@RequestParam(required=false) Boolean overdue,@RequestParam(required=false) String q,@RequestParam(required=false) Instant dueBefore,@RequestParam(required=false) Instant dueAfter,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return service.adminList(status,priority,assigneeStaffProfileId,overdue,q,dueBefore,dueAfter,page,size);}
 @PostMapping public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request,@AuthenticationPrincipal UserPrincipal principal){return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request,principal));}
 @GetMapping("/summary") public TaskSummaryCountsResponse summary(){return service.summaryCounts();}
 @GetMapping("/acceptance") public TaskPageResponse<TaskSummaryResponse> acceptance(@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return service.acceptance(page,size);}
 @GetMapping("/{id}") public TaskResponse detail(@PathVariable @Positive long id){return service.adminDetail(id);}
 @PatchMapping("/{id}") public TaskResponse update(@PathVariable @Positive long id,@Valid @RequestBody UpdateTaskRequest request,@AuthenticationPrincipal UserPrincipal principal){return service.update(id,request,principal);}
 @PostMapping("/{id}/review") public TaskResponse review(@PathVariable @Positive long id,@Valid @RequestBody ReviewTaskRequest request,@AuthenticationPrincipal UserPrincipal principal){return service.review(id,request,principal);}
}
