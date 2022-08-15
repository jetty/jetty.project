//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.demos;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.DefaultHandler;

/**
 * Frequently many handlers are combined together to handle different aspects of
 * a request. A handler may:
 * <ul>
 * <li>handle the request and completely generate the response
 * <li>partially handle the request, but defer response generation to another
 * handler.
 * <li>select another handler to pass the request to.
 * <li>use business logic to decide to do one of the above.
 * </ul>
 * Multiple handlers may be combined with:
 * <ul>
 * <li>{@link Handler.Wrapper} which will nest one handler inside another. In
 * this example, the HelloHandler is nested inside a HandlerWrapper that sets
 * the greeting as a request attribute.
 * <li>{@link Handler.Collection} which will call a collection of handlers until the
 * request is marked as handled. In this example, a list is used to combine the
 * param handler (which only handles the request if there are parameters) and
 * the wrapper handler. Frequently handler lists are terminated with the
 * {@link DefaultHandler}, which will generate a suitable 404 response if the
 * request has not been handled.
 * <li>{@link Handler.Collection} which will call each handler regardless if the
 * request has been handled or not. Typically this is used to always pass a
 * request to the logging handler.
 * </ul>
 */
public class ManyHandlers
{
    //TODO fix me
    /**
     * Produce output that lists all of the request parameters
     */
    /*   public static class ParamHandler extends AbstractHandler
    {
        @Override
        public void handle(String target,
                           Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException,
            ServletException
        {
            Map<String, String[]> params = request.getParameterMap();
            if (!params.isEmpty())
            {
                response.setContentType("text/plain");
                response.getWriter().println(new JSON().toJSON(params));
                baseRequest.setHandled(true);
            }
        }
    }
    
    *//**
       * Add a request attribute, but produce no output.
       *//*
          public static class WelcomeWrapHandler extends HandlerWrapper
          {
           @Override
           public void handle(String target,
                              Request baseRequest,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException,
               ServletException
           {
               response.setHeader("X-Welcome", "Greetings from WelcomeWrapHandler");
               super.handle(target, baseRequest, request, response);
           }
          }
          
          public static Server createServer(int port) throws IOException
          {
           Server server = new Server(port);
          
           // create the handlers
           Handler param = new ParamHandler();
           Handler.Wrapper wrapper = new WelcomeWrapHandler();
           Handler hello = new HelloHandler();
           GzipHandler gzipHandler = new GzipHandler();
           gzipHandler.setMinGzipSize(10);
           gzipHandler.addIncludedMimeTypes("text/plain");
           gzipHandler.addIncludedMimeTypes("text/html");
          
           // configure request logging
           Path requestLogFile = Files.createTempFile("demo", "log");
           CustomRequestLog ncsaLog = new CustomRequestLog(requestLogFile.toString());
           server.setRequestLog(ncsaLog);
          
           // create the handlers list
           Handler.Collection handlers = new Handler.Collection();
          
           // wrap contexts around specific handlers
           wrapper.setHandler(hello);
           ContextHandler helloContext = new ContextHandler("/hello");
           helloContext.setHandler(wrapper);
          
           ContextHandler paramContext = new ContextHandler("/params");
           paramContext.setHandler(param);
          
           ContextHandlerCollection contexts = new ContextHandlerCollection(helloContext, paramContext);
          
           // Wrap Contexts with GZIP
           gzipHandler.setHandler(contexts);
          
           // Set the top level Handler List
           handlers.addHandler(gzipHandler);
           handlers.addHandler(new DefaultHandler());
           server.setHandler(handlers);
          
            At this point you have the following handler hierarchy.
            *
            * Server.handler:
            * HandlerList
            *    \- GzipHandler
            *    |   \- ContextHandlerCollection
            *    |       \- ContextHandler ("/hello")
            *    |       |   \- WelcomeWrapHandler
            *    |       |       \- HelloHandler
            *    |       \- ContextHandler ("/params")
            *    |           \- ParamHandler
            *    \- DefaultHandler
            
          
           return server;
          }
          
          public static void main(String[] args) throws Exception
          {
           int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
           Server server = createServer(port);
           server.start();
           server.join();
          }*/
}
