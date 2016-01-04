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

package org.eclipse.jetty.security.jaspi;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.config.AuthConfigFactory;
import javax.security.auth.message.config.AuthConfigProvider;
import javax.security.auth.message.config.RegistrationListener;
import javax.security.auth.message.config.ServerAuthConfig;
import javax.servlet.ServletContext;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.security.DefaultAuthenticatorFactory;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class JaspiAuthenticatorFactory extends DefaultAuthenticatorFactory
{
    private static final Logger LOG = Log.getLogger(JaspiAuthenticatorFactory.class);

    private static String MESSAGE_LAYER = "HTTP";
    
    private Subject _serviceSubject;
    private String _serverName;
    

    /* ------------------------------------------------------------ */
    /**
     * @return the serviceSubject
     */
    public Subject getServiceSubject()
    {
        return _serviceSubject;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param serviceSubject the serviceSubject to set
     */
    public void setServiceSubject(Subject serviceSubject)
    {
        _serviceSubject = serviceSubject;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the serverName
     */
    public String getServerName()
    {
        return _serverName;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param serverName the serverName to set
     */
    public void setServerName(String serverName)
    {
        _serverName = serverName;
    }

    /* ------------------------------------------------------------ */
    public Authenticator getAuthenticator(Server server, ServletContext context, AuthConfiguration configuration, IdentityService identityService, LoginService loginService)
    {
        Authenticator authenticator=null;
        try 
        {
            AuthConfigFactory authConfigFactory = AuthConfigFactory.getFactory();
            RegistrationListener listener = new RegistrationListener()
            {
                public void notify(String layer, String appContext)
                {}
            };

            Subject serviceSubject=findServiceSubject(server);
            String serverName=findServerName(server,serviceSubject);
            String contextPath=context.getContextPath();
            if (contextPath==null || contextPath.length()==0)
                contextPath="/";
            String appContext = serverName + " " + context.getContextPath();
            
            AuthConfigProvider authConfigProvider = authConfigFactory.getConfigProvider(MESSAGE_LAYER,appContext,listener);
  
            if (authConfigProvider != null)
            {
                ServletCallbackHandler servletCallbackHandler = new ServletCallbackHandler(loginService);
                ServerAuthConfig serverAuthConfig = authConfigProvider.getServerAuthConfig(MESSAGE_LAYER,appContext,servletCallbackHandler);
                if (serverAuthConfig != null)
                {
                    Map map = new HashMap();
                    for (String key : configuration.getInitParameterNames())
                        map.put(key,configuration.getInitParameter(key));
                    authenticator= new JaspiAuthenticator(serverAuthConfig,map,servletCallbackHandler,
                                serviceSubject,true, identityService);
                }
            }
        } 
        catch (AuthException e) 
        {
            LOG.warn(e);
        }
        return authenticator;
    }

    /* ------------------------------------------------------------ */
    /** Find a service Subject.
     * If {@link #setServiceSubject(Subject)} has not been used to 
     * set a subject, then the {@link Server#getBeans(Class)} method is
     * used to look for a Subject.
     * @param server the server to pull the Subject from
     * @return the subject
     */
    protected Subject findServiceSubject(Server server)
    {
        if (_serviceSubject!=null)
            return _serviceSubject;
        List<Subject> subjects = (List<Subject>)server.getBeans(Subject.class);
        if (subjects.size()>0)
            return (Subject)subjects.get(0);
        return null;
    }

    /* ------------------------------------------------------------ */
    /** Find a servername.
     * If {@link #setServerName(String)} has not been called, then
     * use the name of the a principal in the service subject.
     * If not found, return "server".
     * @param server not used
     * @param subject the subject to use
     * @return the server name from the subject (or default value if not found in subject or principals)
     */
    protected String findServerName(Server server, Subject subject)
    {
        if (_serverName!=null)
            return _serverName;
        if (subject!=null)
        {
            Set<Principal> principals = subject.getPrincipals();
            if (principals!=null && !principals.isEmpty())
                return principals.iterator().next().getName();
        }
        
        return "server";
    }
}
