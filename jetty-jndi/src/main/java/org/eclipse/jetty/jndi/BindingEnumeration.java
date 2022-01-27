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

package org.eclipse.jetty.jndi;

import java.util.Iterator;
import javax.naming.Binding;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;

/**
 * BindingEnumeration
 */
public class BindingEnumeration implements NamingEnumeration<Binding>
{
    Iterator<Binding> _delegate;

    public BindingEnumeration(Iterator<Binding> e)
    {
        _delegate = e;
    }

    @Override
    public void close()
        throws NamingException
    {
    }

    @Override
    public boolean hasMore()
        throws NamingException
    {
        return _delegate.hasNext();
    }

    @Override
    public Binding next()
        throws NamingException
    {
        Binding b = (Binding)_delegate.next();
        return new Binding(b.getName(), b.getClassName(), b.getObject(), true);
    }

    @Override
    public boolean hasMoreElements()
    {
        return _delegate.hasNext();
    }

    @Override
    public Binding nextElement()
    {
        Binding b = (Binding)_delegate.next();
        return new Binding(b.getName(), b.getClassName(), b.getObject(), true);
    }
}
