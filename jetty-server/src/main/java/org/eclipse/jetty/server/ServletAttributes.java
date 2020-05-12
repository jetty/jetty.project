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
    private final Attributes _attributes;

    public ServletAttributes()
    {
        _attributes = new AsyncAttributes(new AttributesMap());
    }

    @Override
    public void removeAttribute(String name)
    {
        _attributes.removeAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object attribute)
    {
        _attributes.setAttribute(name, attribute);
    }

    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNameSet()
    {
        return _attributes.getAttributeNameSet();
    }

    @Override
    public void clearAttributes()
    {
        _attributes.clearAttributes();
    }
}
