package com.zorrodev.bpm.rest.error;

import com.zorrodev.bpm.contract.exception.VariableMappingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link VariableMappingException} with a body, so that the caller learns which output of
 * which element rejected the completion: the {@code 422} alone does not say what to fix.
 *
 * <p>Deliberately narrow, like {@link InvalidQueryExceptionHandler}: bound to this module's
 * controllers and to exactly one exception type.
 */
@RestControllerAdvice(basePackages = "com.zorrodev.bpm.rest")
public class VariableMappingExceptionHandler {

    @ExceptionHandler(VariableMappingException.class)
    public ProblemDetail handleVariableMapping(VariableMappingException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
    }
}
