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

package org.eclipse.jetty.servlets;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;

/**
 * 6
 */
public class BalancerServlet extends ProxyServlet
{

    private static final class BalancerMember
    {

        private String _name;

        private String _proxyTo;

        private HttpURI _backendURI;

        public BalancerMember(String name, String proxyTo)
        {
            super();
            _name = name;
            _proxyTo = proxyTo;
            _backendURI = new HttpURI(_proxyTo);
        }

        public String getProxyTo()
        {
            return _proxyTo;
        }

        public HttpURI getBackendURI()
        {
            return _backendURI;
        }

        @Override
        public String toString()
        {
            return "BalancerMember [_name=" + _name + ", _proxyTo=" + _proxyTo + "]";
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((_name == null)?0:_name.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            BalancerMember other = (BalancerMember)obj;
            if (_name == null)
            {
                if (other._name != null)
                    return false;
            }
            else if (!_name.equals(other._name))
                return false;
            return true;
        }

    }

    private static final class RoundRobinIterator implements Iterator<BalancerMember>
    {

        private BalancerMember[] _balancerMembers;

        private AtomicInteger _index;

        public RoundRobinIterator(Collection<BalancerMember> balancerMembers)
        {
            _balancerMembers = (BalancerMember[])balancerMembers.toArray(new BalancerMember[balancerMembers.size()]);
            _index = new AtomicInteger(-1);
        }

        public boolean hasNext()
        {
            return true;
        }

        public BalancerMember next()
        {
            BalancerMember balancerMember = null;
            while (balancerMember == null)
            {
                int currentIndex = _index.get();
                int nextIndex = (currentIndex + 1) % _balancerMembers.length;
                if (_index.compareAndSet(currentIndex,nextIndex))
                {
                    balancerMember = _balancerMembers[nextIndex];
                }
            }
            return balancerMember;
        }

        public void remove()
        {
            throw new UnsupportedOperationException();
        }

    }

    private static final String BALANCER_MEMBER_PREFIX = "BalancerMember.";

    private static final List<String> FORBIDDEN_CONFIG_PARAMETERS;
    static
    {
        List<String> params = new LinkedList<String>();
        params.add("HostHeader");
        params.add("whiteList");
        params.add("blackList");
        FORBIDDEN_CONFIG_PARAMETERS = Collections.unmodifiableList(params);
    }

    private static final List<String> REVERSE_PROXY_HEADERS;
    static
    {
        List<String> params = new LinkedList<String>();
        params.add("Location");
        params.add("Content-Location");
        params.add("URI");
        REVERSE_PROXY_HEADERS = Collections.unmodifiableList(params);
    }

    private static final String JSESSIONID = "jsessionid";

    private static final String JSESSIONID_URL_PREFIX = JSESSIONID + "=";

    private boolean _stickySessions;

    private Set<BalancerMember> _balancerMembers = new HashSet<BalancerMember>();

    private boolean _proxyPassReverse;

    private RoundRobinIterator _roundRobinIterator;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        validateConfig(config);
        super.init(config);
        initStickySessions(config);
        initBalancers(config);
        initProxyPassReverse(config);
        postInit();
    }

    private void validateConfig(ServletConfig config) throws ServletException
    {
        @SuppressWarnings("unchecked")
        List<String> initParameterNames = Collections.list(config.getInitParameterNames());
        for (String initParameterName : initParameterNames)
        {
            if (FORBIDDEN_CONFIG_PARAMETERS.contains(initParameterName))
            {
                throw new UnavailableException(initParameterName + " not supported in " + getClass().getName());
            }
        }
    }

    private void initStickySessions(ServletConfig config) throws ServletException
    {
        _stickySessions = "true".equalsIgnoreCase(config.getInitParameter("StickySessions"));
    }

    private void initBalancers(ServletConfig config) throws ServletException
    {
        Set<String> balancerNames = getBalancerNames(config);
        for (String balancerName : balancerNames)
        {
            String memberProxyToParam = BALANCER_MEMBER_PREFIX + balancerName + ".ProxyTo";
            String proxyTo = config.getInitParameter(memberProxyToParam);
            if (proxyTo == null || proxyTo.trim().length() == 0)
            {
                throw new UnavailableException(memberProxyToParam + " parameter is empty.");
            }
            _balancerMembers.add(new BalancerMember(balancerName,proxyTo));
        }
    }

    private void initProxyPassReverse(ServletConfig config)
    {
        _proxyPassReverse = "true".equalsIgnoreCase(config.getInitParameter("ProxyPassReverse"));
    }

    private void postInit()
    {
        _roundRobinIterator = new RoundRobinIterator(_balancerMembers);
    }

