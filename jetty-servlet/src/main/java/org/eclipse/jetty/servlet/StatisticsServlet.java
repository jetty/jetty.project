//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ConnectorStatistics;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Collect and report statistics about requests / responses / connections and more.
 * <p>
 * You can use normal HTTP content negotiation to ask for the statistics.
 * Specify a request <code>Accept</code> header for one of the following formats:
 * </p>
 * <ul>
 *     <li><code>application/json</code></li>
 *     <li><code>text/xml</code></li>
 *     <li><code>text/html</code></li>
 *     <li><code>text/plain</code> - default if no <code>Accept</code> header specified</li>
 * </ul>
 */
public class StatisticsServlet extends HttpServlet
{
    private static final Logger LOG = Log.getLogger(StatisticsServlet.class);

    boolean _restrictToLocalhost = true; // defaults to true
    private StatisticsHandler _statsHandler;
    private MemoryMXBean _memoryBean;
    private List<Connector> _connectors;

    @Override
    public void init() throws ServletException
    {
        ServletContext context = getServletContext();
        ContextHandler.Context scontext = (ContextHandler.Context)context;
        Server server = scontext.getContextHandler().getServer();

        _statsHandler = server.getChildHandlerByClass(StatisticsHandler.class);

        if (_statsHandler == null)
        {
            LOG.warn("Statistics Handler not installed!");
            return;
        }

        _memoryBean = ManagementFactory.getMemoryMXBean();
        _connectors = Arrays.asList(server.getConnectors());

        if (getInitParameter("restrictToLocalhost") != null)
        {
            _restrictToLocalhost = "true".equals(getInitParameter("restrictToLocalhost"));
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (_statsHandler == null)
        {
            LOG.warn("Statistics Handler not installed!");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (_restrictToLocalhost)
        {
            if (!isLoopbackAddress(request.getRemoteAddr()))
            {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }

        if (Boolean.parseBoolean(request.getParameter("statsReset")))
        {
            response.setStatus(HttpServletResponse.SC_OK);
            _statsHandler.statsReset();
            return;
        }

        if (request.getParameter("xml") != null)
        {
            LOG.warn("'xml' parameter is deprecated, use 'Accept' request header instead");
        }

        List<String> acceptable = getOrderedAcceptableMimeTypes(request);

        for (String mimeType : acceptable)
        {
            switch (mimeType)
            {
                case "application/json":
                    writeJsonResponse(response);
                    return;
                case "text/xml":
                    writeXmlResponse(response);
                    return;
                case "text/html":
                    writeHtmlResponse(response);
                    return;
                case "text/plain":
                case "*/*":
                    writeTextResponse(response);
                    return;
                default:
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Ignoring unrecognized mime-type {}", mimeType);
                    }
                    break;
            }
        }
        // None of the listed `Accept` mime-types were found.
        response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
    }

    private void writeTextResponse(HttpServletResponse response) throws IOException
    {
        response.setCharacterEncoding("utf-8");
        response.setContentType("text/plain");
        CharSequence text = generateResponse(new TextProducer());
        response.getWriter().print(text.toString());
    }

    private void writeHtmlResponse(HttpServletResponse response) throws IOException
    {
        response.setCharacterEncoding("utf-8");
        response.setContentType("text/html");
        Writer htmlWriter = new OutputStreamWriter(response.getOutputStream(), UTF_8);
        htmlWriter.append("<html><head><title>");
        htmlWriter.append(this.getClass().getSimpleName());
        htmlWriter.append("</title></head><body>\n");
        CharSequence html = generateResponse(new HtmlProducer());
        htmlWriter.append(html.toString());
        htmlWriter.append("\n</body></html>\n");
        htmlWriter.flush();
    }

    private void writeXmlResponse(HttpServletResponse response) throws IOException
    {
        response.setCharacterEncoding("utf-8");
        response.setContentType("text/xml");
        CharSequence xml = generateResponse(new XmlProducer());
        response.getWriter().print(xml.toString());
    }

    private void writeJsonResponse(HttpServletResponse response) throws IOException
    {
        // We intentionally don't put "UTF-8" into the response headers
        // as the rules for application/json state that it should never be
        // present on the HTTP Content-Type header.
        // It is also true that the application/json mime-type is always UTF-8.
        response.setContentType("application/json");
        CharSequence json = generateResponse(new JsonProducer());
        Writer jsonWriter = new OutputStreamWriter(response.getOutputStream(), UTF_8);
        jsonWriter.append(json);
        jsonWriter.flush();
    }

    private List<String> getOrderedAcceptableMimeTypes(HttpServletRequest request)
    {
        QuotedQualityCSV values = new QuotedQualityCSV(QuotedQualityCSV.MOST_SPECIFIC_MIME_ORDERING);

        // No accept header specified, try 'accept' parameter (for those clients that are
        // so ancient that they cannot set the standard HTTP `Accept` header)
        String acceptParameter = request.getParameter("accept");
        if (acceptParameter != null)
        {
            values.addValue(acceptParameter);
        }

        Enumeration<String> enumAccept = request.getHeaders(HttpHeader.ACCEPT.toString());
        if (enumAccept != null)
        {
            while (enumAccept.hasMoreElements())
            {
                String value = enumAccept.nextElement();
                if (StringUtil.isNotBlank(value))
                {
                    values.addValue(value);
                }
            }
        }

        if (values.isEmpty())
        {
            // return that we allow ALL mime types
            return Collections.singletonList("*/*");
        }

        return values.getValues();
    }

    private boolean isLoopbackAddress(String address)
    {
        try
        {
            InetAddress addr = InetAddress.getByName(address);
            return addr.isLoopbackAddress();
        }
        catch (UnknownHostException e)
        {
            LOG.warn("Warning: attempt to access statistics servlet from " + address, e);
            return false;
        }
    }

    private CharSequence generateResponse(OutputProducer outputProducer)
    {
        Map<String, Object> top = new HashMap<>();

        // requests
        Map<String, Number> requests = new HashMap<>();
        requests.put("statsOnMs", _statsHandler.getStatsOnMs());

        requests.put("requests", _statsHandler.getRequests());

        requests.put("requestsActive", _statsHandler.getRequestsActive());
        requests.put("requestsActiveMax", _statsHandler.getRequestsActiveMax());
        requests.put("requestsTimeTotal", _statsHandler.getRequestTimeTotal());
        requests.put("requestsTimeMean", _statsHandler.getRequestTimeMean());
        requests.put("requestsTimeMax", _statsHandler.getRequestTimeMax());
        requests.put("requestsTimeStdDev", _statsHandler.getRequestTimeStdDev());

        requests.put("dispatched", _statsHandler.getDispatched());
        requests.put("dispatchedActive", _statsHandler.getDispatchedActive());
        requests.put("dispatchedActiveMax", _statsHandler.getDispatchedActiveMax());
        requests.put("dispatchedTimeTotal", _statsHandler.getDispatchedTimeTotal());
        requests.put("dispatchedTimeMean", _statsHandler.getDispatchedTimeMean());
        requests.put("dispatchedTimeMax", _statsHandler.getDispatchedTimeMax());
        requests.put("dispatchedTimeStdDev", _statsHandler.getDispatchedTimeStdDev());

        requests.put("asyncRequests", _statsHandler.getAsyncRequests());
        requests.put("requestsSuspended", _statsHandler.getAsyncDispatches());
        requests.put("requestsSuspendedMax", _statsHandler.getAsyncRequestsWaiting());
        requests.put("requestsResumed", _statsHandler.getAsyncRequestsWaitingMax());
        requests.put("requestsExpired", _statsHandler.getExpires());

        requests.put("errors", _statsHandler.getErrors());

        top.put("requests", requests);

        // responses
        Map<String, Number> responses = new HashMap<>();
        responses.put("responses1xx", _statsHandler.getResponses1xx());
        responses.put("responses2xx", _statsHandler.getResponses2xx());
        responses.put("responses3xx", _statsHandler.getResponses3xx());
        responses.put("responses4xx", _statsHandler.getResponses4xx());
        responses.put("responses5xx", _statsHandler.getResponses5xx());
        responses.put("responsesBytesTotal", _statsHandler.getResponsesBytesTotal());
        top.put("responses", responses);

        // connections
        List<Object> connections = new ArrayList<>();
        _connectors.forEach((connector) ->
        {
            Map<String, Object> connectorDetail = new HashMap<>();
            connectorDetail.put("name", String.format("%s@%X", connector.getClass().getName(), connector.hashCode()));
            connectorDetail.put("protocols", connector.getProtocols());

            ConnectionStatistics connectionStats = null;
            if (connector instanceof AbstractConnector)
                connectionStats = connector.getBean(ConnectionStatistics.class);
            if (connectionStats != null)
            {
                connectorDetail.put("statsOn", true);
                connectorDetail.put("connections", connectionStats.getConnectionsTotal());
                connectorDetail.put("connectionsOpen", connectionStats.getConnections());
                connectorDetail.put("connectionsOpenMax", connectionStats.getConnectionsMax());
                connectorDetail.put("connectionsDurationMean", connectionStats.getConnectionDurationMean());
                connectorDetail.put("connectionsDurationMax", connectionStats.getConnectionDurationMax());
                connectorDetail.put("connectionsDurationStdDev", connectionStats.getConnectionDurationStdDev());
                connectorDetail.put("bytesIn", connectionStats.getReceivedBytes());
                connectorDetail.put("bytesOut", connectionStats.getSentBytes());
                connectorDetail.put("messagesIn", connectionStats.getReceivedMessages());
                connectorDetail.put("messagesOut", connectionStats.getSentMessages());
            }
            else
            {
                // Support for deprecated ConnectorStatistics (will be dropped in Jetty 10+)
                ConnectorStatistics connectorStats = null;
                if (connector instanceof AbstractConnector)
                    connectorStats = connector.getBean(ConnectorStatistics.class);
                if (connectorStats != null)
                {
                    connectorDetail.put("statsOn", true);
                    connectorDetail.put("connections", connectorStats.getConnections());
                    connectorDetail.put("connectionsOpen", connectorStats.getConnectionsOpen());
                    connectorDetail.put("connectionsOpenMax", connectorStats.getConnectionsOpenMax());
                    connectorDetail.put("connectionsDurationMean", connectorStats.getConnectionDurationMean());
                    connectorDetail.put("connectionsDurationMax", connectorStats.getConnectionDurationMax());
                    connectorDetail.put("connectionsDurationStdDev", connectorStats.getConnectionDurationStdDev());
                    connectorDetail.put("messagesIn", connectorStats.getMessagesIn());
                    connectorDetail.put("messagesOut", connectorStats.getMessagesIn());
                    connectorDetail.put("elapsedMs", connectorStats.getStartedMillis());
                }
                else
                {
                    connectorDetail.put("statsOn", false);
                }
            }
            connections.add(connectorDetail);
        });
        top.put("connections", connections);

        // memory
        Map<String, Number> memoryMap = new HashMap<>();
        memoryMap.put("heapMemoryUsage", _memoryBean.getHeapMemoryUsage().getUsed());
        memoryMap.put("nonHeapMemoryUsage", _memoryBean.getNonHeapMemoryUsage().getUsed());
        top.put("memory", memoryMap);

        // the top level object
        return outputProducer.generate("statistics", top);
    }

    private interface OutputProducer
    {
        CharSequence generate(String id, Map<String, Object> map);
    }

    private static class JsonProducer implements OutputProducer
    {
        @Override
        public CharSequence generate(String id, Map<String, Object> map)
        {
            return JSON.toString(map);
        }
    }

    private static class XmlProducer implements OutputProducer
    {
        private final StringBuilder sb;
        private int indent = 0;

        public XmlProducer()
        {
            this.sb = new StringBuilder();
        }

        @Override
        public CharSequence generate(String id, Map<String, Object> map)
        {
            add(id, map);
            return sb;
        }

        private void indent()
        {
            sb.append("\n");
            for (int i = 0; i < indent; i++)
            {
                sb.append(' ').append(' ');
            }
        }

        private void add(String id, Object obj)
        {
            sb.append('<').append(StringUtil.sanitizeXmlString(id)).append('>');
            indent++;

            boolean wasIndented = false;

            if (obj instanceof Map)
            {
                //noinspection unchecked
                addMap((Map<String, ?>)obj);
                wasIndented = true;
            }
            else if (obj instanceof List)
            {
                addList(id, (List<?>)obj);
                wasIndented = true;
            }
            else
            {
                addObject(obj);
            }

            indent--;
            if (wasIndented)
                indent();
            sb.append("</").append(id).append('>');
        }

        private void addMap(Map<String, ?> map)
        {
            map.keySet().stream().sorted()
                .forEach((key) ->
                {
                    indent();
                    add(key, map.get(key));
                });
        }

        private void addList(String parentId, List<?> list)
        {
            // drop the 's' at the end.
            String childName = parentId.replaceFirst("s$", "");
            list.forEach((entry) ->
            {
                indent();
                add(childName, entry);
            });
        }

        private void addObject(Object obj)
        {
            sb.append(StringUtil.sanitizeXmlString(Objects.toString(obj)));
        }
    }

    private static class TextProducer implements OutputProducer
    {
        private final StringBuilder sb;
        private int indent = 0;

        public TextProducer()
        {
            this.sb = new StringBuilder();
        }

        @Override
        public CharSequence generate(String id, Map<String, Object> map)
        {
            add(id, map);
            return sb;
        }

        private void indent()
        {
            for (int i = 0; i < indent; i++)
            {
                sb.append(' ').append(' ');
            }
        }

        private void add(String id, Object obj)
        {
            indent();
            sb.append(id).append(": ");
            indent++;

            if (obj instanceof Map)
            {
                sb.append('\n');
                //noinspection unchecked
                addMap((Map<String, ?>)obj);
            }
            else if (obj instanceof List)
            {
                sb.append('\n');
                addList(id, (List<?>)obj);
            }
            else
            {
                addObject(obj);
                sb.append('\n');
            }

            indent--;
        }

        private void addMap(Map<String, ?> map)
        {
            map.keySet().stream().sorted()
                .forEach((key) -> add(key, map.get(key)));
        }

        private void addList(String parentId, List<?> list)
        {
            // drop the 's' at the end.
            String childName = parentId.replaceFirst("s$", "");
            list.forEach((entry) -> add(childName, entry));
        }

        private void addObject(Object obj)
        {
            sb.append(obj);
        }
    }

    private static class HtmlProducer implements OutputProducer
    {
        private final StringBuilder sb;
        private int indent = 0;

        public HtmlProducer()
        {
            this.sb = new StringBuilder();
        }

        @Override
        public CharSequence generate(String id, Map<String, Object> map)
        {
            sb.append("<ul>\n");
            add(id, map);
            sb.append("</ul>\n");
            return sb;
        }

        private void indent()
        {
            for (int i = 0; i < indent; i++)
            {
                sb.append(' ').append(' ');
            }
        }

        private void add(String id, Object obj)
        {
            indent();
            indent++;
            sb.append("<li><em>").append(StringUtil.sanitizeXmlString(id)).append("</em>: ");
            if (obj instanceof Map)
            {
                //noinspection unchecked
                addMap((Map<String, ?>)obj);
                indent();
            }
            else if (obj instanceof List)
            {
                addList(id, (List<?>)obj);
                indent();
            }
            else
            {
                addObject(obj);
            }
            sb.append("</li>\n");

            indent--;
        }

        private void addMap(Map<String, ?> map)
        {
            sb.append("\n");
            indent();
            sb.append("<ul>\n");
            indent++;
            map.keySet().stream().sorted(String::compareToIgnoreCase)
                .forEach((key) -> add(key, map.get(key)));
            indent--;
            indent();
            sb.append("</ul>\n");
        }

        private void addList(String parentId, List<?> list)
        {
            sb.append("\n");
            indent();
            sb.append("<ul>\n");
            indent++;
            // drop the 's' at the end.
            String childName = parentId.replaceFirst("s$", "");
            list.forEach((entry) -> add(childName, entry));
            indent--;
            indent();
            sb.append("</ul>\n");
        }

        private void addObject(Object obj)
        {
            sb.append(StringUtil.sanitizeXmlString(Objects.toString(obj)));
        }
    }
}
