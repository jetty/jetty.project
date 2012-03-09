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
 * <p>An enumeration of stream statuses.</p>
 */
public enum StreamStatus
{
    /**
     * <p>The stream status indicating a protocol error</p>
     */
    PROTOCOL_ERROR(1, 1),
    /**
     * <p>The stream status indicating that the stream is not valid</p>
     */
    INVALID_STREAM(2, 2),
    /**
     * <p>The stream status indicating that the stream has been refused</p>
     */
    REFUSED_STREAM(3, 3),
    /**
     * <p>The stream status indicating that the implementation does not support the SPDY version of the stream</p>
     */
    UNSUPPORTED_VERSION(4, 4),
    /**
     * <p>The stream status indicating that the stream is no longer needed</p>
     */
    CANCEL_STREAM(5, 5),
    /**
     * <p>The stream status indicating an implementation error</p>
     */
    INTERNAL_ERROR(6, 6),
    /**
     * <p>The stream status indicating a flow control error</p>
     */
    FLOW_CONTROL_ERROR(7, 7),
    /**
     * <p>The stream status indicating a stream opened more than once</p>
     */
    STREAM_IN_USE(-1, 8),
    /**
     * <p>The stream status indicating data on a stream already closed</p>
     */
    STREAM_ALREADY_CLOSED(-1, 9),
    /**
     * <p>The stream status indicating credentials not valid</p>
     */
    INVALID_CREDENTIALS(-1, 10),
    /**
     * <p>The stream status indicating that the implementation could not support a frame too large</p>
     */
    FRAME_TOO_LARGE(-1, 11);

    /**
     * @param version the SPDY protocol version
     * @param code the stream status code
     * @return a {@link StreamStatus} from the given version and code,
     * or null if no such status exists
     */
    public static StreamStatus from(short version, int code)
    {
        switch (version)
        {
            case SPDY.V2:
                return Codes.v2Codes.get(code);
            case SPDY.V3:
                return Codes.v3Codes.get(code);
            default:
                throw new IllegalStateException();
        }
    }

    private final int v2Code;
    private final int v3Code;

    private StreamStatus(int v2Code, int v3Code)
    {
        this.v2Code = v2Code;
        if (v2Code >= 0)
            Codes.v2Codes.put(v2Code, this);
        this.v3Code = v3Code;
        if (v3Code >= 0)
            Codes.v3Codes.put(v3Code, this);
    }

    /**
     * @param version the SPDY protocol version
     * @return the stream status code
     */
    public int getCode(short version)
    {
        switch (version)
        {
            case SPDY.V2:
                return v2Code;
            case SPDY.V3:
                return v3Code;
            default:
                throw new IllegalStateException();
        }
    }

    private static class Codes
    {
        private static final Map<Integer, StreamStatus> v2Codes = new HashMap<>();
        private static final Map<Integer, StreamStatus> v3Codes = new HashMap<>();
    }
}
