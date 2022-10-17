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

package org.eclipse.jetty.fcgi.proxy;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.TryPathsHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Specific implementation of {@link ProxyHandler.Reverse} for FastCGI.</p>
 * <p>This handler accepts an HTTP request and transforms it into a FastCGI
 * request that is sent to the FastCGI server, and viceversa for the response.</p>
 *
 * @see TryPathsHandler
 */
public class FastCGIProxyHandler extends ProxyHandler.Reverse
{
    private static final Logger LOG = LoggerFactory.getLogger(FastCGIProxyHandler.class);
    private static final String REMOTE_ADDR_ATTRIBUTE = FastCGIProxyHandler.class.getName() + ".remoteAddr";
    private static final String REMOTE_PORT_ATTRIBUTE = FastCGIProxyHandler.class.getName() + ".remotePort";
    private static final String SERVER_NAME_ATTRIBUTE = FastCGIProxyHandler.class.getName() + ".serverName";
    private static final String SERVER_ADDR_ATTRIBUTE = FastCGIProxyHandler.class.getName() + ".serverAddr";
    private static final String SERVER_PORT_ATTRIBUTE = FastCGIProxyHandler.class.getName() + ".serverPort";
    private static final String SCHEME_ATTRIBUTE = FastCGIProxyHandler.class.getName() + ".scheme";
    private static final String REQUEST_URI_ATTRIBUTE = FastCGIProxyHandler.class.getName() + ".requestURI";
    private static final String REQUEST_QUERY_ATTRIBUTE = FastCGIProxyHandler.class.getName() + ".requestQuery";

    private final String scriptRoot;
    private Pattern scriptPattern;
    private String originalPathAttribute;
    private String originalQueryAttribute;
    private boolean fcgiSecure;
    private Set<String> fcgiEnvNames;
    private Path unixDomainPath;

    /**
     * <p>Creates a new instance that rewrites the {@code HttpURI}
     * with the given pattern and replacement strings, using
     * {@link String#replaceAll(String, String)}.</p>
     *
     * @param uriPattern the regex pattern to use to match the incoming URI
     * @param uriReplacement the replacement string to use to rewrite the incoming URI
     * @param scriptRoot the root directory path of the FastCGI files
     * @see ProxyHandler.Reverse#Reverse(String, String)
     */
    public FastCGIProxyHandler(String uriPattern, String uriReplacement, String scriptRoot)
    {
        super(uriPattern, uriReplacement);
        this.scriptRoot = Objects.requireNonNull(scriptRoot);
    }

    /**
     * <p>Creates a new instance with the given {@code HttpURI} rewriter
     * function.</p>
     * <p>The {@code HttpURI} rewriter function should return the URI
     * of the FastCGI server.</p>
     * <p>The {@code scriptRoot} path must be set to the directory where the application
     * that must be served via FastCGI is installed and corresponds to
     * the FastCGI {@code DOCUMENT_ROOT} parameter.</p>
     *
     * @param httpURIRewriter a function that returns the URI of the FastCGI server
     * @param scriptRoot the root directory path of the FastCGI files
     */
    public FastCGIProxyHandler(Function<Request, HttpURI> httpURIRewriter, String scriptRoot)
    {
        super(httpURIRewriter);
        this.scriptRoot = Objects.requireNonNull(scriptRoot);
    }

    /**
     * @return the root directory path of the FastCGI files
     */
    public String getScriptRoot()
    {
        return scriptRoot;
    }

    /**
     * @return the regular expression that extracts the
     * {@code SCRIPT_NAME} and the {@code PATH_INFO} FastCGI parameters
     */
    public Pattern getScriptPattern()
    {
        return scriptPattern;
    }

    /**
     * <p>Sets a regular expression with at least 1 and at most 2 groups
     * that specify respectively:</p>
     * <ul>
     * <li>the FastCGI {@code SCRIPT_NAME} parameter</li>
     * <li>the FastCGI {@code PATH_INFO} parameter</li>
     * </ul>
     *
     * @param scriptPattern the regular expression that extracts the
     * {@code SCRIPT_NAME} and the {@code PATH_INFO} FastCGI parameters
     */
    public void setScriptPattern(Pattern scriptPattern)
    {
        this.scriptPattern = scriptPattern;
    }

    /**
     * @return the attribute name of the original client-to-proxy
     * request path
     */
    public String getOriginalPathAttribute()
    {
        return originalPathAttribute;
    }

    /**
     * <p>Sets the client-to-proxy request attribute name to use to
     * retrieve the original request path.</p>
     * <p>For example, the request URI may be rewritten by a previous
     * handler that might save the original request path in a request
     * attribute.</p>
     *
     * @param originalPathAttribute the attribute name of the original
     * client-to-proxy request path
     */
    public void setOriginalPathAttribute(String originalPathAttribute)
    {
        this.originalPathAttribute = originalPathAttribute;
    }

