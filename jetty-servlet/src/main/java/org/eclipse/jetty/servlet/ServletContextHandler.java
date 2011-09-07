// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletSecurityElement;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;

import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.LazyList;


/* ------------------------------------------------------------ */
/** Servlet Context.
 * This extension to the ContextHandler allows for
 * simple construction of a context with ServletHandler and optionally
 * session and security handlers, et.<pre>
 *   new ServletContext("/context",Context.SESSIONS|Context.NO_SECURITY);
 * </pre>
 * <p/>
 * This class should have been called ServletContext, but this would have
 * cause confusion with {@link ServletContext}.
 */
public class ServletContextHandler extends ContextHandler
{   
    public final static int SESSIONS=1;
    public final static int SECURITY=2;
    public final static int NO_SESSIONS=0;
    public final static int NO_SECURITY=0;

    protected final List<Decorator> _decorators= new ArrayList<Decorator>();
    protected Class<? extends SecurityHandler> _defaultSecurityHandlerClass=org.eclipse.jetty.security.ConstraintSecurityHandler.class;
    protected SessionHandler _sessionHandler;
    protected SecurityHandler _securityHandler;
    protected ServletHandler _servletHandler;
    protected HandlerWrapper _wrapper;
    protected int _options;
    protected JspConfigDescriptor _jspConfig;
    protected Object _restrictedContextListeners;
    private boolean _restrictListeners = true;

    /* ------------------------------------------------------------ */
    public ServletContextHandler()
    {
        this(null,null,null,null,null);
    }
    
    /* ------------------------------------------------------------ */
    public ServletContextHandler(int options)
    {
        this(null,null,options);
    }
    
    /* ------------------------------------------------------------ */
    public ServletContextHandler(HandlerContainer parent, String contextPath)
    {
        this(parent,contextPath,null,null,null,null);
    }
    
    /* ------------------------------------------------------------ */
    public ServletContextHandler(HandlerContainer parent, String contextPath, int options)
    {
        this(parent,contextPath,null,null,null,null);
        _options=options;
    }
    
    /* ------------------------------------------------------------ */
    public ServletContextHandler(HandlerContainer parent, String contextPath, boolean sessions, boolean security)
    {
        this(parent,contextPath,(sessions?SESSIONS:0)|(security?SECURITY:0));
    }

