//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io.content;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.SerializedInvoker;

/**
 * <p>A {@link Content.Source} that only contains EOF.</p>
 */
public class EmptyContentSource implements Content.Source
{
    private static final EmptyContentSource INSTANCE = new EmptyContentSource();

    public static Content.Source instance()
    {
        return INSTANCE;
    }

    private final SerializedInvoker invoker = new SerializedInvoker();

    private EmptyContentSource()
    {
    }

    @Override
    public long getLength()
    {
        return 0L;
    }

    @Override
    public Content.Chunk read()
    {
        return Content.Chunk.EOF;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        invoker.run(() -> runDemandCallback(demandCallback));
    }

    private void runDemandCallback(Runnable demandCallback)
    {
        try
        {
            demandCallback.run();
        }
        catch (Throwable x)
        {
            fail(x);
        }
    }

    @Override
    public void fail(Throwable failure)
    {
    }

    @Override
    public boolean rewind()
    {
        return true;
    }
}
