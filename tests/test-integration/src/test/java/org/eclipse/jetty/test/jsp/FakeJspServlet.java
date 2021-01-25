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

package org.eclipse.jetty.test.jsp;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class FakeJspServlet extends HttpServlet
{

    /*
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
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
