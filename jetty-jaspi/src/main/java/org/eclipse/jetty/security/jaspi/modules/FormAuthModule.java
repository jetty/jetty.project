//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security.jaspi.modules;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.MessagePolicy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.security.authentication.LoginCallbackImpl;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;

@Deprecated
public class FormAuthModule extends BaseAuthModule
{
    private static final Logger LOG = Log.getLogger(FormAuthModule.class);

    /* ------------------------------------------------------------ */
    public final static String __J_URI = "org.eclipse.jetty.util.URI";

    public final static String __J_AUTHENTICATED = "org.eclipse.jetty.server.Auth";

    public final static String __J_SECURITY_CHECK = "/j_security_check";

    public final static String __J_USERNAME = "j_username";

    public final static String __J_PASSWORD = "j_password";

    // private String realmName;
    public static final String LOGIN_PAGE_KEY = "org.eclipse.jetty.security.jaspi.modules.LoginPage";

    public static final String ERROR_PAGE_KEY = "org.eclipse.jetty.security.jaspi.modules.ErrorPage";

    public static final String SSO_SOURCE_KEY = "org.eclipse.jetty.security.jaspi.modules.SsoSource";

    private String _formErrorPage;

    private String _formErrorPath;

    private String _formLoginPage;

    private String _formLoginPath;


    public FormAuthModule()
    {
    }

    public FormAuthModule(CallbackHandler callbackHandler, String loginPage, String errorPage)
    {
        super(callbackHandler);
        setLoginPage(loginPage);
        setErrorPage(errorPage);
    }


