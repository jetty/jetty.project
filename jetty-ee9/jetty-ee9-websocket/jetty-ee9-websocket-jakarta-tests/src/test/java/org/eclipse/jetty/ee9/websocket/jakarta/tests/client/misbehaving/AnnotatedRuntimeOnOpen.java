//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.client.misbehaving;

import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;

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
