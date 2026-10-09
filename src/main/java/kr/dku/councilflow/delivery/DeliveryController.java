package kr.dku.councilflow.delivery;

import jakarta.validation.Valid;
import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.web.IfMatch;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class DeliveryController {

    private final DeliveryService deliveryService;

    DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /** 승인된 결정으로 다른 그룹들에 업무 요청(보내는 그룹의 owner) */
    @PostMapping("/api/decisions/{decisionId}/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    DeliveryService.DecisionDeliveries send(CurrentUser user, @PathVariable long decisionId,
                                            @Valid @RequestBody DeliveryService.CreateDeliveries request) {
        return deliveryService.send(user, decisionId, request);
    }

    /** 대상별 상태와 집계(보낸 조직 소속만) */
    @GetMapping("/api/decisions/{decisionId}/delivery-summary")
    DeliveryService.DecisionDeliveries summary(CurrentUser user, @PathVariable long decisionId) {
        return deliveryService.summary(user, decisionId);
    }

    /** 수신함(받은 조직 소속만) */
    @GetMapping("/api/orgs/{orgId}/inbox")
    List<DeliveryViews.DeliveryView> inbox(CurrentUser user, @PathVariable long orgId) {
        return deliveryService.inbox(user, orgId);
    }

    /** 보낸 요청을 결정별로(보낸 조직 소속만) */
    @GetMapping("/api/orgs/{orgId}/sent-deliveries")
    List<DeliveryService.SentDecision> sent(CurrentUser user, @PathVariable long orgId) {
        return deliveryService.sent(user, orgId);
    }

    /** 수락·확인 요청·해당 없음·시작·답변·취소·담당 부서·기한 (If-Match: version) */
    @PatchMapping("/api/deliveries/{deliveryId}")
    DeliveryViews.DeliveryView update(CurrentUser user, @PathVariable long deliveryId,
                                      @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                      @RequestBody JsonNode body) {
        return deliveryService.update(user, deliveryId, ifMatch, body);
    }

    /** 결과 제출(받은 조직). 링크나 설명 중 하나 이상. 제출물은 고치지 않고 새로 낸다. */
    @PostMapping("/api/deliveries/{deliveryId}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    DeliveryViews.DeliveryView submit(CurrentUser user, @PathVariable long deliveryId,
                                      @Valid @RequestBody DeliveryService.CreateSubmission request) {
        return deliveryService.submit(user, deliveryId, request);
    }

    /** 제출물 확정·반려(보낸 그룹의 회장) */
    @PostMapping("/api/submissions/{submissionId}/review")
    DeliveryViews.DeliveryView review(CurrentUser user, @PathVariable long submissionId,
                                      @Valid @RequestBody DeliveryService.Review request) {
        return deliveryService.review(user, submissionId, request);
    }
}