    /**
     * @return the attribute name of the original client-to-proxy
     * request query
     */
    public String getOriginalQueryAttribute()
    {
        return originalQueryAttribute;
    }

    /**
     * <p>Sets the client-to-proxy request attribute name to use to
     * retrieve the original request query.</p>
     * <p>For example, the request URI may be rewritten by a previous
     * handler that might save the original request query in a request
     * attribute.</p>
     *
     * @param originalQueryAttribute the attribute name of the original
     * client-to-proxy request query
     */
    public void setOriginalQueryAttribute(String originalQueryAttribute)
    {
        this.originalQueryAttribute = originalQueryAttribute;
    }

    /**
     * @return whether to forward the {@code HTTPS} FastCGI
     * parameter in the FastCGI request
     */
    public boolean isFastCGISecure()
    {
        return fcgiSecure;
    }

    /**
     * <p>Sets whether to forward the {@code HTTPS} FastCGI parameter
     * in the FastCGI request to the FastCGI server.</p>
     *
     * @param fcgiSecure whether to forward the {@code HTTPS} FastCGI
     * parameter in the FastCGI request
     */
    public void setFastCGISecure(boolean fcgiSecure)
    {
        this.fcgiSecure = fcgiSecure;
    }

    /**
     * @return the names of the environment variables forwarded
     * in the FastCGI request
     */
    public Set<String> getFastCGIEnvNames()
    {
        return fcgiEnvNames;
    }

    /**
     * <p>Sets the names of environment variables that will forwarded,
     * along with their value retrieved via {@link System#getenv(String)},
     * in the FastCGI request to the FastCGI server.</p>
     *
     * @param fcgiEnvNames the names of the environment variables
     * forwarded in the FastCGI request
     * @see System#getenv(String)
     */
    public void setFastCGIEnvNames(Set<String> fcgiEnvNames)
    {
        this.fcgiEnvNames = fcgiEnvNames;
    }

    /**
     * @return the Unix-Domain path the FastCGI server listens to,
     * or {@code null} if the FastCGI server listens over network
     */
    public Path getUnixDomainPath()
    {
        return unixDomainPath;
    }

    /**
     * <p>Sets the Unix-Domain path the FastCGI server listens to.</p>
     * <p>If the FastCGI server listens over the network (not over a
     * Unix-Domain path), then the FastCGI server host and port must
     * be specified by the {@code HttpURI} rewrite function passed
     * to the constructor.</p>
     *
     * @param unixDomainPath the Unix-Domain path the FastCGI server listens to
     */
    public void setUnixDomainPath(Path unixDomainPath)
    {
        this.unixDomainPath = unixDomainPath;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (scriptPattern == null)
            scriptPattern = Pattern.compile("(.+?\\.php)");

        if (fcgiEnvNames == null)
            fcgiEnvNames = Set.of();
    }

    @Override
    protected HttpClient newHttpClient()
    {
        ClientConnector clientConnector;
        Path unixDomainPath = getUnixDomainPath();
        if (unixDomainPath != null)
            clientConnector = ClientConnector.forUnixDomain(unixDomainPath);
        else
            clientConnector = new ClientConnector();
        QueuedThreadPool proxyClientThreads = new QueuedThreadPool();
        proxyClientThreads.setName("proxy-client");
        clientConnector.setExecutor(proxyClientThreads);
        return new HttpClient(new ProxyHttpClientTransportOverFCGI(clientConnector, getScriptRoot()));
    }

