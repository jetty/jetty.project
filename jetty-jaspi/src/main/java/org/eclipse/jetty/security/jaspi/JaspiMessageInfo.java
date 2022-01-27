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

package org.eclipse.jetty.security.jaspi;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.security.auth.message.MessageInfo;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * Almost an implementation of jaspi MessageInfo.
 */
public class JaspiMessageInfo implements MessageInfo
{
    public static final String MANDATORY_KEY = "javax.security.auth.message.MessagePolicy.isMandatory";
    public static final String AUTH_METHOD_KEY = "javax.servlet.http.authType";
    private ServletRequest request;
    private ServletResponse response;
    private final MIMap map;

    public JaspiMessageInfo(ServletRequest request, ServletResponse response, boolean isAuthMandatory)
    {
        this.request = request;
        this.response = response;
        //JASPI 3.8.1
        map = new MIMap(isAuthMandatory);
    }

    @Override
    public Map getMap()
    {
        return map;
    }

    @Override
    public Object getRequestMessage()
    {
        return request;
    }

    @Override
    public Object getResponseMessage()
    {
        return response;
    }

    @Override
    public void setRequestMessage(Object request)
    {
        this.request = (ServletRequest)request;
    }

    @Override
    public void setResponseMessage(Object response)
    {
        this.response = (ServletResponse)response;
    }

    public String getAuthMethod()
    {
        return map.getAuthMethod();
    }

    public boolean isAuthMandatory()
    {
        return map.isAuthMandatory();
    }

    //TODO this has bugs in the view implementations.  Changing them will not affect the hardcoded values.
    private static class MIMap implements Map
    {
        private final boolean isMandatory;
        private String authMethod;
        private Map delegate;

        private MIMap(boolean mandatory)
        {
            isMandatory = mandatory;
        }

        @Override
        public int size()
        {
            return (isMandatory ? 1 : 0) +
                (authMethod == null ? 0 : 1) +
                (delegate == null ? 0 : delegate.size());
        }

        @Override
        public boolean isEmpty()
        {
            return !isMandatory && authMethod == null && (delegate == null || delegate.isEmpty());
        }

        @Override
        public boolean containsKey(Object key)
        {
            if (MANDATORY_KEY.equals(key))
                return isMandatory;
            if (AUTH_METHOD_KEY.equals(key))
                return authMethod != null;
            return delegate != null && delegate.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value)
        {
            if (isMandatory && "true".equals(value))
                return true;
            if (authMethod == value || (authMethod != null && authMethod.equals(value)))
                return true;
            return delegate != null && delegate.containsValue(value);
        }

        @Override
        public Object get(Object key)
        {
            if (MANDATORY_KEY.equals(key))
                return isMandatory ? "true" : null;
            if (AUTH_METHOD_KEY.equals(key))
                return authMethod;
            if (delegate == null)
                return null;
            return delegate.get(key);
        }

        @Override
        public Object put(Object key, Object value)
        {
            if (MANDATORY_KEY.equals(key))
            {
                throw new IllegalArgumentException("Mandatory not mutable");
            }
            if (AUTH_METHOD_KEY.equals(key))
            {
                String authMethod = this.authMethod;
                this.authMethod = (String)value;
                if (delegate != null)
                    delegate.put(AUTH_METHOD_KEY, value);
                return authMethod;
            }

            return getDelegate(true).put(key, value);
        }

        @Override
        public Object remove(Object key)
        {
            if (MANDATORY_KEY.equals(key))
            {
                throw new IllegalArgumentException("Mandatory not mutable");
            }
            if (AUTH_METHOD_KEY.equals(key))
            {
                String authMethod = this.authMethod;
                this.authMethod = null;
                if (delegate != null)
                    delegate.remove(AUTH_METHOD_KEY);
                return authMethod;
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
            authMethod = null;
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
                if (isMandatory)
                    delegate.put(MANDATORY_KEY, "true");
                if (authMethod != null)
                    delegate.put(AUTH_METHOD_KEY, authMethod);
            }
            return delegate;
        }

        boolean isAuthMandatory()
        {
            return isMandatory;
        }

        String getAuthMethod()
        {
            return authMethod;
        }
    }
}
