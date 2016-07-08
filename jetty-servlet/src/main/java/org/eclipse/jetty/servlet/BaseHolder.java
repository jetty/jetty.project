//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.ServletContext;
import javax.servlet.UnavailableException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * AbstractHolder
 * 
 * Base class for all servlet-related classes that may be lazily instantiated  (eg servlet, filter, 
 * listener), and/or require metadata to be held regarding their origin 
 * (web.xml, annotation, programmatic api etc).
 * @param <T> the type of holder
 */
public abstract class BaseHolder<T> extends AbstractLifeCycle implements Dumpable
{
    private static final Logger LOG = Log.getLogger(BaseHolder.class);
    
    
    final protected Source _source;
    protected transient Class<? extends T> _class;
    protected String _className;
    protected boolean _extInstance;
    protected ServletHandler _servletHandler;
    
    /* ---------------------------------------------------------------- */
    protected BaseHolder(Source source)
    {
        _source=source;
    }

    /* ------------------------------------------------------------ */
    public Source getSource()
    {
        return _source;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Do any setup necessary after starting
     * @throws Exception if unable to initialize
     */
    public void initialize()
    throws Exception
    {
        if (!isStarted())
            throw new IllegalStateException("Not started: "+this);
    }

    /* ------------------------------------------------------------ */
    @SuppressWarnings("unchecked")
    @Override
    public void doStart()
        throws Exception
    {
        //if no class already loaded and no classname, make permanently unavailable
        if (_class==null && (_className==null || _className.equals("")))
            throw new UnavailableException("No class in holder");
        
        //try to load class
        if (_class==null)
        {
            try
            {
                _class=Loader.loadClass(_className);
                if(LOG.isDebugEnabled())
                    LOG.debug("Holding {} from {}",_class,_class.getClassLoader());
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
    @ManagedAttribute(value="Class Name", readonly=true)
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
    /**
     * @return Returns the servletHandler.
     */
    public ServletHandler getServletHandler()
    {
        return _servletHandler;
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
     * @param held The class to hold
     */
    public void setHeldClass(Class<? extends T> held)
    {
        _class=held;
        if (held!=null)
        {
            _className=held.getName();
        }
    }
    

    /* ------------------------------------------------------------ */
    protected void illegalStateIfContextStarted()
    {
        if (_servletHandler!=null)
        {
            ServletContext context=_servletHandler.getServletContext();
            if ((context instanceof ContextHandler.Context) && ((ContextHandler.Context)context).getContextHandler().isStarted())
                throw new IllegalStateException("Started");
        }
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
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(toString())
        .append(" - ").append(AbstractLifeCycle.getState(this)).append("\n");
    }

    /* ------------------------------------------------------------ */
    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }
}
