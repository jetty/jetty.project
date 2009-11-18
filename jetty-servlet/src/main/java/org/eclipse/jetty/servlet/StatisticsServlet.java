// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.log.Log;

public class StatisticsServlet extends HttpServlet
{
    boolean _restrictToLocalhost = true; // defaults to true
    private StatisticsHandler _statsHandler;
    private MemoryMXBean _memoryBean;
    private Connector[] _connectors;

    public void init() throws ServletException
    {
        ServletContext context = getServletContext();
        ContextHandler.Context scontext = (ContextHandler.Context) context;
        Server _server = scontext.getContextHandler().getServer();

        Handler handler = _server.getChildHandlerByClass(StatisticsHandler.class);

        if (handler != null)
        {
            _statsHandler = (StatisticsHandler) handler;
        }
        else
        {
            Log.warn("Statistics Handler not installed!");
            return;
        }
        
        _memoryBean = ManagementFactory.getMemoryMXBean();
        _connectors = _server.getConnectors();

        if (getInitParameter("restrictToLocalhost") != null)
        {
            _restrictToLocalhost = "true".equals(getInitParameter("restrictToLocalhost"));
        }

    }

    public void doPost(HttpServletRequest sreq, HttpServletResponse sres) throws ServletException, IOException
    {
        doGet(sreq, sres);
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        if (_statsHandler == null)
        {
            Log.warn("Statistics Handler not installed!");
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (_restrictToLocalhost)
        {
            if (!"127.0.0.1".equals(req.getRemoteAddr()))
            {
                resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }

        String wantXml = req.getParameter("xml");
        if (wantXml == null)
          wantXml = req.getParameter("XML");

        if (wantXml != null && "true".equalsIgnoreCase(wantXml))
        {
            sendXmlResponse(resp);
        }
        else
        {
            sendTextResponse(resp);
        }

    }

    private void sendXmlResponse(HttpServletResponse response) throws IOException
    {
        StringBuilder sb = new StringBuilder();

        sb.append("<statistics>\n");

        sb.append("  <requests>\n");
        sb.append("    <statsOnMs>").append(_statsHandler.getStatsOnMs()).append("</statsOnMs>\n");
        
        sb.append("    <requests>").append(_statsHandler.getRequests()).append("</requests>\n");
        sb.append("    <requestsActive>").append(_statsHandler.getRequestsActive()).append("</requestsActive>\n");
        sb.append("    <requestsActiveMax>").append(_statsHandler.getRequestsActiveMax()).append("</requestsActiveMax>\n");
        sb.append("    <requestsTimeTotal>").append(_statsHandler.getRequestTimeTotal()).append("</requestsTimeTotal>\n");
        sb.append("    <requestsTimeAverage>").append(_statsHandler.getRequestTimeAverage()).append("</requestsTimeAverage>\n");
        sb.append("    <requestsTimeMax>").append(_statsHandler.getRequestTimeMax()).append("</requestsTimeMax>\n");

        sb.append("    <dispatched>").append(_statsHandler.getDispatched()).append("</dispatched>\n");
        sb.append("    <dispatchedActive>").append(_statsHandler.getDispatchedActive()).append("</dispatchedActive>\n");
        sb.append("    <dispatchedActiveMax>").append(_statsHandler.getDispatchedActiveMax()).append("</dispatchedActiveMax>\n");
        sb.append("    <dispatchedTimeTotal>").append(_statsHandler.getDispatchedTimeTotal()).append("</dispatchedTimeTotal>\n");
        sb.append("    <dispatchedTimeAverage>").append(_statsHandler.getDispatchedTimeAverage()).append("</dispatchedTimeAverage>\n");
        sb.append("    <dispatchedTimeMax>").append(_statsHandler.getDispatchedTimeMax()).append("</dispatchedTimeMax>\n");
        
        sb.append("    <requestsSuspended>").append(_statsHandler.getSuspends()).append("</requestsSuspended>\n");
        sb.append("    <requestsExpired>").append(_statsHandler.getExpires()).append("</requestsExpired>\n");
        sb.append("    <requestsResumed>").append(_statsHandler.getResumes()).append("</requestsResumed>\n");
        sb.append("  </requests>\n");

        sb.append("  <responses>\n");
        sb.append("    <responses1xx>").append(_statsHandler.getResponses1xx()).append("</responses1xx>\n");
        sb.append("    <responses2xx>").append(_statsHandler.getResponses2xx()).append("</responses2xx>\n");
        sb.append("    <responses3xx>").append(_statsHandler.getResponses3xx()).append("</responses3xx>\n");
        sb.append("    <responses4xx>").append(_statsHandler.getResponses4xx()).append("</responses4xx>\n");
        sb.append("    <responses5xx>").append(_statsHandler.getResponses5xx()).append("</responses5xx>\n");
        sb.append("    <responsesBytesTotal>").append(_statsHandler.getResponsesBytesTotal()).append("</responsesBytesTotal>\n");
        sb.append("  </responses>\n");

        sb.append("  <connections>\n");
        for (Connector connector : _connectors)
        {
        	sb.append("    <connector>\n");
        	sb.append("      <name>").append(connector.getName()).append("</name>\n");
        	sb.append("      <statsOn>").append(connector.getStatsOn()).append("</statsOn>\n");
            if (connector.getStatsOn())
            {
            	sb.append("    <statsOnMs>").append(connector.getStatsOnMs()).append("</statsOnMs>\n");
            	sb.append("    <connections>").append(connector.getConnections()).append("</connections>\n");
            	sb.append("    <connectionsOpen>").append(connector.getConnectionsOpen()).append("</connectionsOpen>\n");
            	sb.append("    <connectionsOpenMin>").append(connector.getConnectionsOpenMin()).append("</connectionsOpenMin>\n");
            	sb.append("    <connectionsOpenMax>").append(connector.getConnectionsOpenMax()).append("</connectionsOpenMax>\n");
            	sb.append("    <connectionsDurationTotal>").append(connector.getConnectionsDurationTotal()).append("</connectionsDurationTotal>\n");
            	sb.append("    <connectionsDurationAve>").append(connector.getConnectionsDurationAve()).append("</connectionsDurationAve>\n");
            	sb.append("    <connectionsDurationMin>").append(connector.getConnectionsDurationMin()).append("</connectionsDurationMin>\n");
            	sb.append("    <connectionsDurationMax>").append(connector.getConnectionsDurationMax()).append("</connectionsDurationMax>\n");
                sb.append("    <requests>").append(connector.getRequests()).append("</requests>\n");
                sb.append("    <connectionsRequestsAve>").append(connector.getConnectionsRequestsAve()).append("</connectionsRequestsAve>\n");
                sb.append("    <connectionsRequestsMin>").append(connector.getConnectionsRequestsMin()).append("</connectionsRequestsMin>\n");
                sb.append("    <connectionsRequestsMax>").append(connector.getConnectionsRequestsMax()).append("</connectionsRequestsMax>\n");
            }
            sb.append("    </connector>\n");
        }
        sb.append("  </connections>\n");

        sb.append("  <memory>\n");
        sb.append("    <heapMemoryUsage>").append(_memoryBean.getHeapMemoryUsage().getUsed()).append("</heapMemoryUsage>\n");
        sb.append("    <nonHeapMemoryUsage>").append(_memoryBean.getNonHeapMemoryUsage().getUsed()).append("</nonHeapMemoryUsage>\n");
        sb.append("  </memory>\n");

        sb.append("</statistics>\n");

        response.setContentType("text/xml");
        PrintWriter pout = response.getWriter();
        pout.write(sb.toString());
    }

    private void sendTextResponse(HttpServletResponse response) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append(_statsHandler.toStatsHTML());

        sb.append("<h2>Connections:</h2>\n");
        for (Connector connector : _connectors)
        {
            sb.append("<h3>").append(connector.getName()).append("</h3>");

            if (connector.getStatsOn())
            {
                sb.append("Statistics gathering started ").append(connector.getStatsOnMs()).append("ms ago").append("<br />\n");
                sb.append("Total connections: ").append(connector.getConnections()).append("<br />\n");
                sb.append("Current connections open: ").append(connector.getConnectionsOpen());
                sb.append("Min concurrent connections open: ").append(connector.getConnectionsOpenMin()).append("<br />\n");
                sb.append("Max concurrent connections open: ").append(connector.getConnectionsOpenMax()).append("<br />\n");
                sb.append("Total connections duration: ").append(connector.getConnectionsDurationTotal()).append("<br />\n");
                sb.append("Average connection duration: ").append(connector.getConnectionsDurationAve()).append("<br />\n");
                sb.append("Min connection duration: ").append(connector.getConnectionsDurationMin()).append("<br />\n");
                sb.append("Max connection duration: ").append(connector.getConnectionsDurationMax()).append("<br />\n");
                sb.append("Total requests: ").append(connector.getRequests()).append("<br />\n");
                sb.append("Average requests per connection: ").append(connector.getConnectionsRequestsAve()).append("<br />\n");
                sb.append("Min requests per connection: ").append(connector.getConnectionsRequestsMin()).append("<br />\n");
                sb.append("Max requests per connection: ").append(connector.getConnectionsRequestsMax()).append("<br />\n");
            }
            else
            {
                sb.append("Statistics gathering off.\n");
            }

        }

        sb.append("<h2>Memory:</h2>\n");
        sb.append("Heap memory usage: ").append(_memoryBean.getHeapMemoryUsage().getUsed()).append(" bytes").append("<br />\n");
        sb.append("Non-heap memory usage: ").append(_memoryBean.getNonHeapMemoryUsage().getUsed()).append(" bytes").append("<br />\n");

        response.setContentType("text/html");
        PrintWriter pout = response.getWriter();
        pout.write(sb.toString());

    }
}
