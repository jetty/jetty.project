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

package org.eclipse.jetty.ee9.test.jsp;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class FakeJspServlet extends HttpServlet
{

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
    {
        String path = req.getServletPath();
        URL url = getServletContext().getResource(path);
        if (url == null)
        {
            response.sendError(404);
            return;
        }

        try
        {
            File file = new File(url.toURI());
            if (file.exists())
            {
                response.sendError(200, "fake JSP response");
                return;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        response.sendError(404);
    }
}
