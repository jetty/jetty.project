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

package org.eclipse.jetty.demo;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
