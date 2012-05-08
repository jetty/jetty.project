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
 * <p>A container for GOAWAY frames metadata: the last good stream id and
 * the session status.</p>
 */
public class GoAwayInfo
{
    private final int lastStreamId;
    private final SessionStatus sessionStatus;

    /**
     * <p>Creates a new {@link GoAwayInfo} with the given last good stream id and session status</p>
     *
     * @param lastStreamId  the last good stream id
     * @param sessionStatus the session status
     */
    public GoAwayInfo(int lastStreamId, SessionStatus sessionStatus)
    {
        this.lastStreamId = lastStreamId;
        this.sessionStatus = sessionStatus;
    }

    /**
     * @return the last good stream id
     */
    public int getLastStreamId()
    {
        return lastStreamId;
    }

    /**
     * @return the session status
     */
    public SessionStatus getSessionStatus()
    {
        return sessionStatus;
    }
}
