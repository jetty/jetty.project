//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests.quotes;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.javax.tests.WSEventTracker;

@ClientEndpoint(decoders = QuotesDecoder.class, subprotocols = "quotes")
public class QuotesSocket extends WSEventTracker
{
    public BlockingQueue<Quotes> messageQueue = new LinkedBlockingDeque<>();

    public QuotesSocket(String id)
    {
        super(id);
    }

    @OnOpen
    public void onOpen(Session session)
    {
        super.onWsOpen(session);
    }

    @OnClose
    public void onClose(CloseReason closeReason)
    {
        super.onWsClose(closeReason);
    }

    @OnError
    public void onError(Throwable cause)
    {
        super.onWsError(cause);
    }

    @OnMessage
    public void onMessage(Quotes quote)
    {
        logger.debug("onMessage({})", quote);
        messageQueue.offer(quote);
    }
}
