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

package org.eclipse.jetty.rhttp.client;

import java.io.IOException;

import org.apache.http.HttpHost;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.eclipse.jetty.rhttp.client.ApacheClient;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;


/**
 * @version $Revision$ $Date$
 */
public class ApacheClientTest extends ClientTest
{
    {
        ((StdErrLog)Log.getLog()).setHideStacks(!Log.getLog().isDebugEnabled());
    }
    
    private ClientConnectionManager connectionManager;

    protected RHTTPClient createClient(int port, String targetId) throws Exception
    {
        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), port));
        connectionManager = new ThreadSafeClientConnManager(new BasicHttpParams(), schemeRegistry);
        HttpParams httpParams = new BasicHttpParams();
        httpParams.setParameter("http.default-host", new HttpHost("localhost", port));
        DefaultHttpClient httpClient = new DefaultHttpClient(connectionManager, httpParams);
        httpClient.setHttpRequestRetryHandler(new NoRetryHandler());
        return new ApacheClient(httpClient, "", targetId);
    }

    protected void destroyClient(RHTTPClient client) throws Exception
    {
        connectionManager.shutdown();
    }

    private class NoRetryHandler implements HttpRequestRetryHandler
    {
        public boolean retryRequest(IOException x, int failedAttempts, HttpContext httpContext)
        {
            return false;
        }
    }
}
