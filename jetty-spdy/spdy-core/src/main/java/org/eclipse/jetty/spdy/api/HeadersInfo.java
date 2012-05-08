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
 * <p>A container for HEADERS frame metadata and headers.</p>
 */
public class HeadersInfo
{
    /**
     * <p>Flag that indicates that this {@link HeadersInfo} is the last frame in the stream.</p>
     *
     * @see #isClose()
     * @see #getFlags()
     */
    public static final byte FLAG_CLOSE = 1;
    /**
     * <p>Flag that indicates that the compression of the stream must be reset.</p>
     *
     * @see #isResetCompression()
     * @see #getFlags()
     */
    public static final byte FLAG_RESET_COMPRESSION = 2;

    private final boolean close;
    private final boolean resetCompression;
    private final Headers headers;

    /**
     * <p>Creates a new {@link HeadersInfo} instance with the given headers,
     * the given close flag and no reset compression flag</p>
     *
     * @param headers the {@link Headers}
     * @param close the value of the close flag
     */
    public HeadersInfo(Headers headers, boolean close)
    {
        this(headers, close, false);
    }

    /**
     * <p>Creates a new {@link HeadersInfo} instance with the given headers,
     * the given close flag and the given reset compression flag</p>
     *
     * @param headers the {@link Headers}
     * @param close the value of the close flag
     * @param resetCompression the value of the reset compression flag
     */
    public HeadersInfo(Headers headers, boolean close, boolean resetCompression)
    {
        this.headers = headers;
        this.close = close;
        this.resetCompression = resetCompression;
    }

    /**
     * @return the value of the close flag
     */
    public boolean isClose()
    {
        return close;
    }

    /**
     * @return the value of the reset compression flag
     */
    public boolean isResetCompression()
    {
        return resetCompression;
    }

    /**
     * @return the {@link Headers}
     */
    public Headers getHeaders()
    {
        return headers;
    }

    /**
     * @return the close and reset compression flags as integer
     * @see #FLAG_CLOSE
     * @see #FLAG_RESET_COMPRESSION
     */
    public byte getFlags()
    {
        byte flags = isClose() ? FLAG_CLOSE : 0;
        flags += isResetCompression() ? FLAG_RESET_COMPRESSION : 0;
        return flags;
    }

    @Override
    public String toString()
    {
        return String.format("HEADER close=%b %s", close, headers);
    }
}
