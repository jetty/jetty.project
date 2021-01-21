//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.events;

import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.test.Timeouts;

@SuppressWarnings("serial")
public class EventCapture extends LinkedBlockingQueue<String>
{
    private static final Logger LOG = Log.getLogger(EventCapture.class);

    public void offer(String format, Object... args)
    {
        String msg = String.format(format, args);
        if (LOG.isDebugEnabled())
            LOG.debug("EVENT: {}", msg);
        super.offer(msg);
    }

    public String q(String str)
    {
        if (str == null)
        {
            return "<null>";
        }
        return '"' + str + '"';
    }

    public String safePoll() throws InterruptedException
    {
        return poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
    }
}
