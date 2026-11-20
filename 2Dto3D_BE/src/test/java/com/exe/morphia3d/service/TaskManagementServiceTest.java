package com.exe.morphia3d.service;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.request.*;
import com.exe.morphia3d.dto.response.TaskResponse;
import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.enums.*;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=Morphia3dApplication.class)
@ActiveProfiles("test")
class TaskManagementServiceTest {
 @DynamicPropertySource
 static void isolateH2Database(DynamicPropertyRegistry registry) {
  registry.add("spring.datasource.url", () ->
   "jdbc:h2:mem:task-management-service-test;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON");
 }

 @Autowired TaskManagementService service; @Autowired UserRepository users; @Autowired StaffProfileRepository profiles;
 @Autowired TaskRepository tasks; @Autowired TaskHistoryRepository history; @Autowired TaskSubmissionRepository submissions;
 @Autowired JdbcTemplate jdbc;

 @Test void enforcesAllThreeAssignmentEligibilityConditions(){
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE); StaffProfile valid=profile("valid",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);
  StaffProfile inactiveUser=profile("inactive-user",Role.USER,UserStatus.UNACTIVE,StaffStatus.ACTIVE,admin);
  StaffProfile adminRole=profile("admin-role",Role.ADMIN,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);
  StaffProfile suspended=profile("suspended",Role.DESIGNER,UserStatus.ACTIVE,StaffStatus.SUSPENDED,admin);
  long before=tasks.count(); UserPrincipal principal=new UserPrincipal(admin);
  service.create(create("Eligible",valid),principal);
  for(StaffProfile rejected:new StaffProfile[]{inactiveUser,adminRole,suspended}) assertThatThrownBy(()->service.create(create("Rejected",rejected),principal)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
  assertThat(tasks.count()).isEqualTo(before+1);
 }

