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

package org.eclipse.jetty.websocket.tests;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.FrameHandler;

public class CoreUtils
{
    public static void close(FrameHandler.Channel channel, long timeout, TimeUnit timeoutUnit) throws InterruptedException, ExecutionException, TimeoutException
    {
        if(channel == null)
            return;

        FutureCallback blocking = new FutureCallback();
        channel.close(blocking);
        blocking.get(timeout, timeoutUnit);
    }
}
