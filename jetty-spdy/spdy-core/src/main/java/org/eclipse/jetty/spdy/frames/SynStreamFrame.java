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
import org.eclipse.jetty.spdy.api.SynInfo;

public class SynStreamFrame extends ControlFrame
{
    private final int streamId;
    private final int associatedStreamId;
    private final byte priority;
    private final Headers headers;

    public SynStreamFrame(short version, byte flags, int streamId, int associatedStreamId, byte priority, Headers headers)
    {
        super(version, ControlFrameType.SYN_STREAM, flags);
        this.streamId = streamId;
        this.associatedStreamId = associatedStreamId;
        this.priority = priority;
        this.headers = headers;
    }

    public int getStreamId()
    {
        return streamId;
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

    public boolean isClose()
    {
        return (getFlags() & SynInfo.FLAG_CLOSE) == SynInfo.FLAG_CLOSE;
    }

    public boolean isUnidirectional()
    {
        return (getFlags() & SynInfo.FLAG_UNIDIRECTIONAL) == SynInfo.FLAG_UNIDIRECTIONAL;
    }

    @Override
    public String toString()
    {
        return String.format("%s stream=%d close=%b", super.toString(), getStreamId(), isClose());
    }
}
