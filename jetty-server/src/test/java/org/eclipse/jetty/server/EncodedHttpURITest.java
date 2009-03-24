// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.net.URLDecoder;
import java.net.URLEncoder;

import junit.framework.TestCase;

import org.eclipse.jetty.http.EncodedHttpURI;

public class EncodedHttpURITest extends TestCase
{

    public void testNonURIAscii ()
    throws Exception
    {
        String url = "http://www.foo.com/ma\u00F1ana";
        byte[] asISO = url.getBytes("ISO-8859-1");
        String str = new String(asISO, "ISO-8859-1");
        
        //use a non UTF-8 charset as the encoding and url-escape as per
        //http://www.w3.org/TR/html40/appendix/notes.html#non-ascii-chars
        String s = URLEncoder.encode(url, "ISO-8859-1");     
        EncodedHttpURI uri = new EncodedHttpURI("ISO-8859-1");
        
        //parse it, using the same encoding
        uri.parse(s);
        
        //decode the url encoding
        String d = URLDecoder.decode(uri.getCompletePath(), "ISO-8859-1");
        assertEquals(url, d);
    }
}
