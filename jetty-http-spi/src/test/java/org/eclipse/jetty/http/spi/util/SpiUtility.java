//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.spi.util;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.http.spi.DelegatingThreadPool;
import org.eclipse.jetty.http.spi.JettyHttpServer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;

/**
 * This is a utility class. Test cases uses this utility class
 *
 *
 */
public class SpiUtility
{

    public static ThreadPoolExecutor getThreadPoolExecutor(int poolSize, int[] poolInfo)
    {
        return new ThreadPoolExecutor(poolSize,poolInfo[0],poolInfo[1],TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(poolInfo[2]));
    }

    public static DelegatingThreadPool getDelegatingThreadPool()
    {
        ThreadPoolExecutor threadPoolExecutor = SpiUtility.getThreadPoolExecutor(Pool.CORE_POOL_SIZE.getValue(),SpiConstants.poolInfo);
        DelegatingThreadPool delegatingThreadPool = new DelegatingThreadPool(threadPoolExecutor);
        return delegatingThreadPool;
    }

    public static InetSocketAddress getInetSocketAddress()
    {
        return new InetSocketAddress(SpiConstants.LOCAL_HOST,SpiConstants.DEFAULT_PORT);
    }

    public static void callBind(JettyHttpServer jettyHttpServer) throws Exception
    {
        InetSocketAddress inetSocketAddress = SpiUtility.getInetSocketAddress();
        jettyHttpServer.bind(inetSocketAddress,SpiConstants.BACK_LOG);
    }

    public static Server getServerForContextHandler()
    {
        Handler handler = new ContextHandler();
        Server server = new Server();
        server.setHandler(handler);
        return server;
    }

    public static Server getServerForContextHandlerCollection()
    {
        Handler handler = new ContextHandlerCollection();
        Server server = new Server();
        server.setHandler(handler);
        return server;
    }

    public static Server getServerForHandlerCollection()
    {
        ContextHandler handler = new ContextHandler();
        Handler[] handles =
        { handler };
        HandlerCollection contextHandler = new HandlerCollection();
        contextHandler.setHandlers(handles);
        Server server = new Server();
        server.setHandler(contextHandler);
        return server;
    }

    public static Map<String, List<String>> getAcceptCharsetHeader()
    {
        ArrayList<String> valueSet = new ArrayList<String>();
        valueSet.add(SpiConstants.UTF_8);
        Map<String, List<String>> headers = new Hashtable<>();
        headers.put(SpiConstants.ACCEPT_CHARSET,valueSet);
        return headers;
    }

    public static List<String> getReqHeaderValues()
    {
        List<String> reqHeaderValues = new ArrayList<>();
        reqHeaderValues.add("en-US");
        return reqHeaderValues;
    }
}
