package kr.dku.councilflow.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import kr.dku.councilflow.common.BaseEntity;

/**
 * 규정 조각 하나. 조(ARTICLE)뿐 아니라 장·절 제목, 보칙, 부칙, 머리말도 순서대로 담아
 * 모두 이어 붙이면 원문이 된다. 지출 검토에서 찾는 대상은 확정된 문서의 ARTICLE·EXTRA·SUPPLEMENTARY 이다.
 * text 는 추출한 글자 그대로이며, 사람이 고쳤다면 edited = true 이다.
 */
@Entity
@Table(name = "clause")
public class Clause extends BaseEntity {

    @Column(nullable = false)
    private Long docId;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleTextSplitter.Kind kind;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(name = "article_no")
    private Integer articleNo;

    private Integer branchNumber;

    @Column(length = 300)
    private String title;

    @Column(length = 200)
    private String chapter;

    @Column(name = "section_name", length = 200)
    private String sectionName;

    @Column(nullable = false, columnDefinition = "mediumtext")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClauseReviewStatus reviewStatus = ClauseReviewStatus.UNREVIEWED;

    @Column(nullable = false)
    private boolean edited;

    @Column(length = 1000)
    private String flags;

    protected Clause() {
    }

    public Clause(Long docId, int seq, RuleTextSplitter.Kind kind, String label, Integer number, Integer branchNumber,
                  String title, String chapter, String sectionName, String text, List<String> flags) {
        this.docId = docId;
        this.seq = seq;
        this.kind = kind;
        this.label = label;
        this.articleNo = number;
        this.branchNumber = branchNumber;
        this.title = title;
        this.chapter = chapter;
        this.sectionName = sectionName;
        this.text = text;
        this.flags = flags == null || flags.isEmpty() ? null : String.join("\n", flags);
        // 검색 대상이 아닌 조각(장·절 제목, 머리말)은 검수할 것이 없다.
        if (kind == RuleTextSplitter.Kind.CHAPTER || kind == RuleTextSplitter.Kind.SECTION
                || kind == RuleTextSplitter.Kind.PREAMBLE) {
            this.reviewStatus = ClauseReviewStatus.REVIEWED;
        }
    }

    /** 사람이 라벨·제목·본문을 고친다. 고치면 다시 검수해야 하므로 UNREVIEWED 로 돌아간다. */
    public void edit(String label, String title, String text) {
        boolean textChanged = !this.text.equals(text);
        boolean changed = textChanged || !this.label.equals(label) || !java.util.Objects.equals(this.title, title);
        this.label = label;
        this.title = title;
        this.text = text;
        if (textChanged) {
            this.edited = true;
        }
        if (changed) {
            this.reviewStatus = ClauseReviewStatus.UNREVIEWED;
        }
    }

    /** 사람이 직접 만든 조항임을 표시한다. */
    public void markEdited() {
        this.edited = true;
    }

    public void markReviewed() {
        this.reviewStatus = ClauseReviewStatus.REVIEWED;
    }

    public void markUnreviewed() {
        this.reviewStatus = ClauseReviewStatus.UNREVIEWED;
    }

    public void moveTo(int seq) {
        this.seq = seq;
    }

    /** 사람이 검수해야 하는 종류인가(조·보칙·부칙). */
    public boolean needsReview() {
        return kind == RuleTextSplitter.Kind.ARTICLE || kind == RuleTextSplitter.Kind.EXTRA
                || kind == RuleTextSplitter.Kind.SUPPLEMENTARY;
    }

    public List<String> flagList() {
        return flags == null ? List.of() : Arrays.asList(flags.split("\n"));
    }

    public Long getDocId() { return docId; }
    public int getSeq() { return seq; }
    public RuleTextSplitter.Kind getKind() { return kind; }
    public String getLabel() { return label; }
    public Integer getArticleNo() { return articleNo; }
    public Integer getBranchNumber() { return branchNumber; }
    public String getTitle() { return title; }
    public String getChapter() { return chapter; }
    public String getSectionName() { return sectionName; }
    public String getText() { return text; }
    public ClauseReviewStatus getReviewStatus() { return reviewStatus; }
    public boolean isEdited() { return edited; }
}
