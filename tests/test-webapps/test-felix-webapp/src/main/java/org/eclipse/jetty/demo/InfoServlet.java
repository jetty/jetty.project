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

package org.eclipse.jetty.demo;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;

@WebServlet("/info")
public class InfoServlet extends HttpServlet
{
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("utf-8");

        PrintWriter out = resp.getWriter();
        Framework framework = (Framework)getServletContext().getAttribute(Framework.class.getName());
        out.printf("Framework: %s\n", framework);
        BundleContext bundleContext = framework.getBundleContext();
        out.printf("BundleContext: %s\n", bundleContext);
        Bundle bundleSelf = bundleContext.getBundle();
        out.printf("BundleContext.bundle: %s\n", bundleSelf);
        for (Bundle bundle : bundleContext.getBundles())
        {
            out.printf("bundle[]: %s\n", bundle);
        }
    }
}
