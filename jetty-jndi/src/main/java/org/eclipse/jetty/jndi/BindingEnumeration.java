//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

    public BindingEnumeration (Iterator<Binding> e)
    {
        _delegate = e;
    }

    public void close()
        throws NamingException
    {
    }

    public boolean hasMore ()
        throws NamingException
    {
        return _delegate.hasNext();
    }

    public Binding next()
        throws NamingException
    {
        Binding b = (Binding)_delegate.next();
        return new Binding (b.getName(), b.getClassName(), b.getObject(), true);
    }

    public boolean hasMoreElements()
    {
        return _delegate.hasNext();
    }

    public Binding nextElement()
    {
        Binding b = (Binding)_delegate.next();
        return new Binding (b.getName(), b.getClassName(), b.getObject(),true);
    }
}
