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

package org.eclipse.jetty.session;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;

public class TestableRequest implements Request
{
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

    @Override
    public void clearAttributes()
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
    public String getPathInContext()
    {
        return null;
    }

    @Override
    public HttpFields getHeaders()
    {
        return null;
    }

    public List<HttpCookie> getCookies()
    {
        return null;
    }

    @Override
    public long getTimeStamp()
    {
        return 0;
    }

    @Override
    public boolean isSecure()
    {
        return false;
    }

    @Override
    public long getContentLength()
    {
        return 0;
    }

    @Override
    public Content.Chunk read()
    {
        return null;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
    }

    @Override
    public void fail(Throwable failure)
    {
    }

    @Override
    public boolean addErrorListener(Predicate<Throwable> onError)
    {
        return false;
    }

    @Override
    public void push(org.eclipse.jetty.http.MetaData.Request request)
    {
    }

    @Override
    public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper)
    {
    }
}