    @Override
    public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, 
                           CallbackHandler handler, Map options) 
    throws AuthException
    {
        super.initialize(requestPolicy, responsePolicy, handler, options);
        setLoginPage((String) options.get(LOGIN_PAGE_KEY));
        setErrorPage((String) options.get(ERROR_PAGE_KEY));
    }

    private void setLoginPage(String path)
    {
        if (!path.startsWith("/"))
        {
            LOG.warn("form-login-page must start with /");
            path = "/" + path;
        }
        _formLoginPage = path;
        _formLoginPath = path;
        if (_formLoginPath.indexOf('?') > 0) _formLoginPath = _formLoginPath.substring(0, _formLoginPath.indexOf('?'));
    }

    /* ------------------------------------------------------------ */
    private void setErrorPage(String path)
    {
        if (path == null || path.trim().length() == 0)
        {
            _formErrorPath = null;
            _formErrorPage = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("form-error-page must start with /");
                path = "/" + path;
            }
            _formErrorPage = path;
            _formErrorPath = path;

            if (_formErrorPath.indexOf('?') > 0) _formErrorPath = _formErrorPath.substring(0, _formErrorPath.indexOf('?'));
        }
    }

    @Override
    public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
    {
       
        HttpServletRequest request = (HttpServletRequest) messageInfo.getRequestMessage();
        HttpServletResponse response = (HttpServletResponse) messageInfo.getResponseMessage();
        String uri = request.getRequestURI();
        if (uri==null)
            uri=URIUtil.SLASH;
        
        boolean mandatory = isMandatory(messageInfo);  
        mandatory |= isJSecurityCheck(uri);
        HttpSession session = request.getSession(mandatory);
        
        // not mandatory or its the login or login error page don't authenticate
        if (!mandatory || isLoginOrErrorPage(URIUtil.addPaths(request.getServletPath(),request.getPathInfo()))) 
            return AuthStatus.SUCCESS;  // TODO return null for do nothing?

        try
        {
            // Handle a request for authentication.
            if (isJSecurityCheck(uri))
            {
                final String username = request.getParameter(__J_USERNAME);
                final String password = request.getParameter(__J_PASSWORD);
            
                boolean success = tryLogin(messageInfo, clientSubject, response, session, username, new Password(password));
                if (success)
                {
                    // Redirect to original request                    
                    String nuri=null;
                    synchronized(session)
                    {
                        nuri = (String) session.getAttribute(__J_URI);
                    }
                    
                    if (nuri == null || nuri.length() == 0)
                    {
                        nuri = request.getContextPath();
                        if (nuri.length() == 0) 
                            nuri = URIUtil.SLASH;
                    }
                   
                    response.setContentLength(0);   
                    response.sendRedirect(response.encodeRedirectURL(nuri));
                    return AuthStatus.SEND_CONTINUE;
                }
                // not authenticated
                if (LOG.isDebugEnabled()) LOG.debug("Form authentication FAILED for " + StringUtil.printable(username));
                if (_formErrorPage == null)
                {
                    if (response != null) response.sendError(HttpServletResponse.SC_FORBIDDEN);
                }
                else
                {
                    response.setContentLength(0);
                    response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths(request.getContextPath(), _formErrorPage)));
                }
                // TODO is this correct response if isMandatory false??? Can
                // that occur?
                return AuthStatus.SEND_FAILURE;
            }
            
            
            // Check if the session is already authenticated.
            SessionAuthentication sessionAuth = (SessionAuthentication)session.getAttribute(SessionAuthentication.__J_AUTHENTICATED);
            if (sessionAuth != null)
            {                
                //TODO: ideally we would like the form auth module to be able to invoke the 
                //loginservice.validate() method to check the previously authed user, but it is not visible
                //to FormAuthModule
                if (sessionAuth.getUserIdentity().getSubject() == null)
                    return AuthStatus.SEND_FAILURE;

                Set<Object> credentials = sessionAuth.getUserIdentity().getSubject().getPrivateCredentials();
                if (credentials == null || credentials.isEmpty())
                    return AuthStatus.SEND_FAILURE; //if no private credentials, assume it cannot be authenticated

                clientSubject.getPrivateCredentials().addAll(credentials);
                clientSubject.getPrivateCredentials().add(sessionAuth.getUserIdentity());

                return AuthStatus.SUCCESS;  
            }
            

            // if we can't send challenge
            if (DeferredAuthentication.isDeferred(response))
                return AuthStatus.SUCCESS; 
            

            // redirect to login page  
            StringBuffer buf = request.getRequestURL();
            if (request.getQueryString() != null)
                buf.append("?").append(request.getQueryString());

            synchronized (session)
            {
                session.setAttribute(__J_URI, buf.toString());
            }
            
            response.setContentLength(0);
            response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths(request.getContextPath(), _formLoginPage)));
            return AuthStatus.SEND_CONTINUE;
        }
        catch (IOException e)
        {
            throw new AuthException(e.getMessage());
        }
        catch (UnsupportedCallbackException e)
        {
            throw new AuthException(e.getMessage());
        }

    }
    
    /* ------------------------------------------------------------ */
    public boolean isJSecurityCheck(String uri)
    {
        int jsc = uri.indexOf(__J_SECURITY_CHECK);
        
        if (jsc<0)
            return false;
        int e=jsc+__J_SECURITY_CHECK.length();
        if (e==uri.length())
            return true;
        char c = uri.charAt(e);
        return c==';'||c=='#'||c=='/'||c=='?';
    }

    private boolean tryLogin(MessageInfo messageInfo, Subject clientSubject, 
                             HttpServletResponse response, HttpSession session, 
                             String username, Password password) 
    throws AuthException, IOException, UnsupportedCallbackException
    {
        if (login(clientSubject, username, password, Constraint.__FORM_AUTH, messageInfo))
        {
            char[] pwdChars = password.toString().toCharArray();
            Set<LoginCallbackImpl> loginCallbacks = clientSubject.getPrivateCredentials(LoginCallbackImpl.class);
           
            if (!loginCallbacks.isEmpty())
            {
                LoginCallbackImpl loginCallback = loginCallbacks.iterator().next();
                Set<UserIdentity> userIdentities = clientSubject.getPrivateCredentials(UserIdentity.class);
                if (!userIdentities.isEmpty())
                {
                    UserIdentity userIdentity = userIdentities.iterator().next();
                   
                SessionAuthentication sessionAuth = new SessionAuthentication(Constraint.__FORM_AUTH, userIdentity, password);
                session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, sessionAuth);
                }
            }

            return true;
        }
        return false;
    }

    public boolean isLoginOrErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_formErrorPath) || pathInContext.equals(_formLoginPath));
    }

}
