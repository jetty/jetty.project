// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.servlets;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class GzipFilterTest4 extends GzipFilterTest
{
    @Override
    public Class<?> getServletClass()
    {
        return TestServlet.class;
    }
    
    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = -3603297003496724934L;

        /* ------------------------------------------------------------ */
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            byte[] dataBytes = GzipFilterTest.__content.getBytes("ISO8859_1");

            String fileName = request.getServletPath();
            if (fileName.endsWith("txt"))
                response.setContentType("text/plain");
            else if (fileName.endsWith("mp3")) 
                response.setContentType("audio/mpeg");

            response.setContentLength(dataBytes.length);

            ServletOutputStream out = response.getOutputStream();
            out.write(dataBytes);
        }
    }
}
