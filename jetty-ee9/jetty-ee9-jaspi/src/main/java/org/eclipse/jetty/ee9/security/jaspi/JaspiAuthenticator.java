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

package org.eclipse.jetty.ee9.security.jaspi;

import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.security.auth.Subject;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee9.nested.Authentication;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.ee9.nested.ServletPathMapping;
import org.eclipse.jetty.ee9.security.Authenticator;
import org.eclipse.jetty.ee9.security.ServerAuthException;
import org.eclipse.jetty.ee9.security.UserAuthentication;
import org.eclipse.jetty.ee9.security.WrappedAuthConfiguration;
import org.eclipse.jetty.ee9.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.ee9.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.EmptyLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.ee9.security.jaspi.JaspiAuthenticatorFactory.MESSAGE_LAYER;

/**
 * Implementation of Jetty {@link LoginAuthenticator} that is a bridge from Jakarta Authentication to Jetty Security.
 */
public class JaspiAuthenticator extends LoginAuthenticator
{
    static final Principal UNAUTHENTICATED = () -> null;

    /**
     * Set once the module has been asked about the request, so that a deferred authentication
     * during the dispatch is answered from what that call established rather than by asking the
     * module a second time.
     */
    private static final String VALIDATED_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".validated";

    private static final String VALIDATE_REQUEST_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".validateRequest";

    private static final String CLEAN_SUBJECT_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".cleanSubject";

    /**
     * The container authentication session: the results a module asked to have remembered with
     * {@link JaspiMessageInfo#REGISTER_SESSION_KEY}, held in the {@link HttpSession} of the request.
     */
    private static final String AUTHENTICATION_SESSION_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".authenticationSession";

    private static final Logger LOG = LoggerFactory.getLogger(JaspiAuthenticator.class);

    private final Subject _serviceSubject;
    private final List<String> _appContexts;
    private final boolean _allowLazyAuthentication;
    private final Authenticator _declared;
    private final ContextHandler _contextHandler;
    private final AuthConfigFactory _authConfigFactory = AuthConfigFactory.getFactory();
    // Incremented by whichever thread registers or removes a provider, or configures this authenticator, which is how a cached ServerAuthConfig is invalidated.
    private final AtomicLong _registrations = new AtomicLong();
    private final RegistrationListener _registrationListener = (layer, appContext) -> _registrations.incrementAndGet();
    private Map<String, Object> _authProperties;
    private ServletCallbackHandler _callbackHandler;
    private volatile CachedAuthConfig _cachedAuthConfig;
    // Set once the context is stopping, so that a resolution in progress cannot attach the listener the stop has detached.
    private volatile boolean _stopping;

    /**
     * @param serviceSubject the Subject the module is given as the service Subject, or null
     * @param appContexts the application context identifiers this context answers to, most
     * preferred first, as {@link JaspiAuthenticatorFactory#getAppContexts(String, String, String)} gives them
     * @param contextHandler the context being authenticated, or null if it is not known
     * @param declared the {@link Authenticator} of the declared auth-method, which serves the
     * requests the Servlet Container Profile leaves to constraint processing, or null if the
     * context declared none that can be used
     */
    public JaspiAuthenticator(Subject serviceSubject, List<String> appContexts, ContextHandler contextHandler, Authenticator declared)
    {
        this(serviceSubject, appContexts, true, contextHandler, declared);
    }

    /**
     * @param serviceSubject the Subject the module is given as the service Subject, or null
     * @param appContexts the application context identifiers this context answers to, most
     * preferred first, as {@link JaspiAuthenticatorFactory#getAppContexts(String, String, String)} gives them
     * @param allowLazyAuthentication whether the module may be asked about a request that no
     * constraint requires to be authenticated
     * @param contextHandler the context being authenticated, or null if it is not known
     * @param declared the {@link Authenticator} of the declared auth-method, which serves the
     * requests the Servlet Container Profile leaves to constraint processing, or null if the
     * context declared none that can be used
     * @deprecated use {@link #JaspiAuthenticator(Subject, List, ContextHandler, Authenticator)}, lazy authentication is always allowed.
     */
    @Deprecated(since = "13.0.0", forRemoval = true)
    public JaspiAuthenticator(Subject serviceSubject, List<String> appContexts, boolean allowLazyAuthentication, ContextHandler contextHandler, Authenticator declared)
    {
        _serviceSubject = serviceSubject;
        _appContexts = appContexts;
        _allowLazyAuthentication = allowLazyAuthentication;
        _declared = declared;
        _contextHandler = contextHandler;
    }

