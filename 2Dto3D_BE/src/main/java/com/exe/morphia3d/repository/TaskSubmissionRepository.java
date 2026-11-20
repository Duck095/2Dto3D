package com.exe.morphia3d.repository;
import com.exe.morphia3d.entity.TaskSubmission;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
public interface TaskSubmissionRepository extends JpaRepository<TaskSubmission,Long> {
 List<TaskSubmission> findByTask_IdOrderBySubmittedAtAscIdAsc(Long taskId);
 Optional<TaskSubmission> findFirstByTask_IdAndAssignmentCycleOrderBySubmittedAtDescIdDesc(Long taskId,long cycle);
 Optional<TaskSubmission> findFirstByTask_IdAndAssignmentCycleAndSubmittedByStaffProfile_IdOrderBySubmittedAtDescIdDesc(Long taskId,long cycle,Long staffId);
 int countByTask_Id(Long taskId);
 @Query("select s from TaskSubmission s where s.task.id=:taskId and s.assignmentCycle=:cycle order by s.submittedAt desc,s.id desc")
 List<TaskSubmission> findCurrentCycle(@Param("taskId") Long taskId,@Param("cycle") long cycle);
}
