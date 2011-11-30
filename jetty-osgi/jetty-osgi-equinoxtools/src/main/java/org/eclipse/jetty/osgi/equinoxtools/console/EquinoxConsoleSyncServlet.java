// ========================================================================
// Copyright (c) 2006-2011 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.osgi.equinoxtools.console;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * Take the example ChatServlet and use it to  make an Equinox Console on the web.
 */
public class EquinoxConsoleSyncServlet extends HttpServlet
{

    private static final long serialVersionUID = 1L;
    
    private WebConsoleSession _consoleSession;

    public EquinoxConsoleSyncServlet(WebConsoleSession consoleSession)
    {
    	_consoleSession = consoleSession;
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
    	String cmd = request.getParameter("cmd");
    	String Action = request.getParameter("Action");
    	if (Action != null && Action.toLowerCase().indexOf("clear") != -1)
    	{
            _consoleSession.clearOutput();
    	}
    	if (cmd != null)
    	{
    	    _consoleSession.processCommand(cmd, true);
    	}
    	
        response.setContentType("text/html;charset=utf-8");
    	PrintWriter p = response.getWriter();
        p.println("<html><head><title>Equinox Console (Synchroneous)</title></head><body>");
        p.println("<textarea rows=\"30\" cols=\"110\">");
    	p.println(_consoleSession.getOutputAsWriter().toString());
        p.println("</textarea>");
        p.println("<form method=\"GET\" action=\""+response.encodeURL(getURI(request))+"\">");
        p.println("osgi>&nbsp;<input type=\"text\" name=\"cmd\" value=\"\"/><br/>\n");
        p.println("<input type=\"submit\" name=\"Action\" value=\"Submit or Refresh\"><br/>");
        p.println("<input type=\"submit\" name=\"Action\" value=\"Clear and Submit\"><br/>");
        p.println("</form>");
        p.println("<br/>");
    }
    
    
    private String getURI(HttpServletRequest request)
    {
        String uri= (String)request.getAttribute("javax.servlet.forward.request_uri");
        if (uri == null)
            uri= request.getRequestURI();
        return uri;
    }

}
