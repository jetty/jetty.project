//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.server.proxy;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.proxy.ProxyServlet;

/**
 * Specific implementation of {@link ProxyServlet.Transparent} for FastCGI.
 * <p />
 * This servlet accepts a HTTP request and transforms it into a FastCGI request
 * that is sent to the FastCGI server specified in the <code>proxyTo</code>
 * init-param.
 * <p />
 * This servlet accepts two additional init-params:
 * <ul>
 *     <li><code>scriptRoot</code>, mandatory, that must be set to the directory where
 *     the application that must be served via FastCGI is installed and corresponds to
 *     the FastCGI DOCUMENT_ROOT parameter</li>
 *     <li><code>scriptPattern</code>, optional, defaults to <code>(.+?\.php)</code>,
 *     that specifies a regular expression with at least 1 and at most 2 groups that specify
 *     respectively:
 *     <ul>
 *         <li>the FastCGI SCRIPT_NAME parameter</li>
 *         <li>the FastCGI PATH_INFO parameter</li>
 *     </ul></li>
 *     <li><code>fastCGI.HTTPS</code>, optional, defaults to false, that specifies whether
 *     to force the FastCGI <code>HTTPS</code> parameter to the value <code>on</code></li>
 * </ul>
 *
 * @see TryFilesFilter
 */
public class FastCGIProxyServlet extends AsyncProxyServlet.Transparent
{
    public static final String SCRIPT_ROOT_INIT_PARAM = "scriptRoot";
    public static final String SCRIPT_PATTERN_INIT_PARAM = "scriptPattern";
    public static final String FASTCGI_HTTPS_INIT_PARAM = "fastCGI.HTTPS";

    private static final String REMOTE_ADDR_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".remoteAddr";
    private static final String REMOTE_PORT_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".remotePort";
    private static final String SERVER_NAME_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".serverName";
    private static final String SERVER_ADDR_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".serverAddr";
    private static final String SERVER_PORT_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".serverPort";
    private static final String SCHEME_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".scheme";
    private static final String REQUEST_URI_ATTRIBUTE = FastCGIProxyServlet.class.getName() + ".requestURI";

    private Pattern scriptPattern;
    private boolean fcgiHTTPS;

    @Override
    public void init() throws ServletException
    {
        super.init();

        String value = getInitParameter(SCRIPT_PATTERN_INIT_PARAM);
        if (value == null)
            value = "(.+?\\.php)";
        scriptPattern = Pattern.compile(value);

        fcgiHTTPS = Boolean.parseBoolean(getInitParameter(FASTCGI_HTTPS_INIT_PARAM));
    }

    @Override
    protected HttpClient newHttpClient()
    {
        ServletConfig config = getServletConfig();
        String scriptRoot = config.getInitParameter(SCRIPT_ROOT_INIT_PARAM);
        if (scriptRoot == null)
            throw new IllegalArgumentException("Mandatory parameter '" + SCRIPT_ROOT_INIT_PARAM + "' not configured");
        return new HttpClient(new ProxyHttpClientTransportOverFCGI(scriptRoot), null);
    }

    @Override
    protected void customizeProxyRequest(Request proxyRequest, HttpServletRequest request)
    {
        proxyRequest.attribute(REMOTE_ADDR_ATTRIBUTE, request.getRemoteAddr());
        proxyRequest.attribute(REMOTE_PORT_ATTRIBUTE, String.valueOf(request.getRemotePort()));
        proxyRequest.attribute(SERVER_NAME_ATTRIBUTE, request.getServerName());
        proxyRequest.attribute(SERVER_ADDR_ATTRIBUTE, request.getLocalAddr());
        proxyRequest.attribute(SERVER_PORT_ATTRIBUTE, String.valueOf(request.getLocalPort()));

        proxyRequest.attribute(SCHEME_ATTRIBUTE, request.getScheme());

        // If we are forwarded or included, retain the original request URI.
        String originalPath = (String)request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        String originalQuery = (String)request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING);
        if (originalPath == null)
        {
            originalPath = (String)request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
            originalQuery = (String)request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING);
        }
        if (originalPath != null)
        {
            String originalURI = originalPath;
            if (originalQuery != null)
                originalURI += "?" + originalQuery;
            proxyRequest.attribute(REQUEST_URI_ATTRIBUTE, originalURI);
        }

        super.customizeProxyRequest(proxyRequest, request);
    }

    protected void customizeFastCGIHeaders(Request proxyRequest, HttpFields fastCGIHeaders)
    {
        fastCGIHeaders.put(FCGI.Headers.REMOTE_ADDR, (String)proxyRequest.getAttributes().get(REMOTE_ADDR_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.REMOTE_PORT, (String)proxyRequest.getAttributes().get(REMOTE_PORT_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_NAME, (String)proxyRequest.getAttributes().get(SERVER_NAME_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_ADDR, (String)proxyRequest.getAttributes().get(SERVER_ADDR_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_PORT, (String)proxyRequest.getAttributes().get(SERVER_PORT_ATTRIBUTE));

        if (fcgiHTTPS || HttpScheme.HTTPS.is((String)proxyRequest.getAttributes().get(SCHEME_ATTRIBUTE)))
            fastCGIHeaders.put(FCGI.Headers.HTTPS, "on");

        URI proxyRequestURI = proxyRequest.getURI();
        String rawPath = proxyRequestURI.getRawPath();
        String rawQuery = proxyRequestURI.getRawQuery();

        String requestURI = (String)proxyRequest.getAttributes().get(REQUEST_URI_ATTRIBUTE);
        if (requestURI == null)
        {
            requestURI = rawPath;
            if (rawQuery != null)
                requestURI += "?" + rawQuery;
        }
        fastCGIHeaders.put(FCGI.Headers.REQUEST_URI, requestURI);

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
        public ProxyHttpClientTransportOverFCGI(String scriptRoot)
        {
            super(scriptRoot);
        }

        @Override
        protected void customize(Request request, HttpFields fastCGIHeaders)
        {
            super.customize(request, fastCGIHeaders);
            customizeFastCGIHeaders(request, fastCGIHeaders);
        }
    }
}
