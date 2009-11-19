/*
 * Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.log.Log;

/**
 * <p>Implementation of the
 * <a href="http://dev.w3.org/2006/waf/access-control/">cross-origin resource sharing</a>.</p>
 * <p>A typical example is to use this filter to allow cross-domain
 * <a href="http://cometd.org">cometd</a> communication using the standard
 * long polling transport instead of the JSONP transport (that is less
 * efficient and less reactive to failures).</p>
 * <p>This filter allows the following configuration parameters:
 * <ul>
 * <li><b>allowedOrigins</b>, a comma separated list of origins that are
 * allowed to access the resources. Default value is <b>*</b>, meaning all
 * origins</li>
 * <li><b>allowedMethods</b>, a comma separated list of HTTP methods that
 * are allowed to be used when accessing the resources. Default value is
 * <b>GET,POST</b></li>
 * <li><b>allowedHeaders</b>, a comma separated list of HTTP headers that
 * are allowed to be specified when accessing the resources. Default value
 * is <b>X-Requested-With</b></li>
 * <li><b>preflightMaxAge</b>, the number of seconds that preflight requests
 * can be cached by the client. Default value is <b>1800</b> seconds, or 30
 * minutes</li>
 * <li><b>allowCredentials</b>, a boolean indicating if the resource allows
 * requests with credentials. Default value is <b>false</b></li>
 * </ul></p>
 * <p>A typical configuration could be:
 * <pre>
 * &lt;web-app ...&gt;
 *     ...
 *     &lt;filter&gt;
 *         &lt;filter-name&gt;cross-origin&lt;/filter-name&gt;
 *         &lt;filter-class&gt;org.eclipse.jetty.servlets.CrossOriginFilter&lt;/filter-class&gt;
 *     &lt;/filter&gt;
 *     &lt;filter-mapping&gt;
 *         &lt;filter-name&gt;cross-origin&lt;/filter-name&gt;
 *         &lt;url-pattern&gt;/cometd/*&lt;/url-pattern&gt;
 *     &lt;/filter-mapping&gt;
 *     ...
 * &lt;/web-app&gt;
 * </pre></p>
 *
 * @version $Revision$ $Date$
 */
public class CrossOriginFilter implements Filter
{
    // Request headers
    private static final String ORIGIN_HEADER = "Origin";
    private static final String ACCESS_CONTROL_REQUEST_METHOD_HEADER = "Access-Control-Request-Method";
    private static final String ACCESS_CONTROL_REQUEST_HEADERS_HEADER = "Access-Control-Request-Headers";
    // Response headers
    private static final String ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = "Access-Control-Allow-Origin";
    private static final String ACCESS_CONTROL_ALLOW_METHODS_HEADER = "Access-Control-Allow-Methods";
    private static final String ACCESS_CONTROL_ALLOW_HEADERS_HEADER = "Access-Control-Allow-Headers";
    private static final String ACCESS_CONTROL_MAX_AGE_HEADER = "Access-Control-Max-Age";
    private static final String ACCESS_CONTROL_ALLOW_CREDENTIALS_HEADER = "Access-Control-Allow-Credentials";
    // Implementation constants
    private static final String ALLOWED_ORIGINS_PARAM = "allowedOrigins";
    private static final String ALLOWED_METHODS_PARAM = "allowedMethods";
    private static final String ALLOWED_HEADERS_PARAM = "allowedHeaders";
    private static final String PREFLIGHT_MAX_AGE_PARAM = "preflightMaxAge";
    private static final String ALLOWED_CREDENTIALS_PARAM = "allowCredentials";
    private static final String ANY_ORIGIN = "*";
    private static final List<String> SIMPLE_HTTP_METHODS = Arrays.asList("GET", "POST", "HEAD");

