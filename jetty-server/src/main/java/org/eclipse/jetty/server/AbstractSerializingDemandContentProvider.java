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

import org.eclipse.jetty.util.thread.AutoLock;

public abstract class AbstractSerializingDemandContentProvider implements Content.Provider
{
    private final AutoLock lock = new AutoLock();
    private boolean demanding;
    private Runnable extraDemand;

    @Override
    public final void demandContent(Runnable onContentAvailable)
    {
        while (true)
        {
            try (AutoLock ignore = this.lock.lock())
            {
                if (!demanding)
                {
                    demanding = true;
                }
                else
                {
                    extraDemand = onContentAvailable;
                    break;
                }
            }

            serviceDemand(onContentAvailable);

            try (AutoLock ignore = this.lock.lock())
            {
                demanding = false;

                if (extraDemand != null)
                {
                    onContentAvailable = extraDemand;
                    extraDemand = null;
                }
                else
                {
                    break;
                }
            }
        }
    }

    protected abstract void serviceDemand(Runnable onContentAvailable);
}
