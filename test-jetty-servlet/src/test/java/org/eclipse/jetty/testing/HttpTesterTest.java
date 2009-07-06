// ========================================================================
// Copyright (c) 2007-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.testing;

import junit.framework.TestCase;

public class HttpTesterTest extends TestCase
{
    
    public void testCharset() throws Exception
    {
        HttpTester tester = new HttpTester();
        tester.parse(
                "POST /uri\uA74A HTTP/1.1\r\n"+
                "Host: fakehost\r\n"+
                "Content-Length: 12\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "123456789\uA74A");
        assertEquals("POST",tester.getMethod());
        assertEquals("/uri\uA74A",tester.getURI());
        assertEquals("HTTP/1.1",tester.getVersion());
        assertEquals("fakehost",tester.getHeader("Host"));
        assertEquals("text/plain; charset=utf-8",tester.getContentType());
        assertEquals("utf-8",tester.getCharacterEncoding());
        assertEquals("123456789\uA74A",tester.getContent());
    }

}
