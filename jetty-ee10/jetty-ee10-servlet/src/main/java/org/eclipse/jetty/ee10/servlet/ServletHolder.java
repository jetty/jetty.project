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

import jakarta.servlet.Servlet;
import org.eclipse.jetty.ee.servlet.Source;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * Servlet Instance and Context Holder.
 * <p>
 * Holds the name, params and some state of a jakarta.servlet.Servlet
 * instance. It implements the ServletConfig interface.
 * This class will organise the loading of the servlet when needed or
 * requested.
 */
@ManagedObject("Servlet Holder")
public class ServletHolder extends org.eclipse.jetty.ee.servlet.ServletHolder
{
    public ServletHolder()
    {
        super();
    }

    public ServletHolder(Source creator)
    {
        super(creator);
    }

    public ServletHolder(Servlet servlet)
    {
        super(servlet);
    }

    public ServletHolder(String name, Class<? extends Servlet> servlet)
    {
        super(name, servlet);
    }

    public ServletHolder(String name, Servlet servlet)
    {
        super(name, servlet);
    }

    public ServletHolder(Class<? extends Servlet> servlet)
    {
        super(servlet);
    }

    @Override
    public int compareTo(org.eclipse.jetty.ee.servlet.ServletHolder servletHolder)
    {
        return super.compareTo(servletHolder);
    }
}
