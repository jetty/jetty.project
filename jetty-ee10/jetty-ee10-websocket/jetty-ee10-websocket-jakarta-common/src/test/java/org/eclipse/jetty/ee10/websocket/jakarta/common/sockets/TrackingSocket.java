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

package org.eclipse.jetty.ee10.websocket.jakarta.common.sockets;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;

import jakarta.websocket.CloseReason;

public abstract class TrackingSocket
{
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch dataLatch = new CountDownLatch(1);
    public BlockingQueue<String> events = new LinkedBlockingDeque();
    public BlockingQueue<Throwable> errors = new LinkedBlockingDeque<>();
    public CloseReason closeReason;

    public void addEvent(String format, Object... args)
    {
        events.add(String.format(format, args));
    }

    public void addError(Throwable cause)
    {
        errors.offer(cause);
    }
}
