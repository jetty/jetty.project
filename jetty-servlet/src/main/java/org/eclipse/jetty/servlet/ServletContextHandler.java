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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletSecurityElement;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.descriptor.JspPropertyGroupDescriptor;
import javax.servlet.descriptor.TaglibDescriptor;

import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
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
import org.eclipse.jetty.servlet.BaseHolder.Source;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;


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
@ManagedObject("Servlet Context Handler")
public class ServletContextHandler extends ContextHandler
{
    public final static int SESSIONS=1;
    public final static int SECURITY=2;
    public final static int NO_SESSIONS=0;
    public final static int NO_SECURITY=0;
    
    public interface ServletContainerInitializerCaller extends LifeCycle {};

    protected final List<Decorator> _decorators= new ArrayList<>();
    protected Class<? extends SecurityHandler> _defaultSecurityHandlerClass=org.eclipse.jetty.security.ConstraintSecurityHandler.class;
    protected SessionHandler _sessionHandler;
    protected SecurityHandler _securityHandler;
    protected ServletHandler _servletHandler;
    protected int _options;
    protected JspConfigDescriptor _jspConfig;

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
        this(parent,contextPath,null,null,null,null,options);
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
        this(parent,contextPath,sessionHandler,securityHandler,servletHandler,errorHandler,0);
    }
    
    public ServletContextHandler(HandlerContainer parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler,int options)
    {
        super((ContextHandler.Context)null);
        _options=options;
        _scontext = new Context();
        _sessionHandler = sessionHandler;
        _securityHandler = securityHandler;
        _servletHandler = servletHandler;

        if (contextPath!=null)
            setContextPath(contextPath);
        
        if (parent instanceof HandlerWrapper)
            ((HandlerWrapper)parent).setHandler(this);
        else if (parent instanceof HandlerCollection)
            ((HandlerCollection)parent).addHandler(this);
        
        
        // Link the handlers
        relinkHandlers();
        
        if (errorHandler!=null)
            setErrorHandler(errorHandler);

    }
    
    /* ------------------------------------------------------------ */
    private void relinkHandlers()
    {
        HandlerWrapper handler=this;
        
        // Skip any injected handlers
        while (handler.getHandler() instanceof HandlerWrapper)
        {
            HandlerWrapper wrapper = (HandlerWrapper)handler.getHandler();
            if (wrapper instanceof SessionHandler ||
                wrapper instanceof SecurityHandler ||
                wrapper instanceof ServletHandler)
                break;
            handler=wrapper;
        }
        
        if (getSessionHandler()!=null)
        {
            if (handler==this)
                super.setHandler(_sessionHandler);
            else
                handler.setHandler(_sessionHandler);
            handler=_sessionHandler;
        }

        if (getSecurityHandler()!=null)
        {
            if (handler==this)
                super.setHandler(_securityHandler);
            else
                handler.setHandler(_securityHandler);
            handler=_securityHandler;
        }

        if (getServletHandler()!=null)
        {
            if (handler==this)
                super.setHandler(_servletHandler);
            else
                handler.setHandler(_servletHandler);
            handler=_servletHandler;
        } 
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
    @Override
    protected void startContext() throws Exception
    {
        ServletContainerInitializerCaller sciBean = getBean(ServletContainerInitializerCaller.class);
        if (sciBean!=null)
            sciBean.start();

        if (_servletHandler != null)
        {
            //Call decorators on all holders, and also on any EventListeners before
            //decorators are called on any other classes (like servlets and filters)
            for (int i=_decorators.size()-1;i>=0; i--)
            {
                Decorator decorator = _decorators.get(i);
                //Do any decorations on the ListenerHolders AND the listener instances first up
                if (_servletHandler.getListeners()!=null)
                {
                    for (ListenerHolder holder:_servletHandler.getListeners())
                    {             
                        decorator.decorate(holder.getListener());
                    }
                }
    	    }
    	}
    	
        super.startContext();

        // OK to Initialize servlet handler now that all relevant object trees have been started
        if (_servletHandler != null)
            _servletHandler.initialize();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the securityHandler.
     */
    @ManagedAttribute(value="context security handler", readonly=true)
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
    @ManagedAttribute(value="context servlet handler", readonly=true)
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
    @ManagedAttribute(value="context session handler", readonly=true)
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
     * security constraints and thus will be unaffected by this call.
     */
    public Set<String> setServletSecurity(ServletRegistration.Dynamic registration, ServletSecurityElement servletSecurityElement)
    {
        //Default implementation is to just accept them all. If using a webapp, then this behaviour is overridden in WebAppContext.setServletSecurity       
        Collection<String> pathSpecs = registration.getMappings();
        if (pathSpecs != null)
        {
            for (String pathSpec:pathSpecs)
            {
                List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath(registration.getName(), pathSpec, servletSecurityElement);
                for (ConstraintMapping m:mappings)
                    ((ConstraintAware)getSecurityHandler()).addConstraintMapping(m);
            }
        }
        return Collections.emptySet();
    }

    @Override
    public void callContextInitialized(ServletContextListener l, ServletContextEvent e)
    {
        try
        {
            //toggle state of the dynamic API so that the listener cannot use it
            if(isProgrammaticListener(l))
                this.getServletContext().setEnabled(false);

            super.callContextInitialized(l, e);
        }
        finally
        {
            //untoggle the state of the dynamic API
            this.getServletContext().setEnabled(true);
        }
    }


    @Override
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

        if (_sessionHandler!=null)
            _sessionHandler.setHandler(null);

        _sessionHandler = sessionHandler;
        relinkHandlers();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param securityHandler The {@link SecurityHandler} to set on this context.
     */
    public void setSecurityHandler(SecurityHandler securityHandler)
    {
        if (isStarted())
            throw new IllegalStateException("STARTED");

        if (_securityHandler!=null)
            _securityHandler.setHandler(null);
        _securityHandler = securityHandler;
        relinkHandlers();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servletHandler The servletHandler to set.
     */
    public void setServletHandler(ServletHandler servletHandler)
    {
        if (isStarted())
            throw new IllegalStateException("STARTED");

        Handler next=null;
        if (_servletHandler!=null)
        {
            next=_servletHandler.getHandler();
            _servletHandler.setHandler(null);
        }
        _servletHandler = servletHandler;
        relinkHandlers();
        _servletHandler.setHandler(next);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void setHandler(Handler handler)
    {
        if (handler instanceof ServletHandler)
            setServletHandler((ServletHandler) handler);
        else if (handler instanceof SessionHandler)
            setSessionHandler((SessionHandler) handler);
        else if (handler instanceof SecurityHandler)
            setSecurityHandler((SecurityHandler)handler);
        else if (handler == null || handler instanceof HandlerWrapper)
        {
            super.setHandler(handler);
            relinkHandlers();
        }
        else
            throw new IllegalArgumentException();
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Insert a HandlerWrapper before the first Session,Security or ServletHandler
     * but after any other HandlerWrappers.
     */
    public void insertHandler(HandlerWrapper handler)
    {
        HandlerWrapper h=this;
        
        // Skip any injected handlers
        while (h.getHandler() instanceof HandlerWrapper)
        {
            HandlerWrapper wrapper = (HandlerWrapper)h.getHandler();
            if (wrapper instanceof SessionHandler ||
                wrapper instanceof SecurityHandler ||
                wrapper instanceof ServletHandler)
                break;
            h=wrapper;
        }
        
        h.setHandler(handler);
        relinkHandlers();
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
            decorator.destroy(servlet);
    }

    /* ------------------------------------------------------------ */
    void destroyFilter(Filter filter)
    {
        for (Decorator decorator : _decorators)
            decorator.destroy(filter);
    }

    /* ------------------------------------------------------------ */
    public static class JspPropertyGroup implements JspPropertyGroupDescriptor
    {
        private List<String> _urlPatterns = new ArrayList<String>();
        private String _elIgnored;
        private String _pageEncoding;
        private String _scriptingInvalid;
        private String _isXml;
        private List<String> _includePreludes = new ArrayList<String>();
        private List<String> _includeCodas = new ArrayList<String>();
        private String _deferredSyntaxAllowedAsLiteral;
        private String _trimDirectiveWhitespaces;
        private String _defaultContentType;
        private String _buffer;
        private String _errorOnUndeclaredNamespace;



        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getUrlPatterns()
         */
        public Collection<String> getUrlPatterns()
        {
            return new ArrayList<String>(_urlPatterns); // spec says must be a copy
        }

        public void addUrlPattern (String s)
        {
            if (!_urlPatterns.contains(s))
                _urlPatterns.add(s);
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getElIgnored()
         */
        public String getElIgnored()
        {
            return _elIgnored;
        }

        public void setElIgnored (String s)
        {
            _elIgnored = s;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getPageEncoding()
         */
        public String getPageEncoding()
        {
            return _pageEncoding;
        }

        public void setPageEncoding(String pageEncoding)
        {
            _pageEncoding = pageEncoding;
        }

        public void setScriptingInvalid(String scriptingInvalid)
        {
            _scriptingInvalid = scriptingInvalid;
        }

        public void setIsXml(String isXml)
        {
            _isXml = isXml;
        }

        public void setDeferredSyntaxAllowedAsLiteral(String deferredSyntaxAllowedAsLiteral)
        {
            _deferredSyntaxAllowedAsLiteral = deferredSyntaxAllowedAsLiteral;
        }

        public void setTrimDirectiveWhitespaces(String trimDirectiveWhitespaces)
        {
            _trimDirectiveWhitespaces = trimDirectiveWhitespaces;
        }

        public void setDefaultContentType(String defaultContentType)
        {
            _defaultContentType = defaultContentType;
        }

        public void setBuffer(String buffer)
        {
            _buffer = buffer;
        }

        public void setErrorOnUndeclaredNamespace(String errorOnUndeclaredNamespace)
        {
            _errorOnUndeclaredNamespace = errorOnUndeclaredNamespace;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getScriptingInvalid()
         */
        public String getScriptingInvalid()
        {
            return _scriptingInvalid;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getIsXml()
         */
        public String getIsXml()
        {
            return _isXml;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getIncludePreludes()
         */
        public Collection<String> getIncludePreludes()
        {
            return new ArrayList<String>(_includePreludes); //must be a copy
        }

        public void addIncludePrelude(String prelude)
        {
            if (!_includePreludes.contains(prelude))
                _includePreludes.add(prelude);
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getIncludeCodas()
         */
        public Collection<String> getIncludeCodas()
        {
            return new ArrayList<String>(_includeCodas); //must be a copy
        }

        public void addIncludeCoda (String coda)
        {
            if (!_includeCodas.contains(coda))
                _includeCodas.add(coda);
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getDeferredSyntaxAllowedAsLiteral()
         */
        public String getDeferredSyntaxAllowedAsLiteral()
        {
            return _deferredSyntaxAllowedAsLiteral;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getTrimDirectiveWhitespaces()
         */
        public String getTrimDirectiveWhitespaces()
        {
            return _trimDirectiveWhitespaces;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getDefaultContentType()
         */
        public String getDefaultContentType()
        {
            return _defaultContentType;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getBuffer()
         */
        public String getBuffer()
        {
            return _buffer;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getErrorOnUndeclaredNamespace()
         */
        public String getErrorOnUndeclaredNamespace()
        {
            return _errorOnUndeclaredNamespace;
        }

        public String toString ()
        {
            StringBuffer sb = new StringBuffer();
            sb.append("JspPropertyGroupDescriptor:");
            sb.append(" el-ignored="+_elIgnored);
            sb.append(" is-xml="+_isXml);
            sb.append(" page-encoding="+_pageEncoding);
            sb.append(" scripting-invalid="+_scriptingInvalid);
            sb.append(" deferred-syntax-allowed-as-literal="+_deferredSyntaxAllowedAsLiteral);
            sb.append(" trim-directive-whitespaces"+_trimDirectiveWhitespaces);
            sb.append(" default-content-type="+_defaultContentType);
            sb.append(" buffer="+_buffer);
            sb.append(" error-on-undeclared-namespace="+_errorOnUndeclaredNamespace);
            for (String prelude:_includePreludes)
                sb.append(" include-prelude="+prelude);
            for (String coda:_includeCodas)
                sb.append(" include-coda="+coda);
            return sb.toString();
        }
    }

    /* ------------------------------------------------------------ */
    public static class TagLib implements TaglibDescriptor
    {
        private String _uri;
        private String _location;

        /**
         * @see javax.servlet.descriptor.TaglibDescriptor#getTaglibURI()
         */
        public String getTaglibURI()
        {
           return _uri;
        }

        public void setTaglibURI(String uri)
        {
            _uri = uri;
        }

        /**
         * @see javax.servlet.descriptor.TaglibDescriptor#getTaglibLocation()
         */
        public String getTaglibLocation()
        {
            return _location;
        }

        public void setTaglibLocation(String location)
        {
            _location = location;
        }

        public String toString()
        {
            return ("TagLibDescriptor: taglib-uri="+_uri+" location="+_location);
        }
    }


    /* ------------------------------------------------------------ */
    public static class JspConfig implements JspConfigDescriptor
    {
        private List<TaglibDescriptor> _taglibs = new ArrayList<TaglibDescriptor>();
        private List<JspPropertyGroupDescriptor> _jspPropertyGroups = new ArrayList<JspPropertyGroupDescriptor>();

        public JspConfig() {}

        /**
         * @see javax.servlet.descriptor.JspConfigDescriptor#getTaglibs()
         */
        public Collection<TaglibDescriptor> getTaglibs()
        {
            return new ArrayList<TaglibDescriptor>(_taglibs);
        }

        public void addTaglibDescriptor (TaglibDescriptor d)
        {
            _taglibs.add(d);
        }

        /**
         * @see javax.servlet.descriptor.JspConfigDescriptor#getJspPropertyGroups()
         */
        public Collection<JspPropertyGroupDescriptor> getJspPropertyGroups()
        {
           return new ArrayList<JspPropertyGroupDescriptor>(_jspPropertyGroups);
        }

        public void addJspPropertyGroup(JspPropertyGroupDescriptor g)
        {
            _jspPropertyGroups.add(g);
        }

        public String toString()
        {
            StringBuffer sb = new StringBuffer();
            sb.append("JspConfigDescriptor: \n");
            for (TaglibDescriptor taglib:_taglibs)
                sb.append(taglib+"\n");
            for (JspPropertyGroupDescriptor jpg:_jspPropertyGroups)
                sb.append(jpg+"\n");
            return sb.toString();
        }
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
            if (_servletHandler==null)
                return null;
            ServletHolder holder = _servletHandler.getServlet(name);
            if (holder==null || !holder.isEnabled())
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
            
            if (filterName == null || "".equals(filterName.trim()))
                throw new IllegalStateException("Missing filter name");

            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            FilterHolder holder = handler.getFilter(filterName);
            if (holder == null)
            {
                //new filter
                holder = handler.newFilterHolder(Source.JAVAX_API);
                holder.setName(filterName);
                holder.setHeldClass(filterClass);
                handler.addFilter(holder);
                return holder.getRegistration();
            }
            if (holder.getClassName()==null && holder.getHeldClass()==null)
            {
                //preliminary filter registration completion
                holder.setHeldClass(filterClass);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
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
            
            if (filterName == null || "".equals(filterName.trim()))
                throw new IllegalStateException("Missing filter name");

            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            FilterHolder holder = handler.getFilter(filterName);
            if (holder == null)
            {
                //new filter
                holder = handler.newFilterHolder(Source.JAVAX_API);
                holder.setName(filterName);
                holder.setClassName(className);
                handler.addFilter(holder);
                return holder.getRegistration();
            }
            if (holder.getClassName()==null && holder.getHeldClass()==null)
            {
                //preliminary filter registration completion
                holder.setClassName(className);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
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

            if (filterName == null || "".equals(filterName.trim()))
                throw new IllegalStateException("Missing filter name");
            
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            FilterHolder holder = handler.getFilter(filterName);
            if (holder == null)
            {
                //new filter
                holder = handler.newFilterHolder(Source.JAVAX_API);
                holder.setName(filterName);
                holder.setFilter(filter);
                handler.addFilter(holder);
                return holder.getRegistration();
            }

            if (holder.getClassName()==null && holder.getHeldClass()==null)
            {
                //preliminary filter registration completion
                holder.setFilter(filter);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
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

            if (servletName == null || "".equals(servletName.trim()))
                throw new IllegalStateException("Missing servlet name");
            
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder holder = handler.getServlet(servletName);
            if (holder == null)
            {
                //new servlet
                holder = handler.newServletHolder(Source.JAVAX_API);
                holder.setName(servletName);
                holder.setHeldClass(servletClass);
                handler.addServlet(holder);
                return dynamicHolderAdded(holder);
            }

            //complete a partial registration
            if (holder.getClassName()==null && holder.getHeldClass()==null)
            {
                holder.setHeldClass(servletClass);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
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

            if (servletName == null || "".equals(servletName.trim()))
                throw new IllegalStateException("Missing servlet name");
            
            if (!_enabled)
                throw new UnsupportedOperationException();


            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder holder = handler.getServlet(servletName);
            if (holder == null)
            {
                //new servlet
                holder = handler.newServletHolder(Source.JAVAX_API);
                holder.setName(servletName);
                holder.setClassName(className);
                handler.addServlet(holder);
                return dynamicHolderAdded(holder);
            }

            //complete a partial registration
            if (holder.getClassName()==null && holder.getHeldClass()==null)
            {
                holder.setClassName(className);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
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
            
            if (servletName == null || "".equals(servletName.trim()))
                throw new IllegalStateException("Missing servlet name");
            
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder holder = handler.getServlet(servletName);
            if (holder == null)
            {
                holder = handler.newServletHolder(Source.JAVAX_API);
                holder.setName(servletName);
                holder.setServlet(servlet);
                handler.addServlet(holder);
                return dynamicHolderAdded(holder);
            }

            //complete a partial registration
            if (holder.getClassName()==null && holder.getHeldClass()==null)
            {
                holder.setServlet(servlet);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }

        /* ------------------------------------------------------------ */
        @Override
        public boolean setInitParameter(String name, String value)
        {
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
                T f = createInstance(c);
                for (int i=_decorators.size()-1; i>=0; i--)
                {
                    Decorator decorator = _decorators.get(i);
                    f=decorator.decorate(f);
                }
                return f;
            }
            catch (Exception e)
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
                T s = createInstance(c);
                for (int i=_decorators.size()-1; i>=0; i--)
                {
                    Decorator decorator = _decorators.get(i);
                    s=decorator.decorate(s);
                }
                return s;
            }
            catch (Exception e)
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
            if (!_enabled)
                throw new UnsupportedOperationException();

            if (_sessionHandler!=null)
                return _sessionHandler.getSessionManager().getSessionCookieConfig();
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {
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
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
            super.addListener(className);
        }

        @Override
        public <T extends EventListener> void addListener(T t)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
            super.addListener(t);
            ListenerHolder holder = getServletHandler().newListenerHolder(Source.JAVAX_API);
            holder.setListener(t);
            getServletHandler().addListener(holder);
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass)
        {
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
                T l = createInstance(clazz);
                for (int i=_decorators.size()-1; i>=0; i--)
                {
                    Decorator decorator = _decorators.get(i);
                    l=decorator.decorate(l);
                }
                return l;
            }            
            catch (Exception e)
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
        public void setJspConfigDescriptor(JspConfigDescriptor d)
        {
            _jspConfig = d;
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
        <T> T decorate (T o);
        void destroy (Object o);
    }
}
