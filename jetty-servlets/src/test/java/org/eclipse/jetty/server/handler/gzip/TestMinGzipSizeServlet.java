//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler.gzip;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.MimeTypes;

/**
 * Test servlet for testing against unusual minGzip configurable.
 */
@SuppressWarnings("serial")
public class TestMinGzipSizeServlet extends TestDirContentServlet
{
    private MimeTypes mimeTypes;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        mimeTypes = new MimeTypes();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String fileName = request.getServletPath();
        byte[] dataBytes = loadContentFileBytes(fileName);

        response.setContentLength(dataBytes.length);
        response.setHeader("ETag","W/etag-"+fileName);
        if (fileName.endsWith(".js"))
        {
            // intentionally long-form content type to test ";" splitting in code
            response.setContentType("text/javascript; charset=utf-8");
        }
        else
        {
            String mime = mimeTypes.getMimeByExtension(fileName);
            if (mime != null)
                response.setContentType(mime);
        }
        ServletOutputStream out = response.getOutputStream();
        out.write(dataBytes);
    }
}
