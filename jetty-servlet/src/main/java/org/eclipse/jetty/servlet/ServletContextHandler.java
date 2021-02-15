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
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
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
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.DeprecationWarning;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Servlet Context.
 * <p>
 * This extension to the ContextHandler allows for
 * simple construction of a context with ServletHandler and optionally
 * session and security handlers, et.
 * <pre>
 *   new ServletContext("/context",Context.SESSIONS|Context.NO_SECURITY);
 * </pre>
 * <p>
 * This class should have been called ServletContext, but this would have
 * cause confusion with {@link ServletContext}.
 */
@ManagedObject("Servlet Context Handler")
public class ServletContextHandler extends ContextHandler
{
    private static final Logger LOG = Log.getLogger(ServletContextHandler.class);

    public static final int SESSIONS = 1;
    public static final int SECURITY = 2;
    public static final int GZIP = 4;
    public static final int NO_SESSIONS = 0;
    public static final int NO_SECURITY = 0;

    public interface ServletContainerInitializerCaller extends LifeCycle {}

    protected final DecoratedObjectFactory _objFactory;
    protected Class<? extends SecurityHandler> _defaultSecurityHandlerClass = org.eclipse.jetty.security.ConstraintSecurityHandler.class;
    protected SessionHandler _sessionHandler;
    protected SecurityHandler _securityHandler;
    protected ServletHandler _servletHandler;
    protected GzipHandler _gzipHandler;
    protected int _options;
    protected JspConfigDescriptor _jspConfig;

    private boolean _startListeners;

    public ServletContextHandler()
    {
        this(null, null, null, null, null);
    }

    public ServletContextHandler(int options)
    {
        this(null, null, options);
    }

    public ServletContextHandler(HandlerContainer parent, String contextPath)
    {
        this(parent, contextPath, null, null, null, null);
    }

    public ServletContextHandler(HandlerContainer parent, String contextPath, int options)
    {
        this(parent, contextPath, null, null, null, null, options);
    }

    public ServletContextHandler(HandlerContainer parent, String contextPath, boolean sessions, boolean security)
    {
        this(parent, contextPath, (sessions ? SESSIONS : 0) | (security ? SECURITY : 0));
    }

    public ServletContextHandler(HandlerContainer parent, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        this(parent, null, sessionHandler, securityHandler, servletHandler, errorHandler);
    }

    public ServletContextHandler(HandlerContainer parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        this(parent, contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, 0);
    }

    public ServletContextHandler(HandlerContainer parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        super(parent, contextPath);
        _options = options;
        _scontext = new Context();
        _sessionHandler = sessionHandler;
        _securityHandler = securityHandler;
        _servletHandler = servletHandler;

        _objFactory = new DecoratedObjectFactory();
        _objFactory.addDecorator(new DeprecationWarning());

        // Link the handlers
        relinkHandlers();

        if (errorHandler != null)
            setErrorHandler(errorHandler);
    }

    /**
     * Add EventListener
     * Adds an EventListener to the list. @see org.eclipse.jetty.server.handler.ContextHandler#addEventListener().
     * Also adds any listeners that are session related to the SessionHandler.
     *
     * @param listener the listener to add
     */
    @Override
    public void addEventListener(EventListener listener)
    {
        super.addEventListener(listener);
        if ((listener instanceof HttpSessionActivationListener) ||
            (listener instanceof HttpSessionAttributeListener) ||
            (listener instanceof HttpSessionBindingListener) ||
            (listener instanceof HttpSessionListener) ||
            (listener instanceof HttpSessionIdListener))
        {
            if (_sessionHandler != null)
                _sessionHandler.addEventListener(listener);
        }
    }

    @Override
    public void setHandler(Handler handler)
    {
        if (handler instanceof SessionHandler)
            setSessionHandler((SessionHandler)handler);
        else if (handler instanceof SecurityHandler)
            setSecurityHandler((SecurityHandler)handler);
        else if (handler instanceof ServletHandler)
            setServletHandler((ServletHandler)handler);
        else if (handler instanceof GzipHandler)
            setGzipHandler((GzipHandler)handler);
        else
        {
            if (handler != null)
                LOG.warn("ServletContextHandler.setHandler should not be called directly. Use insertHandler or setSessionHandler etc.");
            super.setHandler(handler);
        }
    }

