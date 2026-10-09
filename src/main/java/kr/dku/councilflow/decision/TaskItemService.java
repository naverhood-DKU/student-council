package kr.dku.councilflow.decision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.common.web.IfMatch;
import kr.dku.councilflow.user.AppUser;
import kr.dku.councilflow.user.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 업무 체크리스트.
 * - 만들기·내용·맡길 사람 바꾸기·지우기: 회장(owner) 또는 그 업무 담당 부서의 부장(editor)
 * - 완료 체크·해제: 맡은 사람 본인, 또는 위의 사람
 * - 맡길 수 있는 사람: 그 업무 담당 부서에 속한 그룹 구성원(부장·부원)
 * - 담당 부서가 정해진, 끝나지 않은 승인 업무에서만 쓴다.
 */
@Service
public class TaskItemService {

    private final TaskRepository tasks;
    private final TaskItemRepository items;
    private final MembershipRepository memberships;
    private final OrgAccessGuard accessGuard;
    private final AuditService audit;
    private final Clock clock;

    TaskItemService(TaskRepository tasks, TaskItemRepository items, MembershipRepository memberships,
                    OrgAccessGuard accessGuard, AuditService audit, Clock clock) {
        this.tasks = tasks;
        this.items = items;
        this.memberships = memberships;
        this.accessGuard = accessGuard;
        this.audit = audit;
        this.clock = clock;
    }

    public record ItemView(long id, long taskId, String title, Long assigneeUserId, String assigneeName,
                           boolean done, Instant doneAt, long version) {

        static ItemView of(TaskItem item) {
            AppUser assignee = item.getAssignee();
            return new ItemView(item.getId(), item.getTask().getId(), item.getTitle(),
                    assignee == null ? null : assignee.getId(), assignee == null ? null : assignee.getName(),
                    item.isDone(), item.getDoneAt(), item.getVersion());
        }
    }

    public record AddItem(@NotBlank @Size(max = 300) String title, Long assigneeUserId) {
    }

    /** 업무별 체크리스트(업무보드에 붙인다) */
    @Transactional(readOnly = true)
    public Map<Long, List<ItemView>> viewsByTask(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return items.findByTaskIds(taskIds).stream()
                .collect(Collectors.groupingBy(i -> i.getTask().getId(), LinkedHashMap::new,
                        Collectors.mapping(ItemView::of, Collectors.toList())));
    }

    @Transactional
    public ItemView add(CurrentUser user, long taskId, AddItem request) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        OrgAccess access = accessGuard.requireMember(user, orgId(task));
        requireManager(access, task);
        requireOpenTask(task);
        TaskItem item = new TaskItem(task, request.title().strip(), assignee(request.assigneeUserId(), task),
                access.userId());
        items.saveAndFlush(item);
        audit.record(access.userId(), orgId(task), "TASK_ITEM_ADDED", "TASK_ITEM", item.getId(), null, snapshot(item));
        return ItemView.of(item);
    }

    /**
     * 본문에 있는 필드만 바꾼다. (If-Match: version)
     * { "title": "...", "assigneeUserId": 7 | null, "done": true }
     */
    @Transactional
    public ItemView update(CurrentUser user, long itemId, String ifMatch, JsonNode body) {
        TaskItem item = items.findById(itemId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        Task task = item.getTask();
        OrgAccess access = accessGuard.requireMember(user, orgId(task));
        IfMatch.check(ifMatch, item.getVersion());
        if (body == null || !body.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "바꿀 내용을 JSON 객체로 보내 주세요.");
        }
        boolean manager = isManager(access, task);
        boolean mine = item.getAssignee() != null && item.getAssignee().getId() == access.userId();
        if ((body.has("title") || body.has("assigneeUserId")) && !manager) {
            throw new ApiException(ErrorCode.FORBIDDEN, "체크리스트 내용과 맡길 사람은 회장이나 담당 부서 부장만 바꿀 수 있습니다.");
        }
        if (body.has("done") && !manager && !mine) {
            throw new ApiException(ErrorCode.FORBIDDEN, "맡은 사람이나 담당 부서 부장만 완료를 체크할 수 있습니다.");
        }
        requireOpenTask(task);
        Map<String, Object> before = snapshot(item);

        if (body.has("title")) {
            String title = body.get("title").isString() ? body.get("title").asString().strip() : "";
            if (title.isEmpty() || title.length() > 300) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "할 일은 1~300자로 적어 주세요.");
            }
            item.rename(title);
        }
        if (body.has("assigneeUserId")) {
            JsonNode node = body.get("assigneeUserId");
            if (!node.isNull() && !node.isIntegralNumber()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "assigneeUserId 는 숫자이거나 null 이어야 합니다.");
            }
            item.assign(assignee(node.isNull() ? null : node.asLong(), task));
        }
        if (body.has("done")) {
            if (!body.get("done").isBoolean()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "done 은 true 나 false 여야 합니다.");
            }
            item.markDone(body.get("done").asBoolean(), access.userId(), Instant.now(clock));
        }
        items.flush();
        audit.record(access.userId(), orgId(task), "TASK_ITEM_UPDATED", "TASK_ITEM", itemId, before, snapshot(item));
        return ItemView.of(item);
    }

    @Transactional
    public void remove(CurrentUser user, long itemId) {
        TaskItem item = items.findById(itemId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        Task task = item.getTask();
        OrgAccess access = accessGuard.requireMember(user, orgId(task));
        requireManager(access, task);
        requireOpenTask(task);
        Map<String, Object> before = snapshot(item);
        items.delete(item);
        items.flush();
        audit.record(access.userId(), orgId(task), "TASK_ITEM_REMOVED", "TASK_ITEM", itemId, before, null);
    }

    private static boolean isManager(OrgAccess access, Task task) {
        return access.isOwner() || (task.getDepartment() != null && access.canWorkFor(task.getDepartment().getId()));
    }

    private static void requireManager(OrgAccess access, Task task) {
        if (!isManager(access, task)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "체크리스트는 회장이나 담당 부서 부장이 만듭니다.");
        }
    }

    private static void requireOpenTask(Task task) {
        if (task.getStatus() == TaskStatus.PROPOSED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "결정을 승인한 뒤에 체크리스트를 쓸 수 있습니다.");
        }
        if (task.getStatus().isFinished()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "완료·취소된 업무의 체크리스트는 바꿀 수 없습니다.");
        }
        if (task.getDepartment() == null) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "담당 부서를 정한 뒤에 체크리스트를 쓸 수 있습니다.");
        }
    }

    /** 맡길 사람: 그 업무 담당 부서의 활성 구성원만. null 이면 아직 안 맡김. */
    private AppUser assignee(Long userId, Task task) {
        if (userId == null) {
            return null;
        }
        long departmentId = task.getDepartment().getId();
        return memberships.findActiveByUserIdAndOrgId(userId, orgId(task)).stream()
                .filter(m -> m.getDepartment() != null && m.getDepartment().getId() == departmentId)
                .map(m -> m.getUser())
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "담당 부서 사람에게만 맡길 수 있습니다."));
    }

    private static long orgId(Task task) {
        return task.getDecision().getMeeting().getOrgId();
    }

    private static Map<String, Object> snapshot(TaskItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", item.getTask().getId());
        map.put("title", item.getTitle());
        map.put("assigneeUserId", item.getAssignee() == null ? null : item.getAssignee().getId());
        map.put("done", item.isDone());
        return map;
    }
}
