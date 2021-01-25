//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.plus.jndi;

import javax.naming.InitialContext;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Dumpable;

/**
 * A utility Dumpable to dump a JNDI naming context tree.
 */
public class NamingDump implements Dumpable
{
    private final ClassLoader _loader;
    private final String _name;

    public NamingDump()
    {
        this(null, "");
    }

    public NamingDump(ClassLoader loader, String name)
    {
        _loader = loader;
        _name = name;
    }

    @Override
    public void dump(Appendable out, String indent)
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try
        {
            if (!StringUtil.isBlank(_name))
                out.append(_name).append(" ");
            if (_loader != null)
                Thread.currentThread().setContextClassLoader(_loader);
            Object context = new InitialContext().lookup(_name);
            if (context instanceof Dumpable)
                ((Dumpable)context).dump(out, indent);
            else
                Dumpable.dumpObjects(out, indent, context);
        }
        catch (Throwable cause)
        {
            throw new RuntimeException(cause);
        }
        finally
        {
            if (_loader != null)
                Thread.currentThread().setContextClassLoader(loader);
        }
    }
}
