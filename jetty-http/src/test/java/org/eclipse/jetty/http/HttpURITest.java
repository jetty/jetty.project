//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
    public static final String __input = "http://example.com:8080/path/to/context?parameter=%22value%22#fragment"; 
    public static final String __scheme = "http";
    public static final String __host = "example.com";
    public static final int    __port = 8080;
    public static final String __path = "/path/to/context";
    public static final String __query = "parameter=%22value%22";
    public static final String __fragment = "fragment";
    
    /* ------------------------------------------------------------ */
    @Test
    public void testFromString() throws Exception
    {
        HttpURI uri = new HttpURI(__input);
        
        assertEquals(__scheme, uri.getScheme());
        assertEquals(__host,uri.getHost());
        assertEquals(__port,uri.getPort());
        assertEquals(__path,uri.getPath());
        assertEquals(__query,uri.getQuery());
        assertEquals(__fragment,uri.getFragment());
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testFromURI() throws Exception
    {
        HttpURI uri = new HttpURI(new URI(__input));
        
        assertEquals(__scheme, uri.getScheme());
        assertEquals(__host,uri.getHost());
        assertEquals(__port,uri.getPort());
        assertEquals(__path,uri.getPath());
        assertEquals(__query,uri.getQuery());
        assertEquals(__fragment,uri.getFragment());
    }
}