    private void doSetHandler(HandlerWrapper wrapper, Handler handler)
    {
        if (wrapper == this)
            super.setHandler(handler);
        else
            wrapper.setHandler(handler);
    }

    private void relinkHandlers()
    {
        HandlerWrapper handler = this;

        // link session handler
        if (getSessionHandler() != null)
        {

            while (!(handler.getHandler() instanceof SessionHandler) &&
                !(handler.getHandler() instanceof SecurityHandler) &&
                !(handler.getHandler() instanceof GzipHandler) &&
                !(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof HandlerWrapper)
            {
                handler = (HandlerWrapper)handler.getHandler();
            }

            if (handler.getHandler() != _sessionHandler)
                doSetHandler(handler, _sessionHandler);
            handler = _sessionHandler;
        }

        // link security handler
        if (getSecurityHandler() != null)
        {
            while (!(handler.getHandler() instanceof SecurityHandler) &&
                !(handler.getHandler() instanceof GzipHandler) &&
                !(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof HandlerWrapper)
            {
                handler = (HandlerWrapper)handler.getHandler();
            }

            if (handler.getHandler() != _securityHandler)
                doSetHandler(handler, _securityHandler);
            handler = _securityHandler;
        }

        // link gzip handler
        if (getGzipHandler() != null)
        {
            while (!(handler.getHandler() instanceof GzipHandler) &&
                !(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof HandlerWrapper)
            {
                handler = (HandlerWrapper)handler.getHandler();
            }

            if (handler.getHandler() != _gzipHandler)
                doSetHandler(handler, _gzipHandler);
            handler = _gzipHandler;
        }

        // link servlet handler
        if (getServletHandler() != null)
        {
            while (!(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof HandlerWrapper)
            {
                handler = (HandlerWrapper)handler.getHandler();
            }

            if (handler.getHandler() != _servletHandler)
                doSetHandler(handler, _servletHandler);
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        getServletContext().setAttribute(DecoratedObjectFactory.ATTR, _objFactory);
        super.doStart();
    }

    /**
     * @see org.eclipse.jetty.server.handler.ContextHandler#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _objFactory.clear();
        getServletContext().removeAttribute(DecoratedObjectFactory.ATTR);
    }

    /**
     * Get the defaultSecurityHandlerClass.
     *
     * @return the defaultSecurityHandlerClass
     */
    public Class<? extends SecurityHandler> getDefaultSecurityHandlerClass()
    {
        return _defaultSecurityHandlerClass;
    }

    /**
     * Set the defaultSecurityHandlerClass.
     *
     * @param defaultSecurityHandlerClass the defaultSecurityHandlerClass to set
     */
    public void setDefaultSecurityHandlerClass(Class<? extends SecurityHandler> defaultSecurityHandlerClass)
    {
        _defaultSecurityHandlerClass = defaultSecurityHandlerClass;
    }

    protected SessionHandler newSessionHandler()
    {
        return new SessionHandler();
    }

    protected SecurityHandler newSecurityHandler()
    {
        try
        {
            return getDefaultSecurityHandlerClass().getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    protected ServletHandler newServletHandler()
    {
        return new ServletHandler();
    }

    /**
     * Finish constructing handlers and link them together.
     *
     * @see org.eclipse.jetty.server.handler.ContextHandler#startContext()
     */
    @Override
    protected void startContext() throws Exception
    {
        for (ServletContainerInitializerCaller  sci : getBeans(ServletContainerInitializerCaller.class))
        {
            if (sci.isStopped())
            {
                sci.start();
                if (isAuto(sci))
                    manage(sci);
            }
        }

        if (_servletHandler != null)
        {
            //Ensure listener instances are created, added to ContextHandler
            if (_servletHandler.getListeners() != null)
            {
                for (ListenerHolder holder : _servletHandler.getListeners())
                {
                    holder.start();
                }
            }
        }

        _startListeners = true;
        super.startContext();

        // OK to Initialize servlet handler now that all relevant object trees have been started
        if (_servletHandler != null)
            _servletHandler.initialize();
    }

    @Override
    protected void stopContext() throws Exception
    {
        _startListeners = false;
        super.stopContext();
    }

    /**
     * @return Returns the securityHandler.
     */
    @ManagedAttribute(value = "context security handler", readonly = true)
    public SecurityHandler getSecurityHandler()
    {
        if (_securityHandler == null && (_options & SECURITY) != 0 && !isStarted())
            _securityHandler = newSecurityHandler();

        return _securityHandler;
    }

    /**
     * @return Returns the servletHandler.
     */
    @ManagedAttribute(value = "context servlet handler", readonly = true)
    public ServletHandler getServletHandler()
    {
        if (_servletHandler == null && !isStarted())
            _servletHandler = newServletHandler();
        return _servletHandler;
    }

    /**
     * @return Returns the sessionHandler.
     */
    @ManagedAttribute(value = "context session handler", readonly = true)
    public SessionHandler getSessionHandler()
    {
        if (_sessionHandler == null && (_options & SESSIONS) != 0 && !isStarted())
            _sessionHandler = newSessionHandler();
        return _sessionHandler;
    }

    /**
     * @return Returns the gzipHandler.
     */
    @ManagedAttribute(value = "context gzip handler", readonly = true)
    public GzipHandler getGzipHandler()
    {
        if (_gzipHandler == null && (_options & GZIP) != 0 && !isStarted())
            _gzipHandler = new GzipHandler();
        return _gzipHandler;
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param className the servlet class name
     * @param pathSpec the path spec to map servlet to
     * @return the ServletHolder for the added servlet
     */
    public ServletHolder addServlet(String className, String pathSpec)
    {
        return getServletHandler().addServletWithMapping(className, pathSpec);
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet the servlet class
     * @param pathSpec the path spec to map servlet to
     * @return the ServletHolder for the added servlet
     */
    public ServletHolder addServlet(Class<? extends Servlet> servlet, String pathSpec)
    {
        return getServletHandler().addServletWithMapping(servlet, pathSpec);
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet the servlet holder
     * @param pathSpec the path spec
     */
    public void addServlet(ServletHolder servlet, String pathSpec)
    {
        getServletHandler().addServletWithMapping(servlet, pathSpec);
    }

    /**
     * Convenience method to add a filter
     *
     * @param holder the filter holder
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     */
    public void addFilter(FilterHolder holder, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        getServletHandler().addFilterWithMapping(holder, pathSpec, dispatches);
    }

    /**
     * Convenience method to add a filter
     *
     * @param filterClass the filter class
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     * @return the FilterHolder that was created
     */
    public FilterHolder addFilter(Class<? extends Filter> filterClass, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return getServletHandler().addFilterWithMapping(filterClass, pathSpec, dispatches);
    }

    /**
     * Convenience method to add a filter
     *
     * @param filterClass the filter class name
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     * @return the FilterHolder that was created
     */
    public FilterHolder addFilter(String filterClass, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return getServletHandler().addFilterWithMapping(filterClass, pathSpec, dispatches);
    }

    /**
     * notification that a ServletRegistration has been created so we can track the annotations
     *
     * @param holder new holder created through the api.
     * @return the ServletRegistration.Dynamic
     */
    protected ServletRegistration.Dynamic dynamicHolderAdded(ServletHolder holder)
    {
        return holder.getRegistration();
    }

    /**
     * delegate for ServletContext.declareRole method
     *
     * @param roleNames role names to add
     */
    protected void addRoles(String... roleNames)
    {
        //Get a reference to the SecurityHandler, which must be ConstraintAware
        if (_securityHandler != null && _securityHandler instanceof ConstraintAware)
        {
            HashSet<String> union = new HashSet<>();
            Set<String> existing = ((ConstraintAware)_securityHandler).getRoles();
            if (existing != null)
                union.addAll(existing);
            union.addAll(Arrays.asList(roleNames));
            ((ConstraintSecurityHandler)_securityHandler).setRoles(union);
        }
    }

    /**
     * Delegate for ServletRegistration.Dynamic.setServletSecurity method
     *
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
            for (String pathSpec : pathSpecs)
            {
                List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath(registration.getName(), pathSpec, servletSecurityElement);
                for (ConstraintMapping m : mappings)
                {
                    ((ConstraintAware)getSecurityHandler()).addConstraintMapping(m);
                }
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
            if (isProgrammaticListener(l))
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

    private void replaceHandler(HandlerWrapper handler, HandlerWrapper replacement)
    {
        if (isStarted())
            throw new IllegalStateException("STARTED");

        Handler next = null;
        if (handler != null)
        {
            next = handler.getHandler();
            handler.setHandler(null);

            HandlerWrapper wrapper = this;
            while (wrapper != null)
            {
                if (wrapper.getHandler() == handler)
                {
                    doSetHandler(wrapper, replacement);
                    break;
                }

                wrapper = (wrapper.getHandler() instanceof HandlerWrapper) ? (HandlerWrapper)wrapper.getHandler() : null;
            }
        }

        if (next != null && replacement.getHandler() == null)
            replacement.setHandler(next);
    }

    /**
     * @param sessionHandler The sessionHandler to set.
     */
    public void setSessionHandler(SessionHandler sessionHandler)
    {
        replaceHandler(_sessionHandler, sessionHandler);
        _sessionHandler = sessionHandler;
        relinkHandlers();
    }

    /**
     * @param securityHandler The {@link SecurityHandler} to set on this context.
     */
    public void setSecurityHandler(SecurityHandler securityHandler)
    {
        replaceHandler(_securityHandler, securityHandler);
        _securityHandler = securityHandler;
        relinkHandlers();
    }

    /**
     * @param gzipHandler The {@link GzipHandler} to set on this context.
     */
    public void setGzipHandler(GzipHandler gzipHandler)
    {
        replaceHandler(_gzipHandler, gzipHandler);
        _gzipHandler = gzipHandler;
        relinkHandlers();
    }

    /**
     * @param servletHandler The servletHandler to set.
     */
    public void setServletHandler(ServletHandler servletHandler)
    {
        replaceHandler(_servletHandler, servletHandler);
        _servletHandler = servletHandler;
        relinkHandlers();
    }

    /**
     * Insert a HandlerWrapper before the first Session,Security or ServletHandler
     * but after any other HandlerWrappers.
     */
    @Override
    public void insertHandler(HandlerWrapper handler)
    {
        if (handler instanceof SessionHandler)
            setSessionHandler((SessionHandler)handler);
        else if (handler instanceof SecurityHandler)
            setSecurityHandler((SecurityHandler)handler);
        else if (handler instanceof GzipHandler)
            setGzipHandler((GzipHandler)handler);
        else if (handler instanceof ServletHandler)
            setServletHandler((ServletHandler)handler);
        else
        {
            HandlerWrapper tail = handler;
            while (tail.getHandler() instanceof HandlerWrapper)
            {
                tail = (HandlerWrapper)tail.getHandler();
            }
            if (tail.getHandler() != null)
                throw new IllegalArgumentException("bad tail of inserted wrapper chain");

            // Skip any injected handlers
            HandlerWrapper h = this;
            while (h.getHandler() instanceof HandlerWrapper)
            {
                HandlerWrapper wrapper = (HandlerWrapper)h.getHandler();
                if (wrapper instanceof SessionHandler ||
                    wrapper instanceof SecurityHandler ||
                    wrapper instanceof ServletHandler)
                    break;
                h = wrapper;
            }

            Handler next = h.getHandler();
            doSetHandler(h, handler);
            doSetHandler(tail, next);
        }
        relinkHandlers();
    }

    /**
     * The DecoratedObjectFactory for use by IoC containers (weld / spring / etc)
     *
     * @return The DecoratedObjectFactory
     */
    public DecoratedObjectFactory getObjectFactory()
    {
        return _objFactory;
    }

    /**
     * @return The decorator list used to resource inject new Filters, Servlets and EventListeners
     * @deprecated use the {@link DecoratedObjectFactory} from getAttribute("org.eclipse.jetty.util.DecoratedObjectFactory") or {@link #getObjectFactory()} instead
     */
    @Deprecated
    public List<Decorator> getDecorators()
    {
        List<Decorator> ret = new ArrayList<>();
        for (org.eclipse.jetty.util.Decorator decorator : _objFactory)
        {
            ret.add(new LegacyDecorator(decorator));
        }
        return Collections.unmodifiableList(ret);
    }

    /**
     * @param decorators The list of {@link Decorator}s
     * @deprecated use the {@link DecoratedObjectFactory} from getAttribute("org.eclipse.jetty.util.DecoratedObjectFactory") or {@link #getObjectFactory()} instead
     */
    @Deprecated
    public void setDecorators(List<Decorator> decorators)
    {
        _objFactory.setDecorators(decorators);
    }

    /**
     * @param decorator The decorator to add
     * @deprecated use the {@link DecoratedObjectFactory} from getAttribute("org.eclipse.jetty.util.DecoratedObjectFactory") or {@link #getObjectFactory()} instead
     */
    @Deprecated
    public void addDecorator(Decorator decorator)
    {
        _objFactory.addDecorator(decorator);
    }

    void destroyServlet(Servlet servlet)
    {
        _objFactory.destroy(servlet);
    }

    void destroyFilter(Filter filter)
    {
        _objFactory.destroy(filter);
    }

    void destroyListener(EventListener listener)
    {
        _objFactory.destroy(listener);
    }

    public static ServletContextHandler getServletContextHandler(ServletContext context)
    {
        ContextHandler handler = getContextHandler(context);
        if (handler == null)
            return null;
        if (handler instanceof ServletContextHandler)
            return (ServletContextHandler)handler;
        return null;
    }

    public static class JspPropertyGroup implements JspPropertyGroupDescriptor
    {
        private final List<String> _urlPatterns = new ArrayList<>();
        private String _elIgnored;
        private String _pageEncoding;
        private String _scriptingInvalid;
        private String _isXml;
        private final List<String> _includePreludes = new ArrayList<>();
        private final List<String> _includeCodas = new ArrayList<>();
        private String _deferredSyntaxAllowedAsLiteral;
        private String _trimDirectiveWhitespaces;
        private String _defaultContentType;
        private String _buffer;
        private String _errorOnUndeclaredNamespace;

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getUrlPatterns()
         */
        @Override
        public Collection<String> getUrlPatterns()
        {
            return new ArrayList<>(_urlPatterns); // spec says must be a copy
        }

        public void addUrlPattern(String s)
        {
            if (!_urlPatterns.contains(s))
                _urlPatterns.add(s);
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getElIgnored()
         */
        @Override
        public String getElIgnored()
        {
            return _elIgnored;
        }

        public void setElIgnored(String s)
        {
            _elIgnored = s;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getPageEncoding()
         */
        @Override
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
        @Override
        public String getScriptingInvalid()
        {
            return _scriptingInvalid;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getIsXml()
         */
        @Override
        public String getIsXml()
        {
            return _isXml;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getIncludePreludes()
         */
        @Override
        public Collection<String> getIncludePreludes()
        {
            return new ArrayList<>(_includePreludes); //must be a copy
        }

        public void addIncludePrelude(String prelude)
        {
            if (!_includePreludes.contains(prelude))
                _includePreludes.add(prelude);
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getIncludeCodas()
         */
        @Override
        public Collection<String> getIncludeCodas()
        {
            return new ArrayList<>(_includeCodas); //must be a copy
        }

        public void addIncludeCoda(String coda)
        {
            if (!_includeCodas.contains(coda))
                _includeCodas.add(coda);
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getDeferredSyntaxAllowedAsLiteral()
         */
        @Override
        public String getDeferredSyntaxAllowedAsLiteral()
        {
            return _deferredSyntaxAllowedAsLiteral;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getTrimDirectiveWhitespaces()
         */
        @Override
        public String getTrimDirectiveWhitespaces()
        {
            return _trimDirectiveWhitespaces;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getDefaultContentType()
         */
        @Override
        public String getDefaultContentType()
        {
            return _defaultContentType;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getBuffer()
         */
        @Override
        public String getBuffer()
        {
            return _buffer;
        }

        /**
         * @see javax.servlet.descriptor.JspPropertyGroupDescriptor#getErrorOnUndeclaredNamespace()
         */
        @Override
        public String getErrorOnUndeclaredNamespace()
        {
            return _errorOnUndeclaredNamespace;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("JspPropertyGroupDescriptor:");
            sb.append(" el-ignored=").append(_elIgnored);
            sb.append(" is-xml=").append(_isXml);
            sb.append(" page-encoding=").append(_pageEncoding);
            sb.append(" scripting-invalid=").append(_scriptingInvalid);
            sb.append(" deferred-syntax-allowed-as-literal=").append(_deferredSyntaxAllowedAsLiteral);
            sb.append(" trim-directive-whitespaces").append(_trimDirectiveWhitespaces);
            sb.append(" default-content-type=").append(_defaultContentType);
            sb.append(" buffer=").append(_buffer);
            sb.append(" error-on-undeclared-namespace=").append(_errorOnUndeclaredNamespace);
            for (String prelude : _includePreludes)
            {
                sb.append(" include-prelude=").append(prelude);
            }
            for (String coda : _includeCodas)
            {
                sb.append(" include-coda=").append(coda);
            }
            return sb.toString();
        }
    }

    public static class TagLib implements TaglibDescriptor
    {
        private String _uri;
        private String _location;

        /**
         * @see javax.servlet.descriptor.TaglibDescriptor#getTaglibURI()
         */
        @Override
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
        @Override
        public String getTaglibLocation()
        {
            return _location;
        }

        public void setTaglibLocation(String location)
        {
            _location = location;
        }

        @Override
        public String toString()
        {
            return ("TagLibDescriptor: taglib-uri=" + _uri + " location=" + _location);
        }
    }

    public static class JspConfig implements JspConfigDescriptor
    {
        private final List<TaglibDescriptor> _taglibs = new ArrayList<>();
        private final List<JspPropertyGroupDescriptor> _jspPropertyGroups = new ArrayList<>();

        public JspConfig()
        {
        }

        /**
         * @see javax.servlet.descriptor.JspConfigDescriptor#getTaglibs()
         */
        @Override
        public Collection<TaglibDescriptor> getTaglibs()
        {
            return new ArrayList<>(_taglibs);
        }

        public void addTaglibDescriptor(TaglibDescriptor d)
        {
            _taglibs.add(d);
        }

        /**
         * @see javax.servlet.descriptor.JspConfigDescriptor#getJspPropertyGroups()
         */
        @Override
        public Collection<JspPropertyGroupDescriptor> getJspPropertyGroups()
        {
            return new ArrayList<>(_jspPropertyGroups);
        }

        public void addJspPropertyGroup(JspPropertyGroupDescriptor g)
        {
            _jspPropertyGroups.add(g);
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("JspConfigDescriptor: \n");
            for (TaglibDescriptor taglib : _taglibs)
            {
                sb.append(taglib).append("\n");
            }
            for (JspPropertyGroupDescriptor jpg : _jspPropertyGroups)
            {
                sb.append(jpg).append("\n");
            }
            return sb.toString();
        }
    }

    public class Context extends ContextHandler.Context
    {
        /*
         * @see javax.servlet.ServletContext#getNamedDispatcher(java.lang.String)
         */
        @Override
        public RequestDispatcher getNamedDispatcher(String name)
        {
            ContextHandler context = org.eclipse.jetty.servlet.ServletContextHandler.this;
            if (_servletHandler == null)
                return null;
            ServletHolder holder = _servletHandler.getServlet(name);
            if (holder == null || !holder.isEnabled())
                return null;
            return new Dispatcher(context, name);
        }

        private void checkDynamic(String name)
        {
            if (isStarted())
                throw new IllegalStateException();
            
            if (ServletContextHandler.this.getServletHandler().isInitialized())
                throw new IllegalStateException();

            if (StringUtil.isBlank(name))
                throw new IllegalStateException("Missing name");

            if (!_enabled)
                throw new UnsupportedOperationException();
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
        {
            checkDynamic(filterName);

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
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                //preliminary filter registration completion
                holder.setHeldClass(filterClass);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, String className)
        {
            checkDynamic(filterName);

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
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                //preliminary filter registration completion
                holder.setClassName(className);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Filter filter)
        {
            checkDynamic(filterName);

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

            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                //preliminary filter registration completion
                holder.setFilter(filter);
                return holder.getRegistration();
            }
            else
                return null; //existing filter
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
        {
            checkDynamic(servletName);

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
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                holder.setHeldClass(servletClass);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, String className)
        {
            checkDynamic(servletName);

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
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                holder.setClassName(className);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }

        /**
         * @since servlet-api-3.0
         */
        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
        {
            checkDynamic(servletName);

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
            if (holder.getClassName() == null && holder.getHeldClass() == null)
            {
                holder.setServlet(servlet);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }

        @Override
        public boolean setInitParameter(String name, String value)
        {
            if (!isStarting())
                throw new IllegalStateException();

            if (!_enabled)
                throw new UnsupportedOperationException();

            return super.setInitParameter(name, value);
        }

        @Override
        protected <T> T createInstance(Class<T> clazz) throws ServletException
        {
            return _objFactory.decorate(super.createInstance(clazz));
        }

        public <T extends Filter> void destroyFilter(T f)
        {
            _objFactory.destroy(f);
        }

        public <T extends Servlet> void destroyServlet(T s)
        {
            _objFactory.destroy(s);
        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            if (_sessionHandler != null)
                return _sessionHandler.getDefaultSessionTrackingModes();
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            if (_sessionHandler != null)
                return _sessionHandler.getEffectiveSessionTrackingModes();
            return null;
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            final FilterHolder holder = ServletContextHandler.this.getServletHandler().getFilter(filterName);
            return (holder == null) ? null : holder.getRegistration();
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            HashMap<String, FilterRegistration> registrations = new HashMap<>();
            ServletHandler handler = ServletContextHandler.this.getServletHandler();
            FilterHolder[] holders = handler.getFilters();
            if (holders != null)
            {
                for (FilterHolder holder : holders)
                {
                    registrations.put(holder.getName(), holder.getRegistration());
                }
            }
            return registrations;
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            final ServletHolder holder = ServletContextHandler.this.getServletHandler().getServlet(servletName);
            return (holder == null) ? null : holder.getRegistration();
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            HashMap<String, ServletRegistration> registrations = new HashMap<>();
            ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder[] holders = handler.getServlets();
            if (holders != null)
            {
                for (ServletHolder holder : holders)
                {
                    registrations.put(holder.getName(), holder.getRegistration());
                }
            }
            return registrations;
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            if (_sessionHandler != null)
                return _sessionHandler.getSessionCookieConfig();
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            if (_sessionHandler != null)
                _sessionHandler.setSessionTrackingModes(sessionTrackingModes);
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

            checkListener(t.getClass());

            ListenerHolder holder = getServletHandler().newListenerHolder(Source.JAVAX_API);
            holder.setListener(t);
            addProgrammaticListener(t);
            getServletHandler().addListener(holder);
            if (_startListeners)
            {
                try
                {
                    holder.start();   
                }
                catch (Exception e)
                {
                    throw new IllegalStateException(e);
                }
            }
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

    /**
     * Legacy Interface to decorate loaded classes.
     * <p>
     * Left for backwards compatibility with Weld / CDI
     *
     * @deprecated use new {@link org.eclipse.jetty.util.Decorator}
     */
    @Deprecated
    public interface Decorator extends org.eclipse.jetty.util.Decorator
    {
    }

    /**
     * Implementation of the legacy interface to decorate loaded classes.
     */
    private static class LegacyDecorator implements Decorator
    {
        private final org.eclipse.jetty.util.Decorator decorator;

        public LegacyDecorator(org.eclipse.jetty.util.Decorator decorator)
        {
            this.decorator = decorator;
        }

        @Override
        public <T> T decorate(T o)
        {
            return decorator.decorate(o);
        }

        @Override
        public void destroy(Object o)
        {
            decorator.destroy(o);
        }
    }

    /**
     * A utility class to hold a {@link ServletContainerInitializer} and implement the
     * {@link ServletContainerInitializerCaller} interface so that the SCI is correctly
     * started if an instance of this class is added as a bean to a {@link ServletContextHandler}.
     */
    public static class Initializer extends AbstractLifeCycle implements ServletContainerInitializerCaller
    {
        private final ServletContextHandler _context;
        private final ServletContainerInitializer _sci;
        private final Set<Class<?>> _classes;

        public Initializer(ServletContextHandler context, ServletContainerInitializer sci, Set<Class<?>> classes)
        {
            _context = context;
            _sci = sci;
            _classes = classes;
        }

        public Initializer(ServletContextHandler context, ServletContainerInitializer sci)
        {
            this(context, sci, Collections.emptySet());
        }

        @Override
        protected void doStart() throws Exception
        {
            boolean oldExtended = _context.getServletContext().isExtendedListenerTypes();
            try
            {
                _context.getServletContext().setExtendedListenerTypes(true);
                _sci.onStartup(_classes, _context.getServletContext());
            }
            finally
            {
                _context.getServletContext().setExtendedListenerTypes(oldExtended);
            }
        }
    }
}
