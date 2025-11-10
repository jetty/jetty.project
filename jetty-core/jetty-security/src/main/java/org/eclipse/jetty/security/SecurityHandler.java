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

package org.eclipse.jetty.security;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.PathSpecGroup;
import org.eclipse.jetty.security.Authenticator.Configuration;
import org.eclipse.jetty.security.Constraint.Authorization;
import org.eclipse.jetty.security.Constraint.Transport;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract SecurityHandler.
 * <p>
 * Select and apply an {@link Authenticator} to a request.
 * <p>
 * The Authenticator may either be directly set on the handler
 * or it will be created during {@link #start()} with a call to
 * either the default or set AuthenticatorFactory.
 * <p>
 * SecurityHandler has a set of parameters that are used by the
 * Authentication.Configuration. At startup, any context init parameters
 * that start with "org.eclipse.jetty.security." that do not have
 * values in the SecurityHandler init parameters, are copied.
 */
public abstract class SecurityHandler extends Handler.Wrapper implements Configuration
{
    public static String SESSION_AUTHENTICATED_ATTRIBUTE = "org.eclipse.jetty.security.sessionAuthenticated";

    private static final Logger LOG = LoggerFactory.getLogger(SecurityHandler.class);
    private static final List<Authenticator.Factory> __knownAuthenticatorFactories = new ArrayList<>();

    private Authenticator _authenticator;
    private Authenticator.Factory _authenticatorFactory;
    private String _realmName;
    private String _authenticationType;
    private final Map<String, String> _parameters = new HashMap<>();
    private LoginService _loginService;
    private IdentityService _identityService;
    private boolean _renewSessionOnAuthentication = true;
    private int _sessionMaxInactiveIntervalOnAuthentication = 0;
    private AuthenticationState.Deferred _deferred;

    static
    {
        TypeUtil.serviceStream(ServiceLoader.load(Authenticator.Factory.class))
            .forEach(__knownAuthenticatorFactories::add);
        __knownAuthenticatorFactories.add(new DefaultAuthenticatorFactory());
    }

    protected SecurityHandler()
    {
        this(null);
    }

    protected SecurityHandler(Handler handler)
    {
        super(handler);
        installBean(new DumpableCollection("knownAuthenticatorFactories", __knownAuthenticatorFactories));
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
     * If a {@link LoginService} is not set, or is set to null,
     * then during {@link #doStart()}
     * the {@link #findLoginService()} method is used to locate one.
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
            _authenticationType = _authenticator.getAuthenticationType();
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
     * @return the name of the Authenticator
     */
    @Override
    public String getAuthenticationType()
    {
        return _authenticationType;
    }

    /**
     * @param authenticationType the name of the Authenticator to use
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setAuthenticationType(String authenticationType)
    {
        if (isRunning())
            throw new IllegalStateException("running");
        _authenticationType = authenticationType;
        if (_authenticator != null && !_authenticator.getAuthenticationType().equals(_authenticationType))
            _authenticator = null;
    }

    @Override
    public String getParameter(String key)
    {
        return _parameters.get(key);
    }

    @Override
    public Set<String> getParameterNames()
    {
        return _parameters.keySet();
    }

    /**
     * Set an authentication parameter for retrieval via {@link Configuration#getParameter(String)}
     *
     * @param key the key
     * @param value the init value
     * @return previous value
     * @throws IllegalStateException if the SecurityHandler is started
     */
    public String setParameter(String key, String value)
    {
        if (isStarted())
            throw new IllegalStateException("started");
        return _parameters.put(key, value);
    }

    /**
     * Find an appropriate {@link LoginService} from the
     * list returned by {@link org.eclipse.jetty.util.component.Container#getBeans(Class)}
     * called on the result of {@link #getServer()}.  A service is selected by:
     * <ul>
     *     <li>if {@link #setRealmName(String)} has been called, the first service
     *     with a matching name is used</li>
     *     <li>if the list is size 1, that service is used</li>
     *     <li>otherwise no service is selected.</li>
     * </ul>
     * @return An appropriate {@link LoginService} or null
     */
    protected LoginService findLoginService()
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

        Context context = ContextHandler.getCurrentContext();

        if (_authenticator == null)
        {
            // If someone has set an authenticator factory only use that, otherwise try the list of discovered factories.
            if (_authenticatorFactory != null)
            {
                Authenticator authenticator = _authenticatorFactory.getAuthenticator(getServer(), context,
                    this);

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
                        this);

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

        if (_authenticator == null)
            setAuthenticator(new Authenticator.NoOp());

        if (_authenticator != null)
            _authenticator.setConfiguration(this);
        else if (_realmName != null)
        {
            LOG.warn("No Authenticator for {}", this);
            throw new IllegalStateException("No Authenticator");
        }

        if (_authenticator instanceof LoginAuthenticator loginAuthenticator)
        {
            _deferred = AuthenticationState.defer(loginAuthenticator);
            addBean(_deferred);
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

        if (_deferred != null)
        {
            removeBean(_deferred);
            _deferred = null;
        }
        super.doStop();
    }

    @Override
    public boolean isSessionRenewedOnAuthentication()
    {
        return _renewSessionOnAuthentication;
    }

    /**
     * Set renew the session on Authentication.
     * <p>
     * If set to true, then on authentication, the session associated with a request is invalidated and replaced with a new session.
     *
     * @param renew true to renew the authentication on session
     * @see Configuration#isSessionRenewedOnAuthentication()
     */
    public void setSessionRenewedOnAuthentication(boolean renew)
    {
        _renewSessionOnAuthentication = renew;
    }

    @Override
    public int getSessionMaxInactiveIntervalOnAuthentication()
    {
        return _sessionMaxInactiveIntervalOnAuthentication;
    }

    /**
     * Set the interval in seconds, which if non-zero, will be set with
     * {@link org.eclipse.jetty.server.Session#setMaxInactiveInterval(int)}
     * when a session is newly authenticated.
     * @param seconds An interval in seconds; or 0 to not set the interval
     *                on authentication; or a negative number to make the
     *                session never timeout after authentication.
     * @see Configuration#getSessionMaxInactiveIntervalOnAuthentication()
     */
    public void setSessionMaxInactiveIntervalOnAuthentication(int seconds)
    {
        _sessionMaxInactiveIntervalOnAuthentication = seconds;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        // Skip security check if this is a dispatch rather than a fresh request
        if (request.getContext().isCrossContextDispatch(request))
            return next.handle(request, response, callback);

        String pathInContext = Request.getPathInContext(request);
        Constraint constraint = getConstraint(pathInContext, request);
        if (LOG.isDebugEnabled())
            LOG.debug("getConstraint({}) -> {}", pathInContext, constraint);

        if (constraint == null)
            constraint = Constraint.ALLOWED;

        if (constraint.getAuthorization() == Authorization.FORBIDDEN)
        {
            doWriteError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return true;
        }

        // Check data constraints
        if (Transport.SECURE.equals(constraint.getTransport()) && !request.isSecure())
        {
            redirectToSecure(request, response, callback);
            return true;
        }

        // Determine Constraint.Authentication
        Authorization constraintAuthorization = constraint.getAuthorization();
        constraintAuthorization = _authenticator.getConstraintAuthentication(pathInContext, constraintAuthorization, request::getSession);
        if (constraintAuthorization == Authorization.INHERIT)
            constraintAuthorization = Authorization.ALLOWED;
        if (LOG.isDebugEnabled())
            LOG.debug("constraintAuthorization {}", constraintAuthorization);
        boolean mustValidate = constraintAuthorization != Authorization.ALLOWED;

        try
        {
            AuthenticationState authenticationState = mustValidate ? _authenticator.validateRequest(request, response, callback) : null;

            if (LOG.isDebugEnabled())
                LOG.debug("AuthenticationState {}", authenticationState);

            if (authenticationState instanceof AuthenticationState.ResponseSent)
                return true;

            if (authenticationState instanceof AuthenticationState.ServeAs serveAs)
            {
                response = serveAsWrap(request, response, serveAs);
                request = response.getRequest();
                authenticationState = _deferred;
            }
            else if (mustValidate && !isAuthorized(constraint, authenticationState))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("!authorized {}", authenticationState);
                return doWriteError(request, response, callback, HttpStatus.FORBIDDEN_403);
            }
            else if (authenticationState == null)
            {
                authenticationState = _deferred;
            }

            AuthenticationState.setAuthenticationState(request, authenticationState);
            IdentityService.Association association =
                (authenticationState instanceof AuthenticationState.Succeeded user)
                ? _identityService.associate(user.getUserIdentity(), null) : null;

            try
            {
                //process the request by other handlers
                return next.handle(_authenticator.prepareRequest(request, authenticationState), response, callback);
            }
            finally
            {
                if (association == null && authenticationState instanceof AuthenticationState.Deferred deferred)
                    association = deferred.getAssociation();
                if (association != null)
                    association.close();
            }
        }
        catch (ServerAuthException e)
        {
            Response.writeError(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500, e.getMessage());
            return true;
        }
    }

    private boolean doWriteError(Request request, Response response, Callback callback, int status)
    {
        AuthenticationState authenticationState = AuthenticationState.writeError(request, response, callback, status);
        if (authenticationState instanceof AuthenticationState.ServeAs serveAs)
        {
            response = serveAsWrap(request, response, serveAs);
            request = response.getRequest();
            authenticationState = _deferred;

            AuthenticationState.setAuthenticationState(request, authenticationState);
            IdentityService.Association association =
                (authenticationState instanceof AuthenticationState.Succeeded user)
                    ? _identityService.associate(user.getUserIdentity(), null) : null;

            try
            {
                //process the request by other handlers
                return getHandler().handle(_authenticator.prepareRequest(request, authenticationState), response, callback);
            }
            catch (Throwable t)
            {
                Response.writeError(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500, t.getMessage());
            }
            finally
            {
                if (association == null && authenticationState instanceof AuthenticationState.Deferred deferred)
                    association = deferred.getAssociation();
                if (association != null)
                    association.close();
            }
        }

        return true;
    }

    private Response serveAsWrap(Request request, Response response, AuthenticationState.ServeAs serveAs)
    {
        Request wrappedRequest = serveAs.wrap(request);
        HttpFields.Mutable headers = response.getHeaders();
        if (!request.getHttpURI().equals(wrappedRequest.getHttpURI()))
        {
            // URI is replaced, so filter out all metadata for the old URI
            response.getHeaders().put(HttpHeader.CACHE_CONTROL.asString(), HttpHeaderValue.NO_CACHE.asString());
            response.getHeaders().putDate(HttpHeader.EXPIRES.asString(), 1);
            headers = new HttpFields.Mutable.Wrapper(headers)
            {
                @Override
                public HttpField onAddField(HttpField field)
                {
                    if (field.getHeader() == null)
                        return field;
                    return switch (field.getHeader())
                    {
                        case CACHE_CONTROL, PRAGMA, ETAG, EXPIRES, LAST_MODIFIED, AGE -> null;
                        default -> field;
                    };
                }
            };
        }

        HttpFields.Mutable finalHeaders = headers;
        return new Response.Wrapper(wrappedRequest, response)
        {
            @Override
            public HttpFields.Mutable getHeaders()
            {
                return finalHeaders;
            }

            @Override
            public Request getRequest()
            {
                return wrappedRequest;
            }
        };
    }

    public static SecurityHandler getCurrentSecurityHandler()
    {
        ContextHandler contextHandler = ContextHandler.getCurrentContextHandler();
        if (contextHandler != null)
            return contextHandler.getDescendant(SecurityHandler.class);
        return null;
    }

    protected abstract Constraint getConstraint(String pathInContext, Request request);

    protected void redirectToSecure(Request request, Response response, Callback callback)
    {
        HttpConfiguration httpConfig = request.getConnectionMetaData().getHttpConfiguration();
        if (httpConfig.getSecurePort() > 0)
        {
            //Redirect to secure port
            String scheme = httpConfig.getSecureScheme();
            int port = httpConfig.getSecurePort();

            String url = URIUtil.newURI(scheme, Request.getServerName(request), port, request.getHttpURI().getPath(), request.getHttpURI().getQuery());
            response.getHeaders().put(HttpFields.CONTENT_LENGTH_0);

            Response.sendRedirect(request, response, callback, HttpStatus.MOVED_TEMPORARILY_302, url, true);
        }
        else
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "!Secure");
        }
    }

    protected boolean isAuthorized(Constraint constraint, AuthenticationState authenticationState)
    {
        UserIdentity userIdentity = authenticationState instanceof AuthenticationState.Succeeded user ? user.getUserIdentity() : null;
        return switch (constraint.getAuthorization())
        {
            case FORBIDDEN, ALLOWED, INHERIT -> true;
            case ANY_USER -> userIdentity != null && userIdentity.getUserPrincipal() != null;
            case KNOWN_ROLE ->
            {
                if (userIdentity != null && userIdentity.getUserPrincipal() != null)
                    for (String role : getKnownRoles())
                        if (userIdentity.isUserInRole(role))
                            yield true;
                yield false;
            }

            case SPECIFIC_ROLE ->
            {
                if (userIdentity != null && userIdentity.getUserPrincipal() != null)
                    for (String role : constraint.getRoles())
                        if (userIdentity.isUserInRole(role))
                            yield true;
                yield false;
            }
        };
    }

    protected Set<String> getKnownRoles()
    {
        return Collections.emptySet();
    }

    private static int compareMappedResources(MappedResource<?> mr1, MappedResource<?> mr2)
    {
        PathSpecGroup g1 = mr1.getPathSpec().getGroup();
        PathSpecGroup g2 = mr2.getPathSpec().getGroup();
        int l1 = mr1.getPathSpec().getSpecLength();
        int l2 = mr2.getPathSpec().getSpecLength();
        if (g1.equals(g2))
            return Integer.compare(l1, l2);
        return Integer.compare(pathSpecGroupOrder(g1), pathSpecGroupOrder(g2));
    }

    private static int pathSpecGroupOrder(PathSpecGroup group)
    {
        return switch (group)
        {
            case EXACT -> 5;
            case ROOT -> 4;
            case SUFFIX_GLOB -> 3;
            case MIDDLE_GLOB -> 2;
            case PREFIX_GLOB -> 1;
            case DEFAULT -> 0;
        };
    }

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

    /**
     * <p>A concrete implementation of {@link SecurityHandler} that uses a {@link PathMappings} to
     * match request to a list of {@link Constraint}s, which are applied in the order of
     * least significant to most significant.
     * <p>
     * An example of using this class is:
     * <pre>{@code
     * SecurityHandler.PathMapped handler = new SecurityHandler.PathMapped();
     * handler.put("/*", Constraint.combine(Constraint.FORBIDDEN, Constraint.SECURE_TRANSPORT));
     * handler.put("", Constraint.ALLOWED);
     * handler.put("/login", Constraint.ALLOWED);
     * handler.put("*.png", Constraint.ANY_TRANSPORT);
     * handler.put("/admin/*", Constraint.from("admin", "operator"));
     * handler.put("/admin/super/*", Constraint.from("operator"));
     * handler.put("/user/*", Constraint.ANY_USER);
     * handler.put("*.xml", Constraint.FORBIDDEN);
     * }</pre>
     * <p>
     * When {@link #getConstraint(String, Request)} is called, any matching
     * constraints are sorted into least to most significant with
     * {@link #compare(PathSpec, PathSpec)}, resulting in the order in which
     * {@link Constraint#combine(Constraint, Constraint)} will be applied.
     * For example:
     * </p>
     * <ul>
     *   <li>{@code "/admin/index.html"} matches {@code "/*"} and {@code "/admin/*"}, resulting in a
     *       constraint of {@link Authorization#SPECIFIC_ROLE} and {@link Transport#SECURE}.</li>
     *   <li>{@code "/admin/logo.png"} matches {@code "/*"}, {@code "/admin/*"} and {@code "*.png"}, resulting in a
     *       constraint of {@link Authorization#SPECIFIC_ROLE} and {@link Transport#ANY}.</li>
     *   <li>{@code "/admin/config.xml"} matches {@code "/*"}, {@code "/admin/*"} and {@code "*.xml"}, resulting in a
     *       constraint of {@link Authorization#FORBIDDEN} and {@link Transport#SECURE}.</li>
     *   <li>{@code "/admin/super/index.html"} matches {@code "/*"}, {@code "/admin/*"} and {@code "/admin/super/*"},
     *       resulting in a constraint of {@link Authorization#SPECIFIC_ROLE} and {@link Transport#SECURE}.</li>
     * </ul>
     * <p>If there is no match for the request path, then the constraint is assumed to be {@link Constraint#ALLOWED}.</p>
     * <p>It is therefore good practice to always explicitly configure a constraint for path {@code /*} or {@code /}.</p>
     */
    public static class PathMapped extends SecurityHandler implements Comparator<PathSpec>
    {
        private final PathMappings<Constraint> _mappings = new PathMappings<>();
        private final Set<String> _knownRoles = new HashSet<>();

        public PathMapped()
        {
            this(null);
        }

        public PathMapped(Handler handler)
        {
            super(handler);
        }

        /**
         * <p>Associates the specified request path pattern with the specified {@link Constraint}.</p>
         *
         * @param pathSpec the request path pattern to match
         * @param constraint the associated {@link Constraint}
         * @return the previous {@link Constraint} associated with the request path pattern,
         * or {@code null} if there was no previous association
         */
        public Constraint put(String pathSpec, Constraint constraint)
        {
            return put(PathSpec.from(pathSpec), constraint);
        }

        /**
         * <p>Associates the specified request path pattern with the specified {@link Constraint}.</p>
         *
         * @param pathSpec the request path pattern to match
         * @param constraint the associated {@link Constraint}
         * @return the previous {@link Constraint} associated with the request path pattern,
         * or {@code null} if there was no previous association
         */
        public Constraint put(PathSpec pathSpec, Constraint constraint)
        {
            Set<String> roles = constraint.getRoles();
            if (roles != null)
                _knownRoles.addAll(roles);
            return _mappings.put(pathSpec, constraint);
        }

        public Constraint get(PathSpec pathSpec)
        {
            return _mappings.get(pathSpec);
        }

        public Constraint remove(PathSpec pathSpec)
        {
            Constraint removed = _mappings.remove(pathSpec);
            _knownRoles.clear();
            _mappings.values().forEach(c ->
            {
                Set<String> roles = c.getRoles();
                if (roles != null)
                    _knownRoles.addAll(roles);

            });
            return removed;
        }

        @Override
        protected Constraint getConstraint(String pathInContext, Request request)
        {
            List<MappedResource<Constraint>> matches = _mappings.getMatches(pathInContext);
            if (matches == null || matches.isEmpty())
                return null;

            if (matches.size() == 1)
                return matches.get(0).getResource();

            // apply from least specific to most specific
            matches.sort(this::compare);
            if (LOG.isDebugEnabled())
                LOG.debug("getConstraint {} -> {}", pathInContext, matches);
            Constraint constraint = null;
            for (MappedResource<Constraint> c : matches)
                constraint = Constraint.combine(constraint, c.getResource());

            return constraint;
        }

        /**
         * {@link Comparator} method to sort paths from least specific to most specific. Using
         * the {@link #pathSpecGroupPrecedence(PathSpecGroup)} to rank different groups and
         * {@link PathSpec#getSpecLength()} to rank within a group.  This method may be overridden
         * to provide different precedence between constraints.
         * @param ps1 the first {@code PathSpec} to be compared.
         * @param ps2 the second {@code PathSpec} to be compared.
         * @return -1, 0 or 1
         */
        @Override
        public int compare(PathSpec ps1, PathSpec ps2)
        {
            PathSpecGroup g1 = ps1.getGroup();
            PathSpecGroup g2 = ps2.getGroup();
            if (g1.equals(g2))
                return Integer.compare(ps1.getSpecLength(), ps2.getSpecLength());

            return Integer.compare(pathSpecGroupPrecedence(g1), pathSpecGroupPrecedence(g2));
        }

        int compare(MappedResource<Constraint> c1, MappedResource<Constraint> c2)
        {
            return compareMappedResources(c1, c2);
        }

        /**
         * Get the relative precedence of a {@link PathSpecGroup} used by {@link #compare(MappedResource, MappedResource)}
         * to sort {@link Constraint}s.  The precedence from most significant to least is:
         * <ul>
         *     <li>{@link PathSpecGroup#EXACT}</li>
         *     <li>{@link PathSpecGroup#ROOT}</li>
         *     <li>{@link PathSpecGroup#SUFFIX_GLOB}</li>
         *     <li>{@link PathSpecGroup#MIDDLE_GLOB}</li>
         *     <li>{@link PathSpecGroup#PREFIX_GLOB}</li>
         *     <li>{@link PathSpecGroup#DEFAULT}</li>
         * </ul>
         * @param group The group to rank.
         * @return An integer representing relative precedence between {@link PathSpecGroup}s.
         */
        protected int pathSpecGroupPrecedence(PathSpecGroup group)
        {
            return pathSpecGroupOrder(group);
        }

        @Override
        protected Set<String> getKnownRoles()
        {
            return _knownRoles;
        }
    }

    /**
     * <p>A concrete implementation of {@link SecurityHandler} that uses a {@link PathMappings}
     * to match request paths to a map of an HTTP method to a {@link Constraint}.</p>
     * <p>The token {@code *} is used to indicate all HTTP methods.</p>
     * <p>Request path matches are sorted from the least significant to the most significant,
     * and the associated constraints are combined in order.</p>
     * <p>For example:</p>
     * <pre>{@code
     * SecurityHandler.PathMethodMapped handler = new SecurityHandler.PathMethodMapped();
     * handler.put(PathSpec.from("/*"), "*", Constraint.combine(Constraint.FORBIDDEN, Constraint.SECURE_TRANSPORT));
     * handler.put(PathSpec.from("/releases/*"), "GET", Constraint.from("read"));
     * handler.put(PathSpec.from("/releases/*"), "PUT", Constraint.from("write"));
     * }</pre>
     * <p>For these request paths:</p>
     * <ul>
     *   <li>{@code /foo} matches {@code /*};
     *   any HTTP method results in a constraint with {@link Authorization#FORBIDDEN} and {@link Transport#SECURE}</li>
     *   <li>{@code /releases/jetty-12.1.0.tar.gz} matches both {@code /*} and {@code /releases/*};
     *   method {@code GET} results in a constraint with {@link Authorization#SPECIFIC_ROLE} with role {@code read}
     *   and {@link Transport#SECURE};
     *   method {@code PUT} results in a constraint with {@link Authorization#SPECIFIC_ROLE} with role {@code write}
     *   and {@link Transport#SECURE};
     *   any other HTTP method results in a constraint with {@link Authorization#FORBIDDEN} and {@link Transport#SECURE}</li>
     * </ul>
     * <p>If there is no match for the request path, then the constraint is assumed to be {@link Constraint#ALLOWED}.</p>
     * <p>If there is no match for the request URI, or no match for the HTTP method, then the constraint is assumed
     * to be {@link Constraint#ALLOWED}.</p>
     * <p>It is therefore good practice to always explicitly configure a constraint for path {@code /*} or {@code /} 
     * and HTTP method {@code *}.</p>
     */
    public static class PathMethodMapped extends SecurityHandler
    {
        private static final String ALL_METHODS = "*";

        private final PathMappings<Map<String, Constraint>> _constraints = new PathMappings<>();
        private final Set<String> _knownRoles = new HashSet<>();

        public PathMethodMapped()
        {
            this(null);
        }

        public PathMethodMapped(Handler handler)
        {
            super(handler);
        }

        /**
         * <p>Associates the given {@link Constraint} to the given request path patten and HTTP method.</p>
         *
         * @param pathSpec the {@link PathSpec} associated to the given constraint
         * @param method the HTTP method associated to the given constraint, or {@code null} or {@code *}
         * to indicate all HTTP methods
         * @param constraint the constraint to associate
         * @return the previous constraint associated with the given path and HTTP method,
         * or {@code null} is there was no association
         */
        public Constraint put(String pathSpec, String method, Constraint constraint)
        {
            return put(PathSpec.from(pathSpec), method, constraint);
        }

        /**
         * <p>Associates the given {@link Constraint} to the given request path pattern and HTTP method.</p>

         * @param pathSpec the {@link PathSpec} associated to the given constraint
         * @param method the HTTP method associated to the given constraint, or {@code null} or {@code *}
         * to indicate all HTTP methods
         * @param constraint the constraint to associate
         * @return the previous constraint associated with the given path and HTTP method,
         * or {@code null} is there was no association
         */
        public Constraint put(PathSpec pathSpec, String method, Constraint constraint)
        {
            Objects.requireNonNull(pathSpec);
            if (method == null)
                method = ALL_METHODS;
            Objects.requireNonNull(constraint);
            Map<String, Constraint> methodConstraints = _constraints.computeIfAbsent(pathSpec, k -> new HashMap<>());
            Constraint result = methodConstraints.put(method, constraint);
            if (result != null)
                recomputeKnownRoles();
            else
                _knownRoles.addAll(constraint.getRoles());
            return result;
        }

        /**
         * <p>Associates the given {@link Constraint} to the given request path pattern and HTTP methods.</p>
         *
         * @param pathSpec the {@link PathSpec} associated to the given constraint
         * @param methods the list of HTTP methods associated to the given constraint
         * @param constraint the constraint to associate
         */
        public void put(PathSpec pathSpec, List<String> methods, Constraint constraint)
        {
            if (methods.isEmpty() || methods.contains(ALL_METHODS))
                throw new IllegalArgumentException("Invalid method list");
            methods.forEach(method -> put(pathSpec, method, constraint));
        }

        @Override
        protected Constraint getConstraint(String pathInContext, Request request)
        {
            List<MappedResource<Map<String, Constraint>>> matches = _constraints.getMatches(pathInContext);

            if (matches == null || matches.isEmpty())
                return Constraint.ALLOWED;

            // Sort from least specific to most specific to combine constraints properly.
            if (matches.size() > 1)
                matches.sort(SecurityHandler::compareMappedResources);

            String method = request.getMethod();

            Constraint result = null;
            for (MappedResource<Map<String, Constraint>> match : matches)
            {
                Map<String, Constraint> methodConstraints = match.getResource();

                // A constraint for all HTTP methods may be used to establish
                // defaults such as Constraint.SECURE_TRANSPORT, so always
                // combine it with the Constraint for the specific HTTP method.
                Constraint allMethodsConstraint = methodConstraints.get(ALL_METHODS);
                Constraint specificMethodConstraint = methodConstraints.get(method);
                Constraint constraint = Constraint.combine(allMethodsConstraint, specificMethodConstraint);

                // Combine the constraints from all URI matches.
                result = Constraint.combine(result, constraint);
            }

            return result;
        }

        private void recomputeKnownRoles()
        {
            _knownRoles.clear();
            for (Map<String, Constraint> m : _constraints.values())
            {
                for (Constraint c : m.values())
                {
                    _knownRoles.addAll(c.getRoles());
                }
            }
        }

        @Override
        protected Set<String> getKnownRoles()
        {
            return _knownRoles;
        }
    }
}
