//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.server.proxy;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.util.ProcessorUtils;

/**
 * Specific implementation of {@link org.eclipse.jetty.proxy.AsyncProxyServlet.Transparent} for FastCGI.
 * <p>
 * This servlet accepts an HTTP request and transforms it into a FastCGI request
 * that is sent to the FastCGI server specified in the {@code proxyTo}
 * init-param.
 * <p>
 * This servlet accepts these additional {@code init-param}s:
 * <ul>
 * <li>{@code scriptRoot}, mandatory, that must be set to the directory where
 * the application that must be served via FastCGI is installed and corresponds to
 * the FastCGI DOCUMENT_ROOT parameter</li>
 * <li>{@code scriptPattern}, optional, defaults to {@code (.+?\.php)},
 * that specifies a regular expression with at least 1 and at most 2 groups that specify
 * respectively:
 * <ul>
 * <li>the FastCGI SCRIPT_NAME parameter</li>
 * <li>the FastCGI PATH_INFO parameter</li>
 * </ul></li>
 * <li>{@code fastCGI.HTTPS}, optional, defaults to false, that specifies whether
 * to force the FastCGI {@code HTTPS} parameter to the value {@code on}</li>
 * <li>{@code fastCGI.envNames}, optional, a comma separated list of environment variable
 * names read via {@link System#getenv(String)} that are forwarded as FastCGI parameters.</li>
 * <li>{@code unixDomainPath}, optional, that specifies the Unix-Domain path the FastCGI
 * server listens to.</li>
 * </ul>
 *
 * @see TryFilesFilter
 */
public class FastCGIProxyServlet extends AsyncProxyServlet.Transparent
{
    public static final String SCRIPT_ROOT_INIT_PARAM = "scriptRoot";
    public static final String SCRIPT_PATTERN_INIT_PARAM = "scriptPattern";
    public static final String ORIGINAL_URI_ATTRIBUTE_INIT_PARAM = "originalURIAttribute";
    public static final String ORIGINAL_QUERY_ATTRIBUTE_INIT_PARAM = "originalQueryAttribute";
    public static final String FASTCGI_HTTPS_INIT_PARAM = "fastCGI.HTTPS";
    public static final String FASTCGI_ENV_NAMES_INIT_PARAM = "fastCGI.envNames";

    private static final String REMOTE_ADDR_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".remoteAddr";
    private static final String REMOTE_PORT_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".remotePort";
    private static final String SERVER_NAME_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".serverName";
    private static final String SERVER_ADDR_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".serverAddr";
    private static final String SERVER_PORT_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".serverPort";
    private static final String SCHEME_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".scheme";
    private static final String REQUEST_URI_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".requestURI";
    private static final String REQUEST_QUERY_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".requestQuery";

    private Pattern scriptPattern;
    private String originalURIAttribute;
    private String originalQueryAttribute;
    private boolean fcgiHTTPS;
    private Set<String> fcgiEnvNames;

    @Override
    public void init() throws ServletException
    {
        super.init();

        String value = getInitParameter(SCRIPT_PATTERN_INIT_PARAM);
        if (value == null)
            value = "(.+?\\.php)";
        scriptPattern = Pattern.compile(value);

        originalURIAttribute = getInitParameter(ORIGINAL_URI_ATTRIBUTE_INIT_PARAM);
        originalQueryAttribute = getInitParameter(ORIGINAL_QUERY_ATTRIBUTE_INIT_PARAM);

        fcgiHTTPS = Boolean.parseBoolean(getInitParameter(FASTCGI_HTTPS_INIT_PARAM));

        fcgiEnvNames = Collections.emptySet();
        String envNames = getInitParameter(FASTCGI_ENV_NAMES_INIT_PARAM);
        if (envNames != null)
        {
            fcgiEnvNames = Stream.of(envNames.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        }
    }

    @Override
    protected HttpClient newHttpClient()
    {
        ServletConfig config = getServletConfig();
        String scriptRoot = config.getInitParameter(SCRIPT_ROOT_INIT_PARAM);
        if (scriptRoot == null)
            throw new IllegalArgumentException("Mandatory parameter '" + SCRIPT_ROOT_INIT_PARAM + "' not configured");

        ClientConnector connector;
        String unixDomainPath = config.getInitParameter("unixDomainPath");
        if (unixDomainPath != null)
        {
            connector = ClientConnector.forUnixDomain(Path.of(unixDomainPath));
        }
        else
        {
            int selectors = Math.max(1, ProcessorUtils.availableProcessors() / 2);
            String value = config.getInitParameter("selectors");
            if (value != null)
                selectors = Integer.parseInt(value);
            connector = new ClientConnector();
            connector.setSelectors(selectors);
        }
        return new HttpClient(new ProxyHttpClientTransportOverFCGI(connector, scriptRoot));
    }

    @Override
    protected void sendProxyRequest(HttpServletRequest request, HttpServletResponse proxyResponse, Request proxyRequest)
    {
        proxyRequest.attribute(REMOTE_ADDR_ATTRIBUTE, request.getRemoteAddr());
        proxyRequest.attribute(REMOTE_PORT_ATTRIBUTE, String.valueOf(request.getRemotePort()));
        proxyRequest.attribute(SERVER_NAME_ATTRIBUTE, request.getServerName());
        proxyRequest.attribute(SERVER_ADDR_ATTRIBUTE, request.getLocalAddr());
        proxyRequest.attribute(SERVER_PORT_ATTRIBUTE, String.valueOf(request.getLocalPort()));
        proxyRequest.attribute(SCHEME_ATTRIBUTE, request.getScheme());

        // Has the original URI been rewritten ?
        String originalURI = null;
        String originalQuery = null;
        if (originalURIAttribute != null)
            originalURI = (String)request.getAttribute(originalURIAttribute);
        if (originalURI != null && originalQueryAttribute != null)
        {
            originalQuery = (String)request.getAttribute(originalQueryAttribute);
            if (originalQuery != null)
                originalURI += "?" + originalQuery;
        }

        if (originalURI == null)
        {
            // If we are forwarded or included, retain the original request URI.
            String originalPath = (String)request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
            originalQuery = (String)request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING);
            if (originalPath == null)
            {
                originalPath = (String)request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
                originalQuery = (String)request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING);
            }
            if (originalPath != null)
            {
                originalURI = originalPath;
                if (originalQuery != null)
                    originalURI += "?" + originalQuery;
            }
        }

