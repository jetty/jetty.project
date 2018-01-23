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

package org.eclipse.jetty.websocket.jsr356.tests.client.samples;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.tests.WSEventTracker;

@ClientEndpoint
public class CloseSessionSocket extends WSEventTracker
{
    @OnOpen public void onOpen(Session session) { super.onWsOpen(session); }
    @OnError public void onError(Throwable cause) { super.onWsError(cause); }

    @OnClose
    public void onClose(Session session)
    {
        addEvent("onClose(Session)");
        super.onWsClose(null);
    }
}
