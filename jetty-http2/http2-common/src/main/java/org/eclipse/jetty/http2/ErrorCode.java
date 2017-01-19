//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2;

import java.util.HashMap;
import java.util.Map;

/**
 * Standard HTTP/2 error codes.
 */
public enum ErrorCode
{
    /**
     * Indicates no errors.
     */
    NO_ERROR(0),
    /**
     * Indicates a generic HTTP/2 protocol violation.
     */
    PROTOCOL_ERROR(1),
    /**
     * Indicates an internal error.
     */
    INTERNAL_ERROR(2),
    /**
     * Indicates a HTTP/2 flow control violation.
     */
    FLOW_CONTROL_ERROR(3),
    /**
     * Indicates that a SETTINGS frame did not receive a reply in a timely manner.
     */
    SETTINGS_TIMEOUT_ERROR(4),
    /**
     * Indicates that a stream frame has been received after the stream was closed.
     */
    STREAM_CLOSED_ERROR(5),
    /**
     * Indicates that a frame has an invalid length.
     */
    FRAME_SIZE_ERROR(6),
    /**
     * Indicates that a stream was rejected before application processing.
     */
    REFUSED_STREAM_ERROR(7),
    /**
     * Indicates that a stream is no longer needed.
     */
    CANCEL_STREAM_ERROR(8),
    /**
     * Indicates inability to maintain the HPACK compression context.
     */
    COMPRESSION_ERROR(9),
    /**
     * Indicates that the connection established by a HTTP CONNECT was abnormally closed.
     */
    HTTP_CONNECT_ERROR(10),
    /**
     * Indicates that the other peer might be generating excessive load.
     */
    ENHANCE_YOUR_CALM_ERROR(11),
    /**
     * Indicates that the transport properties do not meet minimum security requirements.
     */
    INADEQUATE_SECURITY_ERROR(12),
    /**
     * Indicates that HTTP/1.1 must be used rather than HTTP/2.
     */
    HTTP_1_1_REQUIRED_ERROR(13);

    public final int code;

    private ErrorCode(int code)
    {
        this.code = code;
        Codes.codes.put(code, this);
    }

    public static ErrorCode from(int error)
    {
        return Codes.codes.get(error);
    }

    private static class Codes
    {
        private static final Map<Integer, ErrorCode> codes = new HashMap<>();
    }
}
