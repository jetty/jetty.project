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
                "POST /uri© HTTP/1.1\r\n"+
                "Host: fakehost\r\n"+
                "Content-Length: 11\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "123456789©");
        System.err.println(tester.getMethod());
        System.err.println(tester.getURI());
        System.err.println(tester.getVersion());
        System.err.println(tester.getHeader("Host"));
        System.err.println(tester.getContentType());
        System.err.println(tester.getCharacterEncoding());
        System.err.println(tester.getContent());
        assertEquals(tester.getContent(), "123456789©");
        System.err.println(tester.generate());
    }

}
