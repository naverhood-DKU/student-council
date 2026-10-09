package kr.dku.councilflow.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.user.AppUser;

/** 업무 체크리스트 한 줄. 회장이나 담당 부서 부장이 만들고 부서 사람에게 맡기며, 맡은 사람이 완료를 체크한다. */
@Entity
@Table(name = "task_item")
public class TaskItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(nullable = false, length = 300)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_user_id")
    private AppUser assignee;

    @Column(nullable = false)
    private boolean done;

    private Long doneBy;

    private Instant doneAt;

    @Column(nullable = false, updatable = false)
    private Long createdBy;

    protected TaskItem() {
    }

    public TaskItem(Task task, String title, AppUser assignee, long createdBy) {
        this.task = task;
        this.title = title;
        this.assignee = assignee;
        this.createdBy = createdBy;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void assign(AppUser assignee) {
        this.assignee = assignee;
    }

    /** 완료 체크·해제. 해제하면 누가 언제 끝냈는지도 지운다. */
    public void markDone(boolean done, long userId, Instant at) {
        this.done = done;
        this.doneBy = done ? userId : null;
        this.doneAt = done ? at : null;
    }

    public Task getTask() {
        return task;
    }

    public String getTitle() {
        return title;
    }

    public AppUser getAssignee() {
        return assignee;
    }

    public boolean isDone() {
        return done;
    }

    public Instant getDoneAt() {
        return doneAt;
    }
}
