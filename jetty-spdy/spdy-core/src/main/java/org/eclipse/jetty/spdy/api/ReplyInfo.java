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

/**
 * <p>A container for SYN_REPLY frames metadata and headers.</p>
 */
public class ReplyInfo
{
    /**
     * <p>Flag that indicates that this {@link ReplyInfo} is the last frame in the stream.</p>
     *
     * @see #isClose()
     * @see #getFlags()
     */
    public static final byte FLAG_CLOSE = 1;

    private final Headers headers;
    private final boolean close;

    /**
     * <p>Creates a new {@link ReplyInfo} instance with empty headers and the given close flag.</p>
     *
     * @param close the value of the close flag
     */
    public ReplyInfo(boolean close)
    {
        this(new Headers(), close);
    }

    /**
     * <p>Creates a {@link ReplyInfo} instance with the given headers and the given close flag.</p>
     *
     * @param headers the {@link Headers}
     * @param close the value of the close flag
     */
    public ReplyInfo(Headers headers, boolean close)
    {
        this.headers = headers;
        this.close = close;
    }

    /**
     * @return the {@link Headers}
     */
    public Headers getHeaders()
    {
        return headers;
    }

    /**
     * @return the value of the close flag
     */
    public boolean isClose()
    {
        return close;
    }

    /**
     * @return the close and reset compression flags as integer
     * @see #FLAG_CLOSE
     */
    public byte getFlags()
    {
        return isClose() ? FLAG_CLOSE : 0;
    }

    @Override
    public String toString()
    {
        return String.format("REPLY close=%b %s", close, headers);
    }
}
