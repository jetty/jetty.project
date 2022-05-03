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
        catch (Throwable th)
        {
            throw new RuntimeException(th);
        }
        finally
        {
            if (_loader != null)
                Thread.currentThread().setContextClassLoader(loader);
        }
    }
}
