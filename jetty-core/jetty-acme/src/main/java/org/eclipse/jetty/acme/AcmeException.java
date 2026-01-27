//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.acme;

/**
 * <p>Exception thrown when ACME protocol operations fail.</p>
 * <p>This exception wraps errors from ACME server responses,
 * network failures, or invalid protocol states.</p>
 */
public class AcmeException extends Exception
{
    /**
     * ACME error type for bad nonce errors.
     */
    public static final String BAD_NONCE = "urn:ietf:params:acme:error:badNonce";

    /**
     * ACME error type for rate limit errors.
     */
    public static final String RATE_LIMITED = "urn:ietf:params:acme:error:rateLimited";

    private final String type;
    private final int statusCode;
    private final String retryAfter;

    /**
     * Creates a new AcmeException with a message.
     *
     * @param message the error message
     */
    public AcmeException(String message)
    {
        this(message, null, 0, null, null);
    }

    /**
     * Creates a new AcmeException with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public AcmeException(String message, Throwable cause)
    {
        this(message, null, 0, null, cause);
    }

    /**
     * Creates a new AcmeException with ACME error details.
     *
     * @param message the error message
     * @param type the ACME error type URN (e.g., "urn:ietf:params:acme:error:malformed")
     * @param statusCode the HTTP status code from the ACME server
     */
    public AcmeException(String message, String type, int statusCode)
    {
        this(message, type, statusCode, null, null);
    }

    /**
     * Creates a new AcmeException with ACME error details and Retry-After.
     *
     * @param message the error message
     * @param type the ACME error type URN
     * @param statusCode the HTTP status code
     * @param retryAfter the Retry-After header value, or null
     */
    public AcmeException(String message, String type, int statusCode, String retryAfter)
    {
        this(message, type, statusCode, retryAfter, null);
    }

    /**
     * Creates a new AcmeException with full details.
     *
     * @param message the error message
     * @param type the ACME error type URN
     * @param statusCode the HTTP status code
     * @param retryAfter the Retry-After header value, or null
     * @param cause the underlying cause
     */
    public AcmeException(String message, String type, int statusCode, String retryAfter, Throwable cause)
    {
        super(message, cause);
        this.type = type;
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    /**
     * @return the ACME error type URN, or null if not an ACME protocol error
     */
    public String getType()
    {
        return type;
    }

    /**
     * @return the HTTP status code from the ACME server, or 0 if not applicable
     */
    public int getStatusCode()
    {
        return statusCode;
    }

    /**
     * @return the Retry-After header value from the ACME server, or null if not present
     */
    public String getRetryAfter()
    {
        return retryAfter;
    }

    /**
     * @return true if this is a badNonce error that can be retried with a fresh nonce
     */
    public boolean isBadNonce()
    {
        return BAD_NONCE.equals(type);
    }

    /**
     * @return true if this is a rate limit error
     */
    public boolean isRateLimited()
    {
        return RATE_LIMITED.equals(type) || statusCode == 429;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName()).append(": ").append(getMessage());
        if (type != null)
            sb.append(" [type=").append(type).append("]");
        if (statusCode > 0)
            sb.append(" [status=").append(statusCode).append("]");
        if (retryAfter != null)
            sb.append(" [retryAfter=").append(retryAfter).append("]");
        return sb.toString();
    }
}
