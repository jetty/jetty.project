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

import org.eclipse.jetty.spdy.api.SessionStatus;

public class GoAwayFrame extends ControlFrame
{
    private final int lastStreamId;
    private final int statusCode;

    public GoAwayFrame(short version, int lastStreamId, int statusCode)
    {
        super(version, ControlFrameType.GO_AWAY, (byte)0);
        this.lastStreamId = lastStreamId;
        this.statusCode = statusCode;
    }

    public int getLastStreamId()
    {
        return lastStreamId;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    @Override
    public String toString()
    {
        SessionStatus sessionStatus = SessionStatus.from(getStatusCode());
        return String.format("%s last_stream=%d status=%s", super.toString(), getLastStreamId(), sessionStatus == null ? getStatusCode() : sessionStatus);
    }
}
