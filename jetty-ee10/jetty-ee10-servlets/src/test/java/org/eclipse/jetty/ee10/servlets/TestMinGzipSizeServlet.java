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

package org.eclipse.jetty.ee10.servlets;

import java.io.IOException;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Test servlet for testing against unusual minGzip configurable.
 */
public class TestMinGzipSizeServlet extends AbstractFileContentServlet
{
    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String fileName = request.getServletPath();
        byte[] dataBytes = loadContentFileBytes(fileName);

        response.setContentLength(dataBytes.length);
        response.setHeader("ETag", "W/etag-" + fileName);
        if (fileName.endsWith(".js"))
        {
            // intentionally long-form content type to test ";" splitting in code
            response.setContentType("text/javascript; charset=utf-8");
        }
        else
        {
            String mime = request.getServletContext().getMimeType(fileName);
            if (mime != null)
                response.setContentType(mime);
        }
        ServletOutputStream out = response.getOutputStream();
        out.write(dataBytes);
    }
}
