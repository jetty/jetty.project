//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.security.jaspi;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jakarta.security.auth.message.MessageInfo;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Almost an implementation of jaspi MessageInfo.
 */
public class JaspiMessageInfo implements MessageInfo
{
    public static final String AUTHENTICATION_TYPE_KEY = "jakarta.servlet.http.authType";
    private final Callback _callback;
    private Request _request;
    private Response _response;
    private final MIMap _map;

    public JaspiMessageInfo(Request request, Response response, Callback callback)
    {
        _request = request;
        _response = response;
        _callback = callback;
        //JASPI 3.8.1
        _map = new MIMap();
    }
    
    public Callback getCallback()
    {
        return _callback;
    }

    @Override
    public Map getMap()
    {
        return _map;
    }

    public Request getBaseRequest()
    {
        return _request;
    }
    
    public Response getBaseResponse()
    {
        return _response;
    }
    
    @Override
    public Object getRequestMessage()
    {
        if (_request == null)
            return null;
        return Request.asInContext(_request, ServletContextRequest.class).getServletApiRequest();
    }

    @Override
    public Object getResponseMessage()
    {
        if (_response == null)
            return null;
        return Response.asInContext(_response, ServletContextResponse.class).getServletApiResponse();
    }

    @Override
    public void setRequestMessage(Object request)
    {
        if (!(request instanceof ServletRequest))
            throw new IllegalStateException("Not a ServletRequest");
        _request = ServletContextRequest.getServletContextRequest((ServletRequest)request);
    }

    @Override
    public void setResponseMessage(Object response)
    {
        if (!(response instanceof ServletResponse))
            throw new IllegalStateException("Not a ServletResponse");
        _response = ServletContextResponse.getServletContextResponse((ServletResponse)response);
    }

    //TODO this has bugs in the view implementations.  Changing them will not affect the hardcoded values.
    private static class MIMap implements Map
    {
        private String authenticationType;
        private Map delegate;

        private MIMap()
        {
        }

        @Override
        public int size()
        {
            return delegate.size();
        }

        @Override
        public boolean isEmpty()
        {
            return delegate == null || delegate.isEmpty();
        }

        @Override
        public boolean containsKey(Object key)
        {
            if (AUTHENTICATION_TYPE_KEY.equals(key))
                return authenticationType != null;
            return delegate != null && delegate.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value)
        {
            if (authenticationType == value || (authenticationType != null && authenticationType.equals(value)))
                return true;
            return delegate != null && delegate.containsValue(value);
        }

        @Override
        public Object get(Object key)
        {
            if (AUTHENTICATION_TYPE_KEY.equals(key))
                return authenticationType;
            if (delegate == null)
                return null;
            return delegate.get(key);
        }

        @Override
        public Object put(Object key, Object value)
        {
            if (AUTHENTICATION_TYPE_KEY.equals(key))
            {
                String authenticationType = this.authenticationType;
                this.authenticationType = (String)value;
                if (delegate != null)
                    delegate.put(AUTHENTICATION_TYPE_KEY, value);
                return authenticationType;
            }

            return getDelegate(true).put(key, value);
        }

        @Override
        public Object remove(Object key)
        {
            if (AUTHENTICATION_TYPE_KEY.equals(key))
            {
                String authenticationType = this.authenticationType;
                this.authenticationType = null;
                if (delegate != null)
                    delegate.remove(AUTHENTICATION_TYPE_KEY);
                return authenticationType;
            }
            if (delegate == null)
                return null;
            return delegate.remove(key);
        }

        @Override
        public void putAll(Map map)
        {
            if (map != null)
            {
                for (Object o : map.entrySet())
                {
                    Map.Entry entry = (Entry)o;
                    put(entry.getKey(), entry.getValue());
                }
            }
        }

        @Override
        public void clear()
        {
            authenticationType = null;
            delegate = null;
        }

        @Override
        public Set keySet()
        {
            return getDelegate(true).keySet();
        }

        @Override
        public Collection values()
        {
            return getDelegate(true).values();
        }

        @Override
        public Set entrySet()
        {
            return getDelegate(true).entrySet();
        }

        private Map getDelegate(boolean create)
        {
            if (!create || delegate != null)
                return delegate;
            if (create)
            {
                delegate = new HashMap();
                if (authenticationType != null)
                    delegate.put(AUTHENTICATION_TYPE_KEY, authenticationType);
            }
            return delegate;
        }

        String getAuthenticationType()
        {
            return authenticationType;
        }
    }
}
