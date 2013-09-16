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

package org.eclipse.jetty.io.bio;

import java.net.ServerSocket;
import java.net.Socket;

import org.eclipse.jetty.io.EndPointTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class SocketEndPointTest extends EndPointTest<SocketEndPoint>
{
    static ServerSocket connector;
    
    @BeforeClass
    public static void open() throws Exception
    {
        connector = new ServerSocket();
        connector.bind(null);
    }

    @AfterClass
    public static void close() throws Exception
    {
        connector.close();
        connector=null;
    }

    @Override
    protected EndPointPair<SocketEndPoint> newConnection() throws Exception
    {
        EndPointPair<SocketEndPoint> c = new EndPointPair<SocketEndPoint>();
        c.client=new SocketEndPoint(new Socket(connector.getInetAddress(),connector.getLocalPort()));
        c.server=new SocketEndPoint(connector.accept());
        return c;
    }
    

}
