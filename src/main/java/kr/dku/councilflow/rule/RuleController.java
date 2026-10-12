package kr.dku.councilflow.rule;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.common.web.IfMatch;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/** 규정 등록·검수·확정. 보기는 그룹 구성원, 바꾸기는 그 그룹 회장. */
@RestController
class RuleController {

    private final RuleService rules;

    RuleController(RuleService rules) {
        this.rules = rules;
    }

    /**
     * 규정 올리기(multipart): title, issuer, appliesTo?, effectiveDate?(2026-03-01), docVersion?, notes?,
     * bodyStartLine?, 그리고 file(PDF·TXT) 또는 text 중 하나. 결과는 DRAFT 이며 사람이 검수해야 쓸 수 있다.
     */
    @PostMapping(value = "/api/orgs/{orgId}/rule-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    RuleService.DocDetail register(CurrentUser user, @PathVariable long orgId,
                                   @RequestParam String title,
                                   @RequestParam String issuer,
                                   @RequestParam(required = false) String appliesTo,
                                   @RequestParam(required = false) String effectiveDate,
                                   @RequestParam(required = false) String docVersion,
                                   @RequestParam(required = false) String notes,
                                   @RequestParam(required = false) String bodyStartLine,
                                   @RequestParam(required = false) String text,
                                   @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        LocalDate date = null;
        if (effectiveDate != null && !effectiveDate.isBlank()) {
            try {
                date = LocalDate.parse(effectiveDate.strip());
            } catch (DateTimeParseException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "시행일은 2026-03-01 모양으로 입력해 주세요.");
            }
        }
        byte[] bytes = file == null ? null : file.getBytes();
        String fileName = file == null ? null : file.getOriginalFilename();
        return rules.register(user, orgId, new RuleService.RegisterRequest(title, issuer, appliesTo, date,
                docVersion, notes, bodyStartLine, bytes, fileName, text));
    }

    @GetMapping("/api/orgs/{orgId}/rule-documents")
    List<RuleService.DocSummary> list(CurrentUser user, @PathVariable long orgId) {
        return rules.list(user, orgId);
    }

    @GetMapping("/api/rule-documents/{docId}")
    RuleService.DocDetail get(CurrentUser user, @PathVariable long docId) {
        return rules.get(user, docId);
    }

    /** 문서 정보 수정 { title?, issuer?, appliesTo?, effectiveDate?, docVersion?, notes? } (If-Match: 문서 version) */
    @PatchMapping("/api/rule-documents/{docId}")
    RuleService.DocSummary update(CurrentUser user, @PathVariable long docId,
                                  @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                  @RequestBody JsonNode body) {
        return rules.updateMetadata(user, docId, ifMatch, body);
    }

    /** 확정 (If-Match: 문서 version). 모든 조·보칙·부칙이 검수 완료여야 한다. */
    @PostMapping("/api/rule-documents/{docId}/confirm")
    RuleService.DocSummary confirm(CurrentUser user, @PathVariable long docId,
                                   @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch) {
        return rules.confirm(user, docId, ifMatch);
    }

    @PostMapping("/api/rule-documents/{docId}/retire")
    RuleService.DocSummary retire(CurrentUser user, @PathVariable long docId,
                                  @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch) {
        return rules.retire(user, docId, ifMatch);
    }

    /** 확정 전 규정 지우기 */
    @DeleteMapping("/api/rule-documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentUser user, @PathVariable long docId) {
        rules.deleteDraft(user, docId);
    }

    /** 조항 직접 추가 { afterClauseId?, kind?, label, title?, text } */
    @PostMapping("/api/rule-documents/{docId}/clauses")
    @ResponseStatus(HttpStatus.CREATED)
    RuleService.ClauseView addClause(CurrentUser user, @PathVariable long docId, @RequestBody JsonNode body) {
        return rules.addClause(user, docId, body);
    }

    /** 선택한 조항을 한꺼번에 검수 완료로 { clauseIds: [..] } → { reviewed: 개수 } */
    @PostMapping("/api/rule-documents/{docId}/clauses/review")
    Map<String, Integer> reviewClauses(CurrentUser user, @PathVariable long docId,
                                       @RequestBody ReviewRequest request) {
        return Map.of("reviewed", rules.reviewClauses(user, docId, request.clauseIds()));
    }

    /** 조항 수정 { label?, title?, text?, reviewed? } (If-Match: 조항 version) */
    @PatchMapping("/api/clauses/{clauseId}")
    RuleService.ClauseView updateClause(CurrentUser user, @PathVariable long clauseId,
                                        @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                        @RequestBody JsonNode body) {
        return rules.updateClause(user, clauseId, ifMatch, body);
    }

    @DeleteMapping("/api/clauses/{clauseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteClause(CurrentUser user, @PathVariable long clauseId) {
        rules.deleteClause(user, clauseId);
    }

    record ReviewRequest(List<Long> clauseIds) {
    }
}
