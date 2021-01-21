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

package org.eclipse.jetty.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Inject;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/info")
public class InfoServlet extends HttpServlet
{
    @Inject
    BeanManager beanManager;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("utf-8");

        PrintWriter out = resp.getWriter();
        out.println("Bean Manager: " + beanManager);
        Set<Bean<?>> beans = beanManager.getBeans(Object.class, new AnnotationLiteral<Any>() {});
        for (Bean<?> bean : beans)
        {
            out.printf("%16s => %s%n", bean.getName(), bean);
            for (InjectionPoint ij : bean.getInjectionPoints())
            {
                out.printf("%16s -> %s%n", "", ij);
            }
        }
    }
}