    private boolean anyOriginAllowed = false;
    private List<String> allowedOrigins = new ArrayList<String>();
    private List<String> allowedMethods = new ArrayList<String>();
    private List<String> allowedHeaders = new ArrayList<String>();
    private int preflightMaxAge = 0;
    private boolean allowCredentials = false;

    public void init(FilterConfig config) throws ServletException
    {
        String allowedOriginsConfig = config.getInitParameter(ALLOWED_ORIGINS_PARAM);
        if (allowedOriginsConfig == null) allowedOriginsConfig = "*";
        String[] allowedOrigins = allowedOriginsConfig.split(",");
        for (String allowedOrigin : allowedOrigins)
        {
            allowedOrigin = allowedOrigin.trim();
            if (allowedOrigin.length() > 0)
            {
                if (ANY_ORIGIN.equals(allowedOrigin))
                {
                    anyOriginAllowed = true;
                    this.allowedOrigins.clear();
                    break;
                }
                else
                {
                    this.allowedOrigins.add(allowedOrigin);
                }
            }
        }

        String allowedMethodsConfig = config.getInitParameter(ALLOWED_METHODS_PARAM);
        if (allowedMethodsConfig == null) allowedMethodsConfig = "GET,POST";
        allowedMethods.addAll(Arrays.asList(allowedMethodsConfig.split(",")));

        String allowedHeadersConfig = config.getInitParameter(ALLOWED_HEADERS_PARAM);
        if (allowedHeadersConfig == null) allowedHeadersConfig = "X-Requested-With,Content-Type,Accept";
        allowedHeaders.addAll(Arrays.asList(allowedHeadersConfig.split(",")));

        String preflightMaxAgeConfig = config.getInitParameter(PREFLIGHT_MAX_AGE_PARAM);
        if (preflightMaxAgeConfig == null) preflightMaxAgeConfig = "1800"; // Default is 30 minutes
        try
        {
            preflightMaxAge = Integer.parseInt(preflightMaxAgeConfig);
        }
        catch (NumberFormatException x)
        {
            Log.info("Cross-origin filter, could not parse '{}' parameter as integer: {}", PREFLIGHT_MAX_AGE_PARAM, preflightMaxAgeConfig);
        }

        String allowedCredentialsConfig = config.getInitParameter(ALLOWED_CREDENTIALS_PARAM);
        if (allowedCredentialsConfig == null) allowedCredentialsConfig = "false";
        allowCredentials = Boolean.parseBoolean(allowedCredentialsConfig);

        Log.debug("Cross-origin filter configuration: " +
                  ALLOWED_ORIGINS_PARAM + " = " + allowedOriginsConfig + ", " +
                  ALLOWED_METHODS_PARAM + " = " + allowedMethodsConfig + ", " +
                  ALLOWED_HEADERS_PARAM + " = " + allowedHeadersConfig + ", " +
                  PREFLIGHT_MAX_AGE_PARAM + " = " + preflightMaxAgeConfig + ", " +
                  ALLOWED_CREDENTIALS_PARAM + " = " + allowedCredentialsConfig);
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        handle((HttpServletRequest)request, (HttpServletResponse)response, chain);
    }

    private void handle(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        String origin = request.getHeader(ORIGIN_HEADER);
        // Is it a cross origin request ?
        if (origin != null)
        {
            if (originMatches(origin))
            {
                if (isSimpleRequest(request))
                {
                    Log.debug("Cross-origin request to {} is a simple cross-origin request", request.getRequestURI());
                    handleSimpleResponse(request, response, origin);
                }
                else
                {
                    Log.debug("Cross-origin request to {} is a preflight cross-origin request", request.getRequestURI());
                    handlePreflightResponse(request, response, origin);
                }
            }
            else
            {
                Log.debug("Cross-origin request to " + request.getRequestURI() + " with origin " + origin + " does not match allowed origins " + allowedOrigins);
            }
        }

        chain.doFilter(request, response);
    }

