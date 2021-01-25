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

package org.eclipse.jetty.server.session;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.HttpChannel.Listener;
import org.eclipse.jetty.server.Request;

public class TestHttpChannelCompleteListener implements Listener
{
    private final AtomicReference<CountDownLatch> _exitSynchronizer = new AtomicReference<>();

    /**
     * @param exitSynchronizer the exitSynchronizer to set
     */
    public void setExitSynchronizer(CountDownLatch exitSynchronizer)
    {
        _exitSynchronizer.set(exitSynchronizer);
    }

    @Override
    public void onComplete(Request request)
    {
        if (_exitSynchronizer.get() != null)
            _exitSynchronizer.get().countDown();
    }
}