    private Set<String> getBalancerNames(ServletConfig config) throws ServletException
    {
        Set<String> names = new HashSet<String>();
        @SuppressWarnings("unchecked")
        List<String> initParameterNames = Collections.list(config.getInitParameterNames());
        for (String initParameterName : initParameterNames)
        {
            if (!initParameterName.startsWith(BALANCER_MEMBER_PREFIX))
            {
                continue;
            }
            int endOfNameIndex = initParameterName.lastIndexOf(".");
            if (endOfNameIndex <= BALANCER_MEMBER_PREFIX.length())
            {
                throw new UnavailableException(initParameterName + " parameter does not provide a balancer member name");
            }
            names.add(initParameterName.substring(BALANCER_MEMBER_PREFIX.length(),endOfNameIndex));
        }
        return names;
    }

    @Override
    protected HttpURI proxyHttpURI(HttpServletRequest request, String uri) throws MalformedURLException
    {
        BalancerMember balancerMember = selectBalancerMember(request);
        try
        {
            URI dstUri = new URI(balancerMember.getProxyTo() + "/" + uri).normalize();
            return new HttpURI(dstUri.toString());
        }
        catch (URISyntaxException e)
        {
            throw new MalformedURLException(e.getMessage());
        }
    }

    private BalancerMember selectBalancerMember(HttpServletRequest request)
    {
        BalancerMember balancerMember = null;
        if (_stickySessions)
        {
            String name = getBalancerMemberNameFromSessionId(request);
            if (name != null)
            {
                balancerMember = findBalancerMemberByName(name);
                if (balancerMember != null)
                {
                    return balancerMember;
                }
            }
        }
        return _roundRobinIterator.next();
    }

    private BalancerMember findBalancerMemberByName(String name)
    {
        BalancerMember example = new BalancerMember(name,"");
        for (BalancerMember balancerMember : _balancerMembers)
        {
            if (balancerMember.equals(example))
            {
                return balancerMember;
            }
        }
        return null;
    }

    private String getBalancerMemberNameFromSessionId(HttpServletRequest request)
    {
        String name = getBalancerMemberNameFromSessionCookie(request);
        if (name == null)
        {
            name = getBalancerMemberNameFromURL(request);
        }
        return name;
    }

    private String getBalancerMemberNameFromSessionCookie(HttpServletRequest request)
    {
        Cookie[] cookies = request.getCookies();
        String name = null;
        for (Cookie cookie : cookies)
        {
            if (JSESSIONID.equalsIgnoreCase(cookie.getName()))
            {
                name = extractBalancerMemberNameFromSessionId(cookie.getValue());
                break;
            }
        }
        return name;
    }

    private String getBalancerMemberNameFromURL(HttpServletRequest request)
    {
        String name = null;
        String requestURI = request.getRequestURI();
        int idx = requestURI.lastIndexOf(";");
        if (idx != -1)
        {
            String requestURISuffix = requestURI.substring(idx);
            if (requestURISuffix.startsWith(JSESSIONID_URL_PREFIX))
            {
                name = extractBalancerMemberNameFromSessionId(requestURISuffix.substring(JSESSIONID_URL_PREFIX.length()));
            }
        }
        return name;
    }

    private String extractBalancerMemberNameFromSessionId(String sessionId)
    {
        String name = null;
        int idx = sessionId.lastIndexOf(".");
        if (idx != -1)
        {
            String sessionIdSuffix = sessionId.substring(idx + 1);
            name = (sessionIdSuffix.length() > 0)?sessionIdSuffix:null;
        }
        return name;
    }

    @Override
    protected String filterResponseHeaderValue(String headerName, String headerValue, HttpServletRequest request)
    {
        if (_proxyPassReverse && REVERSE_PROXY_HEADERS.contains(headerName))
        {
            HttpURI locationURI = new HttpURI(headerValue);
            if (isAbsoluteLocation(locationURI) && isBackendLocation(locationURI))
            {
                Request jettyRequest = (Request)request;
                URI reverseUri;
                try
                {
                    reverseUri = new URI(jettyRequest.getRootURL().append(locationURI.getCompletePath()).toString()).normalize();
                    return reverseUri.toURL().toString();
                }
                catch (Exception e)
                {
                    _log.warn("Not filtering header response",e);
                    return headerValue;
                }
            }
        }
        return headerValue;
    }

    private boolean isBackendLocation(HttpURI locationURI)
    {
        for (BalancerMember balancerMember : _balancerMembers)
        {
            HttpURI backendURI = balancerMember.getBackendURI();
            if (backendURI.getHost().equals(locationURI.getHost()) && backendURI.getScheme().equals(locationURI.getScheme())
                    && backendURI.getPort() == locationURI.getPort())
            {
                return true;
            }
        }
        return false;
    }

    private boolean isAbsoluteLocation(HttpURI locationURI)
    {
        return locationURI.getHost() != null;
    }

    @Override
    public String getHostHeader()
    {
        throw new UnsupportedOperationException("HostHeader not supported in " + getClass().getName());
    }

    @Override
    public void setHostHeader(String hostHeader)
    {
        throw new UnsupportedOperationException("HostHeader not supported in " + getClass().getName());
    }

    @Override
    public boolean validateDestination(String host, String path)
    {
        return true;
    }

}
