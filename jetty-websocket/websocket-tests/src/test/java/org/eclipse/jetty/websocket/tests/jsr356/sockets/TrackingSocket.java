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

package org.eclipse.jetty.websocket.tests.jsr356.sockets;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;

import javax.websocket.CloseReason;

import org.eclipse.jetty.websocket.tests.EventQueue;

public abstract class TrackingSocket
{
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch dataLatch = new CountDownLatch(1);
    public EventQueue events = new EventQueue();
    public BlockingQueue<Throwable> errors = new LinkedBlockingDeque<>();
    public CloseReason closeReason;
    
    public void addEvent(String format, Object... args)
    {
        events.add(format, args);
    }
    
    public void addError(Throwable cause)
    {
        errors.offer(cause);
    }
}
