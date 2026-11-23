package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.*;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.exe.morphia3d.test.support.PostgresTestRoleProvisioning;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker=true)
class TaskManagementMigrationPostgresTest {
 @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("task_qa_isolated").withUsername("task_qa_only").withPassword("task_qa_only");

 @Test void freshV1ThroughV4ValidatesSchemaTypesConstraintsIndexesAndTransactions() throws Exception {
  DataSource ds=newDataSource();reset(ds);Flyway flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(MigrationVersion.fromVersion("4")).cleanDisabled(false).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);flyway.validate();
  JdbcTemplate jdbc=new JdbcTemplate(ds);assertTaskSchema(jdbc);assertHibernateMappings(ds);
  Fixture fixture=insertFixture(jdbc,"fresh");
  assertThat(jdbc.queryForObject("select count(*) from users where user_id=?",Integer.class,fixture.employeeUserId)).isEqualTo(1);
  assertThatThrownBy(()->jdbc.update("insert into tasks(title,description,priority,assignee_staff_profile_id,created_by_user_id,status,due_at) values ('bad','bad','INVALID',?,?, 'ASSIGNED', now())",fixture.staffAId,fixture.adminUserId)).isInstanceOf(DataAccessException.class);
  jdbc.update("insert into task_history(task_id,actor_user_id,assignment_cycle,action,details) values (?, ?, 1, 'TASK_CREATED', '{\"title\":\"fresh\"}'::jsonb)",fixture.taskId,fixture.adminUserId);
  assertThat(jdbc.queryForObject("select details->>'title' from task_history where task_id=?",String.class,fixture.taskId)).isEqualTo("fresh");
  jdbc.update("insert into task_submissions(task_id,submission_number,submitted_by_user_id,submitted_by_staff_profile_id,assignment_cycle,result_description) values (?,1,?,?,1,'first')",fixture.taskId,fixture.employeeUserId,fixture.staffAId);
  assertThatThrownBy(()->jdbc.update("insert into task_submissions(task_id,submission_number,submitted_by_user_id,submitted_by_staff_profile_id,assignment_cycle,result_description) values (?,1,?,?,1,'duplicate')",fixture.taskId,fixture.employeeUserId,fixture.staffAId)).isInstanceOf(DataAccessException.class);
  assertRollback(ds,jdbc,fixture);assertConcurrentVersionWriters(ds,jdbc,fixture);
 }

 @Test void upgradesExistingV1ThroughV3DataToCurrentSchemaWithoutLoss() {
  DataSource ds=newDataSource();reset(ds);JdbcTemplate jdbc=new JdbcTemplate(ds);PostgresTestRoleProvisioning.provisionMigrationRoles(jdbc,POSTGRES.getUsername());Flyway throughV3=Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(MigrationVersion.fromVersion("3")).cleanDisabled(false).load();
  assertThat(throughV3.migrate().migrationsExecuted).isEqualTo(3);
  Fixture fixture=insertLegacyStaff(jdbc,"upgrade");
  Flyway throughV14=Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(MigrationVersion.fromVersion("14")).cleanDisabled(false).load();
  assertThat(throughV14.migrate().migrationsExecuted).isEqualTo(11);
  PostgresTestRoleProvisioning.grantSnapshotReferencePrivileges(jdbc);
  Flyway upgrade=flyway(ds);assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(7);upgrade.validate();assertTaskSchema(jdbc);assertHibernateMappings(ds);
  assertThat(jdbc.queryForObject("select count(*) from users where user_id=?",Integer.class,fixture.employeeUserId)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from staff_profiles where id=?",Integer.class,fixture.staffAId)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from tasks",Integer.class)).isZero();
 }

 private DataSource newDataSource(){return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());}
 private Flyway flyway(DataSource ds){return Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load();}
 private void reset(DataSource ds){flyway(ds).clean();}
 private void assertTaskSchema(JdbcTemplate jdbc){
  assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema='public' and table_name in ('tasks','task_submissions','task_history')",String.class)).containsExactlyInAnyOrder("tasks","task_submissions","task_history");
  assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema='public' and table_name='task_history' and column_name='details'",String.class)).isEqualTo("jsonb");
  assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema='public' and table_name='tasks' and column_name='due_at'",String.class)).isEqualTo("timestamp with time zone");
  assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema='public' and table_name='task_history' and column_name='occurred_at'",String.class)).isEqualTo("timestamp with time zone");
  assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname='public' and indexname in ('idx_tasks_assignee_status_due_id','idx_tasks_assignee_updated_id','idx_tasks_status_due_id','idx_tasks_updated_id','idx_task_submissions_task_cycle_time','idx_task_history_task_time','idx_task_history_task_cycle_time','idx_task_history_actor_time')",Integer.class)).isEqualTo(8);
  assertThat(jdbc.queryForObject("select count(*) from pg_constraint where connamespace='public'::regnamespace and conname in ('ck_tasks_assignment_cycle','ck_task_submissions_cycle','ck_task_history_cycle','uq_task_submissions_number')",Integer.class)).isEqualTo(4);
 }
 private void assertHibernateMappings(DataSource ds){
  StandardServiceRegistry registry=new StandardServiceRegistryBuilder().applySetting("hibernate.connection.datasource",ds).applySetting("hibernate.dialect","org.hibernate.dialect.PostgreSQLDialect").applySetting("hibernate.hbm2ddl.auto","validate").build();
  try(SessionFactory sf=new MetadataSources(registry).addAnnotatedClass(User.class).addAnnotatedClass(StaffProfile.class).addAnnotatedClass(Task.class).addAnnotatedClass(TaskSubmission.class).addAnnotatedClass(TaskHistory.class).buildMetadata().buildSessionFactory()){assertThat(sf.getMetamodel().getEntities()).hasSize(5);}finally{StandardServiceRegistryBuilder.destroy(registry);}
 }
 private Fixture insertFixture(JdbcTemplate jdbc,String suffix){
  Fixture base=insertLegacyStaff(jdbc,suffix);Long taskId=jdbc.queryForObject("insert into tasks(title,description,priority,assignee_staff_profile_id,created_by_user_id,status,assignment_cycle,due_at) values ('QA task','QA description','NORMAL',?,?,'ASSIGNED',1,now()+interval '1 day') returning id",Long.class,base.staffAId,base.adminUserId);
  return new Fixture(base.adminUserId,base.employeeUserId,base.staffAId,base.staffBId,taskId);
 }
 private Fixture insertLegacyStaff(JdbcTemplate jdbc,String suffix){
  Integer admin=insertUser(jdbc,"admin-"+suffix, "ADMIN"),employee=insertUser(jdbc,"employee-"+suffix,"USER"),second=insertUser(jdbc,"second-"+suffix,"DESIGNER");
  Long a=insertProfile(jdbc,employee,admin,"QA-A-"+suffix),b=insertProfile(jdbc,second,admin,"QA-B-"+suffix);
  return new Fixture(admin,employee,a,b,null);
 }
 private Integer insertUser(JdbcTemplate jdbc,String label,String role){return jdbc.queryForObject("insert into users(name,email,password,role,status) values (?,?,'qa-only',?,'ACTIVE') returning user_id",Integer.class,label,label+"-"+UUID.randomUUID()+"@example.invalid",role);}
 private Long insertProfile(JdbcTemplate jdbc,Integer userId,Integer adminId,String employeeNumber){return jdbc.queryForObject("insert into staff_profiles(user_id,employee_number,department,job_title,status,joined_at,created_by,updated_by) values (?,?,'QA','Test','ACTIVE',DATE '2026-01-01',?,?) returning id",Long.class,userId,employeeNumber.toUpperCase(java.util.Locale.ROOT),adminId,adminId);}
 private void assertRollback(DataSource ds,JdbcTemplate jdbc,Fixture f)throws Exception{
  long historyBefore=jdbc.queryForObject("select count(*) from task_history where task_id=?",Long.class,f.taskId);
  try(Connection c=ds.getConnection()) {c.setAutoCommit(false);try{
    try(PreparedStatement update=c.prepareStatement("update tasks set title='partial title',description='partial description',priority='URGENT',assignee_staff_profile_id=?,assignment_cycle=assignment_cycle+1,version=version+1 where id=?")){update.setLong(1,f.staffBId);update.setLong(2,f.taskId);assertThat(update.executeUpdate()).isEqualTo(1);}
    try(PreparedStatement badAudit=c.prepareStatement("insert into task_history(task_id,actor_user_id,assignment_cycle,action,details) values (?, ?, 2, 'INVALID_AUDIT', '{}'::jsonb)")){badAudit.setLong(1,f.taskId);badAudit.setInt(2,f.adminUserId);badAudit.executeUpdate();}
   }catch(Exception failure){c.rollback();assertThat(failure).isInstanceOf(java.sql.SQLException.class);assertThat(((java.sql.SQLException)failure).getSQLState()).isEqualTo("23514");}finally{if(!c.getAutoCommit())c.rollback();}}
  assertThat(jdbc.queryForMap("select title,description,priority,assignee_staff_profile_id,assignment_cycle,version from tasks where id=?",f.taskId))
   .containsEntry("title","QA task").containsEntry("description","QA description").containsEntry("priority","NORMAL").containsEntry("assignee_staff_profile_id",f.staffAId).containsEntry("assignment_cycle",1L).containsEntry("version",0L);
  assertThat(jdbc.queryForObject("select count(*) from task_history where task_id=?",Long.class,f.taskId)).isEqualTo(historyBefore);
 }
 private void assertConcurrentVersionWriters(DataSource ds,JdbcTemplate jdbc,Fixture f)throws Exception{
  Long raceId=jdbc.queryForObject("insert into tasks(title,description,priority,assignee_staff_profile_id,created_by_user_id,status,assignment_cycle,due_at) values ('race','race description','NORMAL',?,?,'ASSIGNED',1,now()+interval '1 day') returning id",Long.class,f.staffAId,f.adminUserId);
  CountDownLatch firstUpdated=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),secondStarted=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
  try{
   Future<Integer> first=pool.submit(()->{try(Connection c=ds.getConnection()){c.setAutoCommit(false);int changed=updateExpectedVersion(c,raceId,"winner");firstUpdated.countDown();releaseFirst.await();c.commit();return changed;}});
   Future<Integer> second=pool.submit(()->{firstUpdated.await();try(Connection c=ds.getConnection()){c.setAutoCommit(false);secondStarted.countDown();int changed=updateExpectedVersion(c,raceId,"stale");c.commit();return changed;}});
   assertThat(firstUpdated.await(10,TimeUnit.SECONDS)).isTrue();assertThat(secondStarted.await(10,TimeUnit.SECONDS)).isTrue();releaseFirst.countDown();assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(1,0);
  }finally{releaseFirst.countDown();pool.shutdownNow();}
  assertThat(jdbc.queryForObject("select version from tasks where id=?",Long.class,raceId)).isEqualTo(1L);assertThat(jdbc.queryForObject("select title from tasks where id=?",String.class,raceId)).isEqualTo("winner");
 }
 private int updateExpectedVersion(Connection c,Long id,String title)throws Exception{try(PreparedStatement p=c.prepareStatement("update tasks set title=?,version=version+1,updated_at=now() where id=? and version=0")){p.setString(1,title);p.setLong(2,id);return p.executeUpdate();}}
 private record Fixture(Integer adminUserId,Integer employeeUserId,Long staffAId,Long staffBId,Long taskId){}
}
