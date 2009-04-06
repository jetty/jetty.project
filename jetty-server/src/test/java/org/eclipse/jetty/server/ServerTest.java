// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import java.util.Random;

import junit.framework.TestCase;

import org.eclipse.jetty.server.handler.DefaultHandler;

/**
 * @version $Revision$
 */
public class ServerTest extends TestCase
{
    /**
     * JETTY-87, adding a handler to a server without any handlers should not
     * throw an exception
     */
    public void testAddHandlerToEmptyServer()
    {
        Server server=new Server();
        DefaultHandler handler=new DefaultHandler();
        try
        {
            server.setHandler(handler);
        }
        catch (Exception e)
        {
            fail("Adding handler "+handler+" to server "+server+" threw exception "+e);
        }
    }

    public void testServerWithPort()
    {
        int port=new Random().nextInt(20000)+10000;
        Server server=new Server(port);
        assertEquals(port,server.getConnectors()[0].getPort());
    }
}