 @Test void assignmentCycleAToBToADoesNotExposeOldSubmissionOrFeedback(){
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);StaffProfile b=profile("b",Role.DESIGNER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);
  UserPrincipal adminPrincipal=new UserPrincipal(admin);TaskResponse created=service.create(create("Cycle task",a),adminPrincipal);long id=created.id();
  assertThat(service.adminList(TaskStatus.ASSIGNED,null,a.getId(),false,"Cycle",null,null,0,20).totalItems()).isEqualTo(1);
  service.submit(id,new SubmitTaskRequest(0L,"cycle one result",null),new UserPrincipal(a.getUser(),a));
  assertThat(service.acceptance(0,100).items()).extracting("id").contains(id);
  assertThat(service.summaryCounts().submitted()).isGreaterThanOrEqualTo(1);
  service.review(id,new ReviewTaskRequest(1L,"REQUEST_CHANGES","Revise cycle one"),adminPrincipal);
  service.update(id,new UpdateTaskRequest(2L,null,null,null,b.getId(),null),adminPrincipal);
  service.update(id,new UpdateTaskRequest(3L,null,null,null,a.getId(),null),adminPrincipal);
  var employee=service.employeeDetail(id,new UserPrincipal(a.getUser(),a));
  assertThat(employee.assignmentCycle()).isEqualTo(3);assertThat(employee.latestSubmission()).isNull();assertThat(employee.latestRevisionFeedback()).isNull();
  TaskResponse adminView=service.adminDetail(id);assertThat(adminView.submissions()).hasSize(1);assertThat(adminView.submissions().get(0).assignmentCycle()).isEqualTo(1);
  assertThat(adminView.history()).filteredOn(h->h.action()==TaskHistoryAction.TASK_REASSIGNED).extracting("assignmentCycle").containsExactly(2L,3L);
 }

 @Test void combinedMetadataAndReassignmentProducesOneCompleteAuditEvent(){
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);StaffProfile b=profile("b",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);
  long id=service.create(create("Before",a),new UserPrincipal(admin)).id();long prior=history.count();Instant newDueAt=Instant.now().plusSeconds(7200);
  service.update(id,new UpdateTaskRequest(0L,"After","Updated description",TaskPriority.URGENT,b.getId(),newDueAt),new UserPrincipal(admin));
  var event=history.findByTask_IdOrderByOccurredAtAscIdAsc(id).stream().filter(h->h.getAction()==TaskHistoryAction.TASK_REASSIGNED).findFirst().orElseThrow();
  assertThat(history.count()).isEqualTo(prior+1);assertThat(event.getPreviousAssignee().getId()).isEqualTo(a.getId());assertThat(event.getNewAssignee().getId()).isEqualTo(b.getId());
  assertThat(event.getDetails().path("title").path("old").asText()).isEqualTo("Before");assertThat(event.getDetails().path("title").path("new").asText()).isEqualTo("After");
  assertThat(event.getDetails().path("description").path("old").asText()).isEqualTo("Description");assertThat(event.getDetails().path("description").path("new").asText()).isEqualTo("Updated description");
  assertThat(event.getDetails().path("priority").path("old").asText()).isEqualTo("NORMAL");assertThat(event.getDetails().path("priority").path("new").asText()).isEqualTo("URGENT");
  assertThat(event.getDetails().path("dueAt").path("new").asText()).isEqualTo(newDueAt.toString());
  assertThat(event.getDetails().path("assigneeStaffProfileId").path("old").asLong()).isEqualTo(a.getId());assertThat(event.getDetails().path("assigneeStaffProfileId").path("new").asLong()).isEqualTo(b.getId());
  assertThat(event.getDetails().path("assignmentCycle").path("new").asLong()).isEqualTo(2);
  assertThat(event.getDetails().path("status").path("old").asText()).isEqualTo("ASSIGNED");assertThat(event.getDetails().path("status").path("new").asText()).isEqualTo("ASSIGNED");
  assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).filteredOn(h->h.getAction()==TaskHistoryAction.TASK_UPDATED).isEmpty();
 }

 @Test void auditFailureRollsBackMetadataAndVersionTogether(){
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);long id=service.create(create("Before",a),new UserPrincipal(admin)).id();
  StaffProfile b=profile("rollback-target",Role.DESIGNER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);Task before=tasks.findById(id).orElseThrow();Instant dueAt=before.getDueAt();
  jdbc.execute("ALTER TABLE task_history ADD CONSTRAINT ck_test_reject_task_reassigned CHECK (action <> 'TASK_REASSIGNED' OR task_id <> "+id+")");
  try { assertThatThrownBy(()->service.update(id,new UpdateTaskRequest(0L,"After","Changed description",TaskPriority.URGENT,b.getId(),Instant.now().plusSeconds(7200)),new UserPrincipal(admin))).isInstanceOf(DataIntegrityViolationException.class); }
  finally { jdbc.execute("ALTER TABLE task_history DROP CONSTRAINT ck_test_reject_task_reassigned"); }
  Task after=tasks.findById(id).orElseThrow();assertThat(after.getTitle()).isEqualTo("Before");assertThat(after.getDescription()).isEqualTo("Description");
  assertThat(after.getPriority()).isEqualTo(TaskPriority.NORMAL);assertThat(after.getDueAt()).isEqualTo(dueAt);assertThat(after.getAssignee().getId()).isEqualTo(a.getId());
  assertThat(after.getAssignmentCycle()).isEqualTo(1);assertThat(after.getVersion()).isZero();assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).hasSize(1);assertThat(submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id)).isEmpty();
 }

 @Test void employeeCannotReadStartOrSubmitAnotherProfilesTask(){
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("owner-a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);StaffProfile b=profile("owner-b",Role.DESIGNER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);
  long id=service.create(create("Owned by B",b),new UserPrincipal(admin)).id();long historyBefore=history.findByTask_IdOrderByOccurredAtAscIdAsc(id).size();
  UserPrincipal principalA=new UserPrincipal(a.getUser(),a);
  assertThatThrownBy(()->service.employeeDetail(id,principalA)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
  assertThatThrownBy(()->service.start(id,new StartTaskRequest(0L),principalA)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
  assertThatThrownBy(()->service.submit(id,new SubmitTaskRequest(0L,"Unauthorized result",null),principalA)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
  Task unchanged=tasks.findById(id).orElseThrow();assertThat(unchanged.getAssignee().getId()).isEqualTo(b.getId());assertThat(unchanged.getStatus()).isEqualTo(TaskStatus.ASSIGNED);assertThat(unchanged.getVersion()).isZero();
  assertThat(submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id)).isEmpty();assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).hasSize(Math.toIntExact(historyBefore));
 }

 @Test void staleSubmitReviewAndReassignVersionsNeverWritePartialRows(){
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("stale-owner",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);StaffProfile b=profile("stale-target",Role.DESIGNER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);
  long id=service.create(create("Stale task",a),new UserPrincipal(admin)).id();UserPrincipal employee=new UserPrincipal(a.getUser(),a),administrator=new UserPrincipal(admin);
  service.start(id,new StartTaskRequest(0L),employee);
  assertConflict(()->service.submit(id,new SubmitTaskRequest(0L,"stale result",null),employee));
  assertThat(submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id)).isEmpty();assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).hasSize(2);
  service.submit(id,new SubmitTaskRequest(1L,"valid result",null),employee);
  assertConflict(()->service.review(id,new ReviewTaskRequest(1L,"APPROVE",null),administrator));
  assertConflict(()->service.update(id,new UpdateTaskRequest(1L,null,null,null,b.getId(),null),administrator));
  Task unchanged=tasks.findById(id).orElseThrow();assertThat(unchanged.getStatus()).isEqualTo(TaskStatus.SUBMITTED);assertThat(unchanged.getAssignee().getId()).isEqualTo(a.getId());
  assertThat(unchanged.getAssignmentCycle()).isEqualTo(1);assertThat(unchanged.getVersion()).isEqualTo(2);
  assertThat(submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id)).hasSize(1);assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).hasSize(3);
 }

 @Test void concurrentSubmitAndReassignmentHaveOneWinnerAndNoPartialMutation() throws Exception {
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("race-a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);StaffProfile b=profile("race-b",Role.DESIGNER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);
  long id=service.create(create("Concurrent submit/reassign",a),new UserPrincipal(admin)).id();CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
  try{Future<String> submit=pool.submit(()->race(ready,go,()->{service.submit(id,new SubmitTaskRequest(0L,"racing result",null),new UserPrincipal(a.getUser(),a));return "SUBMITTED";}));Future<String> reassign=pool.submit(()->race(ready,go,()->{service.update(id,new UpdateTaskRequest(0L,null,null,null,b.getId(),null),new UserPrincipal(admin));return "REASSIGNED";}));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();java.util.List<String> results=java.util.List.of(submit.get(20,TimeUnit.SECONDS),reassign.get(20,TimeUnit.SECONDS));assertThat(results).filteredOn("SUCCESS"::equals).hasSize(1);}
  finally{pool.shutdownNow();}
  Task finalTask=tasks.findById(id).orElseThrow();var events=history.findByTask_IdOrderByOccurredAtAscIdAsc(id);var finalSubmissions=submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id);
  if(finalTask.getStatus()==TaskStatus.SUBMITTED){assertThat(finalTask.getAssignee().getId()).isEqualTo(a.getId());assertThat(finalTask.getAssignmentCycle()).isEqualTo(1);assertThat(finalSubmissions).hasSize(1);assertThat(events).hasSize(2);}
  else{assertThat(finalTask.getStatus()).isEqualTo(TaskStatus.ASSIGNED);assertThat(finalTask.getAssignee().getId()).isEqualTo(b.getId());assertThat(finalTask.getAssignmentCycle()).isEqualTo(2);assertThat(finalSubmissions).isEmpty();assertThat(events).hasSize(2);}
 }

 @Test void concurrentReviewAndReassignmentKeepSubmittedTaskAndCycleConsistent() throws Exception {
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("review-a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);StaffProfile b=profile("review-b",Role.DESIGNER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);UserPrincipal administrator=new UserPrincipal(admin);
  long id=service.create(create("Concurrent review/reassign",a),administrator).id();service.submit(id,new SubmitTaskRequest(0L,"submitted result",null),new UserPrincipal(a.getUser(),a));
  CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
  try{Future<String> review=pool.submit(()->race(ready,go,()->{service.review(id,new ReviewTaskRequest(1L,"REQUEST_CHANGES","Revise"),administrator);return "REVIEWED";}));Future<String> reassign=pool.submit(()->race(ready,go,()->{service.update(id,new UpdateTaskRequest(1L,null,null,null,b.getId(),null),administrator);return "REASSIGNED";}));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();assertThat(review.get(20,TimeUnit.SECONDS)).isEqualTo("SUCCESS");assertThat(reassign.get(20,TimeUnit.SECONDS)).isNotEqualTo("SUCCESS");}
  finally{pool.shutdownNow();}
  Task finalTask=tasks.findById(id).orElseThrow();assertThat(finalTask.getStatus()).isEqualTo(TaskStatus.REVISION_REQUESTED);assertThat(finalTask.getAssignee().getId()).isEqualTo(a.getId());assertThat(finalTask.getAssignmentCycle()).isEqualTo(1);assertThat(finalTask.getVersion()).isEqualTo(2);
  assertThat(submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id)).hasSize(1);assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).hasSize(3);
 }

 @Test void concurrentStaleSubmitReviewAndReassignDoNotAppendPartialHistory() throws Exception {
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("stale-race-a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);StaffProfile b=profile("stale-race-b",Role.DESIGNER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);UserPrincipal administrator=new UserPrincipal(admin),employee=new UserPrincipal(a.getUser(),a);
  long id=service.create(create("Stale concurrent task",a),administrator).id();service.start(id,new StartTaskRequest(0L),employee);
  List<String> submitAndReassign=runRace(()->{service.submit(id,new SubmitTaskRequest(0L,"stale concurrent result",null),employee);return "SUBMITTED";},()->{service.update(id,new UpdateTaskRequest(0L,null,null,null,b.getId(),null),administrator);return "REASSIGNED";});
  assertThat(submitAndReassign).containsExactlyInAnyOrder("CONFLICT_409","CONFLICT_409");
  assertThat(tasks.findById(id).orElseThrow().getVersion()).isEqualTo(1);assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).hasSize(2);assertThat(submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id)).isEmpty();
  service.submit(id,new SubmitTaskRequest(1L,"current submission",null),employee);long submittedVersion=tasks.findById(id).orElseThrow().getVersion();
  List<String> reviewAndReassign=runRace(()->{service.review(id,new ReviewTaskRequest(1L,"APPROVE",null),administrator);return "REVIEWED";},()->{service.update(id,new UpdateTaskRequest(1L,null,null,null,b.getId(),null),administrator);return "REASSIGNED";});
  assertThat(reviewAndReassign).containsExactlyInAnyOrder("CONFLICT_409","CONFLICT_409");
  Task unchanged=tasks.findById(id).orElseThrow();assertThat(unchanged.getStatus()).isEqualTo(TaskStatus.SUBMITTED);assertThat(unchanged.getAssignee().getId()).isEqualTo(a.getId());assertThat(unchanged.getAssignmentCycle()).isEqualTo(1);assertThat(unchanged.getVersion()).isEqualTo(submittedVersion);
  assertThat(submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id)).hasSize(1);assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).hasSize(3);
 }

 @Test void staleVersionAndConcurrentWritersDoNotWritePartialChanges() throws Exception {
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);long id=service.create(create("Initial",a),new UserPrincipal(admin)).id();
  assertThatThrownBy(()->service.start(id,new StartTaskRequest(99L),new UserPrincipal(a.getUser(),a))).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
  CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
  try{Callable<Boolean> first=()->tryUpdate(id,"First",admin,ready,go);Callable<Boolean> second=()->tryUpdate(id,"Second",admin,ready,go);Future<Boolean> f1=pool.submit(first),f2=pool.submit(second);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();assertThat(java.util.List.of(f1.get(20,TimeUnit.SECONDS),f2.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
  finally{pool.shutdownNow();}
  assertThat(tasks.findById(id).orElseThrow().getVersion()).isEqualTo(1);assertThat(history.findByTask_IdOrderByOccurredAtAscIdAsc(id)).hasSize(2);assertThat(submissions.findByTask_IdOrderBySubmittedAtAscIdAsc(id)).isEmpty();
 }

 @Test void suspendedEmployeeLosesAccessOnNextTaskRequest(){
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=profile("a",Role.USER,UserStatus.ACTIVE,StaffStatus.ACTIVE,admin);long id=service.create(create("Suspension",a),new UserPrincipal(admin)).id();
  a.setStatus(StaffStatus.SUSPENDED);profiles.saveAndFlush(a);
  assertThatThrownBy(()->service.employeeDetail(id,new UserPrincipal(a.getUser(),a))).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(403));
 }

 private boolean tryUpdate(long id,String title,User admin,CountDownLatch ready,CountDownLatch go)throws Exception{ready.countDown();go.await();try{service.update(id,new UpdateTaskRequest(0L,title,null,null,null,null),new UserPrincipal(admin));return true;}catch(ResponseStatusException e){if(e.getStatusCode().value()==409)return false;throw e;}}
 private void assertConflict(Runnable call){assertThatThrownBy(call::run).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));}
 private String race(CountDownLatch ready,CountDownLatch go,java.util.concurrent.Callable<String> action)throws Exception{ready.countDown();go.await();try{action.call();return "SUCCESS";}catch(ResponseStatusException e){if(e.getStatusCode().value()==404||e.getStatusCode().value()==409)return "CONFLICT_"+e.getStatusCode().value();throw e;}}
 private List<String> runRace(java.util.concurrent.Callable<String> firstAction,java.util.concurrent.Callable<String> secondAction)throws Exception{CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<String> first=pool.submit(()->race(ready,go,firstAction));Future<String> second=pool.submit(()->race(ready,go,secondAction));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));}finally{go.countDown();pool.shutdownNow();}}
 private CreateTaskRequest create(String title,StaffProfile profile){return new CreateTaskRequest(title,"Description",TaskPriority.NORMAL,profile.getId(),Instant.now().plusSeconds(3600));}
 private User user(String role,Role userRole,UserStatus status){String id=UUID.randomUUID().toString();return users.saveAndFlush(User.builder().name("Task "+role).email(id+"@example.invalid").password("test").role(userRole).status(status).build());}
 private StaffProfile profile(String label,Role role,UserStatus userStatus,StaffStatus status,User admin){User u=user(label,role,userStatus);StaffProfile p=new StaffProfile();p.setUser(u);p.setEmployeeNumber(("T-"+label+"-"+UUID.randomUUID().toString().substring(0,8)).toUpperCase());p.setDepartment("Design");p.setJobTitle("Designer");p.setStatus(status);p.setJoinedAt(LocalDate.of(2026,1,1));p.setCreatedBy(admin);p.setUpdatedBy(admin);return profiles.saveAndFlush(p);}
}
