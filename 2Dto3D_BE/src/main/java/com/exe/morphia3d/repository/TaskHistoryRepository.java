package com.exe.morphia3d.repository;
import com.exe.morphia3d.entity.TaskHistory;
import com.exe.morphia3d.enums.TaskHistoryAction;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
public interface TaskHistoryRepository extends JpaRepository<TaskHistory,Long> {
 @EntityGraph(attributePaths={"actor","previousAssignee","newAssignee"})
 List<TaskHistory> findByTask_IdOrderByOccurredAtAscIdAsc(Long taskId);
 @EntityGraph(attributePaths={"actor"})
 Optional<TaskHistory> findFirstByTask_IdAndAssignmentCycleAndActionInOrderByOccurredAtDescIdDesc(Long taskId,long cycle,List<TaskHistoryAction> actions);
}
