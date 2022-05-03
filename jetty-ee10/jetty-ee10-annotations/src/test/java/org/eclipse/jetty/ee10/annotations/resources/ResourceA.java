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

package org.eclipse.jetty.ee10.annotations.resources;

import java.io.IOException;

import jakarta.annotation.Resource;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * ResourceA
 */
public class ResourceA implements jakarta.servlet.Servlet
{
    private Integer e;
    private Integer h;
    private Integer k;

    @Resource(name = "myf", mappedName = "resB") //test giving both a name and mapped name from the environment
    private Integer f; //test a non inherited field that needs injection

    @Resource(mappedName = "resA") //test the default naming scheme but using a mapped name from the environment
    private Integer g;

    @Resource(name = "resA") //test using the given name as the name from the environment
    private Integer j;

    @Resource(mappedName = "resB") //test using the default name on an inherited field
    protected Integer n; //TODO - if it's inherited, is it supposed to use the classname of the class it is inherited by?

    @Resource(name = "mye", mappedName = "resA", type = Integer.class)
    public void setE(Integer e)
    {
        this.e = e;
    }

    public Integer getE()
    {
        return this.e;
    }

    public Integer getF()
    {
        return this.f;
    }

    public Integer getG()
    {
        return this.g;
    }

    public Integer getJ()
    {
        return this.j;
    }

    @Resource(mappedName = "resA")
    public void setH(Integer h)
    {
        this.h = h;
    }

    @Resource(name = "resA")
    public void setK(Integer k)
    {
        this.k = k;
    }

    public void x()
    {
        System.err.println("ResourceA.x");
    }

    @Override
    public void destroy()
    {
    }

    @Override
    public ServletConfig getServletConfig()
    {
        return null;
    }

    @Override
    public String getServletInfo()
    {
        return null;
    }

    @Override
    public void init(ServletConfig arg0) throws ServletException
    {
    }

    @Override
    public void service(ServletRequest arg0, ServletResponse arg1)
        throws ServletException, IOException
    {
    }
}
