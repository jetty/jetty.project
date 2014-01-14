//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.api;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>An enumeration of session statuses.</p>
 */
public enum SessionStatus
{
    /**
     * <p>The session status indicating no errors</p>
     */
    OK(0),
    /**
     * <p>The session status indicating a protocol error</p>
     */
    PROTOCOL_ERROR(1);

    /**
     * @param code the session status code
     * @return a {@link SessionStatus} from the given code,
     * or null if no status exists
     */
    public static SessionStatus from(int code)
    {
        return Codes.codes.get(code);
    }

    private final int code;

    private SessionStatus(int code)
    {
        this.code = code;
        Codes.codes.put(code, this);
    }

    /**
     * @return the code of this {@link SessionStatus}
     */
    public int getCode()
    {
        return code;
    }

    private static class Codes
    {
        private static final Map<Integer, SessionStatus> codes = new HashMap<>();
    }
}
