/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
