package com.exe.morphia3d.repository;
import com.exe.morphia3d.entity.Task;
import com.exe.morphia3d.enums.TaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task,Long>, JpaSpecificationExecutor<Task> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from Task t where t.id=:id") Optional<Task> findByIdForUpdate(@Param("id") Long id);
 Optional<Task> findByIdAndAssignee_Id(Long id,Long staffProfileId);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select t from Task t where t.id=:id and t.assignee.id=:staffProfileId")
 Optional<Task> findOwnedByIdAndAssigneeIdForUpdate(@Param("id") Long id,@Param("staffProfileId") Long staffProfileId);
 @Override
 @EntityGraph(attributePaths={"assignee","assignee.user"})
 Page<Task> findAll(Specification<Task> specification, Pageable pageable);

 default Page<Task> findAdminPage(TaskStatus status, com.exe.morphia3d.enums.TaskPriority priority,
                                  Long assigneeId, String q, Instant dueBefore, Instant dueAfter,
                                  Boolean overdue, Instant now, Pageable pageable) {
  Specification<Task> filters = (root, query, criteriaBuilder) -> {
   List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
   if (status != null) predicates.add(criteriaBuilder.equal(root.get("status"), status));
   if (priority != null) predicates.add(criteriaBuilder.equal(root.get("priority"), priority));
   if (assigneeId != null) predicates.add(criteriaBuilder.equal(root.get("assignee").get("id"), assigneeId));
   if (q != null && !q.isBlank()) {
    String pattern = containsPattern(q);
    predicates.add(criteriaBuilder.or(
      criteriaBuilder.like(criteriaBuilder.lower(root.<String>get("title")), pattern, '\\'),
      criteriaBuilder.like(criteriaBuilder.lower(root.<String>get("description")), pattern, '\\')));
   }
   if (dueBefore != null) predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("dueAt"), dueBefore));
   if (dueAfter != null) predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("dueAt"), dueAfter));
   if (overdue != null) {
    if (overdue) {
     predicates.add(criteriaBuilder.lessThan(root.get("dueAt"), now));
     predicates.add(criteriaBuilder.notEqual(root.get("status"), TaskStatus.APPROVED));
    } else {
     predicates.add(criteriaBuilder.or(
       criteriaBuilder.greaterThanOrEqualTo(root.get("dueAt"), now),
       criteriaBuilder.equal(root.get("status"), TaskStatus.APPROVED)));
    }
   }
   return criteriaBuilder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
  };
  return findAll(filters, pageable);
 }

 private static String containsPattern(String value) {
  String literal = value.toLowerCase(Locale.ROOT)
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_");
  return "%" + literal + "%";
 }
 @EntityGraph(attributePaths={"assignee","assignee.user"})
 Page<Task> findByAssignee_IdAndStatus(Long staffProfileId,TaskStatus status,Pageable pageable);
 @EntityGraph(attributePaths={"assignee","assignee.user"})
 Page<Task> findByAssignee_Id(Long staffProfileId,Pageable pageable);
 @EntityGraph(attributePaths={"assignee","assignee.user","createdBy"})
 @Query("select t from Task t where t.status=com.exe.morphia3d.enums.TaskStatus.SUBMITTED order by (select min(s.submittedAt) from TaskSubmission s where s.task=t and s.assignmentCycle=t.assignmentCycle) asc,t.id asc")
 Page<Task> findAcceptancePage(Pageable pageable);
 long countByStatus(TaskStatus status);
 long countByDueAtBeforeAndStatusNot(Instant now,TaskStatus status);
}
