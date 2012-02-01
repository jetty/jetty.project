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

public class HeadersInfo
{
    public static final byte FLAG_FIN = 1;
    public static final byte FLAG_RESET_COMPRESSION = 2;

    private final boolean close;
    private final boolean resetCompression;
    private final Headers headers;

    public HeadersInfo(Headers headers, boolean close)
    {
        this(headers, close, false);
    }

    public HeadersInfo(Headers headers, boolean close, boolean resetCompression)
    {
        this.headers = headers;
        this.close = close;
        this.resetCompression = resetCompression;
    }

    public boolean isClose()
    {
        return close;
    }

    public boolean isResetCompression()
    {
        return resetCompression;
    }

    public Headers getHeaders()
    {
        return headers;
    }

    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_FIN : 0;
        flags += isResetCompression() ? FLAG_RESET_COMPRESSION : 0;
        return flags;
    }
}
