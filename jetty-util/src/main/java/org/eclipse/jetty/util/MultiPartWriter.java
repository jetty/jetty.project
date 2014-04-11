//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


/* ================================================================ */
/** Handle a multipart MIME response.
 *
 * 
 * 
*/
public class MultiPartWriter extends FilterWriter
{
    /* ------------------------------------------------------------ */
    private final static String __CRLF="\015\012";
    private final static String __DASHDASH="--";
    
    public static String MULTIPART_MIXED=MultiPartOutputStream.MULTIPART_MIXED;
    public static String MULTIPART_X_MIXED_REPLACE=MultiPartOutputStream.MULTIPART_X_MIXED_REPLACE;
    
    /* ------------------------------------------------------------ */
    private String boundary;

    /* ------------------------------------------------------------ */
    private boolean inPart=false;    
    
    /* ------------------------------------------------------------ */
    public MultiPartWriter(Writer out)
         throws IOException
    {
        super(out);
        boundary = "jetty"+System.identityHashCode(this)+
        Long.toString(System.currentTimeMillis(),36);
        
        inPart=false;
    }

    /* ------------------------------------------------------------ */
    /** End the current part.
     * @exception IOException IOException
     */
    @Override
    public void close()
         throws IOException
    {
        if (inPart)
            out.write(__CRLF);
        out.write(__DASHDASH);
        out.write(boundary);
        out.write(__DASHDASH);
        out.write(__CRLF);
        inPart=false;
        super.close();
    }
    
    /* ------------------------------------------------------------ */
    public String getBoundary()
    {
        return boundary;
    }
    
    /* ------------------------------------------------------------ */
    /** Start creation of the next Content.
     */
    public void startPart(String contentType)
         throws IOException
    {
        if (inPart)
            out.write(__CRLF);
        out.write(__DASHDASH);
        out.write(boundary);
        out.write(__CRLF);
        out.write("Content-Type: ");
        out.write(contentType);
        out.write(__CRLF);
        out.write(__CRLF);
        inPart=true;
    }
    
    /* ------------------------------------------------------------ */
    /** end creation of the next Content.
     */
    public void endPart()
         throws IOException
    {
        if (inPart)
            out.write(__CRLF);
        inPart=false;
    }
        
    /* ------------------------------------------------------------ */
    /** Start creation of the next Content.
     */
    public void startPart(String contentType, String[] headers)
         throws IOException
    {
        if (inPart)
            out.write(__CRLF);
        out.write(__DASHDASH);
        out.write(boundary);
        out.write(__CRLF);
        out.write("Content-Type: ");
        out.write(contentType);
        out.write(__CRLF);
        for (int i=0;headers!=null && i<headers.length;i++)
        {
            out.write(headers[i]);
            out.write(__CRLF);
        }
        out.write(__CRLF);
        inPart=true;
    }
    
}