        if (originalURI != null)
            proxyRequest.attribute(REQUEST_URI_ATTRIBUTE, originalURI);
        if (originalQuery != null)
            proxyRequest.attribute(REQUEST_QUERY_ATTRIBUTE, originalQuery);

        // If the Host header is missing, add it.
        if (!proxyRequest.getHeaders().contains(HttpHeader.HOST))
        {
            String server = request.getServerName();
            int port = request.getServerPort();
            if (!getHttpClient().isDefaultPort(request.getScheme(), port))
                server += ":" + port;
            String host = server;
            proxyRequest.headers(headers -> headers
                .put(HttpHeader.HOST, host)
                .put(HttpHeader.X_FORWARDED_HOST, host));
        }

        // PHP does not like multiple Cookie headers, coalesce into one.
        List<String> cookies = proxyRequest.getHeaders().getValuesList(HttpHeader.COOKIE);
        if (cookies.size() > 1)
        {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < cookies.size(); ++i)
            {
                if (i > 0)
                    builder.append("; ");
                String cookie = cookies.get(i);
                builder.append(cookie);
            }
            proxyRequest.headers(headers -> headers.put(HttpHeader.COOKIE, builder.toString()));
        }

        super.sendProxyRequest(request, proxyResponse, proxyRequest);
    }

    protected void customizeFastCGIHeaders(Request proxyRequest, HttpFields.Mutable fastCGIHeaders)
    {
        for (String envName : fcgiEnvNames)
        {
            String envValue = System.getenv(envName);
            if (envValue != null)
                fastCGIHeaders.put(envName, envValue);
        }

        fastCGIHeaders.remove("HTTP_PROXY");

        fastCGIHeaders.put(FCGI.Headers.REMOTE_ADDR, (String)proxyRequest.getAttributes().get(REMOTE_ADDR_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.REMOTE_PORT, (String)proxyRequest.getAttributes().get(REMOTE_PORT_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_NAME, (String)proxyRequest.getAttributes().get(SERVER_NAME_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_ADDR, (String)proxyRequest.getAttributes().get(SERVER_ADDR_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_PORT, (String)proxyRequest.getAttributes().get(SERVER_PORT_ATTRIBUTE));

        if (fcgiHTTPS || HttpScheme.HTTPS.is((String)proxyRequest.getAttributes().get(SCHEME_ATTRIBUTE)))
            fastCGIHeaders.put(FCGI.Headers.HTTPS, "on");

        URI proxyRequestURI = proxyRequest.getURI();
        String rawPath = proxyRequestURI == null ? proxyRequest.getPath() : proxyRequestURI.getRawPath();
        String rawQuery = proxyRequestURI == null ? null : proxyRequestURI.getRawQuery();

        String requestURI = (String)proxyRequest.getAttributes().get(REQUEST_URI_ATTRIBUTE);
        if (requestURI == null)
        {
            requestURI = rawPath;
            if (rawQuery != null)
                requestURI += "?" + rawQuery;
        }
        fastCGIHeaders.put(FCGI.Headers.REQUEST_URI, requestURI);

        String requestQuery = (String)proxyRequest.getAttributes().get(REQUEST_QUERY_ATTRIBUTE);
        if (requestQuery != null)
            fastCGIHeaders.put(FCGI.Headers.QUERY_STRING, requestQuery);

        String scriptName = rawPath;
        Matcher matcher = scriptPattern.matcher(rawPath);
        if (matcher.matches())
        {
            // Expect at least one group in the regular expression.
            scriptName = matcher.group(1);

            // If there is a second group, map it to PATH_INFO.
            if (matcher.groupCount() > 1)
                fastCGIHeaders.put(FCGI.Headers.PATH_INFO, matcher.group(2));
        }
        fastCGIHeaders.put(FCGI.Headers.SCRIPT_NAME, scriptName);

        String root = fastCGIHeaders.get(FCGI.Headers.DOCUMENT_ROOT);
        fastCGIHeaders.put(FCGI.Headers.SCRIPT_FILENAME, root + scriptName);
    }

    private class ProxyHttpClientTransportOverFCGI extends HttpClientTransportOverFCGI
    {
        private ProxyHttpClientTransportOverFCGI(ClientConnector connector, String scriptRoot)
        {
            super(connector, scriptRoot);
        }

        @Override
        protected void customize(Request request, HttpFields.Mutable fastCGIHeaders)
        {
            super.customize(request, fastCGIHeaders);
            customizeFastCGIHeaders(request, fastCGIHeaders);
            if (_log.isDebugEnabled())
            {
                TreeMap<String, String> fcgi = new TreeMap<>();
                for (HttpField field : fastCGIHeaders)
                {
                    fcgi.put(field.getName(), field.getValue());
                }
                String eol = System.lineSeparator();
                _log.debug("FastCGI variables {}{}", eol, fcgi.entrySet().stream()
                    .map(entry -> String.format("%s: %s", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(eol)));
            }
        }
    }
}