    @Override
    protected void sendProxyToServerRequest(Request clientToProxyRequest, org.eclipse.jetty.client.api.Request proxyToServerRequest, Response proxyToClientResponse, Callback proxyToClientCallback)
    {
        proxyToServerRequest.attribute(REMOTE_ADDR_ATTRIBUTE, Request.getRemoteAddr(clientToProxyRequest));
        proxyToServerRequest.attribute(REMOTE_PORT_ATTRIBUTE, String.valueOf(Request.getRemotePort(clientToProxyRequest)));
        String serverName = Request.getServerName(clientToProxyRequest);
        proxyToServerRequest.attribute(SERVER_NAME_ATTRIBUTE, serverName);
        proxyToServerRequest.attribute(SERVER_ADDR_ATTRIBUTE, Request.getLocalAddr(clientToProxyRequest));
        int serverPort = Request.getServerPort(clientToProxyRequest);
        proxyToServerRequest.attribute(SERVER_PORT_ATTRIBUTE, String.valueOf(serverPort));
        String scheme = clientToProxyRequest.getHttpURI().getScheme();
        proxyToServerRequest.attribute(SCHEME_ATTRIBUTE, scheme);

        // Has the original URI been rewritten?
        String originalURI = null;
        String originalQuery = null;
        String originalPathAttribute = getOriginalPathAttribute();
        if (originalPathAttribute != null)
            originalURI = (String)clientToProxyRequest.getAttribute(originalPathAttribute);
        if (originalURI != null)
        {
            String originalQueryAttribute = getOriginalQueryAttribute();
            if (originalQueryAttribute != null)
            {
                originalQuery = (String)clientToProxyRequest.getAttribute(originalQueryAttribute);
                if (originalQuery != null)
                    originalURI += "?" + originalQuery;
            }
        }

        if (originalURI != null)
            proxyToServerRequest.attribute(REQUEST_URI_ATTRIBUTE, originalURI);
        if (originalQuery != null)
            proxyToServerRequest.attribute(REQUEST_QUERY_ATTRIBUTE, originalQuery);

        // If the Host header is missing, add it.
        if (!proxyToServerRequest.getHeaders().contains(HttpHeader.HOST))
        {
            if (!getHttpClient().isDefaultPort(scheme, serverPort))
                serverName += ":" + serverPort;
            String host = serverName;
            proxyToServerRequest.headers(headers -> headers
                .put(HttpHeader.HOST, host)
                .put(HttpHeader.X_FORWARDED_HOST, host));
        }

        // PHP does not like multiple Cookie headers, coalesce into one.
        List<String> cookies = proxyToServerRequest.getHeaders().getValuesList(HttpHeader.COOKIE);
        if (cookies.size() > 1)
        {
            String allCookies = String.join("; ", cookies);
            proxyToServerRequest.headers(headers -> headers.put(HttpHeader.COOKIE, allCookies));
        }

        super.sendProxyToServerRequest(clientToProxyRequest, proxyToServerRequest, proxyToClientResponse, proxyToClientCallback);
    }

    protected void customizeFastCGIHeaders(org.eclipse.jetty.client.api.Request proxyToServerRequest, HttpFields.Mutable fastCGIHeaders)
    {
        for (String envName : getFastCGIEnvNames())
        {
            String envValue = System.getenv(envName);
            if (envValue != null)
                fastCGIHeaders.put(envName, envValue);
        }

        fastCGIHeaders.remove("HTTP_PROXY");

        fastCGIHeaders.put(FCGI.Headers.REMOTE_ADDR, (String)proxyToServerRequest.getAttributes().get(REMOTE_ADDR_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.REMOTE_PORT, (String)proxyToServerRequest.getAttributes().get(REMOTE_PORT_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_NAME, (String)proxyToServerRequest.getAttributes().get(SERVER_NAME_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_ADDR, (String)proxyToServerRequest.getAttributes().get(SERVER_ADDR_ATTRIBUTE));
        fastCGIHeaders.put(FCGI.Headers.SERVER_PORT, (String)proxyToServerRequest.getAttributes().get(SERVER_PORT_ATTRIBUTE));

        if (isFastCGISecure() || HttpScheme.HTTPS.is((String)proxyToServerRequest.getAttributes().get(SCHEME_ATTRIBUTE)))
            fastCGIHeaders.put(FCGI.Headers.HTTPS, "on");

        URI proxyRequestURI = proxyToServerRequest.getURI();
        String rawPath = proxyRequestURI == null ? proxyToServerRequest.getPath() : proxyRequestURI.getRawPath();
        String rawQuery = proxyRequestURI == null ? null : proxyRequestURI.getRawQuery();

        String requestURI = (String)proxyToServerRequest.getAttributes().get(REQUEST_URI_ATTRIBUTE);
        if (requestURI == null)
        {
            requestURI = rawPath;
            if (rawQuery != null)
                requestURI += "?" + rawQuery;
        }
        fastCGIHeaders.put(FCGI.Headers.REQUEST_URI, requestURI);

        String requestQuery = (String)proxyToServerRequest.getAttributes().get(REQUEST_QUERY_ATTRIBUTE);
        if (requestQuery != null)
            fastCGIHeaders.put(FCGI.Headers.QUERY_STRING, requestQuery);

        String scriptName = rawPath;
        Matcher matcher = getScriptPattern().matcher(rawPath);
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
        public void customize(org.eclipse.jetty.client.api.Request proxyToServerRequest, HttpFields.Mutable fastCGIHeaders)
        {
            super.customize(proxyToServerRequest, fastCGIHeaders);
            customizeFastCGIHeaders(proxyToServerRequest, fastCGIHeaders);
            if (LOG.isDebugEnabled())
            {
                TreeMap<String, String> fcgi = new TreeMap<>();
                for (HttpField field : fastCGIHeaders)
                {
                    fcgi.put(field.getName(), field.getValue());
                }
                String eol = System.lineSeparator();
                LOG.debug("FastCGI variables {}{}", eol, fcgi.entrySet().stream()
                    .map(entry -> String.format("%s: %s", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(eol)));
            }
        }
    }
}
