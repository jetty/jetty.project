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
import org.eclipse.jetty.http.MimeTypes;

/**
 * Test servlet for testing against unusual MimeTypes and Content-Types.
 */
@SuppressWarnings("serial")
public class TestStaticMimeTypeServlet extends AbstractFileContentServlet
{
    private MimeTypes mimeTypes;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        mimeTypes = new MimeTypes();
        // Some real world, yet not terribly common, mime type mappings.
        mimeTypes.addMimeMapping("bz2", "application/bzip2");
        mimeTypes.addMimeMapping("br", "application/brotli");
        mimeTypes.addMimeMapping("bmp", "image/bmp");
        mimeTypes.addMimeMapping("tga", "application/tga");
        mimeTypes.addMimeMapping("xcf", "image/xcf");
        mimeTypes.addMimeMapping("jp2", "image/jpeg2000");

        // Some of the other gzip mime-types seen in the wild.
        // NOTE: Using oddball extensions just so that the calling request can specify
        //       which strange mime type to use.
        mimeTypes.addMimeMapping("x-gzip", "application/x-gzip");
        mimeTypes.addMimeMapping("x-gunzip", "application/x-gunzip");
        mimeTypes.addMimeMapping("gzipped", "application/gzippped");
        mimeTypes.addMimeMapping("gzip-compressed", "application/gzip-compressed");
        mimeTypes.addMimeMapping("x-compressed", "application/x-compressed");
        mimeTypes.addMimeMapping("x-compress", "application/x-compress");
        mimeTypes.addMimeMapping("gzipdoc", "gzip/document");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String fileName = request.getPathInfo();
        byte[] dataBytes = loadContentFileBytes(fileName);

        response.setContentLength(dataBytes.length);
        response.setHeader("ETag", "W/etag-" + fileName);

        String mime = mimeTypes.getMimeByExtension(fileName);
        if (mime == null)
            response.setContentType("application/octet-stream");
        else
            response.setContentType(mime);

        ServletOutputStream out = response.getOutputStream();
        out.write(dataBytes);
    }
}
