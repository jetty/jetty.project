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

package org.eclipse.jetty.servlet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ChunkedReadTest
{
	private static Server server;
	
	@BeforeClass
	public static void startServer() throws Exception
	{
	    server = new Server();
	    ServerConnector connector = new ServerConnector(server);
	    connector.setPort(0);
	    server.addConnector(connector);
	
	    ServletContextHandler context = new ServletContextHandler();
	    context.setContextPath("/");
	    context.addServlet(ChunkedReadServlet.class, "/chunked/read");
	
	    server.setHandler(context);
	    server.start();
	}

	@AfterClass
	public static void stopServer() throws Exception
	{
	    server.stop();
	}

	@Test
	public void testHttpUrlConnectionChunkedSend_Close() throws IOException
	{
		for(int i=0; i<10000; i++) {
			System.out.println(i);
		
		    HttpURLConnection http = (HttpURLConnection) server.getURI().resolve("/chunked/read").toURL().openConnection();
		    http.setChunkedStreamingMode(4096);
		    http.setDoOutput(true);
		    
		    http.setRequestProperty("number", String.valueOf(i));
		    int status = http.getResponseCode();
	        assertThat("Http Status Code", status, is(HttpURLConnection.HTTP_OK));
	        try (InputStream httpIn = http.getInputStream())
	        {
	            String response = IO.toString(httpIn);
	            System.out.println(response);
	        }
	    }
	}

	public static class ChunkedReadServlet extends HttpServlet
	{
	    private static final Logger LOG = Log.getLogger(ChunkedReadServlet.class);
	
	    @Override
	    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
	    {
	        String te = req.getHeader("Transfer-Encoding");
	        if (StringUtil.isBlank(te) || !te.equalsIgnoreCase("chunked"))
	        {
	            // Error out if "Transfer-Encoding: chunked" is not present on request.
	            LOG.warn("Request Transfer-Encoding <{}> is not expected value of <chunked>", te);
	            resp.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
	            return;
	        }
	        	        
	        String number = req.getHeader("number");
	        resp.setContentType("text/plain");
	        resp.getWriter().print(number);
	    }
	}

	static class NoOpOutputStream extends OutputStream
	{
	    @Override
	    public void write(byte[] b) { }
	
	    @Override
	    public void write(byte[] b, int off, int len) { }
	
	    @Override
	    public void flush() { }
	
	    @Override
	    public void close() { }
	
	    @Override
	    public void write(int b) { }
	}
}
