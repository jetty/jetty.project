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

package org.eclipse.jetty.tls;

public class TLSException extends RuntimeException
{
    private final Alert alert;

    public TLSException(Alert alert)
    {
        this.alert = alert;
    }

    public TLSException(Alert alert, String message)
    {
        super(message);
        this.alert = alert;
    }

    public TLSException(Alert alert, String message, Throwable cause)
    {
        super(message, cause);
        this.alert = alert;
    }

    public TLSException(Alert alert, Throwable cause)
    {
        super(cause);
        this.alert = alert;
    }

    public enum Alert
    {
        CLOSE_NOTIFY(0),
        UNEXPECTED_MESSAGE(10),
        BAD_RECORD_MAC(20),
        RECORD_OVERFLOW(22),
        HANDSHAKE_FAILURE(40),
        BAD_CERTIFICATE(42),
        UNSUPPORTED_CERTIFICATE(43),
        CERTIFICATE_REVOKED(44),
        CERTIFICATE_EXPIRED(45),
        CERTIFICATE_UNKNOWN(46),
        ILLEGAL_PARAMETER(47),
        UNKNOWN_CA(48),
        ACCESS_DENIED(49),
        DECODE_ERROR(50),
        DECRYPT_ERROR(51),
        PROTOCOL_VERSION(70),
        INSUFFICIENT_SECURITY(71),
        INTERNAL_ERROR(80),
        INAPPROPRIATE_FALLBACK(86),
        USER_CANCELED(90),
        MISSING_EXTENSION(109),
        UNSUPPORTED_EXTENSION(110),
        UNRECOGNIZED_NAME(112),
        BAD_CERTIFICATE_STATUS_RESPONSE(113),
        UNKNOWN_PSK_IDENTITY(115),
        CERTIFICATE_REQUIRED(116),
        NO_APPLICATION_PROTOCOL(120);

        private final int code;

        Alert(int code)
        {
            this.code = code;
        }

        public int code()
        {
            return code;
        }
    }
}
