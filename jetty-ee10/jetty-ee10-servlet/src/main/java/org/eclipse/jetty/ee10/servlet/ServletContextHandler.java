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

package org.eclipse.jetty.ee10.servlet;

import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.descriptor.JspPropertyGroupDescriptor;
import jakarta.servlet.descriptor.TaglibDescriptor;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextRequest;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.DeprecationWarning;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class ServletContextHandler extends TempContextHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletContextHandler.class);

    public static final int SESSIONS = 1;
    public static final int SECURITY = 2;
    public static final int NO_SESSIONS = 0;
    public static final int NO_SECURITY = 0;

    public static ServletContextHandler getServletContextHandler(ServletContext servletContext, String purpose)
    {
        if (servletContext instanceof Context)
            return ((Context)servletContext).getServletContextHandler();
        throw new IllegalStateException("No Jetty ServletContextHandler, " + purpose + " unavailable");
    }

    public static ServletContextHandler getServletContextHandler(ServletContext context)
    {
        if (context instanceof Context)
            return ((Context)context).getServletContextHandler();
        return null;
    }

    public static ServletContext getCurrentServletContext()
    {
        return getServletContext(ContextHandler.getCurrentContext());
    }

    public static ServletContext getServletContext(ContextHandler.ScopedContext context)
    {
        if (context instanceof Context)
            return ((Context)context).getServletContext();
        return null;
    }

    public static ServletContextHandler getCurrentServletContextHandler()
    {
        ContextHandler.ScopedContext context = ContextHandler.getCurrentContext();
        if (context instanceof Context)
            return ((Context)context).getServletContextHandler();
        return null;
    }

    public interface ServletContainerInitializerCaller extends LifeCycle {}

    protected final DecoratedObjectFactory _objFactory;
