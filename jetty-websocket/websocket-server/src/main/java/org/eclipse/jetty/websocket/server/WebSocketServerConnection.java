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

package org.eclipse.jetty.websocket.server;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;

public class WebSocketServerConnection extends AbstractWebSocketConnection implements Connection.UpgradeTo
{
    public WebSocketServerConnection(EndPoint endp, Executor executor, Scheduler scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        super(endp, executor, scheduler, policy, bufferPool);

        if (policy.getIdleTimeout() > 0)
            endp.setIdleTimeout(policy.getIdleTimeout());
    }
}
