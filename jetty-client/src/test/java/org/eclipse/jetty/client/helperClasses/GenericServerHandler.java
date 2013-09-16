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

package org.eclipse.jetty.client.helperClasses;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Generic Server Handler used for various client tests.
 */
public class GenericServerHandler extends AbstractHandler
{
    private static final Logger LOG = Log.getLogger(GenericServerHandler.class);

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        int i = 0;
        try
        {
            baseRequest.setHandled(true);
            response.setStatus(200);

            if (request.getServerName().equals("jetty.eclipse.org"))
            {
                response.getOutputStream().println("Proxy request: " + request.getRequestURL());
                response.getOutputStream().println(request.getHeader(HttpHeaders.PROXY_AUTHORIZATION));
            }
            else if (request.getMethod().equalsIgnoreCase("GET"))
            {
                response.getOutputStream().println("<hello>");
                for (; i < 100; i++)
                {
                    response.getOutputStream().println("  <world>" + i + "</world");
                    if (i % 20 == 0)
                        response.getOutputStream().flush();
                }
                response.getOutputStream().println("</hello>");
            }
            else if (request.getMethod().equalsIgnoreCase("OPTIONS"))
            {
                if ("*".equals(target))
                {
                    response.setContentLength(0);
                    response.setHeader("Allow","GET,HEAD,POST,PUT,DELETE,MOVE,OPTIONS,TRACE");
                }
            }
            else if (request.getMethod().equalsIgnoreCase("SLEEP"))
            {
                Thread.sleep(10000);
            }
            else
            {
                response.setContentType(request.getContentType());
                int size = request.getContentLength();
                ByteArrayOutputStream bout = new ByteArrayOutputStream(size > 0?size:32768);
                IO.copy(request.getInputStream(),bout);
                response.getOutputStream().write(bout.toByteArray());
            }
        }
        catch (InterruptedException e)
        {
            LOG.debug(e);
        }
        catch (EofException e)
        {
            LOG.info(e.toString());
            LOG.debug(e);
            throw e;
        }
        catch (IOException e)
        {
            LOG.warn(e);
            throw e;
        }
        catch (Throwable e)
        {
            LOG.warn(e);
            throw new ServletException(e);
        }
    }
}
