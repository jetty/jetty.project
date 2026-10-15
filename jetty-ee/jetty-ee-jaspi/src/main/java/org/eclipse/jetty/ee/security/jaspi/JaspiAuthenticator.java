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

package org.eclipse.jetty.ee.security.jaspi;

import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.security.auth.Subject;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.RegistrationListener;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.ServletContextRequest;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.EmptyLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.ServletAuthenticator;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static jakarta.security.auth.message.AuthStatus.FAILURE;
import static jakarta.security.auth.message.AuthStatus.SEND_CONTINUE;
import static jakarta.security.auth.message.AuthStatus.SEND_FAILURE;
import static jakarta.security.auth.message.AuthStatus.SEND_SUCCESS;
import static jakarta.security.auth.message.AuthStatus.SUCCESS;
import static org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticatorFactory.MESSAGE_LAYER;

/**
 * Implementation of Jetty {@link LoginAuthenticator} that is a bridge from Jakarta Authentication to Jetty Security.
 */
public class JaspiAuthenticator extends LoginAuthenticator implements ServletAuthenticator
{
    static final Principal UNAUTHENTICATED = () -> null;

    private static final Logger LOG = LoggerFactory.getLogger(JaspiAuthenticator.class);

    /**
     * The {@link ServerAuthContext} and {@link JaspiMessageInfo} of the {@code validateRequest} that
     * the Servlet Container Profile requires {@code secureResponse} to be given, removed once it has
     * been handed over.
     */
    private static final String SECURE_RESPONSE_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".secureResponse";

    /**
     * Set once the module has been asked about the request and never removed, so that a deferred
     * authentication on a later dispatch, such as an error dispatch after {@code secureResponse},
     * is answered with what that call established rather than by asking the module again.
     */
    private static final String VALIDATED_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".validated";

    private static final String VALIDATE_REQUEST_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".validateRequest";
    private static final String CLEAN_SUBJECT_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".cleanSubject";

    /**
     * The container authentication session: the results a module asked to have remembered with
     * {@link JaspiMessageInfo#REGISTER_SESSION_KEY}, held in the {@link Session} of the request.
     */
    private static final String AUTHENTICATION_SESSION_ATTRIBUTE = JaspiAuthenticator.class.getName() + ".authenticationSession";

    private final Subject _serviceSubject;
    private final List<String> _appContexts;
    private final Authenticator _declared;
    private final ServletContextHandler _contextHandler;
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
    public JaspiAuthenticator(Subject serviceSubject, List<String> appContexts, ServletContextHandler contextHandler, Authenticator declared)
    {
        _serviceSubject = serviceSubject;
        _appContexts = appContexts;
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
            LOG.warn("No ServletContextHandler for {}, its RegistrationListener will not be detached", _appContexts);
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
    public void setConfiguration(Configuration configuration)
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
            configuration = new JaspiAuthenticatorConfiguration(configuration);
            loginService = configuration.getLoginService();
        }

        super.setConfiguration(configuration);

