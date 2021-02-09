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

package org.eclipse.jetty.websocket.jsr356;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * @deprecated use {@code BlockingArrayQueue<String>} instead
 */
@Deprecated
public class MessageQueue extends BlockingArrayQueue<String>
{
    private static final Logger LOG = Log.getLogger(MessageQueue.class);

    public void awaitMessages(int expectedMessageCount, int timeoutDuration, TimeUnit timeoutUnit) throws TimeoutException
    {
        long msDur = TimeUnit.MILLISECONDS.convert(timeoutDuration, timeoutUnit);
        long now = System.currentTimeMillis();
        long expireOn = now + msDur;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Await Message.. Now: {} - expireOn: {} ({} ms)", now, expireOn, msDur);
        }

        while (this.size() < expectedMessageCount)
        {
            try
            {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            catch (InterruptedException gnore)
            {
                /* ignore */
            }
            if (!LOG.isDebugEnabled() && (System.currentTimeMillis() > expireOn) && this.size() < expectedMessageCount)
            {
                throw new TimeoutException(String.format("Timeout reading all %d expected messages. (managed to only read %d messages)", expectedMessageCount,
                    this.size()));
            }
        }
    }
}
