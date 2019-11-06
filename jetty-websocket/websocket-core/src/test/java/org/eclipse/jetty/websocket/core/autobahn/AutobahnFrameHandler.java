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

import java.time.Duration;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.EchoFrameHandler;

public class AutobahnFrameHandler extends EchoFrameHandler
{
    public AutobahnFrameHandler()
    {
        super("AutobahnFrameHandler");
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        coreSession.setIdleTimeout(Duration.ofSeconds(5));
        coreSession.setMaxTextMessageSize(Integer.MAX_VALUE);
        coreSession.setMaxBinaryMessageSize(Integer.MAX_VALUE);
        super.onOpen(coreSession, callback);
    }
}
