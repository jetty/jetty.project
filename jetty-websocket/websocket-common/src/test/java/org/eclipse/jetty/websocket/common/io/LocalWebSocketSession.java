//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.io;

import java.net.URI;

import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.test.OutgoingFramesCapture;
import org.junit.rules.TestName;

public class LocalWebSocketSession extends WebSocketSession
{
    private String id;
    private OutgoingFramesCapture outgoingCapture;

    public LocalWebSocketSession(WebSocketContainerScope containerScope, TestName testname, EventDriver driver)
    {
        super(containerScope,URI.create("ws://localhost/LocalWebSocketSesssion/" + testname.getMethodName()),driver,
                new LocalWebSocketConnection(testname,containerScope.getBufferPool()));
        this.id = testname.getMethodName();
        outgoingCapture = new OutgoingFramesCapture();
        setOutgoingHandler(outgoingCapture);
    }

    @Override
    public void dispatch(Runnable runnable)
    {
        runnable.run();
    }

    public OutgoingFramesCapture getOutgoingCapture()
    {
        return outgoingCapture;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]",LocalWebSocketSession.class.getSimpleName(),id);
    }
}
