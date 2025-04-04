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

package org.eclipse.jetty.security.siwe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.security.siwe.internal.AnyUserLoginService;
import org.eclipse.jetty.security.siwe.internal.EthereumUtil;
import org.eclipse.jetty.security.siwe.internal.SignInWithEthereumToken;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CharsetStringBuilder.Iso88591StringBuilder;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.server.FormFields.getFormEncodedCharset;

public class EthereumAuthenticator extends LoginAuthenticator implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(EthereumAuthenticator.class);

    public static final String LOGIN_PATH_PARAM = "org.eclipse.jetty.security.siwe.login_path";
    public static final String AUTHENTICATION_PATH_PARAM = "org.eclipse.jetty.security.siwe.authentication_path";
    public static final String NONCE_PATH_PARAM = "org.eclipse.jetty.security.siwe.nonce_path";
    public static final String LOGOUT_REDIRECT_PARAM = "org.eclipse.jetty.security.siwe.logout_redirect_path";
    public static final String ERROR_PATH_PARAM = "org.eclipse.jetty.security.siwe.error_path";
    public static final String ERROR_PARAMETER = "error_description_jetty";
    public static final String MAX_MESSAGE_SIZE_PARAM = "org.eclipse.jetty.security.siwe.max_message_size";
    public static final String DISPATCH_PARAM = "org.eclipse.jetty.security.siwe.dispatch";
    public static final String AUTHENTICATE_NEW_USERS_PARAM = "org.eclipse.jetty.security.siwe.authenticate_new_users";
    public static final String CHAIN_IDS_PARAM = "org.eclipse.jetty.security.siwe.chainIds";
    public static final String DOMAINS_PARAM = "org.eclipse.jetty.security.siwe.domains";
    private static final String J_URI = "org.eclipse.jetty.security.siwe.URI";
    private static final String J_POST = "org.eclipse.jetty.security.siwe.POST";
    private static final String J_METHOD = "org.eclipse.jetty.security.siwe.METHOD";
    private static final String DEFAULT_AUTHENTICATION_PATH = "/auth/login";
    private static final String DEFAULT_NONCE_PATH = "/auth/nonce";
    private static final String NONCE_SET_ATTR = "org.eclipse.jetty.security.siwe.nonce";

    private final IncludeExcludeSet<String, String> _chainIds = new IncludeExcludeSet<>();
    private final IncludeExcludeSet<String, String> _domains = new IncludeExcludeSet<>();

    private String _loginPath;
    private String _authenticationPath = DEFAULT_AUTHENTICATION_PATH;
    private String _noncePath = DEFAULT_NONCE_PATH;
    private int _maxMessageSize = 4 * 1024;
    private String _logoutRedirectPath;
    private String _errorPath;
    private String _errorQuery;
    private boolean _dispatch;
    private boolean _authenticateNewUsers = true;

    public EthereumAuthenticator()
    {
    }

    public void includeDomains(String... domains)
    {
        _domains.include(domains);
    }

    public void includeChainIds(String... chainIds)
    {
        _chainIds.include(chainIds);
    }

    @Override
    public void setConfiguration(Authenticator.Configuration authConfig)
    {
        String loginPath = authConfig.getParameter(LOGIN_PATH_PARAM);
        if (loginPath != null)
            setLoginPath(loginPath);

        String authenticationPath = authConfig.getParameter(AUTHENTICATION_PATH_PARAM);
        if (authenticationPath != null)
            setAuthenticationPath(authenticationPath);

        String noncePath = authConfig.getParameter(NONCE_PATH_PARAM);
        if (noncePath != null)
            setNoncePath(noncePath);

        String maxMessageSize = authConfig.getParameter(MAX_MESSAGE_SIZE_PARAM);
        if (maxMessageSize != null)
            setMaxMessageSize(Integer.parseInt(maxMessageSize));

        String logout = authConfig.getParameter(LOGOUT_REDIRECT_PARAM);
        if (logout != null)
            setLogoutRedirectPath(logout);

        String error = authConfig.getParameter(ERROR_PATH_PARAM);
        if (error != null)
            setErrorPage(error);

        String dispatch = authConfig.getParameter(DISPATCH_PARAM);
        if (dispatch != null)
            setDispatch(Boolean.parseBoolean(dispatch));

        String authenticateNewUsers = authConfig.getParameter(AUTHENTICATE_NEW_USERS_PARAM);
        if (authenticateNewUsers != null)
            setAuthenticateNewUsers(Boolean.parseBoolean(authenticateNewUsers));

        String chainIds = authConfig.getParameter(CHAIN_IDS_PARAM);
        if (chainIds != null)
            includeChainIds(StringUtil.csvSplit(chainIds));

        String domains = authConfig.getParameter(DOMAINS_PARAM);
        if (domains != null)
            includeDomains(StringUtil.csvSplit(domains));

        if (isAuthenticateNewUsers())
        {
            LoginService loginService = new AnyUserLoginService(authConfig.getRealmName(), authConfig.getLoginService());
            authConfig = new Configuration.Wrapper(authConfig)
            {
                @Override
                public LoginService getLoginService()
                {
                    return loginService;
                }
            };
        }

        if (_loginPath == null)
            throw new IllegalStateException("No loginPath");
        super.setConfiguration(authConfig);
    }

    @Override
    public String getAuthenticationType()
    {
        return Authenticator.SIWE_AUTH;
    }

    public boolean isAuthenticateNewUsers()
    {
        return _authenticateNewUsers;
    }

    /**
     * Configures the behavior for authenticating users not found by a wrapped {@link LoginService}.
     * <p>
     * This setting is only meaningful if a wrapped {@link LoginService} has been set.
     * </p>
     * <p>
     * If set to {@code true}, users not found by a wrapped {@link LoginService} will authenticated with no roles.
     * If set to {@code false}, only users found by a wrapped {@link LoginService} will be authenticated.
     * </p>
     *
     * @param authenticateNewUsers whether to authenticate users not found by the wrapped {@link LoginService}
     **/
    public void setAuthenticateNewUsers(boolean authenticateNewUsers)
    {
        this._authenticateNewUsers = authenticateNewUsers;
    }

    public void setLoginPath(String loginPath)
    {
        if (loginPath == null)
        {
            LOG.warn("login path must not be null, defaulting to {}", _loginPath);
            loginPath = _loginPath;
        }
        else if (!loginPath.startsWith("/"))
        {
            LOG.warn("login path must start with /");
            loginPath = "/" + loginPath;
        }

        _loginPath = loginPath;
    }

    public void setAuthenticationPath(String authenticationPath)
    {
        if (authenticationPath == null)
        {
            authenticationPath = _authenticationPath;
            LOG.warn("authentication path must not be null, defaulting to {}", authenticationPath);
        }
        else if (!authenticationPath.startsWith("/"))
        {
            authenticationPath = "/" + authenticationPath;
            LOG.warn("authentication path must start with /");
        }

        _authenticationPath = authenticationPath;
    }

    public void setNoncePath(String noncePath)
    {
        if (noncePath == null)
        {
            noncePath = _noncePath;
            LOG.warn("nonce path must not be null, defaulting to {}", noncePath);
        }
        else if (!noncePath.startsWith("/"))
        {
            noncePath = "/" + noncePath;
            LOG.warn("nonce path must start with /");
        }

        _noncePath = noncePath;
    }

    public void setMaxMessageSize(int maxMessageSize)
    {
        _maxMessageSize = maxMessageSize;
    }

    public void setDispatch(boolean dispatch)
    {
        _dispatch = dispatch;
    }

    public void setLogoutRedirectPath(String logoutRedirectPath)
    {
        if (logoutRedirectPath != null && !logoutRedirectPath.startsWith("/"))
        {
            LOG.warn("logout redirect path must start with /");
            logoutRedirectPath = "/" + logoutRedirectPath;
        }

        _logoutRedirectPath = logoutRedirectPath;
    }

    public void setErrorPage(String path)
    {
        if (path == null || path.trim().isEmpty())
        {
            _errorPath = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("error-page must start with /");
                path = "/" + path;
            }
            _errorPath = path;
            _errorQuery = "";

            int queryIndex = _errorPath.indexOf('?');
            if (queryIndex > 0)
            {
                _errorPath = path.substring(0, queryIndex);
                _errorQuery = path.substring(queryIndex + 1);
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
        }
    }

    /**
     * <p>This will attempt to redirect the request to the {@link #_logoutRedirectPath}.</p>
     *
     * @param request the request to redirect.
     */
    private void attemptLogoutRedirect(Request request, Response response)
    {
        try
        {
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
            if (session == null)
            {
                if (redirectUri != null)
                    sendRedirect(request, response, redirectUri);
            }
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

            // Remove the nonce set used for authentication.
            session.removeAttribute(NONCE_SET_ATTR);

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

    @Override
    public Constraint.Authorization getConstraintAuthentication(String pathInContext, Constraint.Authorization existing, Function<Boolean, Session> getSession)
    {
        if (isAuthenticationRequest(pathInContext))
            return Constraint.Authorization.ANY_USER;
        if (isLoginPage(pathInContext) || isErrorPage(pathInContext))
            return Constraint.Authorization.ALLOWED;
        if (isNonceRequest(pathInContext))
            return Constraint.Authorization.ANY_USER;
        return existing;
    }

    protected String readMessage(InputStream in) throws IOException
    {
        Iso88591StringBuilder out = new Iso88591StringBuilder();
        byte[] buffer = new byte[1024];
        int totalRead = 0;

        while (true)
        {
            int len = in.read(buffer, 0, buffer.length);
            if (len < 0)
                break;

            totalRead += len;
            if (_maxMessageSize >= 0 && totalRead > _maxMessageSize)
                throw new BadMessageException("SIWE Message Too Large");
            out.append(buffer, 0, len);
        }

        return out.build();
    }

    protected SignedMessage parseMessage(Request request, Response response, Callback callback)
    {
        try
        {
            InputStream inputStream = Content.Source.asInputStream(request);
            String requestContent = readMessage(inputStream);
            ByteBufferContentSource contentSource = new ByteBufferContentSource(BufferUtil.toBuffer(requestContent));

            String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
            MimeTypes.Type mimeType = MimeTypes.getBaseType(contentType);
            if (mimeType == null)
                throw new ServerAuthException("Unsupported content type: " + contentType);

            String signature;
            String message;
            switch (mimeType)
            {
                case FORM_ENCODED ->
                {
                    Fields fields = FormFields.getFields(contentSource, request, getFormEncodedCharset(request), 10, _maxMessageSize);
                    signature = fields.get("signature").getValue();
                    message = fields.get("message").getValue();
                }
                case MULTIPART_FORM_DATA ->
                {
                    MultiPartConfig config = Request.getMultiPartConfig(request, null)
                        .maxSize(_maxMessageSize)
                        .maxParts(10)
                        .build();

                    MultiPartFormData.Parts parts = MultiPartFormData.from(contentSource, request, contentType, config).get();
                    signature = parts.getFirst("signature").getContentAsString(StandardCharsets.ISO_8859_1);
                    message = parts.getFirst("message").getContentAsString(StandardCharsets.ISO_8859_1);
                }
                default -> throw new ServerAuthException("Unsupported mime type: " + mimeType);
            };

            // The browser may convert LF to CRLF, EIP4361 specifies to only use LF.
            message = message.replace("\r\n", "\n");
            return new SignedMessage(message, signature);
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("error reading SIWE message and signature", t);
            sendError(request, response, callback, t.getMessage());
            return null;
        }
    }

    protected AuthenticationState handleNonceRequest(Request request, Response response, Callback callback)
    {
        String nonce = createNonce(request.getSession(false));
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
        ByteBuffer content = BufferUtil.toBuffer("{ \"nonce\": \"" + nonce + "\" }");
        response.write(true, content, callback);
        return AuthenticationState.CHALLENGE;
    }

    private AuthenticationState validateSignInWithEthereumToken(SignInWithEthereumToken siwe, SignedMessage signedMessage, Request request, Response response, Callback callback)
    {
        Session session = request.getSession(false);
        if (siwe == null)
            return sendError(request, response, callback, "failed to parse SIWE message");;

        try
        {
            siwe.validate(signedMessage, nonce -> redeemNonce(session, nonce), _domains, _chainIds);
        }
        catch (Throwable t)
        {
            return sendError(request, response, callback, t.getMessage());
        }

        return null;
    }

    @Override
    public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("validateRequest({},{})", request, response);

        String uri = request.getHttpURI().toString();
        if (uri == null)
            uri = "/";

        try
        {
            Session session = request.getSession(false);
            if (session == null)
            {
                session = request.getSession(true);
                if (session == null)
                    return sendError(request, response, callback, "session could not be created");
            }

            if (isNonceRequest(uri))
                return handleNonceRequest(request, response, callback);
            if (isAuthenticationRequest(uri))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("authentication request");

                // Parse and validate SIWE Message.
                SignedMessage signedMessage = parseMessage(request, response, callback);
                if (signedMessage == null)
                    return sendError(request, response, callback, "failed to read SIWE message");
                SignInWithEthereumToken siwe = SignInWithEthereumToken.from(signedMessage.message());
                if (siwe == null)
                    return sendError(request, response, callback, "failed to parse SIWE message");

                AuthenticationState authenticationState = validateSignInWithEthereumToken(siwe, signedMessage, request, response, callback);
                if (authenticationState != null)
                    return authenticationState;

                String address = siwe.address();
                UserIdentity user = login(address, null, request, response);
                if (LOG.isDebugEnabled())
                    LOG.debug("user identity: {}", user);
                if (user != null)
                {
                    // Redirect to original request
                    HttpURI savedURI = (HttpURI)session.getAttribute(J_URI);
                    String originalURI = savedURI != null
                        ? savedURI.getPathQuery()
                        : Request.getContextPath(request);
                    if (originalURI == null)
                        originalURI = "/";
                    UserAuthenticationSent formAuth = new UserAuthenticationSent(getAuthenticationType(), user);
                    String redirectUrl = session.encodeURI(request, originalURI, true);
                    Response.sendRedirect(request, response, callback, redirectUrl, true);
                    return formAuth;
                }

                return sendError(request, response, callback, "auth failed");
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
                        LOG.debug("authentication revoked {}", authenticationState);
                    logoutWithoutRedirect(request, response);
                    return sendError(request, response, callback, "authentication revoked");
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("authenticationState {}", authenticationState);
                return authenticationState;
            }

            // If we can't send challenge.
            if (AuthenticationState.Deferred.isDeferred(response))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("authentication deferred {}", session.getId());
                return null;
            }

            // Save the current URI
            synchronized (session)
            {
                // But only if it is not set already, or we save every uri that leads to a login form redirect
                if (session.getAttribute(J_URI) == null)
                {
                    HttpURI juri = request.getHttpURI();
                    session.setAttribute(J_URI, juri.asImmutable());
                    if (!HttpMethod.GET.is(request.getMethod()))
                        session.setAttribute(J_METHOD, request.getMethod());
                    if (HttpMethod.POST.is(request.getMethod()))
                        session.setAttribute(J_POST, getParameters(request));
                }
            }

            // Send the challenge.
            String loginPath = URIUtil.addPaths(request.getContext().getContextPath(), _loginPath);
            if (_dispatch)
            {
                HttpURI.Mutable newUri = HttpURI.build(request.getHttpURI()).pathQuery(loginPath);
                return new AuthenticationState.ServeAs(newUri);
            }
            else
            {
                String redirectUri = session.encodeURI(request, loginPath, true);
                Response.sendRedirect(request, response, callback, redirectUri, true);
                return AuthenticationState.CHALLENGE;
            }
        }
        catch (Throwable t)
        {
            throw new ServerAuthException(t);
        }
    }

    /**
     * Report an error case either by redirecting to the error page if it is defined, otherwise sending a 403 response.
     * If the message parameter is not null, a query parameter with a key of {@link #ERROR_PARAMETER} and value of the error
     * message will be logged and added to the error redirect URI if the error page is defined.
     * @param request the request.
     * @param response the response.
     * @param callback the callback.
     * @param message the reason for the error or null.
     */
    private AuthenticationState sendError(Request request, Response response, Callback callback, String message)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Authentication FAILED: {}", message);

        if (_errorPath == null)
        {
            return AuthenticationState.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
        }
        else
        {
            String contextPath = Request.getContextPath(request);
            String redirectUri = URIUtil.addPaths(contextPath, _errorPath);
            if (message != null)
            {
                String query = URIUtil.addQueries(ERROR_PARAMETER + "=" + UrlEncoded.encodeString(message), _errorQuery);
                redirectUri = URIUtil.addPathQuery(URIUtil.addPaths(contextPath, _errorPath), query);
            }

            int redirectCode = request.getConnectionMetaData().getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion()
                ? HttpStatus.MOVED_TEMPORARILY_302 : HttpStatus.SEE_OTHER_303;
            Response.sendRedirect(request, response, callback, redirectCode, redirectUri, true);
            return AuthenticationState.SEND_FAILURE;
        }
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

    public boolean isLoginPage(String uri)
    {
        return matchURI(uri, _loginPath);
    }

    public boolean isAuthenticationRequest(String uri)
    {
        return matchURI(uri, _authenticationPath);
    }

    public boolean isNonceRequest(String uri)
    {
        return matchURI(uri, _noncePath);
    }

    private boolean matchURI(String uri, String path)
    {
        int jsc = uri.indexOf(path);
        if (jsc < 0)
            return false;
        int e = jsc + path.length();
        if (e == uri.length())
            return true;
        char c = uri.charAt(e);
        return c == ';' || c == '#' || c == '/' || c == '?';
    }

    public boolean isErrorPage(String pathInContext)
    {
        if (_errorPath == null)
            return false;
        return pathInContext != null && (pathInContext.equals(_errorPath));
    }

    protected String createNonce(Session session)
    {
        String nonce = EthereumUtil.createNonce();
        synchronized (session)
        {
            @SuppressWarnings("unchecked")
            Set<String> attribute = (Set<String>)session.getAttribute(NONCE_SET_ATTR);
            if (attribute == null)
                session.setAttribute(NONCE_SET_ATTR, attribute = new FixedSizeSet<>(5));
            if (!attribute.add(nonce))
                throw new IllegalStateException("Nonce already in use");
        }
        return nonce;
    }

    protected boolean redeemNonce(Session session, String nonce)
    {
        synchronized (session)
        {
            @SuppressWarnings("unchecked")
            Set<String> attribute = (Set<String>)session.getAttribute(NONCE_SET_ATTR);
            if (attribute == null)
                return false;
            return attribute.remove(nonce);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            "loginPath=" + _loginPath,
            "authenticationPath=" + _authenticationPath,
            "noncePath=" + _noncePath,
            "errorPath=" + _errorPath,
            "errorQuery=" + _errorQuery,
            "dispatch=" + _dispatch,
            "authenticateNewUsers=" + _authenticateNewUsers,
            "logoutRedirectPath=" + _logoutRedirectPath,
            "maxMessageSize=" + _maxMessageSize,
            "chainIds=" + _chainIds,
            "domains=" + _domains
        );
    }

    public static class FixedSizeSet<T> extends LinkedHashSet<T>
    {
        private final int maxSize;

        public FixedSizeSet(int maxSize)
        {
            super(maxSize);
            this.maxSize = maxSize;
        }

        @Override
        public boolean add(T element)
        {
            if (size() >= maxSize)
            {
                Iterator<T> it = iterator();
                if (it.hasNext())
                {
                    it.next();
                    it.remove();
                }
            }
            return super.add(element);
        }
    }

    public record SignedMessage(String message, String signature)
    {
        public String recoverAddress()
        {
            return EthereumUtil.recoverAddress(this);
        }
    }
}
