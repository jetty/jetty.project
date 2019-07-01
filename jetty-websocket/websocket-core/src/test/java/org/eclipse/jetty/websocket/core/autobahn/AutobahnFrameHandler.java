//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.autobahn;

import java.nio.ByteBuffer;
import java.time.Duration;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.TestMessageHandler;
import org.eclipse.jetty.websocket.core.WebSocketConstants;

public class AutobahnFrameHandler extends TestMessageHandler
{
    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        coreSession.setIdleTimeout(Duration.ofSeconds(5));
        coreSession.setMaxTextMessageSize(Integer.MAX_VALUE);
        coreSession.setMaxBinaryMessageSize(Integer.MAX_VALUE);
        coreSession.setMaxFrameSize(WebSocketConstants.DEFAULT_MAX_FRAME_SIZE * 2);
        super.onOpen(coreSession, callback);
    }

    @Override
    public void onBinary(ByteBuffer wholeMessage, Callback callback)
    {
        sendBinary(wholeMessage, callback, false);
    }

    @Override
    public void onText(String wholeMessage, Callback callback)
    {
        sendText(wholeMessage, callback, false);
    }
}