    private boolean originMatches(String origin)
    {
        if (anyOriginAllowed) return true;
        for (String allowedOrigin : allowedOrigins)
        {
            if (allowedOrigin.equals(origin)) return true;
        }
        return false;
    }

    private boolean isSimpleRequest(HttpServletRequest request)
    {
        String method = request.getMethod();
        if (SIMPLE_HTTP_METHODS.contains(method))
        {
            // TODO: implement better section 6.1
            // Section 6.1 says that for a request to be simple, custom request headers must be simple.
            // Here for simplicity I just check if there is a Access-Control-Request-Method header,
            // which is required for preflight requests
            return request.getHeader(ACCESS_CONTROL_REQUEST_METHOD_HEADER) == null;
        }
        return false;
    }

    private void handleSimpleResponse(HttpServletRequest request, HttpServletResponse response, String origin)
    {
        response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, origin);
        if (allowCredentials) response.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS_HEADER, "true");
    }

    private void handlePreflightResponse(HttpServletRequest request, HttpServletResponse response, String origin)
    {
        // Implementation of section 5.2

        // 5.2.3 and 5.2.5
        boolean methodAllowed = isMethodAllowed(request);
        if (!methodAllowed) return;
        // 5.2.4 and 5.2.6
        boolean headersAllowed = areHeadersAllowed(request);
        if (!headersAllowed) return;
        // 5.2.7
        response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, origin);
        if (allowCredentials) response.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS_HEADER, "true");
        // 5.2.8
        if (preflightMaxAge > 0) response.setHeader(ACCESS_CONTROL_MAX_AGE_HEADER, String.valueOf(preflightMaxAge));
        // 5.2.9
        response.setHeader(ACCESS_CONTROL_ALLOW_METHODS_HEADER, commify(allowedMethods));
        // 5.2.10
        response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER, commify(this.allowedHeaders));
    }

    private boolean isMethodAllowed(HttpServletRequest request)
    {
        String accessControlRequestMethod = request.getHeader(ACCESS_CONTROL_REQUEST_METHOD_HEADER);
        Log.debug("{} is {}", ACCESS_CONTROL_REQUEST_METHOD_HEADER, accessControlRequestMethod);
        boolean result = false;
        if (accessControlRequestMethod != null)
        {
            result = allowedMethods.contains(accessControlRequestMethod);
        }
        Log.debug("Method {} is" + (result ? "" : " not") + " among allowed methods {}", accessControlRequestMethod, allowedMethods);
        return result;
    }

    private boolean areHeadersAllowed(HttpServletRequest request)
    {
        String accessControlRequestHeaders = request.getHeader(ACCESS_CONTROL_REQUEST_HEADERS_HEADER);
        Log.debug("{} is {}", ACCESS_CONTROL_REQUEST_HEADERS_HEADER, accessControlRequestHeaders);
        boolean result = false;
        if (accessControlRequestHeaders != null)
        {
            result = true;
            String[] headers = accessControlRequestHeaders.split(",");
            for (String header : headers)
            {
                boolean headerAllowed = false;
                for (String allowedHeader : allowedHeaders)
                {
                    if (header.trim().equalsIgnoreCase(allowedHeader.trim()))
                    {
                        headerAllowed = true;
                        break;
                    }
                }
                if (!headerAllowed)
                {
                    result = false;
                    break;
                }
            }
        }
        Log.debug("Headers [{}] are" + (result ? "" : " not") + " among allowed headers {}", accessControlRequestHeaders, allowedHeaders);
        return result;
    }

    private String commify(List<String> strings)
    {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < strings.size(); ++i)
        {
            if (i > 0) builder.append(",");
            String string = strings.get(i);
            builder.append(string);
        }
        return builder.toString();
    }

    public void destroy()
    {
        anyOriginAllowed = false;
        allowedOrigins.clear();
        allowedMethods.clear();
        allowedHeaders.clear();
        preflightMaxAge = 0;
        allowCredentials = false;
    }
}
