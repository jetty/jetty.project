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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.servlet.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract SecurityHandler.
 * <p>
 * Select and apply an {@link Authenticator} to a request.
 * <p>
 * The Authenticator may either be directly set on the handler
 * or will be create during {@link #start()} with a call to
 * either the default or set AuthenticatorFactory.
 * <p>
 * SecurityHandler has a set of initparameters that are used by the
 * Authentication.Configuration. At startup, any context init parameters
 * that start with "org.eclipse.jetty.security." that do not have
 * values in the SecurityHandler init parameters, are copied.
 */
public abstract class SecurityHandler extends Handler.Wrapper implements Authenticator.AuthConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(SecurityHandler.class);
    private static final List<Authenticator.Factory> __knownAuthenticatorFactories = new ArrayList<>();

    private boolean _checkWelcomeFiles = false;
    private Authenticator _authenticator;
    private Authenticator.Factory _authenticatorFactory;
    private String _realmName;
    private String _authMethod;
    private final Map<String, String> _initParameters = new HashMap<>();
    private LoginService _loginService;
    private IdentityService _identityService;
    private boolean _renewSession = true;

    static
    {
        TypeUtil.serviceStream(ServiceLoader.load(Authenticator.Factory.class))
            .forEach(__knownAuthenticatorFactories::add);
        __knownAuthenticatorFactories.add(new DefaultAuthenticatorFactory());
    }

    protected SecurityHandler()
    {
        addBean(new DumpableCollection("knownAuthenticatorFactories", __knownAuthenticatorFactories));
    }

    /**
     * Get the identityService.
     *
     * @return the identityService
     */
    @Override
    public IdentityService getIdentityService()
    {
        return _identityService;
    }

    /**
     * Set the identityService.
     *
     * @param identityService the identityService to set
     */
    public void setIdentityService(IdentityService identityService)
    {
        if (isStarted())
            throw new IllegalStateException("Started");
        updateBean(_identityService, identityService);
        _identityService = identityService;
    }

    /**
     * Get the loginService.
     *
     * @return the loginService
     */
    @Override
    public LoginService getLoginService()
    {
        return _loginService;
    }

    /**
     * Set the loginService.
     *
     * @param loginService the loginService to set
     */
    public void setLoginService(LoginService loginService)
    {
        if (isStarted())
            throw new IllegalStateException("Started");
        updateBean(_loginService, loginService);
        _loginService = loginService;
    }

    public Authenticator getAuthenticator()
    {
        return _authenticator;
    }

    /**
     * Set the authenticator.
     *
     * @param authenticator the authenticator
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setAuthenticator(Authenticator authenticator)
    {
        if (isStarted())
            throw new IllegalStateException("Started");
        updateBean(_authenticator, authenticator);
        _authenticator = authenticator;
        if (_authenticator != null)
            _authMethod = _authenticator.getAuthMethod();
    }

    /**
     * @return the authenticatorFactory
     */
    public Authenticator.Factory getAuthenticatorFactory()
    {
        return _authenticatorFactory;
    }

    /**
     * @param authenticatorFactory the authenticatorFactory to set
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setAuthenticatorFactory(Authenticator.Factory authenticatorFactory)
    {
        if (isRunning())
            throw new IllegalStateException("running");
        updateBean(_authenticatorFactory, authenticatorFactory);
        _authenticatorFactory = authenticatorFactory;
    }

    /**
     * @return the list of discovered authenticatorFactories
     */
    public List<Authenticator.Factory> getKnownAuthenticatorFactories()
    {
        return __knownAuthenticatorFactories;
    }

    /**
     * @return the realmName
     */
    @Override
    public String getRealmName()
    {
        return _realmName;
    }

    /**
     * @param realmName the realmName to set
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setRealmName(String realmName)
    {
        if (isRunning())
            throw new IllegalStateException("running");
        _realmName = realmName;
    }

    /**
     * @return the authMethod
     */
    @Override
    public String getAuthMethod()
    {
        return _authMethod;
    }

    /**
     * @param authMethod the authMethod to set
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setAuthMethod(String authMethod)
    {
        if (isRunning())
            throw new IllegalStateException("running");
        _authMethod = authMethod;
    }

    /**
     * @return True if forwards to welcome files are authenticated
     */
    public boolean isCheckWelcomeFiles()
    {
        return _checkWelcomeFiles;
    }

    /**
     * @param authenticateWelcomeFiles True if forwards to welcome files are
     * authenticated
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setCheckWelcomeFiles(boolean authenticateWelcomeFiles)
    {
        if (isRunning())
            throw new IllegalStateException("running");
        _checkWelcomeFiles = authenticateWelcomeFiles;
    }

    @Override
    public String getInitParameter(String key)
    {
        return _initParameters.get(key);
    }

    @Override
    public Set<String> getInitParameterNames()
    {
        return _initParameters.keySet();
    }

    /**
     * Set an initialization parameter.
     *
     * @param key the init key
     * @param value the init value
     * @return previous value
     * @throws IllegalStateException if the SecurityHandler is started
     */
    public String setInitParameter(String key, String value)
    {
        if (isStarted())
            throw new IllegalStateException("started");
        return _initParameters.put(key, value);
    }

    protected LoginService findLoginService() throws Exception
    {
        java.util.Collection<LoginService> list = getServer().getBeans(LoginService.class);
        LoginService service = null;
        String realm = getRealmName();
        if (realm != null)
        {
            for (LoginService s : list)
            {
                if (s.getName() != null && s.getName().equals(realm))
                {
                    service = s;
                    break;
                }
            }
        }
        else if (list.size() == 1)
            service = list.iterator().next();

        return service;
    }

    protected IdentityService findIdentityService()
    {
        return getServer().getBean(IdentityService.class);
    }

    @Override
    protected void doStart()
        throws Exception
    {
        // copy security init parameters
        ServletContext context = ServletContextHandler.getCurrentServletContext();
        if (context != null)
        {
            Enumeration<String> names = context.getInitParameterNames();
            while (names != null && names.hasMoreElements())
            {
                String name = names.nextElement();
                if (name.startsWith("org.eclipse.jetty.security.") &&
                    getInitParameter(name) == null)
                    setInitParameter(name, context.getInitParameter(name));
            }
        }

        // complicated resolution of login and identity service to handle
        // many different ways these can be constructed and injected.

        if (_loginService == null)
        {
            setLoginService(findLoginService());
            if (_loginService != null)
                unmanage(_loginService);
        }

        if (_identityService == null)
        {
            if (_loginService != null)
                setIdentityService(_loginService.getIdentityService());

            if (_identityService == null)
                setIdentityService(findIdentityService());

            if (_identityService == null)
            {
                setIdentityService(new DefaultIdentityService());
                manage(_identityService);
            }
            else
                unmanage(_identityService);
        }

        if (_loginService != null)
        {
            if (_loginService.getIdentityService() == null)
                _loginService.setIdentityService(_identityService);
            else if (_loginService.getIdentityService() != _identityService)
                throw new IllegalStateException("LoginService has different IdentityService to " + this);
        }

        if (_authenticator == null)
        {
            // If someone has set an authenticator factory only use that, otherwise try the list of discovered factories.
            if (_authenticatorFactory != null)
            {
                Authenticator authenticator = _authenticatorFactory.getAuthenticator(getServer(), context,
                    this, _identityService, _loginService);

                if (authenticator != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Created authenticator {} with {}", authenticator, _authenticatorFactory);

                    setAuthenticator(authenticator);
                }
            }
            else
            {
                for (Authenticator.Factory factory : getKnownAuthenticatorFactories())
                {
                    Authenticator authenticator = factory.getAuthenticator(getServer(), context,
                        this, _identityService, _loginService);

                    if (authenticator != null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Created authenticator {} with {}", authenticator, factory);

                        setAuthenticator(authenticator);
                        break;
                    }
                }
            }
        }

        if (_authenticator != null)
            _authenticator.setConfiguration(this);
        else if (_realmName != null)
        {
            LOG.warn("No Authenticator for {}", this);
            throw new IllegalStateException("No Authenticator");
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        //if we discovered the services (rather than had them explicitly configured), remove them.
        if (!isManaged(_identityService))
        {
            removeBean(_identityService);
            _identityService = null;
        }

        if (!isManaged(_loginService))
        {
            removeBean(_loginService);
            _loginService = null;
        }

        super.doStop();
    }

    protected boolean checkSecurity(ServletRequest request)
    {
        switch (request.getDispatcherType())
        {
            case REQUEST:
            case ASYNC:
                return true;
            case FORWARD:
                if (isCheckWelcomeFiles() && request.getAttribute("org.eclipse.jetty.server.welcome") != null)
                {
                    request.removeAttribute("org.eclipse.jetty.server.welcome");
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean isSessionRenewedOnAuthentication()
    {
        return _renewSession;
    }

    /**
     * Set renew the session on Authentication.
     * <p>
     * If set to true, then on authentication, the session associated with a reqeuest is invalidated and replaced with a new session.
     *
     * @param renew true to renew the authentication on session
     * @see Authenticator.AuthConfiguration#isSessionRenewedOnAuthentication()
     */
    public void setSessionRenewedOnAuthentication(boolean renew)
    {
        _renewSession = renew;
    }
    
    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        final Request.Processor processor = super.handle(request);
        if (processor == null)
            return null; //not handling this
        
        final ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
        final ServletContextRequest.ServletApiRequest servletApiRequest =
            (servletContextRequest == null ? null : servletContextRequest.getServletApiRequest());
        final Authenticator authenticator = _authenticator;
        
        if (!checkSecurity(servletApiRequest))
            return processor; //don't need to do any security work, let other handlers do the processing
        
        //we need to process security
        return new Request.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                //See Servlet Spec 3.1 sec 13.6.3
                if (authenticator != null)
                    authenticator.prepareRequest(request);

                RoleInfo roleInfo = prepareConstraintInfo(servletContextRequest.getPathInContext(), servletApiRequest);

                // Check data constraints
                if (!checkUserDataPermissions(servletContextRequest.getPathInContext(), servletContextRequest, response, callback, roleInfo))
                {
                    return;
                }

                // is Auth mandatory?
                boolean isAuthMandatory =
                    isAuthMandatory(request, response, roleInfo);

                if (isAuthMandatory && authenticator == null)
                {
                    LOG.warn("No authenticator for: {}", roleInfo);
                    //TODO - check this
                    Response.writeError(request, response, callback, HttpServletResponse.SC_FORBIDDEN);
                    return;
                }

                // check authentication
                Object previousIdentity = null;
                try
                {
                    Authentication authentication = servletApiRequest.getAuthentication();
                    if (authentication == null || authentication == Authentication.NOT_CHECKED)
                        authentication = authenticator == null ? Authentication.UNAUTHENTICATED : authenticator.validateRequest(request, response, callback, isAuthMandatory);

                    if (authentication instanceof Authentication.ResponseSent)
                    {
                        //TODO - check this
                        return;
                    }
                    else if (authentication instanceof Authentication.User)
                    {
                        Authentication.User userAuth = (Authentication.User)authentication;
                        servletApiRequest.setAuthentication(authentication);
                        if (_identityService != null)
                            previousIdentity = _identityService.associate(userAuth.getUserIdentity());

                        if (isAuthMandatory)
                        {
                            boolean authorized = checkWebResourcePermissions(Request.getPathInContext(request), request, response, roleInfo, userAuth.getUserIdentity());
                            if (!authorized)
                            {
                                Response.writeError(request, response, callback, HttpServletResponse.SC_FORBIDDEN, "!role");
                                return;
                            }
                        }

                        //process the request by other handlers
                        processor.process(request, response, callback);

                        if (authenticator != null)
                            authenticator.secureResponse(request, response, callback, isAuthMandatory, userAuth);
                    }
                    else if (authentication instanceof Authentication.Deferred)
                    {
                        DeferredAuthentication deferred = (DeferredAuthentication)authentication;
                        servletApiRequest.setAuthentication(authentication);

                        try
                        {
                            //process the request by other handlers
                            processor.process(request, response, callback);
                        }
                        finally
                        {
                            previousIdentity = deferred.getPreviousAssociation();
                        }

                        if (authenticator != null)
                        {
                            Authentication auth = servletApiRequest.getAuthentication();
                            if (auth instanceof Authentication.User)
                            {
                                Authentication.User userAuth = (Authentication.User)auth;
                                authenticator.secureResponse(request, response, callback, isAuthMandatory, userAuth);
                            }
                            else
                                authenticator.secureResponse(request, response, callback, isAuthMandatory, null);
                        }
                    }
                    else if (isAuthMandatory)
                    {
                        Response.writeError(request, response, callback, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated");
                    }
                    else
                    {
                        servletApiRequest.setAuthentication(authentication);
                        if (_identityService != null)
                            previousIdentity = _identityService.associate(null);

                        //process the request by other handlers
                        processor.process(request, response, callback);

                        if (authenticator != null)
                            authenticator.secureResponse(request, response, callback, isAuthMandatory, null);
                    }
                }
                catch (ServerAuthException e)
                {
                    // jaspi 3.8.3 send HTTP 500 internal server error, with message
                    // from AuthException
                    //TODO - check
                    Response.writeError(request, response, callback, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                }
                finally
                {
                    if (_identityService != null)
                        _identityService.disassociate(previousIdentity);
                }
            }
        };
    }

    public static SecurityHandler getCurrentSecurityHandler()
    {
        ServletContextHandler contextHandler = ServletContextHandler.getCurrentServletContextHandler();
        if (contextHandler == null)
            return null;

        return contextHandler.getDescendant(SecurityHandler.class);
    }

    public void logout(Authentication.User user)
    {
        LOG.debug("logout {}", user);
        if (user == null)
            return;

        LoginService loginService = getLoginService();
        if (loginService != null)
        {
            loginService.logout(user.getUserIdentity());
        }

        IdentityService identityService = getIdentityService();
        if (identityService != null)
        {
            // TODO recover previous from threadlocal (or similar)
            Object previous = null;
            identityService.disassociate(previous);
        }
    }

    protected abstract RoleInfo prepareConstraintInfo(String pathInContext, HttpServletRequest request);

    protected abstract boolean checkUserDataPermissions(String pathInContext, Request request, Response response, Callback callback, RoleInfo constraintInfo) throws IOException;

    protected abstract boolean isAuthMandatory(Request baseRequest, Response baseResponse, Object constraintInfo);

    protected abstract boolean checkWebResourcePermissions(String pathInContext, Request request, Response response, Object constraintInfo,
                                                           UserIdentity userIdentity) throws IOException;

    public class NotChecked implements Principal
    {
        @Override
        public String getName()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return "NOT CHECKED";
        }

        public SecurityHandler getSecurityHandler()
        {
            return SecurityHandler.this;
        }
    }

    public static final Principal __NO_USER = new Principal()
    {
        @Override
        public String getName()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return "No User";
        }
    };

    /**
     * Nobody user. The Nobody UserPrincipal is used to indicate a partial state
     * of authentication. A request with a Nobody UserPrincipal will be allowed
     * past all authentication constraints - but will not be considered an
     * authenticated request. It can be used by Authenticators such as
     * FormAuthenticator to allow access to logon and error pages within an
     * authenticated URI tree.
     */
    public static final Principal __NOBODY = new Principal()
    {
        @Override
        public String getName()
        {
            return "Nobody";
        }

        @Override
        public String toString()
        {
            return getName();
        }
    };
}
