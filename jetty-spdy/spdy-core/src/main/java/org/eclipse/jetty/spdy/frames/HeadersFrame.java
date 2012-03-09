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

package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;

public class HeadersFrame extends ControlFrame
{
    private final int streamId;
    private final Headers headers;

    public HeadersFrame(short version, byte flags, int streamId, Headers headers)
    {
        super(version, ControlFrameType.HEADERS, flags);
        this.streamId = streamId;
        this.headers = headers;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public Headers getHeaders()
    {
        return headers;
    }

    public boolean isClose()
    {
        return (getFlags() & HeadersInfo.FLAG_CLOSE) == HeadersInfo.FLAG_CLOSE;
    }

    public boolean isResetCompression()
    {
        return (getFlags() & HeadersInfo.FLAG_RESET_COMPRESSION) == HeadersInfo.FLAG_RESET_COMPRESSION;
    }

    @Override
    public String toString()
    {
        return String.format("%s stream=%d close=%b reset_compression=%b", super.toString(), getStreamId(), isClose(), isResetCompression());
    }
}
