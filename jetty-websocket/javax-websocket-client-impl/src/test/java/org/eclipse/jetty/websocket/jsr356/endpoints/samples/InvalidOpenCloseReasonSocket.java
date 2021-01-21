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

package org.eclipse.jetty.websocket.jsr356.endpoints.samples;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnOpen;

import org.eclipse.jetty.websocket.jsr356.endpoints.TrackingSocket;

@ClientEndpoint
public class InvalidOpenCloseReasonSocket extends TrackingSocket
{
    /**
     * Invalid Open Method Declaration (parameter type CloseReason)
     *
     * @param reason the close reason
     */
    @OnOpen
    public void onOpen(CloseReason reason)
    {
        openLatch.countDown();
    }
}
