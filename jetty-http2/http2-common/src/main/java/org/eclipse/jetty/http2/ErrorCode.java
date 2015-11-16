//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

public enum ErrorCode
{
    NO_ERROR(0),
    PROTOCOL_ERROR(1),
    INTERNAL_ERROR(2),
    FLOW_CONTROL_ERROR(3),
    SETTINGS_TIMEOUT_ERROR(4),
    STREAM_CLOSED_ERROR(5),
    FRAME_SIZE_ERROR(6),
    REFUSED_STREAM_ERROR(7),
    CANCEL_STREAM_ERROR(8),
    COMPRESSION_ERROR(9),
    HTTP_CONNECT_ERROR(10),
    ENHANCE_YOUR_CALM_ERROR(11),
    INADEQUATE_SECURITY_ERROR(12),
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
