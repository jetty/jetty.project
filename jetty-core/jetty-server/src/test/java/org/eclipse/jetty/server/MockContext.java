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

package org.eclipse.jetty.server;

import java.io.File;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.resource.Resource;

public class MockContext implements Context
{
    @Override
    public <T> T decorate(T o)
    {
        return null;
    }

    @Override
    public void destroy(Object o)
    {

    }

    @Override
    public String getContextPath()
    {
        return "";
    }

    @Override
    public ClassLoader getClassLoader()
    {
        return null;
    }

    @Override
    public Resource getBaseResource()
    {
        return null;
    }

    @Override
    public Request.Handler getErrorHandler()
    {
        return null;
    }

    @Override
    public List<String> getVirtualHosts()
    {
        return List.of();
    }

    @Override
    public MimeTypes getMimeTypes()
    {
        return null;
    }

    @Override
    public void execute(Runnable task)
    {

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
        return Set.of();
    }

    @Override
    public void run(Runnable task)
    {

    }

    @Override
    public void run(Runnable task, Request request)
    {

    }

    @Override
    public File getTempDirectory()
    {
        return null;
    }
}
