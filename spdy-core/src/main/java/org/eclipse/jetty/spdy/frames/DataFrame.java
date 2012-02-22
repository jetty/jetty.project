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

import org.eclipse.jetty.spdy.api.DataInfo;

public class DataFrame
{
    public static final int HEADER_LENGTH = 8;

    private final int streamId;
    private final byte flags;
    private final int length;

    public DataFrame(int streamId, byte flags, int length)
    {
        this.streamId = streamId;
        this.flags = flags;
        this.length = length;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public byte getFlags()
    {
        return flags;
    }

    public int getLength()
    {
        return length;
    }

    public boolean isClose()
    {
        return (flags & DataInfo.FLAG_CLOSE) == DataInfo.FLAG_CLOSE;
    }

    public boolean isCompress()
    {
        return (flags & DataInfo.FLAG_COMPRESS) == DataInfo.FLAG_COMPRESS;
    }

    @Override
    public String toString()
    {
        return String.format("DATA frame stream=%d length=%d close=%b compress=%b", getStreamId(), getLength(), isClose(), isCompress());
    }
}
