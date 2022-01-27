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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.gzip.GzipHandler;

/**
 * A sample servlet to serve static content, using a order of construction that has caused problems for
 * {@link GzipHandler} in the past.
 *
 * Using a real-world pattern of:
 *
 * <pre>
 *  1) get stream
 *  2) set content type
 *  2) set content length
 *  4) write
 * </pre>
 *
 * @see <a href="Eclipse Bug 354014">https://bugs.eclipse.org/354014</a>
 */
@SuppressWarnings("serial")
public class BlockingServletStreamTypeLengthWrite extends AbstractFileContentServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String fileName = request.getPathInfo();
        byte[] dataBytes = loadContentFileBytes(fileName);

        ServletOutputStream out = response.getOutputStream();

        if (fileName.endsWith("txt"))
            response.setContentType("text/plain");
        else if (fileName.endsWith("mp3"))
            response.setContentType("audio/mpeg");
        response.setHeader("ETag", "W/etag-" + fileName);

        response.setContentLength(dataBytes.length);

        out.write(dataBytes);
    }
}
