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

package org.eclipse.jetty.ee10.servlet;

import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.TunnelSupport;

public class MockRequest implements Request
{
    @Override
    public void fail(Throwable failure)
    {
    }

    @Override
    public String getId()
    {
        return null;
    }

    @Override
    public Components getComponents()
    {
        return null;
    }

    @Override
    public ConnectionMetaData getConnectionMetaData()
    {
        return null;
    }

    @Override
    public String getMethod()
    {
        return null;
    }

    @Override
    public HttpURI getHttpURI()
    {
        return null;
    }

    @Override
    public Context getContext()
    {
        return null;
    }

    @Override
    public HttpFields getHeaders()
    {
        return null;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
    }

    @Override
    public HttpFields getTrailers()
    {
        return null;
    }

    @Override
    public long getBeginNanoTime()
    {
        return 0;
    }

    @Override
    public long getHeadersNanoTime()
    {
        return 0;
    }

    @Override
    public boolean isSecure()
    {
        return false;
    }

    @Override
    public Content.Chunk read()
    {
        return null;
    }

    @Override
    public boolean consumeAvailable()
    {
        return false;
    }

    @Override
    public void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout)
    {
    }

    @Override
    public void addFailureListener(Consumer<Throwable> onFailure)
    {
    }

    @Override
    public TunnelSupport getTunnelSupport()
    {
        return null;
    }

    @Override
    public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
    {
    }

    @Override
    public Session getSession(boolean create)
    {
        return null;
    }

    @Override
    public Object removeAttribute(String name)
    {
        return null;
    }

    @Override
    public Object setAttribute(String name, Object attribute)
    {
        return null;
    }

    @Override
    public Object getAttribute(String name)
    {
        return null;
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return null;
    }
}