    /* ------------------------------------------------------------ */
    public ServletContextHandler(HandlerContainer parent, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {   
        this(parent,null,sessionHandler,securityHandler,servletHandler,errorHandler);
    }

    /* ------------------------------------------------------------ */
    public ServletContextHandler(HandlerContainer parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {   
        super((ContextHandler.Context)null);
        _scontext = new Context();
        _sessionHandler = sessionHandler;
        _securityHandler = securityHandler;
        _servletHandler = servletHandler;
            
        if (errorHandler!=null)
            setErrorHandler(errorHandler);

        if (contextPath!=null)
            setContextPath(contextPath);

        if (parent instanceof HandlerWrapper)
            ((HandlerWrapper)parent).setHandler(this);
        else if (parent instanceof HandlerCollection)
            ((HandlerCollection)parent).addHandler(this);
    }    

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.ContextHandler#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_decorators != null)
            _decorators.clear();
        if (_wrapper != null)
            _wrapper.setHandler(null);
    }

    /* ------------------------------------------------------------ */
    /** Get the defaultSecurityHandlerClass.
     * @return the defaultSecurityHandlerClass
     */
    public Class<? extends SecurityHandler> getDefaultSecurityHandlerClass()
    {
        return _defaultSecurityHandlerClass;
    }

    /* ------------------------------------------------------------ */
    /** Set the defaultSecurityHandlerClass.
     * @param defaultSecurityHandlerClass the defaultSecurityHandlerClass to set
     */
    public void setDefaultSecurityHandlerClass(Class<? extends SecurityHandler> defaultSecurityHandlerClass)
    {
        _defaultSecurityHandlerClass = defaultSecurityHandlerClass;
    }

    /* ------------------------------------------------------------ */
    protected SessionHandler newSessionHandler()
    {
        return new SessionHandler();
    }
    
    /* ------------------------------------------------------------ */
    protected SecurityHandler newSecurityHandler()
    {
        try
        {
            return (SecurityHandler)_defaultSecurityHandlerClass.newInstance();
        }
        catch(Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    /* ------------------------------------------------------------ */
    protected ServletHandler newServletHandler()
    {
        return new ServletHandler();
    }

    /* ------------------------------------------------------------ */
    /**
     * Finish constructing handlers and link them together.
     * 
     * @see org.eclipse.jetty.server.handler.ContextHandler#startContext()
     */
    protected void startContext() throws Exception
    {
        // force creation of missing handlers.
        getSessionHandler();
        getSecurityHandler();
        getServletHandler();
        
        Handler handler = _servletHandler;
        if (_securityHandler!=null)
        {
            _securityHandler.setHandler(handler);
            handler=_securityHandler;
        }
        
        if (_sessionHandler!=null)
        {
            _sessionHandler.setHandler(handler);
            handler=_sessionHandler;
        }
        
        // skip any wrapped handlers 
        _wrapper=this;
        while (_wrapper!=handler && _wrapper.getHandler() instanceof HandlerWrapper)
            _wrapper=(HandlerWrapper)_wrapper.getHandler();
        
        // if we are not already linked
        if (_wrapper!=handler)
        {
            if (_wrapper.getHandler()!=null )
                throw new IllegalStateException("!ScopedHandler");
            _wrapper.setHandler(handler);
        }
        
    	super.startContext();

    	// OK to Initialize servlet handler now
    	if (_servletHandler != null && _servletHandler.isStarted())
    	{
    	    for (int i=_decorators.size()-1;i>=0; i--)
    	    {
    	        Decorator decorator = _decorators.get(i);
                if (_servletHandler.getFilters()!=null)
                    for (FilterHolder holder:_servletHandler.getFilters())
                        decorator.decorateFilterHolder(holder);
    	        if(_servletHandler.getServlets()!=null)
    	            for (ServletHolder holder:_servletHandler.getServlets())
    	                decorator.decorateServletHolder(holder);
    	    }   
    	        
    	    _servletHandler.initialize();
    	}
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the securityHandler.
     */
    public SecurityHandler getSecurityHandler()
    {
        if (_securityHandler==null && (_options&SECURITY)!=0 && !isStarted()) 
            _securityHandler=newSecurityHandler();
        
        return _securityHandler;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the servletHandler.
     */
    public ServletHandler getServletHandler()
    {
        if (_servletHandler==null && !isStarted()) 
            _servletHandler=newServletHandler();
        return _servletHandler;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the sessionHandler.
     */
    public SessionHandler getSessionHandler()
    {
        if (_sessionHandler==null && (_options&SESSIONS)!=0 && !isStarted()) 
            _sessionHandler=newSessionHandler();
        return _sessionHandler;
    }

    /* ------------------------------------------------------------ */
    /** conveniance method to add a servlet.
     */
    public ServletHolder addServlet(String className,String pathSpec)
    {
        return getServletHandler().addServletWithMapping(className, pathSpec);
    }

    /* ------------------------------------------------------------ */
    /** conveniance method to add a servlet.
     */
    public ServletHolder addServlet(Class<? extends Servlet> servlet,String pathSpec)
    {
        return getServletHandler().addServletWithMapping(servlet.getName(), pathSpec);
    }
    
    /* ------------------------------------------------------------ */
    /** conveniance method to add a servlet.
     */
    public void addServlet(ServletHolder servlet,String pathSpec)
    {
        getServletHandler().addServletWithMapping(servlet, pathSpec);
    }

    /* ------------------------------------------------------------ */
    /** conveniance method to add a filter
     */
    public void addFilter(FilterHolder holder,String pathSpec,EnumSet<DispatcherType> dispatches)
    {
        getServletHandler().addFilterWithMapping(holder,pathSpec,dispatches);
    }

    /* ------------------------------------------------------------ */
    /** convenience method to add a filter
     */
    public FilterHolder addFilter(Class<? extends Filter> filterClass,String pathSpec,EnumSet<DispatcherType> dispatches)
    {
        return getServletHandler().addFilterWithMapping(filterClass,pathSpec,dispatches);
    }

    /* ------------------------------------------------------------ */
    /** convenience method to add a filter
     */
    public FilterHolder addFilter(String filterClass,String pathSpec,EnumSet<DispatcherType> dispatches)
    {
        return getServletHandler().addFilterWithMapping(filterClass,pathSpec,dispatches);
    }

    /**
     * notification that a ServletRegistration has been created so we can track the annotations
     * @param holder new holder created through the api.
     * @return the ServletRegistration.Dynamic
     */
    protected ServletRegistration.Dynamic dynamicHolderAdded(ServletHolder holder) {
        return holder.getRegistration();
    }

    /**
     * delegate for ServletContext.declareRole method
     * @param roleNames role names to add
     */
    protected void addRoles(String... roleNames) {
        //Get a reference to the SecurityHandler, which must be ConstraintAware
        if (_securityHandler != null && _securityHandler instanceof ConstraintAware)
        {
            HashSet<String> union = new HashSet<String>();
            Set<String> existing = ((ConstraintAware)_securityHandler).getRoles();
            if (existing != null)
                union.addAll(existing);
            union.addAll(Arrays.asList(roleNames));
            ((ConstraintSecurityHandler)_securityHandler).setRoles(union);
        }
    }

    /**
     * Delegate for ServletRegistration.Dynamic.setServletSecurity method
     * @param registration ServletRegistration.Dynamic instance that setServletSecurity was called on
     * @param servletSecurityElement new security info
     * @return the set of exact URL mappings currently associated with the registration that are also present in the web.xml
     * security constratins and thus will be unaffected by this call.
     */
    public Set<String> setServletSecurity(ServletRegistration.Dynamic registration, ServletSecurityElement servletSecurityElement)
    {
        return Collections.emptySet();
    }


    
    public void restrictEventListener (EventListener e)
    {
        if (_restrictListeners && e instanceof ServletContextListener)
            _restrictedContextListeners = LazyList.add(_restrictedContextListeners, e);
    }

    public boolean isRestrictListeners() {
        return _restrictListeners;
    }

    public void setRestrictListeners(boolean restrictListeners) {
        this._restrictListeners = restrictListeners;
    }

    public void callContextInitialized(ServletContextListener l, ServletContextEvent e)
    {
        try
        {
            //toggle state of the dynamic API so that the listener cannot use it
            if (LazyList.contains(_restrictedContextListeners, l))
                this.getServletContext().setEnabled(false);
            
            super.callContextInitialized(l, e);
        }
        finally
        {
            //untoggle the state of the dynamic API
            this.getServletContext().setEnabled(true);
        }
    }

    
    public void callContextDestroyed(ServletContextListener l, ServletContextEvent e)
    {
        super.callContextDestroyed(l, e);
    }

  

    /* ------------------------------------------------------------ */
    /**
     * @param sessionHandler The sessionHandler to set.
     */
    public void setSessionHandler(SessionHandler sessionHandler)
    {
        if (isStarted())
            throw new IllegalStateException("STARTED");

        _sessionHandler = sessionHandler;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param securityHandler The {@link SecurityHandler} to set on this context.
     */
    public void setSecurityHandler(SecurityHandler securityHandler)
    {
        if (isStarted())
            throw new IllegalStateException("STARTED");

        _securityHandler = securityHandler;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servletHandler The servletHandler to set.
     */
    public void setServletHandler(ServletHandler servletHandler)
    {
        if (isStarted())
            throw new IllegalStateException("STARTED");

        _servletHandler = servletHandler;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The decorator list used to resource inject new Filters, Servlets and EventListeners
     */
    public List<Decorator> getDecorators()
    {
        return Collections.unmodifiableList(_decorators);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param decorators The lis of {@link Decorator}s
     */
    public void setDecorators(List<Decorator> decorators)
    {
        _decorators.clear();
        _decorators.addAll(decorators);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param decorator The decorator to add
     */
    public void addDecorator(Decorator decorator)
    {
        _decorators.add(decorator);
    }

    /* ------------------------------------------------------------ */
    void destroyServlet(Servlet servlet)
    {
        for (Decorator decorator : _decorators)
            decorator.destroyServletInstance(servlet);
    }

    /* ------------------------------------------------------------ */
    void destroyFilter(Filter filter)
    {
        for (Decorator decorator : _decorators)
            decorator.destroyFilterInstance(filter);
    }
    
    /* ------------------------------------------------------------ */
    public class Context extends ContextHandler.Context
    {
        /* ------------------------------------------------------------ */
        /* 
         * @see javax.servlet.ServletContext#getNamedDispatcher(java.lang.String)
         */
        @Override
        public RequestDispatcher getNamedDispatcher(String name)
        {
            ContextHandler context=org.eclipse.jetty.servlet.ServletContextHandler.this;
            if (_servletHandler==null || _servletHandler.getServlet(name)==null)
                return null;
            return new Dispatcher(context, name);
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
        {
            if (isStarted())
                throw new IllegalStateException();
            
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            final FilterHolder holder= handler.newFilterHolder(Holder.Source.JAVAX_API);
            holder.setName(filterName);
            holder.setHeldClass(filterClass);
            handler.addFilter(holder);
            return holder.getRegistration();
        }

        /* ------------------------------------------------------------ */
        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, String className)
        {
            if (isStarted())
                throw new IllegalStateException();
            
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            final FilterHolder holder= handler.newFilterHolder(Holder.Source.JAVAX_API);
            holder.setName(filterName);
            holder.setClassName(className);
            handler.addFilter(holder);
            return holder.getRegistration();
        }


        /* ------------------------------------------------------------ */
        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Filter filter)
        {
            if (isStarted())
                throw new IllegalStateException();

            if (!_enabled)
                throw new UnsupportedOperationException();
            
            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            final FilterHolder holder= handler.newFilterHolder(Holder.Source.JAVAX_API);
            holder.setName(filterName);
            holder.setFilter(filter);
            handler.addFilter(holder);
            return holder.getRegistration();
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
        {
            if (!isStarting())
                throw new IllegalStateException();
            
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            final ServletHolder holder= handler.newServletHolder(Holder.Source.JAVAX_API);
            holder.setName(servletName);
            holder.setHeldClass(servletClass);
            handler.addServlet(holder);
            return dynamicHolderAdded(holder);
        }

        /* ------------------------------------------------------------ */
        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, String className)
        {
            if (!isStarting())
                throw new IllegalStateException();
            
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            final ServletHolder holder= handler.newServletHolder(Holder.Source.JAVAX_API);
            holder.setName(servletName);
            holder.setClassName(className);
            handler.addServlet(holder);
            return dynamicHolderAdded(holder);
        }

        /* ------------------------------------------------------------ */
        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
        {
            if (!isStarting())
                throw new IllegalStateException();

            if (!_enabled)
                throw new UnsupportedOperationException();
            
            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            final ServletHolder holder= handler.newServletHolder(Holder.Source.JAVAX_API);
            holder.setName(servletName);
            holder.setServlet(servlet);
            handler.addServlet(holder);
            return dynamicHolderAdded(holder);
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean setInitParameter(String name, String value)
        {
            // TODO other started conditions
            if (!isStarting())
                throw new IllegalStateException();
            
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            return super.setInitParameter(name,value);
        }

        /* ------------------------------------------------------------ */
        @Override
        public <T extends Filter> T createFilter(Class<T> c) throws ServletException
        {
            try
            {
                T f = c.newInstance();
                for (int i=_decorators.size()-1; i>=0; i--)
                {
                    Decorator decorator = _decorators.get(i);
                    f=decorator.decorateFilterInstance(f);
                }
                return f;
            }
            catch (InstantiationException e)
            {
                throw new ServletException(e);
            }
            catch (IllegalAccessException e)
            {
                throw new ServletException(e);
            }
        }

        /* ------------------------------------------------------------ */
        @Override
        public <T extends Servlet> T createServlet(Class<T> c) throws ServletException
        {
            try
            {
                T s = c.newInstance();
                for (int i=_decorators.size()-1; i>=0; i--)
                {
                    Decorator decorator = _decorators.get(i);
                    s=decorator.decorateServletInstance(s);
                }
                return s;
            }
            catch (InstantiationException e)
            {
                throw new ServletException(e);
            }
            catch (IllegalAccessException e)
            {
                throw new ServletException(e);
            }
        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            if (_sessionHandler!=null)
                return _sessionHandler.getSessionManager().getDefaultSessionTrackingModes();
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            if (_sessionHandler!=null)
                return _sessionHandler.getSessionManager().getEffectiveSessionTrackingModes();
            return null;
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            final FilterHolder holder=ServletContextHandler.this.getServletHandler().getFilter(filterName);
            return (holder==null)?null:holder.getRegistration();
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            HashMap<String, FilterRegistration> registrations = new HashMap<String, FilterRegistration>();
            ServletHandler handler=ServletContextHandler.this.getServletHandler();
            FilterHolder[] holders=handler.getFilters();
            if (holders!=null)
            {
                for (FilterHolder holder : holders)
                    registrations.put(holder.getName(),holder.getRegistration());
            }
            return registrations;
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            final ServletHolder holder=ServletContextHandler.this.getServletHandler().getServlet(servletName);
            return (holder==null)?null:holder.getRegistration();
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            HashMap<String, ServletRegistration> registrations = new HashMap<String, ServletRegistration>();
            ServletHandler handler=ServletContextHandler.this.getServletHandler();
            ServletHolder[] holders=handler.getServlets();
            if (holders!=null)
            {
                for (ServletHolder holder : holders)
                    registrations.put(holder.getName(),holder.getRegistration());
            }
            return registrations;
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig()
        {
            // TODO other started conditions
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            if (_sessionHandler!=null)
                return _sessionHandler.getSessionManager().getSessionCookieConfig();
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {
            // TODO other started conditions
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            
            if (_sessionHandler!=null)
                _sessionHandler.getSessionManager().setSessionTrackingModes(sessionTrackingModes);
        }

        @Override
        public void addListener(String className)
        {
            // TODO other started conditions
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
            super.addListener(className);
        }

        @Override
        public <T extends EventListener> void addListener(T t)
        {
            // TODO other started conditions
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
            super.addListener(t);
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass)
        {
            // TODO other started conditions
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
            super.addListener(listenerClass);
        }

        @Override
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
        {
            try
            {
                T l = super.createListener(clazz);

                for (int i=_decorators.size()-1; i>=0; i--)
                {
                    Decorator decorator = _decorators.get(i);
                    l=decorator.decorateListenerInstance(l);
                }
                return l;
            }
            catch(ServletException e)
            {
                throw e;
            }
            catch(Exception e)
            {
                throw new ServletException(e);
            }
        }


        @Override
        public JspConfigDescriptor getJspConfigDescriptor()
        {
            return _jspConfig;
        }
        
        @Override
        public void declareRoles(String... roleNames)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
            addRoles(roleNames);


        }

    }



    /* ------------------------------------------------------------ */
    /** Interface to decorate loaded classes.
     */
    public interface Decorator
    {
        <T extends Filter> T decorateFilterInstance(T filter) throws ServletException;
        <T extends Servlet> T decorateServletInstance(T servlet) throws ServletException;
        <T extends EventListener> T decorateListenerInstance(T listener) throws ServletException;

        void decorateFilterHolder(FilterHolder filter) throws ServletException;
        void decorateServletHolder(ServletHolder servlet) throws ServletException;
        
        void destroyServletInstance(Servlet s);
        void destroyFilterInstance(Filter f);
        void destroyListenerInstance(EventListener f);
    }
}
