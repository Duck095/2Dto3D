package com.exe.morphia3d.controller;
import com.exe.morphia3d.dto.request.*;
import com.exe.morphia3d.dto.response.*;
import com.exe.morphia3d.enums.TaskStatus;
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

@RestController @RequestMapping("/api/v1/me/tasks") @RequiredArgsConstructor @Validated
public class MyTaskController {
 private final TaskManagementService service;
 @GetMapping public TaskPageResponse<EmployeeTaskResponse> list(@RequestParam(required=false) TaskStatus status,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@AuthenticationPrincipal UserPrincipal principal){return service.employeeList(status,page,size,principal);}
 @GetMapping("/{id}") public EmployeeTaskResponse detail(@PathVariable @Positive long id,@AuthenticationPrincipal UserPrincipal principal){return service.employeeDetail(id,principal);}
 @PostMapping("/{id}/start") public EmployeeTaskResponse start(@PathVariable @Positive long id,@Valid @RequestBody StartTaskRequest request,@AuthenticationPrincipal UserPrincipal principal){return service.start(id,request,principal);}
 @PostMapping("/{id}/submissions") public ResponseEntity<TaskSubmissionResponse> submit(@PathVariable @Positive long id,@Valid @RequestBody SubmitTaskRequest request,@AuthenticationPrincipal UserPrincipal principal){return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(id,request,principal));}
}
