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

package org.eclipse.jetty.websocket.jsr356.misbehaving;

import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

/**
 * A JSR-356 Endpoint that tosses a RuntimeException during its onOpen call
 */
public class EndpointRuntimeOnOpen extends Endpoint
{
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CloseReason closeReason;
    public LinkedList<Throwable> errors = new LinkedList<>();

    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        throw new RuntimeException("Intentionally Misbehaving");
    }

    @Override
    public void onClose(Session session, CloseReason closeReason)
    {
        super.onClose(session, closeReason);
        this.closeReason = closeReason;
        closeLatch.countDown();
    }

    @Override
    public void onError(Session session, Throwable thr)
    {
        super.onError(session, thr);
        errors.add(thr);
    }
}
