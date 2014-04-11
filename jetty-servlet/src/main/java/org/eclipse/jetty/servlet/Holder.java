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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.Registration;
import javax.servlet.ServletContext;
import javax.servlet.UnavailableException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* --------------------------------------------------------------------- */
/** 
 * 
 */
public class Holder<T> extends AbstractLifeCycle implements Dumpable
{
    public enum Source { EMBEDDED, JAVAX_API, DESCRIPTOR, ANNOTATION };
    final private Source _source;
    private static final Logger LOG = Log.getLogger(Holder.class);

    protected transient Class<? extends T> _class;
    protected final Map<String,String> _initParams=new HashMap<String,String>(3);
    protected String _className;
    protected String _displayName;
    protected boolean _extInstance;
    protected boolean _asyncSupported;

    /* ---------------------------------------------------------------- */
    protected String _name;
    protected ServletHandler _servletHandler;

    /* ---------------------------------------------------------------- */
    protected Holder(Source source)
    {
        _source=source;
        switch(_source)
        {
            case JAVAX_API:
            case DESCRIPTOR:
            case ANNOTATION:
                _asyncSupported=false;
                break;
            default:
                _asyncSupported=true;
        }
    }
    
    public Source getSource()
    {
        return _source;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return True if this holder was created for a specific instance.
     */
    public boolean isInstance()
    {
        return _extInstance;
    }
    
    /* ------------------------------------------------------------ */
    @SuppressWarnings("unchecked")
    public void doStart()
        throws Exception
    {
        //if no class already loaded and no classname, make servlet permanently unavailable
        if (_class==null && (_className==null || _className.equals("")))
            throw new UnavailableException("No class for Servlet or Filter for "+_name);
        
        //try to load class
        if (_class==null)
        {
            try
            {
                _class=Loader.loadClass(Holder.class, _className);
                if(LOG.isDebugEnabled())
                    LOG.debug("Holding {}",_class);
            }
            catch (Exception e)
            {
                LOG.warn(e);
                throw new UnavailableException(e.getMessage());
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void doStop()
        throws Exception
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
    public Class<? extends T> getHeldClass()
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
    public Map<String,String> getInitParameters()
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
        if (_name==null)
            _name=className+"-"+Integer.toHexString(this.hashCode());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param held The class to hold
     */
    public void setHeldClass(Class<? extends T> held)
    {
        _class=held;
        if (held!=null)
        {
            _className=held.getName();
            if (_name==null)
                _name=held.getName()+"-"+Integer.toHexString(this.hashCode());
        }
    }
    
    /* ------------------------------------------------------------ */
    public void setDisplayName(String name)
    {
        _displayName=name;
    }
    
    /* ------------------------------------------------------------ */
    public void setInitParameter(String param,String value)
    {
        _initParams.put(param,value);
    }
    
    /* ---------------------------------------------------------------- */
    public void setInitParameters(Map<String,String> map)
    {
        _initParams.clear();
        _initParams.putAll(map);
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
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(_name).append("==").append(_className)
        .append(" - ").append(AbstractLifeCycle.getState(this)).append("\n");
        AggregateLifeCycle.dump(out,indent,_initParams.entrySet());
    }

    /* ------------------------------------------------------------ */
    public String dump()
    {
        return AggregateLifeCycle.dump(this);
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
    }

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    protected class HolderRegistration implements Registration.Dynamic
    {
        public void setAsyncSupported(boolean isAsyncSupported)
        {
            illegalStateIfContextStarted();
            Holder.this.setAsyncSupported(isAsyncSupported);
        }

        public void setDescription(String description)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(this+" is "+description);
        }

        public String getClassName()
        {
            return Holder.this.getClassName();
        }

        public String getInitParameter(String name)
        {
            return Holder.this.getInitParameter(name);
        }

        public Map<String, String> getInitParameters()
        {
            return Holder.this.getInitParameters();
        }

        public String getName()
        {
            return Holder.this.getName();
        }

        public boolean setInitParameter(String name, String value)
        {
            illegalStateIfContextStarted();
            if (name == null) {
                throw new IllegalArgumentException("init parameter name required");
            }
            if (value == null) {
                throw new IllegalArgumentException("non-null value required for init parameter " + name);
            }
            if (Holder.this.getInitParameter(name)!=null)
                return false;
            Holder.this.setInitParameter(name,value);
            return true;
        }

        public Set<String> setInitParameters(Map<String, String> initParameters)
        {
            illegalStateIfContextStarted();
            Set<String> clash=null;
            for (Map.Entry<String, String> entry : initParameters.entrySet())
            {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException("init parameter name required");
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("non-null value required for init parameter " + entry.getKey());
                }
                if (Holder.this.getInitParameter(entry.getKey())!=null)
                {
                    if (clash==null)
                        clash=new HashSet<String>();
                    clash.add(entry.getKey());
                }
            }
            if (clash!=null)
                return clash;
            Holder.this.getInitParameters().putAll(initParameters);
            return Collections.emptySet();
        }
        
        
    }
}





