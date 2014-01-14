//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.spdy.api;

/**
 * <p>A container for GOAWAY frames metadata: the last good stream id and
 * the session status.</p>
 */
public class GoAwayResultInfo
{
    private final int lastStreamId;
    private final SessionStatus sessionStatus;

    /**
     * <p>Creates a new {@link GoAwayResultInfo} with the given last good stream id and session status</p>
     *
     * @param lastStreamId  the last good stream id
     * @param sessionStatus the session status
     */
    public GoAwayResultInfo(int lastStreamId, SessionStatus sessionStatus)
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
