package com.exe.morphia3d.controller;

import com.exe.morphia3d.Morphia3dApplication;
import com.exe.morphia3d.dto.request.*;
import com.exe.morphia3d.dto.response.TaskResponse;
import com.exe.morphia3d.entity.*;
import com.exe.morphia3d.enums.*;
import com.exe.morphia3d.repository.*;
import com.exe.morphia3d.security.*;
import com.exe.morphia3d.service.TaskManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MvcResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=Morphia3dApplication.class) @ActiveProfiles("test") @Transactional
class TaskManagementSecurityIntegrationTest {
 @Autowired WebApplicationContext context;@Autowired UserRepository users;@Autowired StaffProfileRepository profiles;@Autowired JwtUtil jwt;
 @Autowired UserDetailsServiceImpl userDetails;@Autowired TaskManagementService tasks;
 MockMvc mvc;
 @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}

 @Test void adminRoutesRequireAdminRole() throws Exception {
  mvc.perform(get("/api/admin/tasks")).andExpect(status().isUnauthorized());
  User employee=user("user",Role.USER,UserStatus.ACTIVE);activeProfile(employee,user("admin",Role.ADMIN,UserStatus.ACTIVE));
  mvc.perform(get("/api/admin/tasks").header("Authorization",bearer(employee))).andExpect(status().isForbidden());
  User admin=user("admin2",Role.ADMIN,UserStatus.ACTIVE);
  mvc.perform(get("/api/admin/tasks").header("Authorization",bearer(admin))).andExpect(status().isOk());
  mvc.perform(get("/api/admin/tasks/acceptance").header("Authorization",bearer(admin))).andExpect(status().isOk());
  mvc.perform(get("/api/admin/tasks/summary").header("Authorization",bearer(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.total").isNumber());
 }

 @Test void employeeMustHaveActiveStaffAccessAndSuspensionTakesEffectOnNextRequest() throws Exception {
  User noProfile=user("no-profile",Role.USER,UserStatus.ACTIVE);
  mvc.perform(get("/api/v1/me/tasks").header("Authorization",bearer(noProfile))).andExpect(status().isForbidden());
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);User employee=user("employee",Role.DESIGNER,UserStatus.ACTIVE);StaffProfile profile=activeProfile(employee,admin);
  mvc.perform(get("/api/v1/me/tasks").header("Authorization",bearer(employee))).andExpect(status().isOk());
  profile.setStatus(StaffStatus.SUSPENDED);profiles.saveAndFlush(profile);
  mvc.perform(get("/api/v1/me/tasks").header("Authorization",bearer(employee))).andExpect(status().isForbidden());
 }

 @Test void employeeCannotReadAnotherEmployeesTaskAndResponseOmitsAuditAndPriorSubmissions() throws Exception {
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile a=activeProfile(user("a",Role.USER,UserStatus.ACTIVE),admin);StaffProfile b=activeProfile(user("b",Role.DESIGNER,UserStatus.ACTIVE),admin);
  long id=createTask(admin,b);String tokenA=bearer(a.getUser());
  mvc.perform(get("/api/v1/me/tasks/{id}",id).header("Authorization",tokenA)).andExpect(status().isNotFound());
  serviceSubmit(id,b);
  mvc.perform(get("/api/v1/me/tasks/{id}",id).header("Authorization",bearer(b.getUser())))
   .andExpect(status().isOk()).andExpect(jsonPath("$.latestSubmission.resultDescription").value("Employee B result"))
   .andExpect(jsonPath("$.history").doesNotExist()).andExpect(jsonPath("$.submissions").doesNotExist());
  mvc.perform(get("/api/admin/tasks/{id}",id).header("Authorization",bearer(admin)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.history").isArray()).andExpect(jsonPath("$.submissions").isArray());
 }

 @Test void taskHttpLifecyclePersistsAndReturnsExpectedVersionsAndCurrentCycleFeedback() throws Exception {
  User admin=user("admin",Role.ADMIN,UserStatus.ACTIVE);StaffProfile employee=activeProfile(user("employee",Role.USER,UserStatus.ACTIVE),admin);
  MvcResult created=mvc.perform(post("/api/admin/tasks").header("Authorization",bearer(admin)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"title\":\"HTTP task\",\"description\":\"Backend task\",\"priority\":\"HIGH\",\"assigneeStaffProfileId\":%d,\"dueAt\":\"2099-01-01T00:00:00Z\"}".formatted(employee.getId())))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ASSIGNED")).andExpect(jsonPath("$.history").isArray()).andReturn();
  long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getResponse().getContentAsString()).get("id").asLong();String employeeToken=bearer(employee.getUser());
  mvc.perform(post("/api/v1/me/tasks/{id}/start",id).header("Authorization",employeeToken).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS")).andExpect(jsonPath("$.version").value(1));
  mvc.perform(post("/api/v1/me/tasks/{id}/submissions",id).header("Authorization",employeeToken).contentType(MediaType.APPLICATION_JSON)
    .content("{\"expectedVersion\":1,\"resultDescription\":\"Result URL description\",\"resultUrl\":\"https://example.invalid/result\"}"))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.assignmentCycle").value(1));
  mvc.perform(post("/api/admin/tasks/{id}/review",id).header("Authorization",bearer(admin)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"expectedVersion\":2,\"decision\":\"REQUEST_CHANGES\",\"feedback\":\"Please revise\"}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVISION_REQUESTED"));
  mvc.perform(get("/api/v1/me/tasks/{id}",id).header("Authorization",employeeToken))
    .andExpect(status().isOk()).andExpect(jsonPath("$.latestRevisionFeedback").value("Please revise"))
    .andExpect(jsonPath("$.latestSubmission.resultDescription").value("Result URL description"))
    .andExpect(jsonPath("$.history").doesNotExist()).andExpect(jsonPath("$.submissions").doesNotExist());
 }

 private long createTask(User admin,StaffProfile assignee){return tasks.create(new CreateTaskRequest("Private task","Description",TaskPriority.NORMAL,assignee.getId(),Instant.now().plusSeconds(3600)),new UserPrincipal(admin)).id();}
 private void serviceSubmit(long id,StaffProfile employee){tasks.submit(id,new SubmitTaskRequest(0L,"Employee B result",null),new UserPrincipal(employee.getUser(),employee));}
 private User user(String label,Role role,UserStatus status){return users.saveAndFlush(User.builder().name(label).email(UUID.randomUUID()+"@example.invalid").password("test").role(role).status(status).build());}
 private StaffProfile activeProfile(User employee,User admin){StaffProfile p=new StaffProfile();p.setUser(employee);p.setEmployeeNumber("S-"+UUID.randomUUID().toString().substring(0,10).toUpperCase());p.setDepartment("Design");p.setJobTitle("Designer");p.setJoinedAt(LocalDate.of(2026,1,1));p.setStatus(StaffStatus.ACTIVE);p.setCreatedBy(admin);p.setUpdatedBy(admin);return profiles.saveAndFlush(p);}
 private String bearer(User user){return "Bearer "+jwt.generateToken(userDetails.loadUserByUsername(user.getEmail()));}
}
