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

package org.eclipse.jetty.websocket.core.autobahn.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.core.CloseStatus;

public class UpdateReportsHandler extends AbstractClientFrameHandler
{
    private CountDownLatch latch = new CountDownLatch(1);

    public void awaitClose() throws InterruptedException
    {
        latch.await(15, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        LOG.info("Updating reports ...");
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        super.onClosed(closeStatus);
        LOG.debug("onClose({})",closeStatus);
        LOG.info("Reports updated.");
        LOG.info("Test suite finished!");
        latch.countDown();
    }
}
