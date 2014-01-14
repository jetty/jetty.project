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


package org.eclipse.jetty.http;

import static org.junit.Assert.assertEquals;

import java.net.URI;

import org.junit.Test;


/* ------------------------------------------------------------ */
public class HttpURITest
{
    String[][] tests=
    {
        {"/path/to/context",null,null,"-1","/path/to/context",null,null,null},
        {"http://example.com/path/to/context;param?query=%22value%22#fragment","http","example.com","-1","/path/to/context","param","query=%22value%22","fragment"},
        {"http://[::1]/path/to/context;param?query=%22value%22#fragment","http","::1","-1","/path/to/context","param","query=%22value%22","fragment"},
        {"http://example.com:8080/path/to/context;param?query=%22value%22#fragment","http","example.com","8080","/path/to/context","param","query=%22value%22","fragment"},
        {"http://[::1]:8080/path/to/context;param?query=%22value%22#fragment","http","::1","8080","/path/to/context","param","query=%22value%22","fragment"},
    };
    
    public static int
    INPUT=0,SCHEME=1,HOST=2,PORT=3,PATH=4,PARAM=5,QUERY=6,FRAGMENT=7;

    /* ------------------------------------------------------------ */
    @Test
    public void testFromString() throws Exception
    {
        for (String[] test:tests)
        {
            HttpURI uri = new HttpURI(test[INPUT]);

            assertEquals(test[SCHEME], uri.getScheme());
            assertEquals(test[HOST], uri.getHost());
            assertEquals(Integer.parseInt(test[PORT]), uri.getPort());
            assertEquals(test[PATH], uri.getPath());
            assertEquals(test[PARAM], uri.getParam());
            assertEquals(test[QUERY], uri.getQuery());
            assertEquals(test[FRAGMENT], uri.getFragment());
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testFromURI() throws Exception
    {
        for (String[] test:tests)
        {
            HttpURI uri = new HttpURI(new URI(test[INPUT]));

            assertEquals(test[SCHEME], uri.getScheme());
            assertEquals(test[HOST], uri.getHost());
            assertEquals(Integer.parseInt(test[PORT]), uri.getPort());
            assertEquals(test[PATH], uri.getPath());
            assertEquals(test[PARAM], uri.getParam());
            assertEquals(test[QUERY], uri.getQuery());
            assertEquals(test[FRAGMENT], uri.getFragment());
        }
    }
}
