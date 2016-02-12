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
import java.nio.ByteBuffer;
import java.util.function.Function;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.message.MessageSink;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.test.OutgoingFramesCapture;
import org.junit.rules.TestName;

public class LocalWebSocketSession extends WebSocketSession
{
    private String id;
    private OutgoingFramesCapture outgoingCapture;

    public LocalWebSocketSession(WebSocketContainerScope containerScope, TestName testname, Object websocket)
    {
        super(containerScope,URI.create("ws://localhost/LocalWebSocketSesssion/" + testname.getMethodName()),websocket,
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
    
    public Function<Session, Void> getOnOpenFunction()
    {
        return onOpenFunction;
    }

    public Function<CloseInfo, Void> getOnCloseFunction()
    {
        return onCloseFunction;
    }

    public Function<Throwable, Void> getOnErrorFunction()
    {
        return onErrorFunction;
    }

    public Function<ByteBuffer, Void> getOnPingFunction()
    {
        return onPingFunction;
    }

    public Function<ByteBuffer, Void> getOnPongFunction()
    {
        return onPongFunction;
    }

    public Function<Frame, Void> getOnFrameFunction()
    {
        return onFrameFunction;
    }

    public MessageSink getOnTextSink()
    {
        return onTextSink;
    }

    public MessageSink getOnBinarySink()
    {
        return onBinarySink;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]",LocalWebSocketSession.class.getSimpleName(),id);
    }
}