        _callbackHandler = new ServletCallbackHandler(loginService);
        _authProperties = new HashMap<>();
        for (String key : configuration.getParameterNames())
        {
            _authProperties.put(key, configuration.getParameter(key));
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
     * auth-method is that mechanism. A provider registered later takes the next request from it,
     * as {@code AuthConfigFactory.registerServerAuthModule} says it overrides modules registered
     * "via proprietary means".</p>
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
            // A provider is registered but could not be asked for its configuration, so the request is the module's and doValidateRequest reports the failure.
            return null;
        }
    }

    /**
     * The declared auth-method while it is standing in, as the
     * {@link org.eclipse.jetty.security.SecurityHandler} applies that one itself rather than
     * deferring it to the servlet layer.
     */
    @Override
    public boolean deferValidation(Request request)
    {
        return getDeclaredAuthenticator() == null;
    }

    /**
     * The declared auth-method while it is standing in, so that a context declaring one is still
     * known by it, and {@code JASPI} otherwise.
     */
    @Override
    public String getAuthenticationType()
    {
        return _declared == null ? "JASPI" : _declared.getAuthenticationType();
    }

    @Override
    public Constraint.Authorization getConstraintAuthentication(String pathInContext, Constraint.Authorization existing, Function<Boolean, Session> getSession)
    {
        Authenticator declared = getDeclaredAuthenticator();
        return declared == null
            ? super.getConstraintAuthentication(pathInContext, existing, getSession)
            : declared.getConstraintAuthentication(pathInContext, existing, getSession);
    }

    @Override
    public Request prepareRequest(Request request, AuthenticationState authenticationState)
    {
        Authenticator declared = getDeclaredAuthenticator();
        return declared == null ? request : declared.prepareRequest(request, authenticationState);
    }

    @Override
    public UserIdentity login(String username, Object password, Request request, Response response)
    {
        return getDeclaredAuthenticator() instanceof LoginAuthenticator declared
            ? declared.login(username, password, request, response)
            : super.login(username, password, request, response);
    }

    /**
     * Called by the servlet channel before the request is dispatched, with the mandatory decision
     * the {@link org.eclipse.jetty.security.SecurityHandler} made from the constraints. The
     * application has not run, so this is never an authentication request.
     */
    @Override
    public AuthenticationState validateServletRequest(Request request, Response response, boolean mandatory) throws ServerAuthException
    {
        JaspiMessageInfo messageInfo = new JaspiMessageInfo(request, response);
        messageInfo.setMandatory(mandatory);
        return doValidateRequest(messageInfo, true);
    }

    /**
     * <p>Called from {@link AuthenticationState.Deferred} with a real response when the application
     * calls {@link jakarta.servlet.http.HttpServletRequest#authenticate}, which the Servlet Container
     * Profile requires to carry both the mandatory and the authentication request keys.</p>
     * <p>A deferred response means the container is establishing {@code getUserPrincipal} instead,
     * and the profile defines a second {@code validateRequest} only for {@code authenticate}
     * (a487), so the answer from before the dispatch stands.</p>
     * <p>This is also where the {@link org.eclipse.jetty.security.SecurityHandler} validates a
     * request that {@link #deferValidation(Request)} kept from the servlet layer.</p>
     */
    @Override
    public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
    {
        Authenticator declared = getDeclaredAuthenticator();
        if (declared != null)
            return declared.validateRequest(request, response, callback);

        boolean isAuthenticationRequest = !AuthenticationState.Deferred.isDeferred(response);
        if (!isAuthenticationRequest && hasValidatedRequest(request))
            return null;

        JaspiMessageInfo messageInfo = new JaspiMessageInfo(request, response);
        messageInfo.setMandatory(isAuthenticationRequest);
        messageInfo.setAuthenticationRequest(isAuthenticationRequest);
        AuthenticationState authenticationState = doValidateRequest(messageInfo, false);

        // The module sends its own response with the Servlet API, so a caller blocking on the callback for it is released here.
        if (isAuthenticationRequest && authenticationState instanceof AuthenticationState.ResponseSent)
            callback.succeeded();
        return authenticationState;
    }

    /**
     * @param messageInfo the message for this call.
     * @param keepMessageInfo whether this is the validation of the request before the dispatch,
     * whose MessageInfo is the one the Servlet Container Profile requires {@code secureResponse}
     * to be given.
     */
    private AuthenticationState doValidateRequest(JaspiMessageInfo messageInfo, boolean keepMessageInfo) throws ServerAuthException
    {
        ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(messageInfo.getBaseRequest());
        if (servletContextRequest == null)
            throw new ServerAuthException("No ServletContextRequest");
        if (!enterAuthContext(servletContextRequest, VALIDATE_REQUEST_ATTRIBUTE))
            return null;

        try
        {
            // With no AuthConfigProvider the profile requires the request served by servlet constraint processing alone (a392).
            // a396 requires getAuthContext to be non-null, so a null ServerAuthContext is a provider violation the profile does not cover, taken the same way.
            ServerAuthConfig authConfig = getAuthConfig();
            if (authConfig == null)
                return null;

            String authContextId = authConfig.getAuthContextID(messageInfo);
            ServerAuthContext authContext = authConfig.getAuthContext(authContextId, _serviceSubject, _authProperties);
            if (authContext == null)
                return null;
            Subject clientSubject = new Subject();
            AuthenticationState.Succeeded registered = getAuthenticationSession(servletContextRequest);

            servletContextRequest.setAttribute(VALIDATED_ATTRIBUTE, Boolean.TRUE);
            AuthStatus authStatus = validateRequest(authContext, messageInfo, clientSubject, servletContextRequest, registered);
            if (authStatus == SUCCESS)
            {
                // Keep the messages the module may have replaced, even if it does not go on to name a caller.
                servletContextRequest.setServletRequest((HttpServletRequest)messageInfo.getRequestMessage());
                servletContextRequest.setServletResponse((HttpServletResponse)messageInfo.getResponseMessage());

                if (keepMessageInfo)
                    servletContextRequest.setAttribute(SECURE_RESPONSE_ATTRIBUTE, new SecureResponse(authContext, messageInfo));

                // Build the identity from the callbacks the module has already issued.
                JaspiUserIdentity userIdentity = ServletCallbackHandler.getUserIdentity(clientSubject);
                Principal callerPrincipal = userIdentity == null ? null : userIdentity.getUserPrincipal();
                // A module returning SUCCESS with no CallerPrincipalCallback is not compliant (a442), so the request is treated as having named the unauthenticated caller.
                if (callerPrincipal == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No CallerPrincipalCallback from {} for {}", authContext, servletContextRequest);
                    return null;
                }
                if (callerPrincipal == UNAUTHENTICATED)
                    return null;

                // The container authentication session holds the authType named at registration, which a module continuing the session without naming one keeps.
                boolean continuesRegisteredSession = continuesRegisteredSession(registered, userIdentity);
                String authType = messageInfo.getAuthenticationType();
                if (authType == null && continuesRegisteredSession)
                    authType = registered.getAuthenticationType();
                if (authType == null)
                    authType = getAuthenticationType();

                UserIdentity identity = continuesRegisteredSession ? registered.getUserIdentity() : userIdentity;

                UserAuthenticationSucceeded succeeded = new UserAuthenticationSucceeded(authType, identity);
                if (messageInfo.isRegisterSession())
                    registerAuthenticationSession(servletContextRequest, messageInfo.getBaseResponse(), succeeded);
                return succeeded;
            }
            else if (authStatus == SEND_SUCCESS)
                return AuthenticationState.SEND_SUCCESS;
            else if (authStatus == SEND_CONTINUE)
                return AuthenticationState.CHALLENGE;
            else if (authStatus == SEND_FAILURE)
                return AuthenticationState.SEND_FAILURE;
            else if (authStatus == FAILURE)
            {
                // The profile does not define FAILURE for validateRequest, so the runtime answers with its own status rather than the module's response.
                ((HttpServletResponse)messageInfo.getResponseMessage()).sendError(HttpServletResponse.SC_FORBIDDEN);
                return AuthenticationState.SEND_FAILURE;
            }
            throw new ServerAuthException("Bad AuthStatus "  + authStatus);
        }
        catch (AuthException | IOException e)
        {
            throw new ServerAuthException(e);
        }
        finally
        {
            servletContextRequest.removeAttribute(VALIDATE_REQUEST_ATTRIBUTE);
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
    private static boolean continuesRegisteredSession(AuthenticationState.Succeeded registered, JaspiUserIdentity userIdentity)
    {
        if (registered == null || userIdentity.hasEstablishedGroups())
            return false;
        String name = userIdentity.getUserPrincipal().getName();
        return name != null && name.equals(registered.getUserIdentity().getUserPrincipal().getName());
    }

    /**
     * <p>Ask the module to validate the request, with any container authentication session
     * established as the authentication results first.</p>
     * <p>The Servlet Container Profile requires {@code validateRequest} for all requests the
     * Servlet security model applies to (a418), which excludes a forward or include through a
     * {@code RequestDispatcher}, so the session is not used in place of the call: the module reads
     * it with {@code getUserPrincipal} and decides for itself whether to continue it, and the
     * results of this call are the ones that stand.</p>
     */
    private AuthStatus validateRequest(ServerAuthContext authContext, JaspiMessageInfo messageInfo,
                                       Subject clientSubject, ServletContextRequest request,
                                       AuthenticationState.Succeeded registered) throws AuthException
    {
        if (registered == null)
            return authContext.validateRequest(messageInfo, clientSubject, _serviceSubject);

        AuthenticationState previous = AuthenticationState.getAuthenticationState(request);
        AuthenticationState.setAuthenticationState(request, registered);
        try
        {
            return authContext.validateRequest(messageInfo, clientSubject, _serviceSubject);
        }
        finally
        {
            AuthenticationState.setAuthenticationState(request, previous);
        }
    }

    /**
     * @param request the request.
     * @return the authentication results registered for the session of this request, or null if
     * there are none or they could not be restored.
     */
    private static AuthenticationState.Succeeded getAuthenticationSession(Request request)
    {
        Session session = request.getSession(false);
        if (session == null || !(session.getAttribute(AUTHENTICATION_SESSION_ATTRIBUTE) instanceof AuthenticationState.Succeeded succeeded))
            return null;
        // A persisted session cannot carry the identity a module established, so the registration does not survive it.
        UserIdentity userIdentity = succeeded.getUserIdentity();
        return userIdentity != null && userIdentity.getUserPrincipal() != null ? succeeded : null;
    }

    /**
     * The session id is renewed first, as it is for any other authenticator, so only the renewed
     * id carries these results.
     */
    private void registerAuthenticationSession(Request request, Response response, AuthenticationState.Succeeded succeeded)
    {
        updateSession(request, response);
        Session session = request.getSession(true);
        if (session != null)
            session.setAttribute(AUTHENTICATION_SESSION_ATTRIBUTE, succeeded);
    }

    /**
     * <p>Called by the servlet channel as the response is completed, which is point (3) of the
     * message processing model, where the Servlet Container Profile requires the module to
     * secure the response.</p>
     * <p>{@code SEND_SUCCESS} and {@code SEND_CONTINUE} have the runtime send the response as the
     * module left it, and {@code SUCCESS} is taken the same way: the profile does not define it
     * here, but {@link AuthStatus#SUCCESS} is defined as the runtime proceeding with the resulting
     * message, which at this point is to send it. Any other status reports no success, and is
     * handled as the {@link AuthException} the profile allows instead, replacing the response.</p>
     */
    @Override
    public void secureResponse(Request request, Response response) throws ServerAuthException
    {
        ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(request);
        if (servletContextRequest == null || !(servletContextRequest.removeAttribute(SECURE_RESPONSE_ATTRIBUTE) instanceof SecureResponse secureResponse))
            return;

        try
        {
            AuthStatus authStatus = secureResponse.authContext().secureResponse(secureResponse.messageInfo(), _serviceSubject);
            if (authStatus != SEND_SUCCESS && authStatus != SEND_CONTINUE && authStatus != SUCCESS)
                throw new ServerAuthException("Response not secured, AuthStatus " + authStatus);
        }
        catch (AuthException e)
        {
            throw new ServerAuthException(e);
        }
    }

    /**
     * The Servlet Container Profile requires the module to clean the subject when the application
     * calls {@link jakarta.servlet.http.HttpServletRequest#logout()}. A module that fails to do
     * so does not stop the caller being logged out or the container authentication session being
     * discarded.
     */
    @Override
    public void logout(Request request, Response response)
    {
        // Discarded whichever mechanism serves the logout, or a provider registered again would restore the caller that was logged out.
        Session session = request.getSession(false);
        if (session != null)
            session.removeAttribute(AUTHENTICATION_SESSION_ATTRIBUTE);

        if (getDeclaredAuthenticator() instanceof LoginAuthenticator declared)
        {
            declared.logout(request, response);
            return;
        }

        try
        {
            ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(request);
            if (servletContextRequest != null && enterAuthContext(servletContextRequest, CLEAN_SUBJECT_ATTRIBUTE))
            {
                try
                {
                    cleanSubject(request, response);
                }
                catch (Exception e)
                {
                    LOG.warn("Failed to clean subject for {}", request, e);
                }
                finally
                {
                    servletContextRequest.removeAttribute(CLEAN_SUBJECT_ATTRIBUTE);
                }
            }
        }
        finally
        {
            super.logout(request, response);
        }
    }

    /**
     * The profile requires the MessageInfo to be mandatory whatever the constraints on the resource
     * say, and it also selects the {@link ServerAuthContext} to clean.
     */
    private void cleanSubject(Request request, Response response) throws AuthException
    {
        if (getAuthConfig() == null)
            return;

        JaspiMessageInfo messageInfo = new JaspiMessageInfo(request, response);
        messageInfo.setMandatory(true);

        Subject clientSubject = AuthenticationState.getAuthenticationState(request) instanceof AuthenticationState.Succeeded succeeded
            ? succeeded.getUserIdentity().getSubject() : null;
        if (clientSubject == null)
            clientSubject = new Subject();

        ServerAuthContext authContext = getAuthContext(messageInfo);
        if (authContext != null)
            authContext.cleanSubject(messageInfo, clientSubject);
    }

    private static boolean hasValidatedRequest(Request request)
    {
        ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(request);
        return servletContextRequest != null && servletContextRequest.getAttribute(VALIDATED_ATTRIBUTE) != null;
    }

    /**
     * <p>Mark a {@link ServerAuthContext} call as in progress for a request. The Servlet Container
     * Profile requires that a call the module makes back into the container does not re-enter it:
     * {@code authenticate} from within {@code validateRequest}, or {@code logout} from within
     * {@code cleanSubject}.</p>
     *
     * @param request the request.
     * @param attribute the attribute marking the call.
     * @return true if the call may be made, false if it is already in progress for the request.
     */
    private static boolean enterAuthContext(ServletContextRequest request, String attribute)
    {
        if (request.getAttribute(attribute) != null)
            return false;
        request.setAttribute(attribute, Boolean.TRUE);
        return true;
    }

    private ServerAuthContext getAuthContext(JaspiMessageInfo messageInfo) throws AuthException
    {
        ServerAuthConfig authConfig = getAuthConfig();
        if (authConfig == null)
            throw new AuthException("No ServerAuthConfig");
        return authConfig.getAuthContext(authConfig.getAuthContextID(messageInfo), _serviceSubject, _authProperties);
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

    /**
     * The profile requires the response to be secured on the same {@link ServerAuthContext} as the
     * {@code validateRequest} that validated the request, so it is kept rather than resolved again.
     */
    private record SecureResponse(ServerAuthContext authContext, JaspiMessageInfo messageInfo)
    {
    }

    private static class JaspiAuthenticatorConfiguration extends Configuration.Wrapper
    {
        private final LoginService loginService = new EmptyLoginService();

        public JaspiAuthenticatorConfiguration(Configuration configuration)
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
