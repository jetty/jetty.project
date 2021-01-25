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

package org.eclipse.jetty.websocket.jsr356.server.samples;

import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.jsr356.server.TrackingSocket;

@ServerEndpoint(value = "/invalid")
public class InvalidOpenSessionIntSocket extends TrackingSocket
{
    /**
     * Invalid Open Method Declaration (parameter of type int)
     *
     * @param session the sesion
     * @param count the count
     */
    @OnOpen
    public void onOpen(Session session, int count)
    {
        openLatch.countDown();
    }
}
