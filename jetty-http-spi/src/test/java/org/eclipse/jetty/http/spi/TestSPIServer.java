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

package org.eclipse.jetty.http.spi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;


public class TestSPIServer
{
    public static void main(String[] args) throws Exception
    {
        String host="localhost";
        int port = 8080;
        
        HttpServer server = new JettyHttpServerProvider().createHttpServer(new
                InetSocketAddress(host, port), 10);
        server.start();
        
        final HttpContext httpContext = server.createContext("/",
                new HttpHandler()
        {

            public void handle(HttpExchange exchange) throws IOException
            {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type","text/plain");
                exchange.sendResponseHeaders(200,0);

                OutputStream responseBody = exchange.getResponseBody();
                Headers requestHeaders = exchange.getRequestHeaders();
                Set<String> keySet = requestHeaders.keySet();
                Iterator<String> iter = keySet.iterator();
                while (iter.hasNext())
                {
                    String key = iter.next();
                    List values = requestHeaders.get(key);
                    String s = key + " = " + values.toString() + "\n";
                    responseBody.write(s.getBytes());
                }
                responseBody.close();

            }
        });
        
        httpContext.setAuthenticator(new BasicAuthenticator("Test")
        {
            @Override
            public boolean checkCredentials(String username, String password)
            {
                if ("username".equals(username) && password.equals("password"))
                    return true;
                return false;
            }
        });
          
        
        Thread.sleep(10000000);
                
    }
}
