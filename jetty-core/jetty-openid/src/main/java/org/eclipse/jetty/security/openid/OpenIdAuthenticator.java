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

package org.eclipse.jetty.security.openid;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Implements authentication using OpenId Connect on top of OAuth 2.0.
 *
 * <p>The OpenIdAuthenticator redirects unauthenticated requests to the OpenID Connect Provider. The End-User is
 * eventually redirected back with an Authorization Code to the path set by {@link #setRedirectPath(String)} within the context.
 * The Authorization Code is then used to authenticate the user through the {@link OpenIdCredentials} and {@link OpenIdLoginService}.
 * </p>
 * <p>
 * Once a user is authenticated the OpenID Claims can be retrieved through an attribute on the session with the key {@link #CLAIMS}.
 * The full response containing the OAuth 2.0 Access Token can be obtained with the session attribute {@link #RESPONSE}.
 * </p>
 * <p>{@link SessionAuthentication} is then used to wrap Authentication results so that they are associated with the session.</p>
 */
public class OpenIdAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(OpenIdAuthenticator.class);

    public static final String CLAIMS = "org.eclipse.jetty.security.openid.claims";
    public static final String RESPONSE = "org.eclipse.jetty.security.openid.response";
    public static final String ISSUER = "org.eclipse.jetty.security.openid.issuer";
    public static final String REDIRECT_PATH = "org.eclipse.jetty.security.openid.redirect_path";
    public static final String LOGOUT_REDIRECT_PATH = "org.eclipse.jetty.security.openid.logout_redirect_path";
    public static final String ERROR_PAGE = "org.eclipse.jetty.security.openid.error_page";
    public static final String J_URI = "org.eclipse.jetty.security.openid.URI";
    public static final String J_POST = "org.eclipse.jetty.security.openid.POST";
    public static final String J_METHOD = "org.eclipse.jetty.security.openid.METHOD";
    public static final String J_SECURITY_CHECK = "/j_security_check";
    public static final String ERROR_PARAMETER = "error_description_jetty";
    private static final String CSRF_MAP = "org.eclipse.jetty.security.openid.csrf_map";

    @Deprecated
    public static final String CSRF_TOKEN = "org.eclipse.jetty.security.openid.csrf_token";

    private final SecureRandom _secureRandom = new SecureRandom();
    private OpenIdConfiguration _openIdConfiguration;
    private String _redirectPath;
    private String _logoutRedirectPath;
    private String _errorPage;
    private String _errorPath;
    private String _errorQuery;
    private boolean _alwaysSaveUri;

    public OpenIdAuthenticator()
    {
        this(null, J_SECURITY_CHECK, null);
    }

    public OpenIdAuthenticator(OpenIdConfiguration configuration)
    {
        this(configuration, J_SECURITY_CHECK, null);
    }

    public OpenIdAuthenticator(OpenIdConfiguration configuration, String errorPage)
    {
        this(configuration, J_SECURITY_CHECK, errorPage);
    }

    public OpenIdAuthenticator(OpenIdConfiguration configuration, String redirectPath, String errorPage)
    {
        this(configuration, redirectPath, errorPage, null);
    }

    public OpenIdAuthenticator(OpenIdConfiguration configuration, String redirectPath, String errorPage, String logoutRedirectPath)
    {
        _openIdConfiguration = configuration;
        setRedirectPath(redirectPath);
        if (errorPage != null)
            setErrorPage(errorPage);
        if (logoutRedirectPath != null)
            setLogoutRedirectPath(logoutRedirectPath);
    }

    @Override
    public void setConfiguration(Configuration authConfig)
    {
        if (_openIdConfiguration == null)
        {
            LoginService loginService = authConfig.getLoginService();
            if (!(loginService instanceof OpenIdLoginService))
                throw new IllegalArgumentException("invalid LoginService " + loginService);
            this._openIdConfiguration = ((OpenIdLoginService)loginService).getConfiguration();
        }

        String redirectPath = authConfig.getParameter(REDIRECT_PATH);
        if (redirectPath != null)
            setRedirectPath(redirectPath);

        String error = authConfig.getParameter(ERROR_PAGE);
        if (error != null)
            setErrorPage(error);

        String logout = authConfig.getParameter(LOGOUT_REDIRECT_PATH);
        if (logout != null)
            setLogoutRedirectPath(logout);

        super.setConfiguration(new OpenIdAuthenticatorConfiguration(_openIdConfiguration, authConfig));
    }

    @Override
    public String getAuthenticationType()
    {
        return Authenticator.OPENID_AUTH;
    }

    @Deprecated
    public void setAlwaysSaveUri(boolean alwaysSave)
    {
        _alwaysSaveUri = alwaysSave;
    }

    @Deprecated
    public boolean isAlwaysSaveUri()
    {
        return _alwaysSaveUri;
    }

    public void setRedirectPath(String redirectPath)
    {
        if (redirectPath == null)
        {
            LOG.warn("redirect path must not be null, defaulting to " + J_SECURITY_CHECK);
            redirectPath = J_SECURITY_CHECK;
        }
        else if (!redirectPath.startsWith("/"))
        {
            LOG.warn("redirect path must start with /");
            redirectPath = "/" + redirectPath;
        }

        _redirectPath = redirectPath;
    }

    public void setLogoutRedirectPath(String logoutRedirectPath)
    {
        if (logoutRedirectPath == null)
        {
            LOG.warn("redirect path must not be null, defaulting to /");
            logoutRedirectPath = "/";
        }
        else if (!logoutRedirectPath.startsWith("/"))
        {
            LOG.warn("redirect path must start with /");
            logoutRedirectPath = "/" + logoutRedirectPath;
        }

        _logoutRedirectPath = logoutRedirectPath;
    }

    public void setErrorPage(String path)
    {
        if (path == null || path.trim().length() == 0)
        {
            _errorPath = null;
            _errorPage = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("error-page must start with /");
                path = "/" + path;
            }
            _errorPage = path;
            _errorPath = path;
            _errorQuery = "";

            int queryIndex = _errorPath.indexOf('?');
            if (queryIndex > 0)
            {
                _errorPath = _errorPage.substring(0, queryIndex);
                _errorQuery = _errorPage.substring(queryIndex + 1);
            }
        }
    }

    @Override
    public UserIdentity login(String username, Object credentials, Request request, Response response)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("login {} {} {}", username, credentials, request);

        UserIdentity user = super.login(username, credentials, request, response);
        if (user != null)
        {
            Session session = request.getSession(true);
            AuthenticationState cached = new SessionAuthentication(getAuthenticationType(), user, credentials);
            synchronized (session)
            {
                session.setAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE, cached);
                session.setAttribute(CLAIMS, ((OpenIdCredentials)credentials).getClaims());
                session.setAttribute(RESPONSE, ((OpenIdCredentials)credentials).getResponse());
                session.setAttribute(ISSUER, _openIdConfiguration.getIssuer());
            }
        }
        return user;
    }

    @Override
    public void logout(Request request, Response response)
    {
        attemptLogoutRedirect(request, response);
        logoutWithoutRedirect(request, response);
    }

    private void logoutWithoutRedirect(Request request, Response response)
    {
        super.logout(request, response);
        Session session = request.getSession(false);
        if (session == null)
            return;
        synchronized (session)
        {
            session.removeAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
            session.removeAttribute(CLAIMS);
            session.removeAttribute(RESPONSE);
            session.removeAttribute(ISSUER);
        }
    }

    private boolean hasExpiredIdToken(Session session)
    {
        if (session != null)
        {
            Map<String, Object> claims = (Map<String, Object>)session.getAttribute(CLAIMS);
            if (claims != null)
                return OpenIdCredentials.checkExpiry(claims);
        }
        return false;
    }

    /**
     * <p>This will attempt to redirect the request to the end_session_endpoint, and finally to the {@link #REDIRECT_PATH}.</p>
     *
     * <p>If end_session_endpoint is defined the request will be redirected to the end_session_endpoint, the optional
     * post_logout_redirect_uri parameter will be set if {@link #REDIRECT_PATH} is non-null.</p>
     *
     * <p>If the end_session_endpoint is not defined then the request will be redirected to {@link #REDIRECT_PATH} if it is a
     * non-null value, otherwise no redirection will be done.</p>
     *
     * @param request the request to redirect.
     */
    private void attemptLogoutRedirect(Request request, Response response)
    {
        try
        {
            String endSessionEndpoint = _openIdConfiguration.getEndSessionEndpoint();
            String redirectUri = null;
            if (_logoutRedirectPath != null)
            {
                HttpURI.Mutable httpURI = HttpURI.build()
                    .scheme(request.getHttpURI().getScheme())
                    .host(Request.getServerName(request))
                    .port(Request.getServerPort(request))
                    .path(URIUtil.compactPath(Request.getContextPath(request) + _logoutRedirectPath));
                redirectUri = httpURI.toString();
            }

            Session session = request.getSession(false);
            if (endSessionEndpoint == null || session == null)
            {
                if (redirectUri != null)
                    sendRedirect(request, response, redirectUri);
                return;
            }

            Object openIdResponse = session.getAttribute(OpenIdAuthenticator.RESPONSE);
            if (!(openIdResponse instanceof Map))
            {
                if (redirectUri != null)
                    sendRedirect(request, response, redirectUri);
                return;
            }

            @SuppressWarnings("rawtypes")
            String idToken = (String)((Map)openIdResponse).get("id_token");
            sendRedirect(request, response, endSessionEndpoint +
                    "?id_token_hint=" + UrlEncoded.encodeString(idToken, StandardCharsets.UTF_8) +
                    ((redirectUri == null) ? "" : "&post_logout_redirect_uri=" + UrlEncoded.encodeString(redirectUri, StandardCharsets.UTF_8)));
        }
        catch (Throwable t)
        {
            LOG.warn("failed to redirect to end_session_endpoint", t);
        }
    }

    private void sendRedirect(Request request, Response response, String location) throws IOException
    {
        try (Blocker.Callback callback = Blocker.callback())
        {
            Response.sendRedirect(request, response, callback, location);
            callback.block();
        }
    }

    @Override
    public Request prepareRequest(Request request, AuthenticationState authenticationState)
    {
        // if this is a request resulting from a redirect after auth is complete
        // (ie its from a redirect to the original request uri) then due to
        // browser handling of 302 redirects, the method may not be the same as
        // that of the original request. Replace the method and original post
        // params (if it was a post).
        if (authenticationState instanceof AuthenticationState.Succeeded)
        {
            Session session = request.getSession(false);
            if (session == null)
                return request; //not authenticated yet

            HttpURI juri = (HttpURI)session.getAttribute(J_URI);
            HttpURI uri = request.getHttpURI();
            if ((uri.equals(juri)))
            {
                session.removeAttribute(J_URI);

                Fields fields = (Fields)session.removeAttribute(J_POST);
                if (fields != null)
                    request.setAttribute(FormFields.class.getName(), fields);

                String method = (String)session.removeAttribute(J_METHOD);
                if (method != null && request.getMethod().equals(method))
                {
                    return new Request.Wrapper(request)
                    {
                        @Override
                        public String getMethod()
                        {
                            return method;
                        }
                    };
                }
            }
        }

        return request;
    }

    protected Fields getParameters(Request request)
    {
        try
        {
            Fields queryFields = Request.extractQueryParameters(request);
            Fields formFields = FormFields.from(request).get();
            return Fields.combine(queryFields, formFields);
        }
        catch (InterruptedException | ExecutionException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Constraint.Authorization getConstraintAuthentication(String pathInContext, Constraint.Authorization existing, Function<Boolean, Session> getSession)
    {
        Session session = getSession.apply(false);
        if (_openIdConfiguration.isLogoutWhenIdTokenIsExpired() && hasExpiredIdToken(session))
            return Constraint.Authorization.ANY_USER;

        if (isJSecurityCheck(pathInContext))
            return Constraint.Authorization.ANY_USER;
        if (isErrorPage(pathInContext))
            return Constraint.Authorization.ALLOWED;
        return existing;
    }

    @Override
    public AuthenticationState validateRequest(Request request, Response response, Callback cb) throws ServerAuthException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("validateRequest({},{})", request, response);

        String uri = request.getHttpURI().toString();
        if (uri == null)
            uri = "/";

        Session session = request.getSession(false);
        if (_openIdConfiguration.isLogoutWhenIdTokenIsExpired() && hasExpiredIdToken(session))
        {
            // After logout, fall through to the code below and send another login challenge.
            logoutWithoutRedirect(request, response);
        }

        try
        {
            // Get the Session.
            if (session == null)
                session = request.getSession(true);
            if (session == null)
            {
                sendError(request, response, cb, "session could not be created");
                return AuthenticationState.SEND_FAILURE;
            }

            // TODO: No session API to work this out?
            /*
            if (request.isRequestedSessionIdFromURL())
            {
                sendError(req, res, cb, "Session ID must be a cookie to support OpenID authentication");
                return Authentication.SEND_FAILURE;
            }
             */

            // Handle a request for authentication.
            if (isJSecurityCheck(uri))
            {
                Fields parameters = getParameters(request);
                String authCode = parameters.getValue("code");
                if (authCode == null)
                {
                    sendError(request, response, cb, "auth failed: no code parameter");
                    return AuthenticationState.SEND_FAILURE;
                }

                String state = parameters.getValue("state");
                if (state == null)
                {
                    sendError(request, response, cb, "auth failed: no state parameter");
                    return AuthenticationState.SEND_FAILURE;
                }

                // Verify anti-forgery state token.
                UriRedirectInfo uriRedirectInfo;
                synchronized (session)
                {
                    uriRedirectInfo = removeAndClearCsrfMap(session, state);
                }
                if (uriRedirectInfo == null)
                {
                    sendError(request, response, cb, "auth failed: invalid state parameter");
                    return AuthenticationState.SEND_FAILURE;
                }

                // Attempt to login with the provided authCode.
                OpenIdCredentials credentials = new OpenIdCredentials(authCode, getRedirectUri(request));
                UserIdentity user = login(null, credentials, request, response);
                if (user == null)
                {
                    sendError(request, response, cb, null);
                    return AuthenticationState.SEND_FAILURE;
                }

                LoginAuthenticator.UserAuthenticationSent openIdAuth = new LoginAuthenticator.UserAuthenticationSent(getAuthenticationType(), user);
                if (LOG.isDebugEnabled())
                    LOG.debug("authenticated {}->{}", openIdAuth, uriRedirectInfo.getUri());

                // Save redirect info in session so original request can be restored after redirect.
                synchronized (session)
                {
                    // TODO: We are duplicating this logic.
                    session.setAttribute(J_URI, uriRedirectInfo.getUri().asImmutable());
                    session.setAttribute(J_METHOD, uriRedirectInfo.getMethod());
                    session.setAttribute(J_POST, uriRedirectInfo.getFormParameters());
                }

                // Redirect to the original URI.
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                int redirectCode = request.getConnectionMetaData().getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion()
                    ? HttpStatus.MOVED_TEMPORARILY_302 : HttpStatus.SEE_OTHER_303;
                Response.sendRedirect(request, response, cb, redirectCode, uriRedirectInfo.getUri().toString(), true);
                return openIdAuth;
            }

            // Look for cached authentication in the Session.
            AuthenticationState authenticationState = (AuthenticationState)session.getAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
            if (authenticationState != null)
            {
                // Has authentication been revoked?
                if (authenticationState instanceof AuthenticationState.Succeeded && _loginService != null &&
                    !_loginService.validate(((AuthenticationState.Succeeded)authenticationState).getUserIdentity()))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth revoked {}", authenticationState);
                    logoutWithoutRedirect(request, response);
                }
                else
                {
                    synchronized (session)
                    {
                        HttpURI jUri = (HttpURI)session.getAttribute(J_URI);
                        if (jUri != null)
                        {
                            // Check if the request is for the same url as the original and restore params if it was a post.
                            if (LOG.isDebugEnabled())
                                LOG.debug("auth retry {}->{}", authenticationState, jUri);

                            if (jUri.equals(request.getHttpURI()))
                            {
                                @SuppressWarnings("unchecked")
                                MultiMap<String> jPost = (MultiMap<String>)session.getAttribute(J_POST);
                                if (jPost != null)
                                {
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("auth rePOST {}->{}", authenticationState, jUri);
                                    // TODO:
                                    // baseRequest.setContentParameters(jPost);
                                }
                                session.removeAttribute(J_URI);
                                session.removeAttribute(J_METHOD);
                                session.removeAttribute(J_POST);
                            }
                        }
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("auth {}", authenticationState);
                    return authenticationState;
                }
            }

            // If we can't send challenge.
            if (AuthenticationState.Deferred.isDeferred(response))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("auth deferred {}", session.getId());
                return null;
            }

            // Save the current URI
            synchronized (session)
            {
                // But only if it is not set already, or we save every uri that leads to a login form redirect
                if (session.getAttribute(J_URI) == null || _alwaysSaveUri)
                {
                    HttpURI juri = request.getHttpURI();
                    session.setAttribute(J_URI, juri.asImmutable());
                    if (!HttpMethod.GET.is(request.getMethod()))
                        session.setAttribute(J_METHOD, request.getMethod());

                    if (HttpMethod.POST.is(request.getMethod()))
                    {
                        try
                        {
                            session.setAttribute(J_POST, FormFields.from(request).get());
                        }
                        catch (ExecutionException e)
                        {
                            throw new ServerAuthException(e.getCause());
                        }
                        catch (InterruptedException e)
                        {
                            throw new ServerAuthException(e);
                        }
                    }
                }
            }

            // Send the challenge.
            String challengeUri = getChallengeUri(request);
            if (LOG.isDebugEnabled())
                LOG.debug("challenge {}->{}", session.getId(), challengeUri);
            int redirectCode = request.getConnectionMetaData().getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion()
                ? HttpStatus.MOVED_TEMPORARILY_302 : HttpStatus.SEE_OTHER_303;
            Response.sendRedirect(request, response, cb, redirectCode, challengeUri, true);
            return AuthenticationState.CHALLENGE;
        }
        catch (IOException e)
        {
            throw new ServerAuthException(e);
        }
    }

    /**
     * Report an error case either by redirecting to the error page if it is defined, otherwise sending a 403 response.
     * If the message parameter is not null, a query parameter with a key of {@link #ERROR_PARAMETER} and value of the error
     * message will be logged and added to the error redirect URI if the error page is defined.
     * @param request the request.
     * @param response the response.
     * @param message the reason for the error or null.
     * @throws IOException if sending the error fails for any reason.
     */
    private void sendError(Request request, Response response, Callback callback, String message) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("OpenId authentication FAILED: {}", message);

        if (_errorPage == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("auth failed 403");
            if (response != null)
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("auth failed {}", _errorPage);

            String contextPath = Request.getContextPath(request);
            String redirectUri = URIUtil.addPaths(contextPath, _errorPage);
            if (message != null)
            {
                String query = URIUtil.addQueries(ERROR_PARAMETER + "=" + UrlEncoded.encodeString(message), _errorQuery);
                redirectUri = URIUtil.addPathQuery(URIUtil.addPaths(contextPath, _errorPath), query);
            }

            int redirectCode = request.getConnectionMetaData().getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion()
                ? HttpStatus.MOVED_TEMPORARILY_302 : HttpStatus.SEE_OTHER_303;
            Response.sendRedirect(request, response, callback, redirectCode, redirectUri, true);
        }
    }

    public boolean isJSecurityCheck(String uri)
    {
        int jsc = uri.indexOf(_redirectPath);

        if (jsc < 0)
            return false;
        int e = jsc + _redirectPath.length();
        if (e == uri.length())
            return true;
        char c = uri.charAt(e);
        return c == ';' || c == '#' || c == '/' || c == '?';
    }

    public boolean isErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_errorPath));
    }

    private String getRedirectUri(Request request)
    {
        final StringBuffer redirectUri = new StringBuffer(128);
        URIUtil.appendSchemeHostPort(redirectUri, request.getHttpURI().getScheme(),
            Request.getServerName(request), Request.getServerPort(request));
        redirectUri.append(URIUtil.addPaths(request.getContext().getContextPath(), _redirectPath));
        return redirectUri.toString();
    }

    protected String getChallengeUri(Request request)
    {
        Session session = request.getSession(true);
        String antiForgeryToken;
        synchronized (session)
        {
            Map<String, UriRedirectInfo> csrfMap = ensureCsrfMap(session);
            antiForgeryToken = new BigInteger(130, _secureRandom).toString(32);
            csrfMap.put(antiForgeryToken, new UriRedirectInfo(request));
        }

        // any custom scopes requested from configuration
        StringBuilder scopes = new StringBuilder();
        for (String s : _openIdConfiguration.getScopes())
        {
            scopes.append(" ").append(s);
        }

        return _openIdConfiguration.getAuthEndpoint() +
            "?client_id=" + UrlEncoded.encodeString(_openIdConfiguration.getClientId(), StandardCharsets.UTF_8) +
            "&redirect_uri=" + UrlEncoded.encodeString(getRedirectUri(request), StandardCharsets.UTF_8) +
            "&scope=openid" + UrlEncoded.encodeString(scopes.toString(), StandardCharsets.UTF_8) +
            "&state=" + antiForgeryToken +
            "&response_type=code";
    }

    private UriRedirectInfo removeAndClearCsrfMap(Session session, String csrf)
    {
        @SuppressWarnings("unchecked")
        Map<String, UriRedirectInfo> csrfMap = (Map<String, UriRedirectInfo>)session.getAttribute(CSRF_MAP);
        if (csrfMap == null)
            return null;

        UriRedirectInfo uriRedirectInfo = csrfMap.get(csrf);
        csrfMap.clear();
        return uriRedirectInfo;
    }

    private Map<String, UriRedirectInfo> ensureCsrfMap(Session session)
    {
        @SuppressWarnings("unchecked")
        Map<String, UriRedirectInfo> csrfMap = (Map<String, UriRedirectInfo>)session.getAttribute(CSRF_MAP);
        if (csrfMap == null)
        {
            csrfMap = new MRUMap(64);
            session.setAttribute(CSRF_MAP, csrfMap);
        }
        return csrfMap;
    }

    private static class MRUMap extends LinkedHashMap<String, UriRedirectInfo>
    {
        @Serial
        private static final long serialVersionUID = 5375723072014233L;

        private final int _size;

        private MRUMap(int size)
        {
            _size = size;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, UriRedirectInfo> eldest)
        {
            return size() > _size;
        }
    }

    private static class UriRedirectInfo implements Serializable
    {
        @Serial
        private static final long serialVersionUID = 139567755844461433L;

        private final HttpURI _uri;
        private final String _method;
        private final MultiMap<String> _formParameters;

        public UriRedirectInfo(Request request)
        {
            _uri = request.getHttpURI();
            _method = request.getMethod();

            if (MimeTypes.Type.FORM_ENCODED.is(request.getHeaders().get(HttpHeader.CONTENT_TYPE)) && HttpMethod.POST.is(request.getMethod()))
            {
                // TODO request.extractFormParameters(formParameters);
                _formParameters = new MultiMap<>();
            }
            else
            {
                _formParameters = null;
            }
        }

        public HttpURI getUri()
        {
            return _uri;
        }

        public String getMethod()
        {
            return _method;
        }

        public MultiMap<String> getFormParameters()
        {
            return _formParameters;
        }
    }
}
