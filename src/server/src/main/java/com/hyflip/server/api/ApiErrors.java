package com.hyflip.server.api;

import com.hyflip.server.flipper.forge.ForgeException;
import com.hyflip.server.journal.TradeJournal;
import com.hyflip.server.profile.ProfileLinkException;
import com.hyflip.server.settings.SettingsException;
import com.hyflip.server.storage.DatabaseTransfer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Error bodies as RFC 9457 problem details ({@code type, title, status, detail, instance}), for
 * rejected requests and Spring MVC errors alike.
 */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ForgeException.class)
    ProblemDetail forge(ForgeException e) {
        HttpStatus status = switch (e.kind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    @ExceptionHandler(SettingsException.class)
    ProblemDetail settings(SettingsException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setProperty("problems", e.problems());
        return p;
    }

    @ExceptionHandler(ProfileLinkException.class)
    ProblemDetail profile(ProfileLinkException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_CONFIGURED, KEY_REJECTED -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setProperty("kind", e.kind().name());
        return p;
    }

    @ExceptionHandler(com.hyflip.server.icons.PackIcons.IconException.class)
    ProblemDetail icons(com.hyflip.server.icons.PackIcons.IconException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DatabaseTransfer.TransferException.class)
    ProblemDetail transfer(DatabaseTransfer.TransferException e) {
        return ProblemDetail.forStatusAndDetail(e.busy() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(TradeJournal.TradeException.class)
    ProblemDetail trade(TradeJournal.TradeException e) {
        return ProblemDetail.forStatusAndDetail(e.notFound() ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                e.getMessage());
    }
}
