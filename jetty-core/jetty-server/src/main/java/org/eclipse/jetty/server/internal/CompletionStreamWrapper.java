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

package org.eclipse.jetty.server.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ExceptionUtil;

/**
 * A HttpStream wrapper that keeps a list of listeners.
 * This is optimized for multiple completion event listeners being added, without other
 * stream wrappers interleaved.
 */
public class CompletionStreamWrapper extends HttpStream.Wrapper
{
    private final List<Consumer<Throwable>> _listeners = new ArrayList<>();

    public CompletionStreamWrapper(HttpStream stream, Consumer<Throwable> listener)
    {
        super(stream);
        _listeners.add(listener);
    }

    @Override
    public void succeeded()
    {
        if (Request.LOG.isDebugEnabled())
            Request.LOG.debug("succeeded {}", this);
        onCompletion(null);
        super.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        if (Request.LOG.isDebugEnabled())
            Request.LOG.debug("failed {}", this, x);
        onCompletion(x);
        super.failed(x);
    }

    public HttpStream addListener(Consumer<Throwable> listener)
    {
        // A simple array list will suffice as this is called from handle
        _listeners.add(listener);
        return this;
    }

    private void onCompletion(Throwable x)
    {
        // completion can only be called once because it is protected by the HttpChannelState.

        // completion events in reverse order
        for (int i = _listeners.size(); i-- > 0; )
        {
            Consumer<Throwable> r = _listeners.get(i);
            try
            {
                r.accept(x);
            }
            catch (Throwable t)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(x, t);
                Request.LOG.warn("{} threw", r, t);
            }
        }
    }
}
