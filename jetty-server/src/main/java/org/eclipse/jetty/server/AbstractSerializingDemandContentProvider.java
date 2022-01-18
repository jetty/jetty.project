//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractSerializingDemandContentProvider implements Content.Provider
{
    private final AtomicBoolean demanding = new AtomicBoolean();
    private final AtomicBoolean extraDemand = new AtomicBoolean();

    @Override
    public final void demandContent(Runnable onContentAvailable)
    {
        while (true)
        {
            if (demanding.compareAndSet(false, true))
            {
                serviceDemand(onContentAvailable);
                demanding.set(false);
            }
            else
            {
                extraDemand.set(true);
                break;
            }

            if (!extraDemand.compareAndSet(true, false))
                break;
        }
    }

    protected abstract void serviceDemand(Runnable onContentAvailable);
}
