package kr.dku.councilflow.common.error;

import java.util.stream.Collectors;
import kr.dku.councilflow.storage.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 컨트롤러에서 나온 예외를 공통 오류 본문으로 바꾼다. 인증·인가 필터 단계의 오류는 JsonSecurityErrorHandler 가 맡는다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> handleApi(ApiException e) {
        return respond(e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        // 필드 이름과 규칙만 알려 준다. 사용자가 보낸 값은 메시지에 다시 넣지 않는다.
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return respond(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage() + " (" + detail + ")");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.defaultMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> handleTooLarge(MaxUploadSizeExceededException e) {
        return respond(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.defaultMessage());
    }

    /** DB 고유 제약에 걸림(동시에 같은 전달을 두 번 만들 때 등). 미리 확인하지만 마지막 방어선이다. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> handleDuplicate(DataIntegrityViolationException e) {
        return respond(ErrorCode.DUPLICATE, ErrorCode.DUPLICATE.defaultMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ErrorBody> handleVersionConflict(OptimisticLockingFailureException e) {
        return respond(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.defaultMessage());
    }

    /** 운영에서 녹음 저장소(버킷·키) 설정이 비어 있음. 앱은 쓰되 녹음 업로드만 막는다. */
    @ExceptionHandler(StorageUnavailableException.class)
    ResponseEntity<ErrorBody> handleStorageUnavailable(StorageUnavailableException e) {
        return respond(ErrorCode.STORAGE_UNAVAILABLE, ErrorCode.STORAGE_UNAVAILABLE.defaultMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> handleAccessDenied(AccessDeniedException e) {
        return respond(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> handleOther(Exception e) {
        // 스프링 MVC 기본 예외(404, 405 등)는 상태 코드를 그대로 따른다.
        if (e instanceof ErrorResponse springError) {
            HttpStatus status = HttpStatus.resolve(springError.getStatusCode().value());
            ErrorCode code = switch (status) {
                case NOT_FOUND -> ErrorCode.NOT_FOUND;
                case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
                case null, default -> status != null && status.is4xxClientError()
                        ? ErrorCode.BAD_REQUEST
                        : ErrorCode.INTERNAL_ERROR;
            };
            return ResponseEntity.status(springError.getStatusCode()).body(ErrorBody.of(code));
        }
        // 예외 메시지에 회의 원문이 섞일 수 있으므로 기본 로그에는 오류 종류만 남긴다.
        log.error("unhandled error type={}", e.getClass().getName());
        log.debug("unhandled error detail", e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private static ResponseEntity<ErrorBody> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ErrorBody.of(code, message));
    }
}
