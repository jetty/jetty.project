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

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A sample servlet to serve static content, using a order of construction that has caused problems for
 * {@link GzipHandler} in the past.
 * 
 * Using a real-world pattern of:
 * 
 * <pre>
 *  1) get stream
 *  2) set content length
 *  3) set content type
 *  4) write and flush
 * </pre>
 * 
 * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
 */
@SuppressWarnings("serial")
public class TestServletStreamLengthTypeWriteWithFlush extends TestDirContentServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String fileName = request.getServletPath();
        byte[] dataBytes = loadContentFileBytes(fileName);

        ServletOutputStream out = response.getOutputStream();

        // set content-length of uncompressed content (GzipHandler should handle this)
        response.setContentLength(dataBytes.length);
        
        if (fileName.endsWith("txt"))
            response.setContentType("text/plain");
        else if (fileName.endsWith("mp3"))
            response.setContentType("audio/mpeg");
        response.setHeader("ETag","W/etag-"+fileName);

        for ( int i = 0 ; i < dataBytes.length ; i++)
        {
            out.write(dataBytes[i]);
            // flush using response object (not the stream itself)
            response.flushBuffer();
        }
    }
}
