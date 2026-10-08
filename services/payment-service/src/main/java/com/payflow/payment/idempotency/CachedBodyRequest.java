package com.payflow.payment.idempotency;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reads the whole body before the controller does, so it can be fingerprinted, then serves the
 * same bytes to the controller. Spring's ContentCachingRequestWrapper cannot do this: it only
 * records what downstream code has already read.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    static CachedBodyRequest read(HttpServletRequest request, int maxBytes) throws IOException {
        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw new PayFlowException(ErrorCode.VALIDATION_FAILED,
                    "Request body exceeds " + maxBytes + " bytes");
        }
        return new CachedBodyRequest(request, body);
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return source.read(buffer, offset, length);
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Asynchronous reads are not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        return encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
    }
}
