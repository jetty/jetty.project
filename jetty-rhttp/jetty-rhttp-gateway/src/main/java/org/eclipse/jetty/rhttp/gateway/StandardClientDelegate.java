//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.gateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>Default implementation of {@link ClientDelegate}.</p>
 *
 * @version $Revision$ $Date$
 */
public class StandardClientDelegate implements ClientDelegate
{
    private final Logger logger = Log.getLogger(getClass().toString());
    private final Object lock = new Object();
    private final List<RHTTPRequest> requests = new ArrayList<RHTTPRequest>();
    private final String targetId;
    private volatile boolean firstFlush = true;
    private volatile long timeout;
    private volatile boolean closed;
    private Continuation continuation;

    public StandardClientDelegate(String targetId)
    {
        this.targetId = targetId;
    }

    public String getTargetId()
    {
        return targetId;
    }

    public long getTimeout()
    {
        return timeout;
    }

    public void setTimeout(long timeout)
    {
        this.timeout = timeout;
    }

    public boolean enqueue(RHTTPRequest request)
    {
        if (isClosed())
            return false;

        synchronized (lock)
        {
            requests.add(request);
            resume();
        }

        return true;
    }

    private void resume()
    {
        synchronized (lock)
        {
            // Continuation may be null in several cases:
            // 1. there always is something to deliver so we never suspend
            // 2. concurrent calls to add() and close()
            // 3. concurrent close() with a long poll that expired
            // 4. concurrent close() with a long poll that resumed
            if (continuation != null)
            {
                continuation.resume();
                // Null the continuation, as there is no point is resuming multiple times
                continuation = null;
            }
        }
    }

    public List<RHTTPRequest> process(HttpServletRequest httpRequest) throws IOException
    {
        // We want to respond in the following cases:
        // 1. It's the first time we process: the client will wait for a response before issuing another connect.
        // 2. The client disconnected, so we want to return from this connect before it times out.
        // 3. We've been woken up because there are responses to send.
        // 4. The continuation was suspended but timed out.
        //    The timeout case is different from a non-first connect, in that we want to return
        //    a (most of the times empty) response and we do not want to wait again.
        // The order of these if statements is important, as the continuation timed out only if
        // the client is not closed and there are no responses to send
        List<RHTTPRequest> result = Collections.emptyList();
        if (firstFlush)
        {
            firstFlush = false;
            logger.debug("Connect request (first) from device {}, delivering requests {}", targetId, result);
        }
        else
        {
            // Synchronization is crucial here, since we don't want to suspend if there is something to deliver
            synchronized (lock)
            {
                int size = requests.size();
                if (size > 0)
                {
                    assert continuation == null;
                    result = new ArrayList<RHTTPRequest>(size);
                    result.addAll(requests);
                    requests.clear();
                    logger.debug("Connect request (resumed) from device {}, delivering requests {}", targetId, result);
                }
                else
                {
                    if (continuation != null)
                    {
                        continuation = null;
                        logger.debug("Connect request (expired) from device {}, delivering requests {}", targetId, result);
                    }
                    else
                    {
                        if (isClosed())
                        {
                            logger.debug("Connect request (closed) from device {}, delivering requests {}", targetId, result);
                        }
                        else
                        {
                            // Here we need to suspend
                            continuation = ContinuationSupport.getContinuation(httpRequest);
                            continuation.setTimeout(getTimeout());
                            continuation.suspend();
                            result = null;
                            logger.debug("Connect request (suspended) from device {}", targetId);
                        }
                    }
                }
            }
        }
        return result;
    }

    public void close()
    {
        closed = true;
        resume();
    }

    public boolean isClosed()
    {
        return closed;
    }
}
