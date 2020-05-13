//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.util.Set;

import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;

public class ServletAttributes implements Attributes
{
    private final Attributes _attributes = new AttributesMap();
    private AsyncAttributes _asyncAttributes;

    public void setAsyncAttributes(String requestURI, String contextPath, String servletPath, String pathInfo, String queryString)
    {
        _asyncAttributes = new AsyncAttributes(_attributes, requestURI, contextPath, servletPath, pathInfo, queryString);
    }

    private Attributes getAttributes()
    {
        return (_asyncAttributes == null) ? _attributes : _asyncAttributes;
    }

    @Override
    public void removeAttribute(String name)
    {
        getAttributes().removeAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object attribute)
    {
        getAttributes().setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return getAttributes().getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return getAttributes().getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        getAttributes().clearAttributes();
        _asyncAttributes = null;
    }
}
