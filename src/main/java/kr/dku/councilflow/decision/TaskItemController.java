package kr.dku.councilflow.decision;

import jakarta.validation.Valid;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.web.IfMatch;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class TaskItemController {

    private final TaskItemService itemService;

    TaskItemController(TaskItemService itemService) {
        this.itemService = itemService;
    }

    /** 체크리스트 추가 { title, assigneeUserId? } (회장·담당 부서 부장) */
    @PostMapping("/api/tasks/{taskId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    TaskItemService.ItemView add(CurrentUser user, @PathVariable long taskId,
                                 @Valid @RequestBody TaskItemService.AddItem request) {
        return itemService.add(user, taskId, request);
    }

    /** 내용·맡길 사람·완료 체크 { title?, assigneeUserId?, done? } (If-Match: version) */
    @PatchMapping("/api/task-items/{itemId}")
    TaskItemService.ItemView update(CurrentUser user, @PathVariable long itemId,
                                    @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                    @RequestBody JsonNode body) {
        return itemService.update(user, itemId, ifMatch, body);
    }

    /** 체크리스트 지우기(회장·담당 부서 부장) */
    @DeleteMapping("/api/task-items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(CurrentUser user, @PathVariable long itemId) {
        itemService.remove(user, itemId);
    }
}
