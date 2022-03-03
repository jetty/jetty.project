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

package com.acme.osgi;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Dictionary;
import java.util.Hashtable;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Bootstrap a webapp
 */
public class Activator implements BundleActivator
{

    private ServiceRegistration _srA;
    private ServiceRegistration _srB;

    public static class TestServlet extends HttpServlet
    {

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            //report the mimetype of a file
            String mimetype = req.getServletContext().getMimeType("file.gz");
            resp.setContentType("text/html");
            PrintWriter writer = resp.getWriter();
            writer.write("<html><body><p>MIMETYPE=" + mimetype + "</p></body</html>");
            writer.flush();
        }
    }

    /**
     *
     */
    @Override
    public void start(BundleContext context) throws Exception
    {
        //Create webappA as a Service and target it at the default server
        WebAppContext webapp = new WebAppContext();
        webapp.addServlet(new ServletHolder(new TestServlet()), "/mime");
        Dictionary props = new Hashtable();
        props.put("Jetty-WarResourcePath", "webappA");
        props.put("Web-ContextPath", "/acme");
        props.put("managedServerName", "defaultJettyServer");
        _srA = context.registerService(WebAppContext.class.getName(), webapp, props);

        //Create a second webappB as a Service and target it at a custom Server
        //deployed by another bundle
        final WebAppContext webappB = new WebAppContext();
        Dictionary propsB = new Hashtable();
        propsB.put("Jetty-WarResourcePath", "webappB");
        propsB.put("Web-ContextPath", "/acme");
        propsB.put("managedServerName", "fooServer");
        _srB = context.registerService(WebAppContext.class.getName(), webappB, propsB);
    }

    /**
     * Stop the activator.
     *
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    @Override
    public void stop(BundleContext context) throws Exception
    {
        _srA.unregister();
        _srB.unregister();
    }
}
