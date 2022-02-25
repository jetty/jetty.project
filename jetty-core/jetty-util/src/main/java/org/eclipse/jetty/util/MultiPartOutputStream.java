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

package org.eclipse.jetty.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Handle a multipart MIME response.
 */
public class MultiPartOutputStream extends FilterOutputStream
{

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] DASHDASH = {'-', '-'};

    public static final String MULTIPART_MIXED = "multipart/mixed";
    public static final String MULTIPART_X_MIXED_REPLACE = "multipart/x-mixed-replace";

    private final String boundary;
    private final byte[] boundaryBytes;

    private boolean inPart = false;

    public MultiPartOutputStream(OutputStream out)
        throws IOException
    {
        super(out);

        boundary = "jetty" + System.identityHashCode(this) +
            Long.toString(System.currentTimeMillis(), 36);
        boundaryBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);
    }

    public MultiPartOutputStream(OutputStream out, String boundary)
        throws IOException
    {
        super(out);

        this.boundary = boundary;
        boundaryBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * End the current part.
     *
     * @throws IOException IOException
     */
    @Override
    public void close()
        throws IOException
    {
        try
        {
            if (inPart)
                out.write(CRLF);
            out.write(DASHDASH);
            out.write(boundaryBytes);
            out.write(DASHDASH);
            out.write(CRLF);
            inPart = false;
        }
        finally
        {
            super.close();
        }
    }

    public String getBoundary()
    {
        return boundary;
    }

    public OutputStream getOut()
    {
        return out;
    }

    /**
     * Start creation of the next Content.
     *
     * @param contentType the content type of the part
     * @throws IOException if unable to write the part
     */
    public void startPart(String contentType)
        throws IOException
    {
        if (inPart)
        {
            out.write(CRLF);
        }
        inPart = true;
        out.write(DASHDASH);
        out.write(boundaryBytes);
        out.write(CRLF);
        if (contentType != null)
        {
            out.write(("Content-Type: " + contentType).getBytes(StandardCharsets.ISO_8859_1));
            out.write(CRLF);
        }
        out.write(CRLF);
    }

    /**
     * Start creation of the next Content.
     *
     * @param contentType the content type of the part
     * @param headers the part headers
     * @throws IOException if unable to write the part
     */
    public void startPart(String contentType, String[] headers)
        throws IOException
    {
        if (inPart)
            out.write(CRLF);
        inPart = true;
        out.write(DASHDASH);
        out.write(boundaryBytes);
        out.write(CRLF);
        if (contentType != null)
        {
            out.write(("Content-Type: " + contentType).getBytes(StandardCharsets.ISO_8859_1));
            out.write(CRLF);
        }
        for (int i = 0; headers != null && i < headers.length; i++)
        {
            out.write(headers[i].getBytes(StandardCharsets.ISO_8859_1));
            out.write(CRLF);
        }
        out.write(CRLF);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        out.write(b, off, len);
    }
}
