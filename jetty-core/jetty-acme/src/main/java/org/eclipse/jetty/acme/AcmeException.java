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
    private final String type;
    private final int statusCode;

    /**
     * Creates a new AcmeException with a message.
     *
     * @param message the error message
     */
    public AcmeException(String message)
    {
        this(message, null, 0, null);
    }

    /**
     * Creates a new AcmeException with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public AcmeException(String message, Throwable cause)
    {
        this(message, null, 0, cause);
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
        this(message, type, statusCode, null);
    }

    /**
     * Creates a new AcmeException with full details.
     *
     * @param message the error message
     * @param type the ACME error type URN
     * @param statusCode the HTTP status code
     * @param cause the underlying cause
     */
    public AcmeException(String message, String type, int statusCode, Throwable cause)
    {
        super(message, cause);
        this.type = type;
        this.statusCode = statusCode;
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

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName()).append(": ").append(getMessage());
        if (type != null)
            sb.append(" [type=").append(type).append("]");
        if (statusCode > 0)
            sb.append(" [status=").append(statusCode).append("]");
        return sb.toString();
    }
}