    /**
     * The {@link AuthConfigFactory} is process wide, so an attachment left behind holds this
     * authenticator and everything it reached for good.
     */
    private void detachWhenContextStops()
    {
        if (_contextHandler == null)
        {
            LOG.warn("No ContextHandler for {}, its RegistrationListener will not be detached", _appContexts);
            return;
        }

        // On stopping rather than stopped: a context discards the listeners added since it started before it reaches stopped.
        _contextHandler.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStopping(LifeCycle event)
            {
                _stopping = true;
                _contextHandler.removeEventListener(this);
                _authConfigFactory.detachListener(_registrationListener, MESSAGE_LAYER, null);
            }
        });
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        // The SecurityHandler keeps its Authenticator across a stop and calls this on every start, so the listener the stop discarded is added again here.
        _stopping = false;
        detachWhenContextStops();

        if (_declared != null)
            _declared.setConfiguration(configuration);

        LoginService loginService = configuration.getLoginService();
        if (loginService == null)
        {
            // Add an empty login service so we can use JASPI without tying into Jetty auth mechanisms.
            configuration = new JaspiAuthConfiguration(configuration);
            loginService = configuration.getLoginService();
        }

        super.setConfiguration(configuration);

        _callbackHandler = new ServletCallbackHandler(loginService);
        _authProperties = new HashMap<>();
        for (String key : configuration.getInitParameterNames())
        {
            _authProperties.put(key, configuration.getInitParameter(key));
        }

        // Invalidated after the new handler and properties are in place, so a resolution in progress across a restart cannot leave the stopped generation's config cached.
        _registrations.incrementAndGet();
    }

    /**
     * The {@link ServerAuthConfig} of the first application context identifier of this context that
     * a provider is registered for. The listener is left attached to every identifier tried while
     * the context is running, so a provider registered later for any of them has the identifiers
     * resolved again.
     * <p>The result, including having found none, is cached against the registration count it was
     * resolved under, so a registration made while the provider was being asked is not undone by
     * the result arriving after it.</p>
     */
    private ServerAuthConfig getAuthConfig() throws AuthException
    {
        long registrations = _registrations.get();
        CachedAuthConfig cached = _cachedAuthConfig;
        if (cached != null && cached.registrations() == registrations)
            return cached.authConfig();

        ServerAuthConfig authConfig = null;
        for (String appContext : _appContexts)
        {
            AuthConfigProvider authConfigProvider = _authConfigFactory.getConfigProvider(MESSAGE_LAYER, appContext, _stopping ? null : _registrationListener);
            if (authConfigProvider != null)
            {
                authConfig = authConfigProvider.getServerAuthConfig(MESSAGE_LAYER, appContext, _callbackHandler);
                break;
            }
        }

        _cachedAuthConfig = new CachedAuthConfig(registrations, authConfig);
        return authConfig;
    }

    /**
     * <p>The {@link Authenticator} of the declared auth-method while no {@link AuthConfigProvider}
     * is registered for this context, or null while one is or if the context declared none.</p>
     * <p>The Servlet Container Profile has a null provider leave the request to constraint
     * processing "without further reliance on this profile", which for a context that declares an
     * auth-method is that mechanism. A provider registered later takes the next request from it.</p>
     */
    private Authenticator getDeclaredAuthenticator()
    {
        if (_declared == null)
            return null;

        try
        {
            return getAuthConfig() == null ? _declared : null;
        }
        catch (AuthException e)
        {
            // A provider is registered but could not be asked for its configuration, so the request is the module's and validateRequest reports the failure.
            return null;
        }
    }

    /**
     * The declared auth-method while it is standing in, so that a context declaring one is still
     * known by it, and {@code JASPI} otherwise.
     */
    @Override
    public String getAuthMethod()
    {
        return _declared == null ? "JASPI" : _declared.getAuthMethod();
    }

    @Override
    public void prepareRequest(ServletRequest request)
    {
        Authenticator declared = getDeclaredAuthenticator();
        if (declared == null)
            super.prepareRequest(request);
        else
            declared.prepareRequest(request);
    }

    @Override
    public UserIdentity login(String username, Object password, ServletRequest request)
    {
        return getDeclaredAuthenticator() instanceof LoginAuthenticator declared
            ? declared.login(username, password, request)
            : super.login(username, password, request);
    }

    @Override
    public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
    {
        Authenticator declared = getDeclaredAuthenticator();
        if (declared != null)
        {
            Authentication authentication = declared.validateRequest(request, response, mandatory);
            // A DeferredAuthentication on the declared Authenticator would take a later login() or logout() past the container authentication session held here.
            return authentication instanceof DeferredAuthentication ? new DeferredAuthentication(this) : authentication;
        }

        // The profile defines a second validateRequest only for authenticate() (a487), which arrives with the real response, so the answer from before the dispatch stands.
        boolean validated = request.getAttribute(VALIDATED_ATTRIBUTE) != null;
        if (validated &&
            response instanceof HttpServletResponse httpServletResponse &&
            DeferredAuthentication.isDeferred(httpServletResponse))
            return Authentication.UNAUTHENTICATED;

        // The profile requires that a call the module makes back into the container while validating does not re-enter the module.
        if (request.getAttribute(VALIDATE_REQUEST_ATTRIBUTE) != null)
            return Authentication.UNAUTHENTICATED;
        request.setAttribute(VALIDATE_REQUEST_ATTRIBUTE, Boolean.TRUE);

        try
        {
            JaspiMessageInfo info = new JaspiMessageInfo(request, response, mandatory);

            // Already validated with the real response is authenticate(), whose message the profile requires to carry the authentication request key as well.
            info.setAuthenticationRequest(validated);

            Authentication a = validateRequest(info, mandatory, !validated);

            if (_allowLazyAuthentication && !mandatory)
            {
                if (a == Authentication.UNAUTHENTICATED)
                    a = new DeferredAuthentication(this);
                else if (a instanceof JaspiUnauthenticated unauthenticated)
                    a = new JaspiDeferredAuthentication(this, unauthenticated);
            }
            return a;
        }
        finally
        {
            request.removeAttribute(VALIDATE_REQUEST_ATTRIBUTE);
        }
    }

    /**
     * @param messageInfo the message for this call.
     * @param mandatory whether the constraints on the resource require authentication.
     * @param keepMessageInfo whether this is the validation of the request before the dispatch,
     * whose MessageInfo is the one the Servlet Container Profile requires {@code secureResponse}
     * to be given.
     */
    private Authentication validateRequest(JaspiMessageInfo messageInfo, boolean mandatory, boolean keepMessageInfo) throws ServerAuthException
    {
        try
        {
            // With no AuthConfigProvider the profile requires the request served by servlet constraint processing alone (a392), applied here from the mandatory flag.
            // a396 requires getAuthContext to be non-null, so a null ServerAuthContext is a provider violation the profile does not cover, taken the same way.
            ServerAuthConfig authConfig = getAuthConfig();
            ServerAuthContext authContext = authConfig == null ? null
                : authConfig.getAuthContext(authConfig.getAuthContextID(messageInfo), _serviceSubject, _authProperties);
            if (authContext == null)
            {
                if (!mandatory)
                    return Authentication.UNAUTHENTICATED;
                ((HttpServletResponse)messageInfo.getResponseMessage()).sendError(HttpServletResponse.SC_FORBIDDEN);
                return Authentication.SEND_FAILURE;
            }

            Subject clientSubject = new Subject();
            Object requestMessage = messageInfo.getRequestMessage();
            Object responseMessage = messageInfo.getResponseMessage();
            Request baseRequest = Request.getBaseRequest((ServletRequest)requestMessage);
            UserAuthentication registered = getAuthenticationSession(baseRequest);
            ((HttpServletRequest)requestMessage).setAttribute(VALIDATED_ATTRIBUTE, Boolean.TRUE);
            AuthStatus authStatus = validateRequest(authContext, messageInfo, clientSubject, baseRequest, registered);

            if (authStatus == AuthStatus.SEND_CONTINUE)
                return Authentication.SEND_CONTINUE;
            if (authStatus == AuthStatus.SEND_FAILURE)
                return Authentication.SEND_FAILURE;

            if (authStatus == AuthStatus.SUCCESS)
            {
                // Point (3) of the message processing model: the response is secured as it completes, on the ServerAuthContext and MessageInfo this task holds.
                if (keepMessageInfo && baseRequest != null)
                    baseRequest.getHttpChannel().setSecureResponseTask(inDispatchScope(baseRequest, () -> secureResponseAtCompletion(authContext, messageInfo)));

                // Build the identity from the callbacks the module has already issued.
                JaspiUserIdentity userIdentity = ServletCallbackHandler.getUserIdentity(clientSubject);
                Principal callerPrincipal = userIdentity == null ? null : userIdentity.getUserPrincipal();
                // A module returning SUCCESS with no CallerPrincipalCallback is not compliant (a442), so the request is treated as having named the unauthenticated caller.
                if (callerPrincipal == null || callerPrincipal == UNAUTHENTICATED)
                {
                    // No caller is in any role, so the profile has the runtime set the Servlet status, not dispatch the request, and still secure the response.
                    if (mandatory && keepMessageInfo)
                    {
                        ((HttpServletResponse)messageInfo.getResponseMessage()).sendError(HttpServletResponse.SC_FORBIDDEN);
                        return Authentication.SEND_FAILURE;
                    }

                    // The profile attaches no caller condition to the messages the module established, so an unauthenticated request carries them too.
                    if (messageInfo.getRequestMessage() != requestMessage || messageInfo.getResponseMessage() != responseMessage)
                    {
                        return new JaspiUnauthenticated(
                            (HttpServletRequest)messageInfo.getRequestMessage(),
                            (HttpServletResponse)messageInfo.getResponseMessage());
                    }
                    return Authentication.UNAUTHENTICATED;
                }

                // The container authentication session holds the authType named at registration, which a module continuing the session without naming one keeps.
                boolean continuesRegisteredSession = continuesRegisteredSession(registered, userIdentity);
                String authMethod = messageInfo.getAuthenticationType();
                if (authMethod == null && continuesRegisteredSession)
                    authMethod = registered.getAuthMethod();
                if (authMethod == null)
                    authMethod = getAuthMethod();

                UserIdentity identity = continuesRegisteredSession ? registered.getUserIdentity() : userIdentity;

                // Later requests have messages of their own, so the registered results never carry the messages the module replaced on this one.
                UserAuthentication authentication = new UserAuthentication(authMethod, identity);
                if (messageInfo.isRegisterSession())
                    registerAuthenticationSession(baseRequest, authentication);

                // The profile allows the module to replace either message, and the resource is invoked with the replacements.
                if (messageInfo.getRequestMessage() != requestMessage || messageInfo.getResponseMessage() != responseMessage)
                {
                    return new JaspiAuthentication(authMethod, identity,
                        (HttpServletRequest)messageInfo.getRequestMessage(),
                        (HttpServletResponse)messageInfo.getResponseMessage());
                }
                return authentication;
            }
            if (authStatus == AuthStatus.SEND_SUCCESS)
            {
                // we are processing a message in a secureResponse dialog.
                return Authentication.SEND_SUCCESS;
            }
            if (authStatus == AuthStatus.FAILURE)
            {
                // The profile does not define FAILURE for validateRequest, so the runtime answers with its own status rather than the module's response.
                HttpServletResponse response = (HttpServletResponse)messageInfo.getResponseMessage();
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return Authentication.SEND_FAILURE;
            }
            // should not happen
            throw new IllegalStateException("No AuthStatus returned");
        }
        catch (IOException | AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

    /**
     * <p>Whether this request continues the registered authentication session, which a module does
     * by naming its caller: the identity that session holds, roles included, is then restored.</p>
     * <p>The caller is matched by name, as a module may name it with either the {@link Principal}
     * the container restored or the name it read from that Principal. Two principals with no name
     * are not the same caller, so a null name matches nothing. A module that established group
     * principals of its own does not continue the session, as the authorization of this request is
     * decided by the identity its callbacks established.</p>
     */
    private static boolean continuesRegisteredSession(UserAuthentication registered, JaspiUserIdentity userIdentity)
    {
        if (registered == null || userIdentity.hasEstablishedGroups())
            return false;
        String name = userIdentity.getUserPrincipal().getName();
        return name != null && name.equals(registered.getUserIdentity().getUserPrincipal().getName());
    }

    /**
     * <p>Ask the module to validate the request, with any container authentication session
     * established as the authentication of the request first.</p>
     * <p>The Servlet Container Profile requires {@code validateRequest} for all requests the
     * Servlet security model applies to (a418), which excludes a forward or include through a
     * {@code RequestDispatcher}, so the session is not used in place of the call: the module reads
     * it with {@code getUserPrincipal} and decides for itself whether to continue it, and the
     * results of this call are the ones that stand.</p>
     */
    private AuthStatus validateRequest(ServerAuthContext authContext, JaspiMessageInfo messageInfo, Subject clientSubject,
                                       Request baseRequest, UserAuthentication registered) throws AuthException
    {
        if (registered == null)
            return authContext.validateRequest(messageInfo, clientSubject, _serviceSubject);

        Authentication previous = baseRequest.getAuthentication();
        baseRequest.setAuthentication(registered);
        try
        {
            return authContext.validateRequest(messageInfo, clientSubject, _serviceSubject);
        }
        finally
        {
            baseRequest.setAuthentication(previous);
        }
    }

    /**
     * @param baseRequest the request, or null if it has none.
     * @return the authentication results registered for the session of this request, or null if
     * there are none or they could not be restored.
     */
    private static UserAuthentication getAuthenticationSession(Request baseRequest)
    {
        HttpSession session = baseRequest == null ? null : baseRequest.getSession(false);
        if (session == null || !(session.getAttribute(AUTHENTICATION_SESSION_ATTRIBUTE) instanceof UserAuthentication registered))
            return null;
        // A persisted session cannot carry the identity a module established, so the registration does not survive it.
        UserIdentity userIdentity = registered.getUserIdentity();
        return userIdentity != null && userIdentity.getUserPrincipal() != null ? registered : null;
    }

    /**
     * The session id is renewed first, as it is for any other authenticator, so only the renewed
     * id carries these results.
     */
    private void registerAuthenticationSession(Request baseRequest, UserAuthentication authentication)
    {
        if (baseRequest == null)
            return;

        renewSession(baseRequest, baseRequest.getResponse());
        HttpSession session = baseRequest.getSession(true);
        if (session != null)
            session.setAttribute(AUTHENTICATION_SESSION_ATTRIBUTE, authentication);
    }

    /**
     * A module secures the response from the task the channel runs as it completes the response,
     * so the return of the dispatch, which is not the end of an asynchronous request and not the
     * final response of one that called {@code sendError}, has nothing to secure. A declared
     * auth-method standing in for a module secures it here, where it always did.
     */
    @Override
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, Authentication.User validatedUser) throws ServerAuthException
    {
        Authenticator declared = getDeclaredAuthenticator();
        return declared == null || declared.secureResponse(req, res, mandatory, validatedUser);
    }

    /**
     * <p>Wrap a task so that it runs with the request back in the context and servlet scope it was
     * dispatched in.</p>
     * <p>Both scopes are exited as the dispatch returns, well before the response is completed, and
     * a request outside them reports no context path, no servlet path and no dispatcher type. The
     * module is given the request message it validated, so it must see the same request in both
     * calls.</p>
     */
    private static Runnable inDispatchScope(Request baseRequest, Runnable task)
    {
        ContextHandler.APIContext context = baseRequest.getContext();
        String pathInContext = baseRequest.getPathInContext();
        ServletPathMapping servletPathMapping = baseRequest.getServletPathMapping();
        DispatcherType dispatcherType = baseRequest.getDispatcherType();
        return () ->
        {
            ContextHandler.APIContext oldContext = baseRequest.getContext();
            String oldPathInContext = baseRequest.getPathInContext();
            ServletPathMapping oldServletPathMapping = baseRequest.getServletPathMapping();
            DispatcherType oldDispatcherType = baseRequest.getDispatcherType();
            baseRequest.setContext(context, pathInContext);
            baseRequest.setServletPathMapping(servletPathMapping);
            baseRequest.setDispatcherType(dispatcherType);
            try
            {
                task.run();
            }
            finally
            {
                baseRequest.setContext(oldContext, oldPathInContext);
                baseRequest.setServletPathMapping(oldServletPathMapping);
                baseRequest.setDispatcherType(oldDispatcherType);
            }
        };
    }

    /**
     * <p>Point (3) of the message processing model, where the Servlet Container Profile requires
     * the module to secure the response.</p>
     * <p>{@code SEND_SUCCESS} and {@code SEND_CONTINUE} have the runtime send the response as the
     * module left it, and {@code SUCCESS} is taken the same way: the profile does not define it
     * here, but {@link AuthStatus#SUCCESS} is defined as the runtime proceeding with the resulting
     * message, which at this point is to send it. Any other status reports no success, and is
     * handled as the {@link AuthException} the profile allows instead, replacing the response.</p>
     */
    private void secureResponseAtCompletion(ServerAuthContext authContext, JaspiMessageInfo messageInfo)
    {
        try
        {
            AuthStatus authStatus = authContext.secureResponse(messageInfo, _serviceSubject);
            if (authStatus != AuthStatus.SEND_SUCCESS && authStatus != AuthStatus.SEND_CONTINUE && authStatus != AuthStatus.SUCCESS)
                throw new AuthException("Response not secured, AuthStatus " + authStatus);
        }
        catch (AuthException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * The Servlet Container Profile requires the module to clean the subject when the application
     * calls {@link jakarta.servlet.http.HttpServletRequest#logout()}. A module that fails to do
     * so does not stop the caller being logged out or the container authentication session being
     * discarded.
     */
    @Override
    public void logout(ServletRequest request)
    {
        // Discarded whichever mechanism serves the logout, or a provider registered again would restore the caller that was logged out.
        HttpSession session = ((HttpServletRequest)request).getSession(false);
        if (session != null)
            session.removeAttribute(AUTHENTICATION_SESSION_ATTRIBUTE);

        if (getDeclaredAuthenticator() instanceof LoginAuthenticator declared)
        {
            declared.logout(request);
            return;
        }

        try
        {
            // The profile allows the module to log the caller out from its own cleanSubject, which must not re-enter cleanSubject.
            if (request.getAttribute(CLEAN_SUBJECT_ATTRIBUTE) == null)
            {
                request.setAttribute(CLEAN_SUBJECT_ATTRIBUTE, Boolean.TRUE);
                try
                {
                    cleanSubject(request);
                }
                catch (Exception e)
                {
                    LOG.warn("Failed to clean subject for {}", request, e);
                }
                finally
                {
                    request.removeAttribute(CLEAN_SUBJECT_ATTRIBUTE);
                }
            }
        }
        finally
        {
            super.logout(request);
        }
    }

    /**
     * The profile requires the MessageInfo to be mandatory whatever the constraints on the resource
     * say, and it also selects the {@link ServerAuthContext} to clean.
     */
    private void cleanSubject(ServletRequest request) throws AuthException
    {
        if (getAuthConfig() == null)
            return;

        Request baseRequest = Request.getBaseRequest(request);
        if (baseRequest == null)
            return;

        JaspiMessageInfo messageInfo = new JaspiMessageInfo(request, baseRequest.getResponse(), true);

        Subject clientSubject = baseRequest.getAuthentication() instanceof Authentication.User user
            ? user.getUserIdentity().getSubject() : null;
        if (clientSubject == null)
            clientSubject = new Subject();

        ServerAuthContext authContext = getAuthContext(messageInfo);
        if (authContext != null)
            authContext.cleanSubject(messageInfo, clientSubject);
    }

    private ServerAuthContext getAuthContext(JaspiMessageInfo messageInfo) throws AuthException
    {
        ServerAuthConfig authConfig = getAuthConfig();
        if (authConfig == null)
            throw new AuthException("No ServerAuthConfig");
        return authConfig.getAuthContext(authConfig.getAuthContextID(messageInfo), _serviceSubject, _authProperties);
    }

    /**
     * The authentication results of a request whose messages the module replaced, which the
     * {@link org.eclipse.jetty.ee9.security.SecurityHandler} dispatches the resource with.
     */
    private static class JaspiAuthentication extends UserAuthentication implements Authentication.Wrapped
    {
        private final HttpServletRequest _request;
        private final HttpServletResponse _response;

        private JaspiAuthentication(String method, UserIdentity userIdentity, HttpServletRequest request, HttpServletResponse response)
        {
            super(method, userIdentity);
            _request = request;
            _response = response;
        }

        @Override
        public HttpServletRequest getHttpServletRequest()
        {
            return _request;
        }

        @Override
        public HttpServletResponse getHttpServletResponse()
        {
            return _response;
        }
    }

    /**
     * The authentication of a request whose messages the module replaced without naming a caller.
     */
    private static class JaspiUnauthenticated implements Authentication.Wrapped
    {
        private final HttpServletRequest _request;
        private final HttpServletResponse _response;

        private JaspiUnauthenticated(HttpServletRequest request, HttpServletResponse response)
        {
            _request = request;
            _response = response;
        }

        @Override
        public HttpServletRequest getHttpServletRequest()
        {
            return _request;
        }

        @Override
        public HttpServletResponse getHttpServletResponse()
        {
            return _response;
        }
    }

    /**
     * A {@link JaspiUnauthenticated} that the container may still authenticate lazily, which is
     * what an unconstrained request is given, carrying the same messages on.
     */
    private static class JaspiDeferredAuthentication extends DeferredAuthentication implements Authentication.Wrapped
    {
        private final Authentication.Wrapped _wrapped;

        private JaspiDeferredAuthentication(LoginAuthenticator authenticator, Authentication.Wrapped wrapped)
        {
            super(authenticator);
            _wrapped = wrapped;
        }

        @Override
        public HttpServletRequest getHttpServletRequest()
        {
            return _wrapped.getHttpServletRequest();
        }

        @Override
        public HttpServletResponse getHttpServletResponse()
        {
            return _wrapped.getHttpServletResponse();
        }
    }

    /**
     * A {@link ServerAuthConfig}, or null where no provider was registered, and the number of
     * registration changes that had been made when it began to be resolved. {@code
     * getServerAuthConfig} is provider code that may take any length of time, and a registration
     * made or removed while it runs leaves the config it returns stale before it arrives, so the
     * count is compared rather than the config being cleared.
     */
    private record CachedAuthConfig(long registrations, ServerAuthConfig authConfig)
    {
    }

    private static class JaspiAuthConfiguration extends WrappedAuthConfiguration
    {
        private final LoginService loginService = new EmptyLoginService();

        public JaspiAuthConfiguration(AuthConfiguration configuration)
        {
            super(configuration);
        }

        @Override
        public LoginService getLoginService()
        {
            return loginService;
        }
    }
}
