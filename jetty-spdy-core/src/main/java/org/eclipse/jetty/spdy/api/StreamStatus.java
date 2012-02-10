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

public enum StreamStatus
{
    PROTOCOL_ERROR(1, 1),
    INVALID_STREAM(2, 2),
    REFUSED_STREAM(3, 3),
    UNSUPPORTED_VERSION(4, 4),
    CANCEL_STREAM(5, 5),
    INTERNAL_ERROR(6, -1),
    FLOW_CONTROL_ERROR(7, 6),
    STREAM_IN_USE(-1, 7),
    STREAM_ALREADY_CLOSED(-1, 8);

    public static StreamStatus from(short version, int code)
    {
        switch (version)
        {
            case SPDY.V2:
                return Mapper.v2Codes.get(code);
            case SPDY.V3:
                return Mapper.v3Codes.get(code);
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
            Mapper.v2Codes.put(v2Code, this);
        this.v3Code = v3Code;
        if (v3Code >= 0)
            Mapper.v3Codes.put(v3Code, this);
    }

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

    private static class Mapper
    {
        private static final Map<Integer, StreamStatus> v2Codes = new HashMap<>();
        private static final Map<Integer, StreamStatus> v3Codes = new HashMap<>();
    }
}
