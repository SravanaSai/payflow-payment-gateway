package com.payflow.common.core.result;

import com.payflow.common.core.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    @Test
    void successHoldsValue() {
        Result<String> result = Result.success("ok");
        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
        assertEquals("ok", result.getValue());
    }

    @Test
    void failureHoldsErrorCode() {
        Result<String> result = Result.failure(ErrorCode.PAYMENT_NOT_FOUND);
        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
        assertEquals(ErrorCode.PAYMENT_NOT_FOUND, result.getErrorCode());
    }

    @Test
    void cannotGetValueFromFailure() {
        Result<String> result = Result.failure(ErrorCode.INTERNAL_ERROR);
        assertThrows(IllegalStateException.class, result::getValue);
    }

    @Test
    void mapTransformsSuccess() {
        Result<Integer> result = Result.success(2).map(n -> n * 3);
        assertEquals(6, result.getValue());
    }
}
