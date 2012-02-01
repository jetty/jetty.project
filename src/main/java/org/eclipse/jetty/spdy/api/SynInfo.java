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

public class SynInfo
{
    public static final byte FLAG_FIN = 1;
    public static final byte FLAG_UNIDIRECTIONAL = 2;

    private final boolean close;
    private final boolean unidirectional;
    private final int associatedStreamId;
    private final byte priority;
    private final Headers headers;

    public SynInfo(boolean close)
    {
        this(new Headers(), close);
    }

    public SynInfo(Headers headers, boolean close)
    {
        this(headers, close, false, 0, (byte)0);
    }

    public SynInfo(Headers headers, boolean close, boolean unidirectional, int associatedStreamId, byte priority)
    {
        this.close = close;
        this.unidirectional = unidirectional;
        this.associatedStreamId = associatedStreamId;
        this.priority = priority;
        this.headers = headers;
    }

    public boolean isClose()
    {
        return close;
    }

    public boolean isUnidirectional()
    {
        return unidirectional;
    }

    public int getAssociatedStreamId()
    {
        return associatedStreamId;
    }

    public byte getPriority()
    {
        return priority;
    }

    public Headers getHeaders()
    {
        return headers;
    }

    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_FIN : 0;
        flags += isUnidirectional() ? FLAG_UNIDIRECTIONAL : 0;
        return flags;
    }
}
