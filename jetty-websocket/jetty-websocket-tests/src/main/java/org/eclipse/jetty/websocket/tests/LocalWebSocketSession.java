//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import org.eclipse.jetty.websocket.common.WebSocketSessionImpl;
import org.junit.rules.TestName;

public class LocalWebSocketSession extends WebSocketSessionImpl
{
    private String id;
    private OutgoingFramesCapture outgoingCapture;
    
    public LocalWebSocketSession(TestName testname)
    {
        super(new LocalWebSocketConnection(testname.getMethodName()));
        this.id = testname.getMethodName();
        outgoingCapture = new OutgoingFramesCapture();
        ((LocalWebSocketConnection) getConnection()).setOutgoingFrames(outgoingCapture);
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
