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

package org.eclipse.jetty.server.session;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.server.HttpChannel.Listener;
import org.eclipse.jetty.server.Request;

public class TestHttpChannelCompleteListener implements Listener
{
    AtomicReference<CountDownLatch> _exitSynchronizer = new AtomicReference<>();

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
