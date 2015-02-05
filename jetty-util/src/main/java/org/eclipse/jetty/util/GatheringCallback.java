//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.concurrent.atomic.AtomicInteger;

public class GatheringCallback implements Callback
{
    private final Callback callback;
    private final AtomicInteger count;

    public GatheringCallback(Callback callback, int count)
    {
        this.callback = callback;
        this.count = new AtomicInteger(count);
    }

    @Override
    public void succeeded()
    {
        // Forward success on the last success.
        while (true)
        {
            int current = count.get();

            // Already completed ?
            if (current == 0)
                return;

            if (count.compareAndSet(current, current - 1))
            {
                if (current == 1)
                {
                    callback.succeeded();
                    return;
                }
            }
        }
    }

    @Override
    public void failed(Throwable failure)
    {
        // Forward failure on the first failure.
        while (true)
        {
            int current = count.get();

            // Already completed ?
            if (current == 0)
                return;

            if (count.compareAndSet(current, 0))
            {
                callback.failed(failure);
                return;
            }
        }
    }
}
