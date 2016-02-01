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

package org.eclipse.jetty.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;


/** Handle a multipart MIME response.
 *
 * 
 * 
*/
public class MultiPartOutputStream extends FilterOutputStream
{
    /* ------------------------------------------------------------ */
    private static final byte[] __CRLF={'\r','\n'};
    private static final byte[] __DASHDASH={'-','-'};
    
    public static final String MULTIPART_MIXED="multipart/mixed";
    public static final String MULTIPART_X_MIXED_REPLACE="multipart/x-mixed-replace";
    
    /* ------------------------------------------------------------ */
    private final String boundary;
    private final byte[] boundaryBytes;

    /* ------------------------------------------------------------ */
    private boolean inPart=false;    
    
    /* ------------------------------------------------------------ */
    public MultiPartOutputStream(OutputStream out)
    throws IOException
    {
        super(out);

        boundary = "jetty"+System.identityHashCode(this)+
        Long.toString(System.currentTimeMillis(),36);
        boundaryBytes=boundary.getBytes(StandardCharsets.ISO_8859_1);
    }

    public MultiPartOutputStream(OutputStream out, String boundary)
         throws IOException
    {
        super(out);

        this.boundary = boundary;
        boundaryBytes=boundary.getBytes(StandardCharsets.ISO_8859_1);
    }

    /* ------------------------------------------------------------ */
    /** End the current part.
     * @exception IOException IOException
     */
    @Override
    public void close()
         throws IOException
    {
        try
        {
            if (inPart)
                out.write(__CRLF);
            out.write(__DASHDASH);
            out.write(boundaryBytes);
            out.write(__DASHDASH);
            out.write(__CRLF);
            inPart=false;
        }
        finally
        {
            super.close();
        }
    }
    
    /* ------------------------------------------------------------ */
    public String getBoundary()
    {
        return boundary;
    }

    public OutputStream getOut() {return out;}
    
    /* ------------------------------------------------------------ */
    /** Start creation of the next Content.
     * @param contentType the content type of the part
     * @throws IOException if unable to write the part
     */
    public void startPart(String contentType)
         throws IOException
    {
        if (inPart)
            out.write(__CRLF);
        inPart=true;
        out.write(__DASHDASH);
        out.write(boundaryBytes);
        out.write(__CRLF);
        if (contentType != null)
            out.write(("Content-Type: "+contentType).getBytes(StandardCharsets.ISO_8859_1));
        out.write(__CRLF);
        out.write(__CRLF);
    }
        
    /* ------------------------------------------------------------ */
    /** Start creation of the next Content.
     * @param contentType the content type of the part
     * @param headers the part headers
     * @throws IOException if unable to write the part
     */
    public void startPart(String contentType, String[] headers)
         throws IOException
    {
        if (inPart)
            out.write(__CRLF);
        inPart=true;
        out.write(__DASHDASH);
        out.write(boundaryBytes);
        out.write(__CRLF);
        if (contentType != null)
            out.write(("Content-Type: "+contentType).getBytes(StandardCharsets.ISO_8859_1));
        out.write(__CRLF);
        for (int i=0;headers!=null && i<headers.length;i++)
        {
            out.write(headers[i].getBytes(StandardCharsets.ISO_8859_1));
            out.write(__CRLF);
        }
        out.write(__CRLF);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        out.write(b,off,len);
    }
}




