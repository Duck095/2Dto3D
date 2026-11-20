package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.*;
import com.exe.morphia3d.dto.response.*;
import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.enums.*;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.security.UserPrincipal;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class TaskManagementService {
 private static final List<TaskHistoryAction> REVIEW_ACTIONS=List.of(TaskHistoryAction.REVISION_REQUESTED,TaskHistoryAction.REVIEW_APPROVED);
 private final TaskRepository tasks;
 private final TaskSubmissionRepository submissions;
 private final TaskHistoryRepository history;
 private final StaffProfileRepository staffProfiles;
 private final UserRepository users;

 @Transactional(readOnly=true)
 public TaskPageResponse<TaskSummaryResponse> adminList(TaskStatus status,TaskPriority priority,Long assigneeId,Boolean overdue,String q,Instant before,Instant after,int page,int size){
 Page<Task> result=tasks.findAdminPage(status,priority,assigneeId,blankToNull(q),before,after,overdue,Instant.now(),pageable(page,size,Sort.by(Sort.Order.desc("updatedAt"),Sort.Order.desc("id"))));
 return page(result,this::summary);
 }
 @Transactional
 public TaskResponse create(CreateTaskRequest request,UserPrincipal principal){
 User actor=requireAdmin(principal);
 StaffProfile assignee=eligibleAssignee(request.assigneeStaffProfileId());
 Task task=new Task(); task.setTitle(trim(request.title()));task.setDescription(trim(request.description()));task.setPriority(request.priority());task.setAssignee(assignee);task.setCreatedBy(actor);task.setStatus(TaskStatus.ASSIGNED);task.setAssignmentCycle(1);task.setDueAt(request.dueAt());
 task=tasks.saveAndFlush(task);
 append(task,actor,TaskHistoryAction.TASK_CREATED,null,TaskStatus.ASSIGNED,null,assignee,null,null,emptyDetails());
 return adminDetail(task.getId());
 }
 @Transactional(readOnly=true)
 public TaskResponse adminDetail(long id){ Task task=tasks.findById(id).orElseThrow(()->notFound("Task not found"));return adminResponse(task); }
 @Transactional
 public TaskResponse update(long id,UpdateTaskRequest request,UserPrincipal principal){
 User actor=requireAdmin(principal); Task task=lockedTask(id);checkVersion(task,request.expectedVersion());
 if(!request.hasChanges()) throw bad("At least one task field must be changed");
 if(!editable(task.getStatus())) throw conflict("Task cannot be edited in its current status");
 TaskStatus oldStatus=task.getStatus(); StaffProfile oldAssignee=task.getAssignee(); long oldCycle=task.getAssignmentCycle();
 ObjectNode delta=emptyDetails();
 if(request.title()!=null){String value=trim(request.title()); if(value.isBlank())throw bad("Title is required");if(!value.equals(task.getTitle())){delta.putObject("title").put("old",task.getTitle()).put("new",value);task.setTitle(value);}}
 if(request.description()!=null){String value=trim(request.description());if(value.isBlank())throw bad("Description is required");if(!value.equals(task.getDescription())){delta.putObject("description").put("old",task.getDescription()).put("new",value);task.setDescription(value);}}
 if(request.priority()!=null&&request.priority()!=task.getPriority()){delta.putObject("priority").put("old",task.getPriority().name()).put("new",request.priority().name());task.setPriority(request.priority());}
 if(request.dueAt()!=null&&!request.dueAt().equals(task.getDueAt())){delta.putObject("dueAt").put("old",task.getDueAt().toString()).put("new",request.dueAt().toString());task.setDueAt(request.dueAt());}
 boolean reassigned=request.assigneeStaffProfileId()!=null&&!request.assigneeStaffProfileId().equals(oldAssignee.getId());
 if(reassigned){
   StaffProfile next=eligibleAssignee(request.assigneeStaffProfileId());
   delta.putObject("assigneeStaffProfileId").put("old",oldAssignee.getId()).put("new",next.getId());
   delta.putObject("assignmentCycle").put("old",oldCycle).put("new",oldCycle+1);
   delta.putObject("status").put("old",oldStatus.name()).put("new",TaskStatus.ASSIGNED.name());
   task.setAssignee(next);task.setAssignmentCycle(oldCycle+1);task.setStatus(TaskStatus.ASSIGNED);
 }
 if(delta.isEmpty())throw bad("Task update contains no changes");
 tasks.saveAndFlush(task);
 append(task,actor,reassigned?TaskHistoryAction.TASK_REASSIGNED:TaskHistoryAction.TASK_UPDATED,oldStatus,task.getStatus(),reassigned?oldAssignee:null,task.getAssignee(),null,null,delta);
 tasks.flush();
 return adminDetail(id);
 }
 @Transactional(readOnly=true)
 public TaskPageResponse<TaskSummaryResponse> acceptance(int page,int size){
 Page<Task> result=tasks.findAcceptancePage(PageRequest.of(page,size));return page(result,this::summary);
 }
 @Transactional
 public TaskResponse review(long id,ReviewTaskRequest request,UserPrincipal principal){
 User actor=requireAdmin(principal);Task task=lockedTask(id);checkVersion(task,request.expectedVersion());
 if(task.getStatus()!=TaskStatus.SUBMITTED)throw conflict("Only submitted tasks can be reviewed");
 String decision=request.decision().trim().toUpperCase(Locale.ROOT);TaskHistoryAction action;TaskStatus next;String feedback=trimNullable(request.feedback());
 if("APPROVE".equals(decision)){action=TaskHistoryAction.REVIEW_APPROVED;next=TaskStatus.APPROVED;}
 else if("REQUEST_CHANGES".equals(decision)){if(feedback==null||feedback.isBlank())throw bad("Feedback is required when requesting changes");action=TaskHistoryAction.REVISION_REQUESTED;next=TaskStatus.REVISION_REQUESTED;}
 else throw bad("Decision must be APPROVE or REQUEST_CHANGES");
 if(feedback!=null&&feedback.length()>2000)throw bad("Feedback exceeds 2000 characters");
 TaskStatus previous=task.getStatus();task.setStatus(next);tasks.saveAndFlush(task);
 TaskSubmission latest=submissions.findFirstByTask_IdAndAssignmentCycleOrderBySubmittedAtDescIdDesc(id,task.getAssignmentCycle()).orElseThrow(()->conflict("Current assignment has no submission"));
 append(task,actor,action,previous,next,null,null,latest,feedback,emptyDetails());tasks.flush();return adminDetail(id);
 }
 @Transactional(readOnly=true)
 public TaskSummaryCountsResponse summaryCounts(){
 long assigned=tasks.countByStatus(TaskStatus.ASSIGNED),progress=tasks.countByStatus(TaskStatus.IN_PROGRESS),submitted=tasks.countByStatus(TaskStatus.SUBMITTED),revision=tasks.countByStatus(TaskStatus.REVISION_REQUESTED),approved=tasks.countByStatus(TaskStatus.APPROVED);
 return new TaskSummaryCountsResponse(assigned+progress+submitted+revision+approved,assigned,progress,submitted,revision,approved,tasks.countByDueAtBeforeAndStatusNot(Instant.now(),TaskStatus.APPROVED),submitted);
 }
 @Transactional(readOnly=true)
 public TaskPageResponse<EmployeeTaskResponse> employeeList(TaskStatus status,int page,int size,UserPrincipal principal){
 StaffProfile employee=requireEmployee(principal);Pageable pageable=PageRequest.of(page,size,Sort.by(Sort.Order.desc("updatedAt"),Sort.Order.desc("id")));
 Page<Task> result=status==null?tasks.findByAssignee_Id(employee.getId(),pageable):tasks.findByAssignee_IdAndStatus(employee.getId(),status,pageable);
 return page(result,t->employeeResponse(t,employee));
 }
 @Transactional(readOnly=true)
 public EmployeeTaskResponse employeeDetail(long id,UserPrincipal principal){StaffProfile employee=requireEmployee(principal);Task t=tasks.findByIdAndAssignee_Id(id,employee.getId()).orElseThrow(()->notFound("Task not found"));return employeeResponse(t,employee);}
 @Transactional
 public EmployeeTaskResponse start(long id,StartTaskRequest request,UserPrincipal principal){
 StaffProfile employee=requireEmployee(principal);Task t=employeeOwnedTaskLocked(id,employee);checkVersion(t,request.expectedVersion());
 if(t.getStatus()!=TaskStatus.ASSIGNED)throw conflict("Only assigned tasks can be started");TaskStatus before=t.getStatus();t.setStatus(TaskStatus.IN_PROGRESS);tasks.saveAndFlush(t);
 append(t,employee.getUser(),TaskHistoryAction.TASK_STARTED,before,t.getStatus(),null,null,null,null,emptyDetails());tasks.flush();return employeeResponse(t,employee);
 }
 @Transactional
 public TaskSubmissionResponse submit(long id,SubmitTaskRequest request,UserPrincipal principal){
 StaffProfile employee=requireEmployee(principal);Task t=employeeOwnedTaskLocked(id,employee);checkVersion(t,request.expectedVersion());
 if(t.getStatus()!=TaskStatus.ASSIGNED&&t.getStatus()!=TaskStatus.IN_PROGRESS&&t.getStatus()!=TaskStatus.REVISION_REQUESTED)throw conflict("Task cannot be submitted in its current status");
 String description=trimNullable(request.resultDescription()),url=trimNullable(request.resultUrl());if((description==null||description.isBlank())&&(url==null||url.isBlank()))throw bad("Result description or URL is required");if(url!=null)validateHttps(url);
 TaskSubmission submission=new TaskSubmission();submission.setTask(t);submission.setSubmissionNumber(submissions.countByTask_Id(id)+1);submission.setSubmittedBy(employee.getUser());submission.setSubmittedByStaffProfile(employee);submission.setAssignmentCycle(t.getAssignmentCycle());submission.setResultDescription(description);submission.setResultUrl(url);submission=submissions.saveAndFlush(submission);
 TaskStatus before=t.getStatus();t.setStatus(TaskStatus.SUBMITTED);tasks.saveAndFlush(t);append(t,employee.getUser(),TaskHistoryAction.TASK_SUBMITTED,before,t.getStatus(),null,null,submission,null,emptyDetails());tasks.flush();return submissionResponse(submission);
 }

 private User requireAdmin(UserPrincipal principal){if(principal==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");User u=users.findByIdForUpdate(principal.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin access is not active"));if(u.getStatus()!=UserStatus.ACTIVE||u.getRole()!=Role.ADMIN)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin access is not active");return u;}
 private StaffProfile requireEmployee(UserPrincipal p){if(p==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");User u=users.findById(p.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee access is not active"));if(u.getStatus()!=UserStatus.ACTIVE||(u.getRole()!=Role.USER&&u.getRole()!=Role.DESIGNER))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee access is not active");StaffProfile s=staffProfiles.findByUser_Id(u.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee access is not active"));if(!Objects.equals(s.getId(),p.getStaffProfileId())||s.getStatus()!=StaffStatus.ACTIVE)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee access is not active");return s;}
 private StaffProfile eligibleAssignee(Long id){StaffProfile profile=staffProfiles.findByIdForUpdate(id).orElseThrow(()->conflict("Assignee is not eligible"));User user=users.findByIdForUpdate(profile.getUser().getId()).orElseThrow(()->conflict("Assignee is not eligible"));if(profile.getStatus()!=StaffStatus.ACTIVE||user.getStatus()!=UserStatus.ACTIVE||(user.getRole()!=Role.USER&&user.getRole()!=Role.DESIGNER))throw conflict("Assignee is not eligible");return profile;}
 private Task lockedTask(long id){return tasks.findByIdForUpdate(id).orElseThrow(()->notFound("Task not found"));}
 private Task employeeOwnedTaskLocked(long id,StaffProfile employee){return tasks.findOwnedByIdAndAssigneeIdForUpdate(id,employee.getId()).orElseThrow(()->notFound("Task not found"));}
 private static void checkVersion(Task t,long expected){if(t.getVersion()!=expected)throw conflict("Task was changed by another request; reload and retry");}
 private static boolean editable(TaskStatus s){return s==TaskStatus.ASSIGNED||s==TaskStatus.IN_PROGRESS||s==TaskStatus.REVISION_REQUESTED;}
 private void append(Task t,User actor,TaskHistoryAction action,TaskStatus before,TaskStatus after,StaffProfile oldAssignee,StaffProfile newAssignee,TaskSubmission submission,String note,ObjectNode details){TaskHistory h=new TaskHistory();h.setTask(t);h.setActor(actor);h.setAssignmentCycle(t.getAssignmentCycle());h.setAction(action);h.setPreviousStatus(before);h.setNewStatus(after);h.setPreviousAssignee(oldAssignee);h.setNewAssignee(newAssignee);h.setSubmission(submission);h.setNote(note);h.setDetails(details);h.setOccurredAt(Instant.now());history.save(h);}
 private TaskResponse adminResponse(Task t){List<TaskSubmission> all=submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(t.getId());List<TaskHistory> events=history.findByTask_IdOrderByOccurredAtAscIdAsc(t.getId());TaskSubmission latest=all.stream().filter(s->s.getAssignmentCycle()==t.getAssignmentCycle()).max(Comparator.comparing(TaskSubmission::getSubmittedAt).thenComparing(TaskSubmission::getId)).orElse(null);TaskHistory feedback=events.stream().filter(h->h.getAssignmentCycle()==t.getAssignmentCycle()&&h.getAction()==TaskHistoryAction.REVISION_REQUESTED).max(Comparator.comparing(TaskHistory::getOccurredAt).thenComparing(TaskHistory::getId)).orElse(null);TaskSummaryResponse s=summary(t);return new TaskResponse(s.id(),s.title(),s.priority(),s.assignee(),s.status(),s.assignmentCycle(),s.dueAt(),s.overdue(),s.updatedAt(),s.version(),t.getDescription(),t.getCreatedBy().getId(),t.getCreatedBy().getName(),latest==null?null:submissionResponse(latest),feedback==null?null:feedback.getNote(),feedback==null?null:feedback.getActor().getId(),feedback==null?null:feedback.getOccurredAt(),t.getCreatedAt(),all.stream().map(this::submissionResponse).toList(),events.stream().map(this::historyResponse).toList());}
 private EmployeeTaskResponse employeeResponse(Task t,StaffProfile employee){TaskSubmission s=submissions.findFirstByTask_IdAndAssignmentCycleAndSubmittedByStaffProfile_IdOrderBySubmittedAtDescIdDesc(t.getId(),t.getAssignmentCycle(),employee.getId()).orElse(null);TaskHistory h=history.findFirstByTask_IdAndAssignmentCycleAndActionInOrderByOccurredAtDescIdDesc(t.getId(),t.getAssignmentCycle(),List.of(TaskHistoryAction.REVISION_REQUESTED)).orElse(null);return new EmployeeTaskResponse(t.getId(),t.getTitle(),t.getDescription(),t.getPriority(),t.getStatus(),t.getAssignmentCycle(),t.getDueAt(),isOverdue(t),t.getUpdatedAt(),t.getVersion(),s==null?null:submissionResponse(s),h==null?null:h.getNote(),h==null?null:h.getOccurredAt());}
 private TaskSummaryResponse summary(Task t){return new TaskSummaryResponse(t.getId(),t.getTitle(),t.getPriority(),new TaskSummaryResponse.Assignee(t.getAssignee().getId(),t.getAssignee().getEmployeeNumber(),t.getAssignee().getUser().getName()),t.getStatus(),t.getAssignmentCycle(),t.getDueAt(),isOverdue(t),t.getUpdatedAt(),t.getVersion());}
 private TaskSubmissionResponse submissionResponse(TaskSubmission s){return new TaskSubmissionResponse(s.getId(),s.getSubmissionNumber(),s.getAssignmentCycle(),s.getResultDescription(),s.getResultUrl(),s.getSubmittedAt(),s.getSubmittedBy().getId(),s.getSubmittedBy().getName());}
 private TaskHistoryResponse historyResponse(TaskHistory h){StaffProfile old=h.getPreviousAssignee(),next=h.getNewAssignee();return new TaskHistoryResponse(h.getId(),h.getAction(),h.getActor().getId(),h.getActor().getName(),h.getAssignmentCycle(),h.getPreviousStatus(),h.getNewStatus(),old==null?null:old.getId(),old==null?null:old.getEmployeeNumber(),old==null?null:old.getUser().getName(),next==null?null:next.getId(),next==null?null:next.getEmployeeNumber(),next==null?null:next.getUser().getName(),h.getSubmission()==null?null:h.getSubmission().getId(),h.getNote(),h.getDetails(),h.getOccurredAt());}
 private boolean isOverdue(Task t){return t.getStatus()!=TaskStatus.APPROVED&&t.getDueAt().isBefore(Instant.now());}
 private static String trim(String s){return s==null?null:s.trim();} private static String trimNullable(String s){return s==null||s.isBlank()?null:s.trim();} private static String blankToNull(String s){return s==null||s.isBlank()?null:s.trim();}
 private static ObjectNode emptyDetails(){return JsonNodeFactory.instance.objectNode();}
 private static void validateHttps(String value){try{URI uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null)throw bad("Result URL must be an absolute HTTPS URL");}catch(IllegalArgumentException e){throw bad("Result URL must be an absolute HTTPS URL");}}
 private static Pageable pageable(int page,int size,Sort sort){if(page<0||size<1||size>100)throw bad("Page must be nonnegative and size must be between 1 and 100");return PageRequest.of(page,size,sort);}
 private static <T,R> TaskPageResponse<R> page(Page<T> p,java.util.function.Function<T,R> map){return new TaskPageResponse<>(p.getContent().stream().map(map).toList(),p.getNumber(),p.getSize(),p.getTotalElements(),p.getTotalPages());}
 private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}private static ResponseStatusException notFound(String message){return new ResponseStatusException(HttpStatus.NOT_FOUND,message);}
}
