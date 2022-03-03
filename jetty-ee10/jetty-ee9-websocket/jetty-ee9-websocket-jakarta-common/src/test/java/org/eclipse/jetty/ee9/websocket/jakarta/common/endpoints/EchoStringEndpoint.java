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

package org.eclipse.jetty.ee9.websocket.jakarta.common.endpoints;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Legitimate structure for an Endpoint
 */
public class EchoStringEndpoint extends AbstractStringEndpoint
{
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();

    @Override
    public void onMessage(String message)
    {
        messageQueue.offer(message);
        session.getAsyncRemote().sendText(message);
    }
}
