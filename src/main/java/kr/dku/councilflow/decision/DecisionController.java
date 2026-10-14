package kr.dku.councilflow.decision;

import jakarta.validation.Valid;
import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.web.IfMatch;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class DecisionController {

    private final DecisionService decisionService;
    private final TaskService taskService;

    DecisionController(DecisionService decisionService, TaskService taskService) {
        this.decisionService = decisionService;
        this.taskService = taskService;
    }

    /** AI 후보(결정·업무)와 근거 발언 */
    @GetMapping("/api/meetings/{meetingId}/proposals")
    DecisionService.Proposals proposals(CurrentUser user, @PathVariable long meetingId) {
        return decisionService.proposals(user, meetingId);
    }

    /** 승인 전 결정 문구 수정 (If-Match: version) */
    @PatchMapping("/api/decisions/{decisionId}")
    DecisionViews.DecisionView edit(CurrentUser user, @PathVariable long decisionId,
                                    @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                    @Valid @RequestBody DecisionService.EditDecision request) {
        return decisionService.edit(user, decisionId, ifMatch, request);
    }

    /** 승인 (If-Match: version). 그룹 owner만. */
    @PostMapping("/api/decisions/{decisionId}/approve")
    DecisionViews.DecisionView approve(CurrentUser user, @PathVariable long decisionId,
                                       @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch) {
        return decisionService.approve(user, decisionId, ifMatch);
    }

    /** 업무 직접 추가(그룹 owner) */
    @PostMapping("/api/decisions/{decisionId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    DecisionViews.TaskView addTask(CurrentUser user, @PathVariable long decisionId,
                                   @Valid @RequestBody TaskService.AddTask request) {
        return taskService.add(user, decisionId, request);
    }

    /** 승인된 결정 목록(업무 추가할 때 고르는 용도) */
    @GetMapping("/api/orgs/{orgId}/decisions")
    List<DecisionService.DecisionOption> approvedDecisions(CurrentUser user, @PathVariable long orgId) {
        return decisionService.approvedDecisions(user, orgId);
    }

    /** 담당·기한·상태 변경 (If-Match: version) */
    @PatchMapping("/api/tasks/{taskId}")
    DecisionViews.TaskView updateTask(CurrentUser user, @PathVariable long taskId,
                                      @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                      @RequestBody JsonNode body) {
        return taskService.update(user, taskId, ifMatch, body);
    }

    /** 업무 완전 삭제(회장만, 어떤 상태든, 체크리스트 포함) */
    @DeleteMapping("/api/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTask(CurrentUser user, @PathVariable long taskId) {
        taskService.delete(user, taskId);
    }

    /** 부서 업무보드: 승인된 업무만 */
    @GetMapping("/api/orgs/{orgId}/tasks")
    List<TaskService.BoardTask> board(CurrentUser user, @PathVariable long orgId,
                                      @RequestParam(required = false) Long departmentId) {
        return taskService.board(user, orgId, departmentId);
    }
}
