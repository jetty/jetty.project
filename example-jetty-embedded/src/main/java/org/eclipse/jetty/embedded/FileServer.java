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

package org.eclipse.jetty.embedded;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/** File server
 * Usage - java org.eclipse.jetty.server.example.FileServer [ port [ docroot ]]
 * @author gregw
 *
 */
public class FileServer
{
    public static void main(String[] args)
        throws Exception
    {
        Server server = new Server(args.length==0?8080:Integer.parseInt(args[0]));
        
        ResourceHandler resource_handler=new ResourceHandler()
        {
            protected void doDirectory(HttpServletRequest request, HttpServletResponse response, Resource resource) throws IOException
            {
                String listing=resource.getListHTML(request.getRequestURI(),request.getPathInfo().lastIndexOf("/")>0);
                response.setContentType("text/html; charset=UTF-8");
                response.getWriter().println(listing);
            }
        };
        resource_handler.setWelcomeFiles(new String[]{"index.html"});
        
        resource_handler.setResourceBase(args.length==2?args[1]:".");
        Log.info("serving "+resource_handler.getBaseResource());
        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]{resource_handler,new DefaultHandler()});
        server.setHandler(handlers);
        
        server.start();
        server.join();
    }

}
