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

package org.eclipse.jetty.util;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * Handle a multipart MIME response.
 */
public class MultiPartWriter extends FilterWriter
{

    private static final String CRLF = IO.CRLF;
    private static final String DASHDASH = "--";

    public static final String MULTIPART_MIXED = MultiPartOutputStream.MULTIPART_MIXED;
    public static final String MULTIPART_X_MIXED_REPLACE = MultiPartOutputStream.MULTIPART_X_MIXED_REPLACE;

    private String boundary;

    private boolean inPart = false;

    public MultiPartWriter(Writer out)
        throws IOException
    {
        super(out);
        boundary = "jetty" + System.identityHashCode(this) +
            Long.toString(System.currentTimeMillis(), 36);

        inPart = false;
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
            out.write(boundary);
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

    /**
     * Start creation of the next Content.
     *
     * @param contentType the content type
     * @throws IOException if unable to write the part
     */
    public void startPart(String contentType)
        throws IOException
    {
        if (inPart)
            out.write(CRLF);
        out.write(DASHDASH);
        out.write(boundary);
        out.write(CRLF);
        out.write("Content-Type: ");
        out.write(contentType);
        out.write(CRLF);
        out.write(CRLF);
        inPart = true;
    }

    /**
     * end creation of the next Content.
     *
     * @throws IOException if unable to write the part
     */
    public void endPart()
        throws IOException
    {
        if (inPart)
            out.write(CRLF);
        inPart = false;
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
        out.write(DASHDASH);
        out.write(boundary);
        out.write(CRLF);
        out.write("Content-Type: ");
        out.write(contentType);
        out.write(CRLF);
        for (int i = 0; headers != null && i < headers.length; i++)
        {
            out.write(headers[i]);
            out.write(CRLF);
        }
        out.write(CRLF);
        inPart = true;
    }
}




