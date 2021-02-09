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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Header Filter
 * <p>
 * This filter sets or adds a header to the response.
 * <p>
 * The {@code headerConfig} init param is a CSV of actions to perform on headers, with the following syntax: <br>
 * [action] [header name]: [header value] <br>
 * [action] can be one of <code>set</code>, <code>add</code>, <code>setDate</code>, or <code>addDate</code> <br>
 * The date actions will add the header value in milliseconds to the current system time before setting a date header.
 * <p>
 * Below is an example value for <code>headerConfig</code>:<br>
 *
 * <pre>
 * set X-Frame-Options: DENY,
 * "add Cache-Control: no-cache, no-store, must-revalidate",
 * setDate Expires: 31540000000,
 * addDate Date: 0
 * </pre>
 *
 * @see IncludeExcludeBasedFilter
 */
public class HeaderFilter extends IncludeExcludeBasedFilter
{
    private List<ConfiguredHeader> _configuredHeaders = new ArrayList<>();
    private static final Logger LOG = Log.getLogger(HeaderFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        super.init(filterConfig);
        String headerConfig = filterConfig.getInitParameter("headerConfig");

        if (headerConfig != null)
        {
            String[] configs = StringUtil.csvSplit(headerConfig);
            for (String config : configs)
            {
                _configuredHeaders.add(parseHeaderConfiguration(config));
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug(this.toString());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpServletResponse httpResponse = (HttpServletResponse)response;

        if (super.shouldFilter(httpRequest, httpResponse))
        {
            for (ConfiguredHeader header : _configuredHeaders)
            {
                if (header.isDate())
                {
                    long headerValue = System.currentTimeMillis() + header.getMsOffset();
                    if (header.isAdd())
                    {
                        httpResponse.addDateHeader(header.getName(), headerValue);
                    }
                    else
                    {
                        httpResponse.setDateHeader(header.getName(), headerValue);
                    }
                }
                else // constant header value
                {
                    if (header.isAdd())
                    {
                        httpResponse.addHeader(header.getName(), header.getValue());
                    }
                    else
                    {
                        httpResponse.setHeader(header.getName(), header.getValue());
                    }
                }
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString()).append("\n");
        sb.append("configured headers:\n");
        for (ConfiguredHeader c : _configuredHeaders)
        {
            sb.append(c).append("\n");
        }

        return sb.toString();
    }

    private ConfiguredHeader parseHeaderConfiguration(String config)
    {
        String[] configTokens = config.trim().split(" ", 2);
        String method = configTokens[0].trim();
        String header = configTokens[1];
        String[] headerTokens = header.trim().split(":", 2);
        String headerName = headerTokens[0].trim();
        String headerValue = headerTokens[1].trim();
        ConfiguredHeader configuredHeader = new ConfiguredHeader(headerName, headerValue, method.startsWith("add"), method.endsWith("Date"));
        return configuredHeader;
    }

    private static class ConfiguredHeader
    {
        private String _name;
        private String _value;
        private long _msOffset;
        private boolean _add;
        private boolean _date;

        public ConfiguredHeader(String name, String value, boolean add, boolean date)
        {
            _name = name;
            _value = value;
            _add = add;
            _date = date;

            if (_date)
            {
                _msOffset = Long.parseLong(_value);
            }
        }

        public String getName()
        {
            return _name;
        }

        public String getValue()
        {
            return _value;
        }

        public boolean isAdd()
        {
            return _add;
        }

        public boolean isDate()
        {
            return _date;
        }

        public long getMsOffset()
        {
            return _msOffset;
        }

        @Override
        public String toString()
        {
            return (_add ? "add" : "set") + (_date ? "Date" : "") + " " + _name + ": " + _value;
        }
    }
}
