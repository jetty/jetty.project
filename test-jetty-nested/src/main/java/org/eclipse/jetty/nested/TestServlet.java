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

package org.eclipse.jetty.nested;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;

public class TestServlet extends HttpServlet
{

    /* (non-Javadoc)
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet(final HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.setContentType("text/plain");
        final PrintStream out = new PrintStream(resp.getOutputStream());
        
        out.println("Try out evil things.");

        try
        {
            out.println("\nList home dir...");
            for (File f : new File("/home").listFiles())
                out.println(f);
        }
        catch(Throwable e)
        {
            e.printStackTrace(out);
        }
        try
        {
            out.println("\nList tmp dir...");
            for (File f : new File("/var/tmp").listFiles())
                out.println(f);
        }
        catch(Throwable e)
        {
            e.printStackTrace(out);
        }
        
        try
        {
            out.println("\nCreate a /var/tmp file...");
            File file = new File("/var/tmp/eviltest");

            out.println(file+" exists="+file.exists());
            file.createNewFile();
            file.deleteOnExit();
            out.println(file+" exists="+file.exists());
            file.delete();
        }
        catch(Throwable e)
        {
            e.printStackTrace(out);
        }
        

        try
        {
            out.println("\nOpen a localhost server socket ...");
            
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress("localhost",0));
            out.println("local port = "+socket.getLocalPort());
        }
        catch(Throwable e)
        {
            e.printStackTrace(out);
        }
        
        try
        {
            out.println("\nOpen a any server socket ...");
            
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress(0));
            out.println("local port = "+socket.getLocalPort());
        }
        catch(Throwable e)
        {
            e.printStackTrace(out);
        }
        try
        {
            out.println("\nTalk to any server socket ...");
            
            final ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(0));
            out.println("local port = "+server.getLocalPort());
            final int port = server.getLocalPort();
            
            final CountDownLatch latch = new CountDownLatch(1);
            
            new Thread()
            {
                public void run()
                {
                    try
                    {
                        Socket inbound = server.accept();
                        out.println("accepted "+inbound);
                        BufferedReader in = new BufferedReader(new InputStreamReader(inbound.getInputStream()));
                        String data= in.readLine();
                        out.println("read "+data);
                    }
                    catch(Throwable e)
                    {
                        e.printStackTrace(out);
                    }
                    finally
                    {
                        latch.countDown();
                    }
                }
            }.start();
            
           
            Socket socket = new Socket("localhost",port); 
            socket.getOutputStream().write("Hello World\n".getBytes());
            
            latch.await();
            socket.close();
        }
        catch(Throwable e)
        {
            e.printStackTrace(out);
        }
        
        try
        {
            out.println("\nRead to own content ...");
            out.println("Real path / = "+getServletContext().getRealPath("/"));
           
            for (File f : new File(getServletContext().getRealPath("/")).listFiles())
                out.println(f);
            
        }
        catch(Throwable e)
        {
            e.printStackTrace(out);
        }
        
        
        try
        {
            out.println("\nWrite own content ...");
            
            File wibble = new File(getServletContext().getRealPath("/wibble.txt"));
            if (!wibble.exists())
                wibble.createNewFile();
            
            for (File f : new File(getServletContext().getRealPath("/")).listFiles())
                out.println(f);
            
        }
        catch(Throwable e)
        {
            e.printStackTrace(out);
        }
     
        out.flush();
        out.close();
    }
    

}
