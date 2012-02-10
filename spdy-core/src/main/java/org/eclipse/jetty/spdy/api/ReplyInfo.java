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

public class ReplyInfo
{
    public static final byte FLAG_FIN = 1;

    private final Headers headers;
    private final boolean close;

    public ReplyInfo(boolean close)
    {
        this(new Headers(), close);
    }

    public ReplyInfo(Headers headers, boolean close)
    {
        this.headers = headers;
        this.close = close;
    }

    public Headers getHeaders()
    {
        return headers;
    }

    public boolean isClose()
    {
        return close;
    }

    public byte getFlags()
    {
        return isClose() ? FLAG_FIN : 0;
    }

    @Override
    public String toString()
    {
        return String.format("REPLY close=%b %s", close, headers);
    }
}
