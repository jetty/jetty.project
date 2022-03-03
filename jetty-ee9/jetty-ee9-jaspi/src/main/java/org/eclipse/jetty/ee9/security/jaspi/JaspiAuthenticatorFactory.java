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

package org.eclipse.jetty.ee9.security.jaspi;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import javax.security.auth.Subject;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee9.security.Authenticator;
import org.eclipse.jetty.ee9.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.ee9.security.DefaultAuthenticatorFactory;
import org.eclipse.jetty.ee9.security.IdentityService;
import org.eclipse.jetty.ee9.security.LoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jakarta Authentication (JASPI) Authenticator Factory.
 * 
 * This is used to link a jetty-security {@link Authenticator.Factory} to a Jakarta Authentication {@link AuthConfigFactory}.
 * <p>
 * This should be initialized with the provided {@link DefaultAuthConfigFactory} to set up Jakarta Authentication {@link AuthConfigFactory} before use. 
 * (A different {@link AuthConfigFactory} may also be provided using the same steps below)
 * <p>
 * To initialize either:
 * <ul>
 * <li>invoke {@link AuthConfigFactory#setFactory(AuthConfigFactory)}</li>
 * <li>Alternatively: set {@link AuthConfigFactory#DEFAULT_FACTORY_SECURITY_PROPERTY}</li>
 * </ul>
 *
 */
public class JaspiAuthenticatorFactory extends DefaultAuthenticatorFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(JaspiAuthenticatorFactory.class);
    public static final String MESSAGE_LAYER = "HttpServlet";

    private Subject _serviceSubject;
    private String _serverName;

    /**
     * @return the serviceSubject
     */
    public Subject getServiceSubject()
    {
        return _serviceSubject;
    }

    /**
     * @param serviceSubject the serviceSubject to set
     */
    public void setServiceSubject(Subject serviceSubject)
    {
        _serviceSubject = serviceSubject;
    }

    /**
     * @return the serverName
     */
    public String getServerName()
    {
        return _serverName;
    }

    /**
     * @param serverName the serverName to set
     */
    public void setServerName(String serverName)
    {
        _serverName = serverName;
    }

    @Override
    public Authenticator getAuthenticator(Server server, ServletContext context, AuthConfiguration configuration, IdentityService identityService, LoginService loginService)
    {
        AuthConfigFactory factory = AuthConfigFactory.getFactory();
        if (factory == null)
            return null;

        String serverName = findServerName(context, server);
        Subject serviceSubject = findServiceSubject(server);
        String contextPath = StringUtil.isEmpty(context.getContextPath()) ? "/" : context.getContextPath();
        String appContext = serverName + " " + contextPath;

        // We will only create the Authenticator if an AuthConfigProvider matches this context.
        if (factory.getConfigProvider(MESSAGE_LAYER, appContext, null) == null)
            return null;

        return new JaspiAuthenticator(serviceSubject, appContext, true);
    }

    /**
     * Find a service Subject. If {@link #setServiceSubject(Subject)} has not been
     * used to set a subject, then the {@link Server#getBeans(Class)} method is used
     * to look for a Subject.
     *
     * @param server the server to pull the Subject from
     * @return the subject
     */
    protected Subject findServiceSubject(Server server)
    {
        if (_serviceSubject != null)
            return _serviceSubject;
        List<Subject> subjects = (List<Subject>)server.getBeans(Subject.class);
        if (subjects.size() > 0)
            return subjects.get(0);
        return null;
    }

    /**
     * Find a servername. If {@link #setServerName(String)} has not been called,
     * then use the virtualServerName of the context. 
     * If this is also null, then use the name of the a principal in the service subject. 
     * If none are found, return "server".
     * @param context 
     *
     * @param server the server to find the name of
     * @return the server name from the service Subject (or default value if not
     *         found in subject or principals)
     */
    protected String findServerName(ServletContext context, Server server)
    {   
        if (_serverName != null)
            return _serverName;
        
        String virtualServerName = context.getVirtualServerName();
        if (virtualServerName != null)
            return virtualServerName;

        Subject subject = findServiceSubject(server);
        if (subject != null)
        {
            Set<Principal> principals = subject.getPrincipals();
            if (principals != null && !principals.isEmpty())
                return principals.iterator().next().getName();
        }

        return "server";
    }
}
