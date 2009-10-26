/*
 * Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;

/**
 * @version $Revision: 889 $ $Date: 2009-09-14 14:52:16 +1000 (Mon, 14 Sep 2009) $
 */
public class AsyncUploadTest extends TestCase
{
    int _total;
    
    public void test() throws Exception
    {
        Server server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);

        server.setHandler(new EmptyHandler());

        server.start();
        try
        {
            _total=0;
            final Socket socket =  new Socket("localhost",connector.getLocalPort());

            byte[] content = new byte[16*4096];
            Arrays.fill(content, (byte)120);
            
            long start = System.nanoTime();
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\n".getBytes());
            out.write("Host: localhost\r\n".getBytes());
            out.write(("Content-Length: "+content.length+"\r\n").getBytes());
            out.write("Content-Type: bytes\r\n".getBytes());
            out.write("Connection: close\r\n".getBytes());
            out.write("\r\n".getBytes());
            out.flush();

            out.write(content,0,4*4096);
            Thread.sleep(100);
            out.write(content,8192,4*4096);
            Thread.sleep(100);
            out.write(content,8*4096,content.length-8*4096);
            
            out.flush();
            
            InputStream in = socket.getInputStream();
            String response = IO.toString(in);
            // System.err.println(response);
            assertTrue(response.indexOf("200 OK")>0);

            long end = System.nanoTime();
            System.err.println("upload time: " + TimeUnit.NANOSECONDS.toMillis(end - start));
            assertEquals(content.length,_total);
            
        }
        finally
        {
            server.stop();
        }
    }

    private class EmptyHandler extends AbstractHandler
    {
        public void handle(String path, final Request request, HttpServletRequest httpRequest, final HttpServletResponse httpResponse) throws IOException, ServletException
        {
            // System.out.println("path = " + path);
            
            final Continuation continuation = ContinuationSupport.getContinuation(request);
            httpResponse.setStatus(500);
            request.setHandled(true);
            
            new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        Thread.sleep(100);
                        InputStream in = request.getInputStream();
                        byte[] b = new byte[4*4096];
                        int l;

                        while((l=in.read(b))>=0)
                        {
                            // System.err.println("read "+l);
                            _total+=l;
                        }
                        
                        System.err.println("Read "+_total);
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                        _total=-1;
                    }
                    finally
                    {
                        httpResponse.setStatus(200);
                        continuation.complete();
                    }
                }
            }.start();
            
            continuation.suspend();
        }
    }

    private class EmptyHostnameVerifier implements HostnameVerifier
    {
        public boolean verify(String s, SSLSession sslSession)
        {
            return true;
        }
    }
}
