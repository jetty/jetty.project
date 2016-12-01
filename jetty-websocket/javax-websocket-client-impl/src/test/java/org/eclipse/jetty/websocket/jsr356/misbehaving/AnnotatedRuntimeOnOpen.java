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

package org.eclipse.jetty.websocket.jsr356.misbehaving;

import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;

/**
 * A JSR-356 Annotated that tosses a RuntimeException during its onOpen call
 */
@ClientEndpoint
public class AnnotatedRuntimeOnOpen
{
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CloseReason closeReason;
    public LinkedList<Throwable> errors = new LinkedList<>();

    @OnOpen
    public void onOpen(Session session, EndpointConfig config)
    {
        throw new RuntimeException("Intentionally Misbehaving");
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason)
    {
        this.closeReason = closeReason;
        closeLatch.countDown();
    }

    @OnError
    public void onError(Session session, Throwable thr)
    {
        errors.add(thr);
    }
}