//    protected Class<? extends SecurityHandler> _defaultSecurityHandlerClass = org.eclipse.jetty.security.ConstraintSecurityHandler.class;
    protected SessionHandler _sessionHandler;
    protected SecurityHandler _securityHandler;
    protected ServletHandler _servletHandler;
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

    public ServletContextHandler(Container parent, String contextPath)
    {
        this(parent, contextPath, null, null, null, null);
    }

    public ServletContextHandler(Container parent, String contextPath, int options)
    {
        this(parent, contextPath, null, null, null, null, options);
    }

    public ServletContextHandler(Container parent, String contextPath, boolean sessions, boolean security)
    {
        this(parent, contextPath, (sessions ? SESSIONS : 0) | (security ? SECURITY : 0));
    }

    public ServletContextHandler(Container parent, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        this(parent, null, sessionHandler, securityHandler, servletHandler, errorHandler);
    }

    public ServletContextHandler(Container parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        this(parent, contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, 0);
    }

    public ServletContextHandler(Container parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        super(parent, contextPath);
        _options = options;
        _sessionHandler = sessionHandler;
        _securityHandler = securityHandler;
        _servletHandler = servletHandler;

        _objFactory = new DecoratedObjectFactory();

        // Link the handlers
        relinkHandlers();

        /*
        TODO: error handling.
        if (errorHandler != null)
            setErrorHandler(errorHandler);
        */
    }

    @Override
    public ServletContext getServletContext()
    {
        return getContext().getServletContext();
    }

    @Override
    protected ContextHandler.ScopedContext newContext()
    {
        return new Context();
    }

    @Override
    public Context getContext()
    {
        return (Context)super.getContext();
    }

    protected void setParent(Container parent)
    {
        if (parent instanceof Handler.Wrapper)
            ((Handler.Wrapper)parent).setHandler(this);
        else if (parent instanceof Collection)
            ((Collection)parent).addHandler(this);
    }

    /**
     * Add EventListener
     *
     * @param listener the listener to add
     * @return true if the listener was added
     * @see HttpSessionAttributeListener
     * @see HttpSessionActivationListener
     * @see HttpSessionBindingListener
     * @see HttpSessionListener
     * @see HttpSessionIdListener
     * @see ContextHandler#addEventListener(EventListener)
     */
    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if ((listener instanceof HttpSessionActivationListener) ||
                (listener instanceof HttpSessionAttributeListener) ||
                (listener instanceof HttpSessionBindingListener) ||
                (listener instanceof HttpSessionListener) ||
                (listener instanceof HttpSessionIdListener))
            {
                if (_sessionHandler != null)
                    _sessionHandler.addEventListener(listener);
            }
            return true;
        }
        return false;
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

    private void doSetHandler(Handler.Wrapper wrapper, Handler handler)
    {
        if (wrapper == this)
            super.setHandler(handler);
        else
            wrapper.setHandler(handler);
    }

    // TODO: review this.
    private void relinkHandlers()
    {
        Handler.Wrapper handler = this;

        // link session handler
        if (getSessionHandler() != null)
        {
            while (!(handler.getHandler() instanceof SessionHandler) &&
                !(handler.getHandler() instanceof SecurityHandler) &&
                !(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof Handler.Wrapper)
            {
                handler = (Handler.Wrapper)handler.getHandler();
            }

            if (handler.getHandler() != _sessionHandler)
                doSetHandler(handler, _sessionHandler);
            handler = _sessionHandler;
        }

        // link security handler
        if (getSecurityHandler() != null)
        {
            while (!(handler.getHandler() instanceof SecurityHandler) &&
                !(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof Handler.Wrapper)
            {
                handler = (Handler.Wrapper)handler.getHandler();
            }

            if (handler.getHandler() != _securityHandler)
                doSetHandler(handler, _securityHandler);
            handler = _securityHandler;
        }

        // link servlet handler
        if (getServletHandler() != null)
        {
            while (!(handler.getHandler() instanceof ServletHandler) &&
                handler.getHandler() instanceof Handler.Wrapper)
            {
                handler = (Handler.Wrapper)handler.getHandler();
            }

            if (handler.getHandler() != _servletHandler)
                doSetHandler(handler, _servletHandler);
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        _servletContextContext = getContext().getServletContext();

        _objFactory.addDecorator(new DeprecationWarning());
        getServletContext().setAttribute(DecoratedObjectFactory.ATTR, _objFactory);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _objFactory.clear();
        getServletContext().removeAttribute(DecoratedObjectFactory.ATTR);
    }

    @Override
    protected ServletScopedRequest wrap(Request request, String pathInContext)
    {
        ServletHandler.MappedServlet mappedServlet = _servletHandler.getMappedServlet(pathInContext);
        if (mappedServlet == null)
            return null;

        // Get a servlet request, possibly from a cached version in the channel attributes.
        // TODO there is a little bit of effort here to recycle the ServletRequest, but not the underlying jetty request.
        //      the servlet request is still a heavy weight object with state, input streams, cookie caches etc. so it is
        //      probably worth while.
        HttpChannel channel = request.getHttpChannel();
        ServletChannel servletChannel = (ServletChannel)channel.getAttribute(ServletChannel.class.getName());
        if (servletChannel == null)
        {
            servletChannel = new ServletChannel();
            if (channel.getConnectionMetaData().isPersistent())
                channel.setAttribute(ServletChannel.class.getName(), servletChannel);
        }

        ServletScopedRequest servletScopedRequest = new ServletScopedRequest(_servletContextContext, servletChannel, request, pathInContext, mappedServlet);
        servletChannel.init(servletScopedRequest);
        return servletScopedRequest;
    }

    @Override
    protected Request.Processor processByContextHandler(ContextRequest request)
    {
        ServletScopedRequest scopedRequest = Request.as(request, ServletScopedRequest.class);
        DispatcherType dispatch = scopedRequest.getHttpServletRequest().getDispatcherType();
        if (dispatch == DispatcherType.REQUEST && isProtectedTarget(request.getPathInContext()))
            return (req, resp, cb) -> Response.writeError(req, resp, cb, HttpServletResponse.SC_NOT_FOUND, null);

        return super.processByContextHandler(request);
    }

    // TODO: do we really need this?? / ask greg what this is all about?
    private final Deque<Boolean> _newContext = new ArrayDeque<>();

    @Override
    protected void enterScope(Request request, Object reason)
    {
        super.enterScope(request, reason);

        ServletScopedRequest scopedRequest = Request.as(request, ServletScopedRequest.class);
        if (scopedRequest != null)
        {
            boolean newContext = scopedRequest.takeNewContext();
            _newContext.push(newContext);
            if (newContext)
                requestInitialized(request, scopedRequest.getHttpServletRequest());
        }
    }

    @Override
    protected void exitScope(Request request)
    {
        ServletScopedRequest scopedRequest = Request.as(request, ServletScopedRequest.class);
        if (scopedRequest != null && !_newContext.isEmpty())
        {
            boolean newContext = _newContext.pop();
            if (newContext)
                requestDestroyed(request, scopedRequest.getHttpServletRequest());
        }
        super.exitScope(request);
    }

    /**
     * Get the defaultSecurityHandlerClass.
     *
     * @return the defaultSecurityHandlerClass
     */
    public Class<? extends SecurityHandler> getDefaultSecurityHandlerClass()
    {
        // return _defaultSecurityHandlerClass;
        return null;
    }

    /**
     * Set the defaultSecurityHandlerClass.
     *
     * @param defaultSecurityHandlerClass the defaultSecurityHandlerClass to set
     */
    public void setDefaultSecurityHandlerClass(Class<? extends SecurityHandler> defaultSecurityHandlerClass)
    {
        // _defaultSecurityHandlerClass = defaultSecurityHandlerClass;
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
     */
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
     * Convenience method to add a servlet.
     *
     * @param servlet the servlet instance
     * @param pathSpec the path spec
     * @return the ServletHolder for the added servlet
     */
    public ServletHolder addServlet(HttpServlet servlet, String pathSpec)
    {
        ServletHolder servletHolder = new ServletHolder(servlet);
        getServletHandler().addServletWithMapping(servletHolder, pathSpec);
        return servletHolder;
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
     * Convenience method to add a servlet.
     *
     * @param filter the filter instance
     * @param pathSpec the path spec
     * @param dispatches the dispatcher types for this filter
     * @return the FilterHolder that was created
     */
    public FilterHolder addFilter(HttpFilter filter, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        FilterHolder filterHolder = new FilterHolder(filter);
        getServletHandler().addFilterWithMapping(filterHolder, pathSpec, dispatches);
        return filterHolder;
    }

    /**
     * Convenience method to programmatically add a {@link ServletContainerInitializer}.
     * @param sci the ServletContainerInitializer to register.
     * @return the ServletContainerInitializerHolder that was created
     */
    public ServletContainerInitializerHolder addServletContainerInitializer(ServletContainerInitializer sci)
    {
        if (!isStopped())
            throw new IllegalStateException("ServletContainerInitializers should be added before starting");

        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(sci);
        addServletContainerInitializer(holder);
        return holder;
    }

    /**
     * Convenience method to programmatically add a {@link ServletContainerInitializer}.
     * @param sci the ServletContainerInitializer to register.
     * @param classes the Set of application classes.
     * @return the ServletContainerInitializerHolder that was created
     */
    public ServletContainerInitializerHolder addServletContainerInitializer(ServletContainerInitializer sci, Class<?>... classes)
    {
        if (!isStopped())
            throw new IllegalStateException("ServletContainerInitializers should be added before starting");

        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(sci, classes);
        addServletContainerInitializer(holder);
        return holder;
    }
    
    /**
     * Convenience method to programmatically add a list of {@link ServletContainerInitializer}.
     * The initializers are guaranteed to be called in the order they are passed into this method.
     * @param sciHolders the ServletContainerInitializerHolders
     */
    public void addServletContainerInitializer(ServletContainerInitializerHolder... sciHolders)
    {
        ServletContainerInitializerStarter starter = getBean(ServletContainerInitializerStarter.class);
        if (starter == null)
        {
            //add the starter as bean which will start when the context is started
            //NOTE: do not use addManaged(starter) because this will start the
            //starter immediately, which  may not be before we have parsed web.xml
            starter = new ServletContainerInitializerStarter();
            addBean(starter, true);
        }
        starter.addServletContainerInitializerHolders(sciHolders);
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
        /*
        TODO: implement security.
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
         */
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
        /*
        TODO: implement security.
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
         */
        return Collections.emptySet();
    }

    public void callContextInitialized(ServletContextListener l, ServletContextEvent e)
    {
        /*
        TODO: listeners
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
         */
    }

    public void callContextDestroyed(ServletContextListener l, ServletContextEvent e)
    {
        // TODO: listeners.
        // super.callContextDestroyed(l, e);
    }

    private void replaceHandler(Handler.Wrapper handler, Handler.Wrapper replacement)
    {
        if (isStarted())
            throw new IllegalStateException("STARTED");

        Handler next = null;
        if (handler != null)
        {
            next = handler.getHandler();
            handler.setHandler(null);

            Handler.Wrapper wrapper = this;
            while (wrapper != null)
            {
                if (wrapper.getHandler() == handler)
                {
                    doSetHandler(wrapper, replacement);
                    break;
                }

                wrapper = (wrapper.getHandler() instanceof Handler.Wrapper) ? (Handler.Wrapper)wrapper.getHandler() : null;
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
     * @param servletHandler The servletHandler to set.
     */
    public void setServletHandler(ServletHandler servletHandler)
    {
        replaceHandler(_servletHandler, servletHandler);
        _servletHandler = servletHandler;
        relinkHandlers();
    }

    /**
     * @param gzipHandler the GzipHandler for this ServletContextHandler
     * @deprecated use {@link #insertHandler(Handler.Wrapper)} instead
     */
    @Deprecated
    public void setGzipHandler(GzipHandler gzipHandler)
    {
        insertHandler(gzipHandler);
        LOG.warn("ServletContextHandler.setGzipHandler(GzipHandler) is deprecated, use insertHandler(HandlerWrapper) instead.");
    }

    /**
     * Insert a HandlerWrapper before the first Session, Security or ServletHandler
     * but after any other HandlerWrappers.
     */
    @Override
    public void insertHandler(Handler.Wrapper handler)
    {
        if (handler instanceof SessionHandler)
            setSessionHandler((SessionHandler)handler);
        else if (handler instanceof SecurityHandler)
            setSecurityHandler((SecurityHandler)handler);
        else if (handler instanceof ServletHandler)
            setServletHandler((ServletHandler)handler);
        else
        {
            Handler.Wrapper tail = handler;
            while (tail.getHandler() instanceof Handler.Wrapper)
            {
                tail = (Handler.Wrapper)tail.getHandler();
            }
            if (tail.getHandler() != null)
                throw new IllegalArgumentException("bad tail of inserted wrapper chain");

            // Skip any injected handlers
            Handler.Wrapper h = this;
            while (h.getHandler() instanceof Handler.Wrapper)
            {
                Handler.Wrapper wrapper = (Handler.Wrapper)h.getHandler();
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

        @Override
        public java.util.Collection<String> getUrlPatterns()
        {
            return new ArrayList<>(_urlPatterns); // spec says must be a copy
        }

        public void addUrlPattern(String s)
        {
            if (!_urlPatterns.contains(s))
                _urlPatterns.add(s);
        }

        @Override
        public String getElIgnored()
        {
            return _elIgnored;
        }

        @Override
        public String getErrorOnELNotFound()
        {
            return "true";
        }

        public void setElIgnored(String s)
        {
            _elIgnored = s;
        }

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

        @Override
        public String getScriptingInvalid()
        {
            return _scriptingInvalid;
        }

        @Override
        public String getIsXml()
        {
            return _isXml;
        }

        @Override
        public java.util.Collection getIncludePreludes()
        {
            return new ArrayList<>(_includePreludes); //must be a copy
        }

        public void addIncludePrelude(String prelude)
        {
            if (!_includePreludes.contains(prelude))
                _includePreludes.add(prelude);
        }

        @Override
        public java.util.Collection getIncludeCodas()
        {
            return new ArrayList<>(_includeCodas); //must be a copy
        }

        public void addIncludeCoda(String coda)
        {
            if (!_includeCodas.contains(coda))
                _includeCodas.add(coda);
        }

        @Override
        public String getDeferredSyntaxAllowedAsLiteral()
        {
            return _deferredSyntaxAllowedAsLiteral;
        }

        @Override
        public String getTrimDirectiveWhitespaces()
        {
            return _trimDirectiveWhitespaces;
        }

        @Override
        public String getDefaultContentType()
        {
            return _defaultContentType;
        }

        @Override
        public String getBuffer()
        {
            return _buffer;
        }

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

        @Override
        public String getTaglibURI()
        {
            return _uri;
        }

        public void setTaglibURI(String uri)
        {
            _uri = uri;
        }

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

        @Override
        public java.util.Collection getTaglibs()
        {
            return new ArrayList<>(_taglibs);
        }

        public void addTaglibDescriptor(TaglibDescriptor d)
        {
            _taglibs.add(d);
        }

        @Override
        public java.util.Collection getJspPropertyGroups()
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

    /**
     * Context.
     * <p>
     * A partial implementation of {@link ServletContext}. A complete implementation is provided by the
     * derived {@link ContextHandler} implementations.
     * </p>
     */
    public class PartialContext extends StaticContext
    {
        protected boolean _enabled = true; // whether or not the dynamic API is enabled for callers
        protected boolean _extendedListenerTypes = false;

        protected PartialContext()
        {
        }

        public ContextHandler getContextHandler()
        {
            return ServletContextHandler.this;
        }

        @Override
        public ServletContext getContext(String uripath)
        {
            List<ServletContextHandler> contexts = new ArrayList<>();
            List<ServletContextHandler> handlers = getServer().getDescendants(ServletContextHandler.class);
            String matchedPath = null;

            for (ServletContextHandler ch : handlers)
            {
                if (ch == null)
                    continue;
                String contextPath = ch.getContextPath();

                if (uripath.equals(contextPath) ||
                    (uripath.startsWith(contextPath) && uripath.charAt(contextPath.length()) == '/') ||
                    "/".equals(contextPath))
                {
                    // look first for vhost matching context only
                    if (getVirtualHosts() != null && getVirtualHosts().size() > 0)
                    {
                        if (ch.getVirtualHosts() != null && ch.getVirtualHosts().size() > 0)
                        {
                            for (String h1 : getVirtualHosts())
                            {
                                for (String h2 : ch.getVirtualHosts())
                                {
                                    if (h1.equals(h2))
                                    {
                                        if (matchedPath == null || contextPath.length() > matchedPath.length())
                                        {
                                            contexts.clear();
                                            matchedPath = contextPath;
                                        }

                                        if (matchedPath.equals(contextPath))
                                            contexts.add(ch);
                                    }
                                }
                            }
                        }
                    }
                    else
                    {
                        if (matchedPath == null || contextPath.length() > matchedPath.length())
                        {
                            contexts.clear();
                            matchedPath = contextPath;
                        }

                        if (matchedPath.equals(contextPath))
                            contexts.add(ch);
                    }
                }
            }

            if (contexts.size() > 0)
                return contexts.get(0).getServletContext();

            // try again ignoring virtual hosts
            matchedPath = null;
            for (ServletContextHandler ch : handlers)
            {
                if (ch == null)
                    continue;
                String contextPath = ch.getContextPath();

                if (uripath.equals(contextPath) || (uripath.startsWith(contextPath) && uripath.charAt(contextPath.length()) == '/') || "/".equals(contextPath))
                {
                    if (matchedPath == null || contextPath.length() > matchedPath.length())
                    {
                        contexts.clear();
                        matchedPath = contextPath;
                    }

                    if (matchedPath.equals(contextPath))
                        contexts.add(ch);
                }
            }

            if (contexts.size() > 0)
                return contexts.get(0).getServletContext();
            return null;
        }

        @Override
        public String getMimeType(String file)
        {
            if (_mimeTypes == null)
                return null;
            return _mimeTypes.getMimeByExtension(file);
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String uriInContext)
        {
            // uriInContext is encoded, potentially with query.
            if (uriInContext == null)
                return null;

            if (!uriInContext.startsWith("/"))
                return null;

            try
            {
                String contextPath = getContextPath();
                // uriInContext is canonicalized by HttpURI.
                HttpURI.Mutable uri = HttpURI.build(uriInContext);
                String pathInfo = uri.getDecodedPath();
                if (StringUtil.isEmpty(pathInfo))
                    return null;

                if (!StringUtil.isEmpty(contextPath))
                {
                    uri.path(URIUtil.addPaths(contextPath, uri.getPath()));
                    pathInfo = uri.getDecodedPath().substring(contextPath.length());
                }
                return new Dispatcher(ServletContextHandler.this, uri, pathInfo);
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
            }
            return null;
        }

        @Override
        public String getRealPath(String path)
        {
            // This is an API call from the application which may have arbitrary non canonical paths passed
            // Thus we canonicalize here, to avoid the enforcement of only canonical paths in
            // ContextHandler.this.getResource(path).
            path = URIUtil.canonicalPath(path);
            if (path == null)
                return null;
            if (path.length() == 0)
                path = URIUtil.SLASH;
            else if (path.charAt(0) != '/')
                path = URIUtil.SLASH + path;

            try
            {
                Resource resource = ServletContextHandler.this.getResource(path);
                if (resource != null)
                {
                    File file = resource.getFile();
                    if (file != null)
                        return file.getCanonicalPath();
                }
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
            }

            return null;
        }

        @Override
        public URL getResource(String path) throws MalformedURLException
        {
            // This is an API call from the application which may have arbitrary non canonical paths passed
            // Thus we canonicalize here, to avoid the enforcement of only canonical paths in
            // ContextHandler.this.getResource(path).
            path = URIUtil.canonicalPath(path);
            if (path == null)
                return null;
            Resource resource = ServletContextHandler.this.getResource(path);
            if (resource != null && resource.exists())
                return resource.getURI().toURL();
            return null;
        }

        @Override
        public InputStream getResourceAsStream(String path)
        {
            try
            {
                URL url = getResource(path);
                if (url == null)
                    return null;
                Resource r = Resource.newResource(url);
                // Cannot serve directories as an InputStream
                if (r.isDirectory())
                    return null;
                return r.getInputStream();
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
                return null;
            }
        }

        @Override
        public Set<String> getResourcePaths(String path)
        {
            // This is an API call from the application which may have arbitrary non canonical paths passed
            // Thus we canonicalize here, to avoid the enforcement of only canonical paths in
            // ContextHandler.this.getResource(path).
            path = URIUtil.canonicalPath(path);
            if (path == null)
                return null;
            return ServletContextHandler.this.getResourcePaths(path);
        }

        @Override
        public void log(String msg)
        {
            getLogger().info(msg);
        }

        @Override
        public void log(String message, Throwable throwable)
        {
            if (throwable == null)
                getLogger().warn(message);
            else
                getLogger().warn(message, throwable);
        }

        @Override
        public String getInitParameter(String name)
        {
            return ServletContextHandler.this.getInitParameter(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames()
        {
            return ServletContextHandler.this.getInitParameterNames();
        }

        @Override
        public Object getAttribute(String name)
        {
            Object o = ServletContextHandler.this.getAttribute(name);
            if (o == null)
                o = super.getAttribute(name);
            return o;
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            HashSet<String> set = new HashSet<>();
            Enumeration<String> e = super.getAttributeNames();
            while (e.hasMoreElements())
            {
                set.add(e.nextElement());
            }

            set.addAll(ServletContextHandler.this.getAttributeNameSet());
            return Collections.enumeration(set);
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            Object oldValue = super.getAttribute(name);

            if (value == null)
                super.removeAttribute(name);
            else
                super.setAttribute(name, value);

            if (!_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_servletContextContext, name, oldValue == null ? value : oldValue);

                for (ServletContextAttributeListener listener : _servletContextAttributeListeners)
                {
                    if (oldValue == null)
                        listener.attributeAdded(event);
                    else if (value == null)
                        listener.attributeRemoved(event);
                    else
                        listener.attributeReplaced(event);
                }
            }
        }

        @Override
        public void removeAttribute(String name)
        {
            Object oldValue = super.getAttribute(name);
            super.removeAttribute(name);
            if (oldValue != null && !_servletContextAttributeListeners.isEmpty())
            {
                ServletContextAttributeEvent event = new ServletContextAttributeEvent(_servletContextContext, name, oldValue);
                for (ServletContextAttributeListener listener : _servletContextAttributeListeners)
                {
                    listener.attributeRemoved(event);
                }
            }
        }

        @Override
        public String getServletContextName()
        {
            String name = ServletContextHandler.this.getDisplayName();
            if (name == null)
                name = ServletContextHandler.this.getContextPath();
            return name;
        }

        @Override
        public String getContextPath()
        {
            return getRequestContextPath();
        }

        @Override
        public String toString()
        {
            return "ServletContext@" + ServletContextHandler.this.toString();
        }

        @Override
        public boolean setInitParameter(String name, String value)
        {
            if (ServletContextHandler.this.getInitParameter(name) != null)
                return false;
            ServletContextHandler.this.getInitParams().put(name, value);
            return true;
        }

        @Override
        public void addListener(String className)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                ClassLoader classLoader = ServletContextHandler.this.getClassLoader();
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<? extends EventListener> clazz = classLoader == null ? Loader.loadClass(className) : (Class)classLoader.loadClass(className);
                addListener(clazz);
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public <T extends EventListener> void addListener(T t)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            checkListener(t.getClass());

            ServletContextHandler.this.addEventListener(t);
            ServletContextHandler.this.addProgrammaticListener(t);
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass)
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            try
            {
                EventListener e = createListener(listenerClass);
                addListener(e);
            }
            catch (ServletException e)
            {
                throw new IllegalArgumentException(e);
            }
        }

        public void checkListener(Class<? extends EventListener> listener) throws IllegalStateException
        {
            boolean ok = false;
            int startIndex = (isExtendedListenerTypes() ? EXTENDED_LISTENER_TYPE_INDEX : DEFAULT_LISTENER_TYPE_INDEX);
            for (int i = startIndex; i < SERVLET_LISTENER_TYPES.length; i++)
            {
                if (SERVLET_LISTENER_TYPES[i].isAssignableFrom(listener))
                {
                    ok = true;
                    break;
                }
            }
            if (!ok)
                throw new IllegalArgumentException("Inappropriate listener class " + listener.getName());
        }

        public void setExtendedListenerTypes(boolean extended)
        {
            _extendedListenerTypes = extended;
        }

        public boolean isExtendedListenerTypes()
        {
            return _extendedListenerTypes;
        }

        @Override
        public ClassLoader getClassLoader()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();

            // no security manager just return the classloader
            ClassLoader classLoader = ServletContextHandler.this.getClassLoader();
            if (!isUsingSecurityManager())
            {
                return classLoader;
            }
            else
            {
                // check to see if the classloader of the caller is the same as the context
                // classloader, or a parent of it, as required by the javadoc specification.

                // Wrap in a PrivilegedAction so that only Jetty code will require the
                // "createSecurityManager" permission, not also application code that calls this method.
                Caller caller = AccessController.doPrivileged((PrivilegedAction<Caller>)Caller::new);
                ClassLoader callerLoader = caller.getCallerClassLoader(2);
                while (callerLoader != null)
                {
                    if (callerLoader == classLoader)
                        return classLoader;
                    else
                        callerLoader = callerLoader.getParent();
                }
                System.getSecurityManager().checkPermission(new RuntimePermission("getClassLoader"));
                return classLoader;
            }
        }

        @Override
        public JspConfigDescriptor getJspConfigDescriptor()
        {
            LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getJspConfigDescriptor()");
            return null;
        }

        public void setJspConfigDescriptor(JspConfigDescriptor d)
        {

        }

        @Override
        public void declareRoles(String... roleNames)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();
        }

        public void setEnabled(boolean enabled)
        {
            _enabled = enabled;
        }

        public boolean isEnabled()
        {
            return _enabled;
        }

        @Override
        public String getVirtualServerName()
        {
            List<String> hosts = getVirtualHosts();
            if (hosts != null && !hosts.isEmpty())
                return hosts.get(0);
            return null;
        }
    }

    public class Context extends ContextHandler.ScopedContext
    {
        public ServletContextContext servletContext = new ServletContextContext();

        public Set<Map.Entry<String, Object>> getAttributeEntrySet()
        {
            return servletContext.getAttributeEntrySet();
        }

        public ServletContextContext getServletContext()
        {
            return servletContext;
        }

        public ServletContextHandler getServletContextHandler()
        {
            return ServletContextHandler.this;
        }

        public <T> T createInstance(BaseHolder<T> holder) throws ServletException
        {
            try
            {
                //set a thread local
                DecoratedObjectFactory.associateInfo(holder);
                return servletContext.createInstance(holder.getHeldClass());
            }
            finally
            {
                //unset the thread local
                DecoratedObjectFactory.disassociateInfo();
            }
        }

        public <T extends Filter> void destroyFilter(T f)
        {
            _objFactory.destroy(f);
        }

        public <T extends Servlet> void destroyServlet(T s)
        {
            _objFactory.destroy(s);
        }

        public void setExtendedListenerTypes(boolean b)
        {
            servletContext.setExtendedListenerTypes(b);
        }
    }

    public class ServletContextContext extends PartialContext
    {
        public Context getContext()
        {
            return ServletContextHandler.this.getContext();
        }

        @Override
        public RequestDispatcher getNamedDispatcher(String name)
        {
            ServletContextHandler context = ServletContextHandler.this;
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
                throw new IllegalArgumentException("Missing name");

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
        public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile)
        {
            checkDynamic(servletName);
            
            final ServletHandler handler = ServletContextHandler.this.getServletHandler();
            ServletHolder holder = handler.getServlet(servletName);
            if (holder == null)
            {
                //new servlet
                holder = handler.newServletHolder(Source.JAVAX_API);
                holder.setName(servletName);
                holder.setForcedPath(jspFile);
                handler.addServlet(holder);
                return dynamicHolderAdded(holder);
            }
            
            //complete a partial registration
            if (holder.getClassName() == null && holder.getHeldClass() == null && holder.getForcedPath() == null)
            {
                holder.setForcedPath(jspFile);
                return holder.getRegistration();
            }
            else
                return null; //existing completed registration for servlet name
        }

        @Override
        public String getInitParameter(String name)
        {
            //since servlet spec 4.0
            Objects.requireNonNull(name);
            return super.getInitParameter(name);
        }

        @Override
        public boolean setInitParameter(String name, String value)
        {
            //since servlet spec 4.0
            Objects.requireNonNull(name);

            if (!isStarting())
                throw new IllegalStateException();

            if (!_enabled)
                throw new UnsupportedOperationException();

            return super.setInitParameter(name, value);
        }

        @Override
        public <T> T createInstance(Class<T> clazz) throws ServletException
        {
            return _objFactory.decorate(super.createInstance(clazz));
        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
            if (_sessionHandler != null)
                return _sessionHandler.getDefaultSessionTrackingModes();
            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            
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
        public int getSessionTimeout()
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            int timeout = -1;
            if (_sessionHandler != null)
            {
                timeout = _sessionHandler.getMaxInactiveInterval();
            }

            return (int)TimeUnit.SECONDS.toMinutes(timeout);
        }

        @Override
        public void setSessionTimeout(int sessionTimeout)
        {
            if (!isStarting())
                throw new IllegalStateException();
            if (!_enabled)
                throw new UnsupportedOperationException();

            if (_sessionHandler != null)
            {
                long tmp = TimeUnit.MINUTES.toSeconds(sessionTimeout);
                if (tmp > Integer.MAX_VALUE)
                    tmp = Integer.MAX_VALUE;
                if (tmp < Integer.MIN_VALUE)
                    tmp = Integer.MIN_VALUE;
                _sessionHandler.setMaxInactiveInterval((int)tmp);
            }
        }
        
        @Override
        public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            return super.createServlet(clazz);
        }

        @Override
        public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            return super.createFilter(clazz);
        }
        
        @Override
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
        {
            if (!_enabled)
                throw new UnsupportedOperationException();
            try
            {
                checkListener(clazz);
            }
            catch (IllegalArgumentException e)
            {
                //Bizarrely, according to the spec, it is NOT an error to create an instance of
                //a ServletContextListener from inside a ServletContextListener, but it IS an error
                //to call addListener with one!
                if (!ServletContextListener.class.isAssignableFrom(clazz))
                    throw e;
            }
            return super.createListener(clazz);
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

        @Override
        public String getRequestCharacterEncoding()
        {
            return getDefaultRequestCharacterEncoding();
        }

        @Override
        public void setRequestCharacterEncoding(String encoding)
        {
            if (!isStarting())
                throw new IllegalStateException();
            
            setDefaultRequestCharacterEncoding(encoding);
        }

        @Override
        public String getResponseCharacterEncoding()
        {
            return getDefaultResponseCharacterEncoding();
        }

        @Override
        public void setResponseCharacterEncoding(String encoding)
        {
            if (!isStarting())
                throw new IllegalStateException();
            
            setDefaultResponseCharacterEncoding(encoding);
        }
    }

    /**
     * Bean that is added to the ServletContextHandler to start all of the
     * ServletContainerInitializers by starting their corresponding
     * ServletContainerInitializerHolders when this bean is itself started.
     * Note that the SCIs will be started in order of addition.
     */
    public static class ServletContainerInitializerStarter extends ContainerLifeCycle implements ServletContainerInitializerCaller
    {
        public void addServletContainerInitializerHolders(ServletContainerInitializerHolder... holders)
        {
            for (ServletContainerInitializerHolder holder:holders)
                addBean(holder, true);
        }
        
        public java.util.Collection getServletContainerInitializerHolders()
        {
            return getContainedBeans(ServletContainerInitializerHolder.class);
        }

        @Override
        protected void doStart() throws Exception
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Starting SCIs");
            super.doStart();
        }

        @Override
        protected void doStop() throws Exception
        {
            //remove all of the non-programmatic holders
            java.util.Collection<ServletContainerInitializerHolder> holders = getServletContainerInitializerHolders();
            for (ServletContainerInitializerHolder h : holders)
            {
                if (h.getSource().getOrigin() != Source.Origin.EMBEDDED)
                    removeBean(h);
            }
            super.doStop();
        }
    }

    private static class Caller extends SecurityManager
    {
        public ClassLoader getCallerClassLoader(int depth)
        {
            if (depth < 0)
                return null;
            Class<?>[] classContext = getClassContext();
            if (classContext.length <= depth)
                return null;
            return classContext[depth].getClassLoader();
        }
    }
}
