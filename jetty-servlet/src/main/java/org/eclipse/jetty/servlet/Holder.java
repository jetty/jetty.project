// ========================================================================
// Copyright (c) 1996-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.servlet;

import java.io.Serializable;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.UnavailableException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;


/* --------------------------------------------------------------------- */
/** 
 * 
 */
public class Holder extends AbstractLifeCycle implements Serializable
{
    protected transient Class _class;
    protected String _className;
    protected String _displayName;
    protected Map _initParams;
    protected boolean _extInstance;
    protected boolean _asyncSupported;
    protected AttributesMap _initAttributes;

    /* ---------------------------------------------------------------- */
    protected String _name;
    protected ServletHandler _servletHandler;

    protected Holder()
    {}

    /* ---------------------------------------------------------------- */
    protected Holder(Class held)
    {
        _class=held;
        if (held!=null)
        {
            _className=held.getName();
            _name=held.getName()+"-"+this.hashCode();
        }
    }

    /* ------------------------------------------------------------ */
    public void doStart()
        throws Exception
    {
        //if no class already loaded and no classname, make servlet permanently unavailable
        if (_class==null && (_className==null || _className.equals("")))
            throw new UnavailableException("No class for Servlet or Filter", -1);
        
        //try to load class
        if (_class==null)
        {
            try
            {
                _class=Loader.loadClass(Holder.class, _className);
                if(Log.isDebugEnabled())Log.debug("Holding {}",_class);
            }
            catch (Exception e)
            {
                Log.warn(e);
                throw new UnavailableException(e.getMessage(), -1);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    public void doStop()
    {
        if (!_extInstance)
            _class=null;
    }
    
    /* ------------------------------------------------------------ */
    public String getClassName()
    {
        return _className;
    }
    
    /* ------------------------------------------------------------ */
    public Class getHeldClass()
    {
        return _class;
    }
    
    /* ------------------------------------------------------------ */
    public String getDisplayName()
    {
        return _displayName;
    }

    /* ---------------------------------------------------------------- */
    public String getInitParameter(String param)
    {
        if (_initParams==null)
            return null;
        return (String)_initParams.get(param);
    }
    
    /* ------------------------------------------------------------ */
    public Enumeration getInitParameterNames()
    {
        if (_initParams==null)
            return Collections.enumeration(Collections.EMPTY_LIST);
        return Collections.enumeration(_initParams.keySet());
    }

    /* ---------------------------------------------------------------- */
    public Map getInitParameters()
    {
        return _initParams;
    }
    
    /* ------------------------------------------------------------ */
    public String getName()
    {
        return _name;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the servletHandler.
     */
    public ServletHandler getServletHandler()
    {
        return _servletHandler;
    }
    
    /* ------------------------------------------------------------ */
    public synchronized Object newInstance()
        throws InstantiationException,
               IllegalAccessException
    {
        if (_class==null)
            throw new InstantiationException("!"+_className);
        return _class.newInstance();
    }

    /* ------------------------------------------------------------ */
    public void destroyInstance(Object instance)
    throws Exception
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param className The className to set.
     */
    public void setClassName(String className)
    {
        _className = className;
        _class=null;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param className The className to set.
     */
    public void setHeldClass(Class held)
    {
        _class=held;
        _className = held!=null?held.getName():null;
    }
    
    /* ------------------------------------------------------------ */
    public void setDisplayName(String name)
    {
        _displayName=name;
    }
    
    /* ------------------------------------------------------------ */
    public void setInitParameter(String param,String value)
    {
        if (_initParams==null)
            _initParams=new HashMap(3);
        _initParams.put(param,value);
    }
    
    /* ---------------------------------------------------------------- */
    public void setInitParameters(Map map)
    {
        _initParams=map;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * The name is a primary key for the held object.
     * Ensure that the name is set BEFORE adding a Holder
     * (eg ServletHolder or FilterHolder) to a ServletHandler.
     * @param name The name to set.
     */
    public void setName(String name)
    {
        _name = name;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param servletHandler The {@link ServletHandler} that will handle requests dispatched to this servlet.
     */
    public void setServletHandler(ServletHandler servletHandler)
    {
        _servletHandler = servletHandler;
    }

    /* ------------------------------------------------------------ */
    public void setAsyncSupported(boolean suspendable)
    {
        _asyncSupported=suspendable;
    }

    /* ------------------------------------------------------------ */
    public boolean isAsyncSupported()
    {
        return _asyncSupported;
    }
    
    /* ------------------------------------------------------------ */
    public String toString()
    {
        return _name;
    }

    /* ------------------------------------------------------------ */
    protected void illegalStateIfContextStarted()
    {
        if (_servletHandler!=null)
        {
            ContextHandler.Context context=(ContextHandler.Context)_servletHandler.getServletContext();
            if (context!=null && context.getContextHandler().isStarted())
                throw new IllegalStateException("Started");
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected class HolderConfig 
    {   
        
        /* -------------------------------------------------------- */
        public ServletContext getServletContext()
        {
            return _servletHandler.getServletContext();
        }

        /* -------------------------------------------------------- */
        public String getInitParameter(String param)
        {
            return Holder.this.getInitParameter(param);
        }
    
        /* -------------------------------------------------------- */
        public Enumeration getInitParameterNames()
        {
            return Holder.this.getInitParameterNames();
        }

        /* ------------------------------------------------------------ */
        /**
         * @see javax.servlet.ServletConfig#getInitAttribute(java.lang.String)
         */
        public Object getInitAttribute(String name)
        {
            return (Holder.this._initAttributes==null)?null:Holder.this._initAttributes.getAttribute(name);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see javax.servlet.ServletConfig#getInitAttributeNames()
         */
        public Iterable<String> getInitAttributeNames()
        {
            if (Holder.this._initAttributes!=null)
                return Holder.this._initAttributes.keySet();
            return Collections.emptySet();    
        }
        
    }

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    protected class HolderRegistration
    {
        public boolean setAsyncSupported(boolean isAsyncSupported)
        {
            illegalStateIfContextStarted();
            Holder.this.setAsyncSupported(isAsyncSupported);
            return true;
        }

        public boolean setDescription(String description)
        {
            return true;
        }

        public boolean setInitParameter(String name, String value)
        {
            illegalStateIfContextStarted();
            if (Holder.this.getInitParameter(name)!=null)
                return false;
            Holder.this.setInitParameter(name,value);
            return true;
        }

        public boolean setInitParameters(Map<String, String> initParameters)
        {
            illegalStateIfContextStarted();
            for (String name : initParameters.keySet())
                if (Holder.this.getInitParameter(name)!=null)
                    return false;
            Holder.this.setInitParameters(initParameters);
            return true;
        }

        /* ------------------------------------------------------------ */
        /**
         * @see javax.servlet.ServletRegistration#setInitAttribute(java.lang.String, java.lang.Object)
         */
        public boolean setInitAttribute(String name, Object value)
        {
            illegalStateIfContextStarted();
            if (_initAttributes==null)
                _initAttributes=new AttributesMap();
            else if (_initAttributes.getAttribute(name)!=null)
                return false;
            _initAttributes.setAttribute(name,value);
            return true;
        }

        /* ------------------------------------------------------------ */
        /**
         * @see javax.servlet.ServletRegistration#setInitAttributes(java.util.Map)
         */
        public boolean setInitAttributes(Map<String, Object> initAttributes)
        {
            illegalStateIfContextStarted();
            if (_initAttributes==null)
                _initAttributes=new AttributesMap();
            else
            {
                for (String name : initAttributes.keySet())
                    if (_initAttributes.getAttribute(name)!=null)
                        return false;
            }
            for (String name : initAttributes.keySet())
                _initAttributes.setAttribute(name,initAttributes.get(name));
            
            return true;
        };
    }
}





