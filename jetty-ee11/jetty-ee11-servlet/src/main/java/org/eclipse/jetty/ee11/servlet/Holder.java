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

package org.eclipse.jetty.ee11.servlet;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.Registration;
import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee.Source;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Specialization of ServletContextHolder for servlet-related classes that
 * have init-params etc
 *
 * @param <T> the type of holder
 */
@ManagedObject("Holder - a container for servlets and the like")
public abstract class Holder<T> extends ServletContextHolder<T>
{
    private static final Logger LOG = LoggerFactory.getLogger(Holder.class);

    private final Map<String, String> _initParams = new HashMap<>(3);
    private boolean _asyncSupported;

    protected Holder(Source source)
    {
        super(source);
        switch (getSource().getOrigin())
        {
            case SERVLET_API:
            case DESCRIPTOR:
            case ANNOTATION:
                _asyncSupported = false;
                break;
            default:
                _asyncSupported = true;
        }
    }

    public String getInitParameter(String param)
    {
        return _initParams.get(param);
    }

    public Enumeration<String> getInitParameterNames()
    {
        return Collections.enumeration(_initParams.keySet());
    }

    @ManagedAttribute(value = "Initial Parameters", readonly = true)
    public Map<String, String> getInitParameters()
    {
        return _initParams;
    }

    @Override
    protected void setInstance(T instance)
    {
        try (AutoLock ignored = lock())
        {
            super.setInstance(instance);
            if (getName() == null)
                setName(String.format("%s@%x", instance.getClass().getName(), instance.hashCode()));
        }
    }

    public void destroyInstance(Object instance)
        throws Exception
    {
    }

    public void setInitParameter(String param, String value)
    {
        _initParams.put(param, value);
    }

    public void setInitParameters(Map<String, String> map)
    {
        _initParams.clear();
        _initParams.putAll(map);
    }

    public void setAsyncSupported(boolean suspendable)
    {
        _asyncSupported = suspendable;
    }

    public boolean isAsyncSupported()
    {
        return _asyncSupported;
    }

    @Override
    public String dump()
    {
        return super.dump();
    }

    protected class HolderConfig
    {
        public ServletContext getServletContext()
        {
            return Holder.this.getServletContext();
        }

        public String getInitParameter(String param)
        {
            return Holder.this.getInitParameter(param);
        }

        public Enumeration<String> getInitParameterNames()
        {
            return Holder.this.getInitParameterNames();
        }
    }

    protected class HolderRegistration implements Registration.Dynamic
    {
        @Override
        public void setAsyncSupported(boolean isAsyncSupported)
        {
            illegalStateIfContextStarted();
            Holder.this.setAsyncSupported(isAsyncSupported);
        }

        public void setDescription(String description)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} is {}", this, description);
        }

        @Override
        public String getClassName()
        {
            return Holder.this.getClassName();
        }

        @Override
        public String getInitParameter(String name)
        {
            return Holder.this.getInitParameter(name);
        }

        @Override
        public Map<String, String> getInitParameters()
        {
            return Holder.this.getInitParameters();
        }

        @Override
        public String getName()
        {
            return Holder.this.getName();
        }

        @Override
        public boolean setInitParameter(String name, String value)
        {
            illegalStateIfContextStarted();
            if (name == null)
            {
                throw new IllegalArgumentException("init parameter name required");
            }
            if (value == null)
            {
                throw new IllegalArgumentException("non-null value required for init parameter " + name);
            }
            if (Holder.this.getInitParameter(name) != null)
                return false;
            Holder.this.setInitParameter(name, value);
            return true;
        }

        @Override
        public Set<String> setInitParameters(Map<String, String> initParameters)
        {
            illegalStateIfContextStarted();
            Set<String> clash = null;
            for (Map.Entry<String, String> entry : initParameters.entrySet())
            {
                if (entry.getKey() == null)
                {
                    throw new IllegalArgumentException("init parameter name required");
                }
                if (entry.getValue() == null)
                {
                    throw new IllegalArgumentException("non-null value required for init parameter " + entry.getKey());
                }
                if (Holder.this.getInitParameter(entry.getKey()) != null)
                {
                    if (clash == null)
                        clash = new HashSet<String>();
                    clash.add(entry.getKey());
                }
            }
            if (clash != null)
                return clash;
            Holder.this.getInitParameters().putAll(initParameters);
            return Collections.emptySet();
        }
    }
}





