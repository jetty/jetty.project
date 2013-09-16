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

package org.eclipse.jetty.server.handler;

import java.net.URI;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.SimpleRequest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Resource Handler test
 * 
 * TODO: increase the testing going on here
 */
public class ResourceHandlerTest extends TestCase
{
    private static Server _server;
    private static Connector _connector;
    private static ContextHandler _contextHandler;
    private static ResourceHandler _resourceHandler;
 
    
    @BeforeClass
    public void setUp() throws Exception
    {
        _server = new Server();
        _connector = new SocketConnector();
        _server.setConnectors(new Connector[] { _connector });

        _resourceHandler = new ResourceHandler();

        _contextHandler = new ContextHandler("/resource");
        _contextHandler.setHandler(_resourceHandler);
        _server.setHandler(_contextHandler);
        _server.start();
    }
    
    /* ------------------------------------------------------------ */
    @AfterClass
    public void tearDown() throws Exception
    {
        _server.stop();
    }
    
    @Test
    public void testSimpleResourceHandler() throws Exception
    {
        _resourceHandler.setResourceBase(MavenTestingUtils.getTestResourceDir("simple").getAbsolutePath());
        
        SimpleRequest sr = new SimpleRequest(new URI("http://localhost:" + _connector.getLocalPort()));
        
        Assert.assertEquals("simple text", sr.getString("/resource/simple.txt"));
        
        Assert.assertNotNull("missing jetty.css" , sr.getString("/resource/jetty-dir.css"));     
    }
    
}
