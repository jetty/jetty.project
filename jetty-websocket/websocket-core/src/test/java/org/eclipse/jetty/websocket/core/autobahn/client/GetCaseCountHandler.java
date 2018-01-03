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

/**
 * Get the autobahn case count
 */
public class GetCaseCountHandler extends AbstractClientFrameHandler
{
    private Integer casecount = null;
    private CountDownLatch latch = new CountDownLatch(1);

    public void awaitMessage() throws InterruptedException
    {
        latch.await(1, TimeUnit.SECONDS);
    }

    public int getCaseCount()
    {
        return casecount.intValue();
    }

    public boolean hasCaseCount()
    {
        return (casecount != null);
    }

    @Override
    protected void onWholeText(String message)
    {
        LOG.debug("onWholeText(\"{}\")",message);
        casecount = Integer.decode(message);
        latch.countDown();
    }
}
