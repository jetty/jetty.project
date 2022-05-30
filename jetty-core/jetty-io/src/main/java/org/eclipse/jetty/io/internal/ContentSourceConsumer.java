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

package org.eclipse.jetty.io.internal;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;

public class ContentSourceConsumer implements Invocable.Task
{
    private final Content.Source source;
    private final Callback callback;

    public ContentSourceConsumer(Content.Source source, Callback callback)
    {
        this.source = source;
        this.callback = callback;
    }

    @Override
    public void run()
    {
        while (true)
        {
            Content.Chunk chunk = source.read();

            if (chunk == null)
            {
                source.demand(this);
                return;
            }

            if (chunk instanceof Content.Chunk.Error error)
            {
                callback.failed(error.getCause());
                return;
            }

            chunk.release();

            if (chunk.isLast())
            {
                callback.succeeded();
                return;
            }
        }
    }

    @Override
    public InvocationType getInvocationType()
    {
        return callback.getInvocationType();
    }
}
