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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.security.auth.Subject;

import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.security.Authenticator;
import org.eclipse.jetty.ee9.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.ee9.security.DefaultAuthenticatorFactory;
import org.eclipse.jetty.ee9.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
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

    private static final String DEFAULT_SERVER_NAME = "server";

    private Subject _serviceSubject;
    private String _serverName;

    /**
     * Get the serviceSubject.
     * @return the serviceSubject
     */
    public Subject getServiceSubject()
    {
        return _serviceSubject;
    }

    /**
     * Set the serviceSubject to set.
     * @param serviceSubject the serviceSubject to set
     */
    public void setServiceSubject(Subject serviceSubject)
    {
        _serviceSubject = serviceSubject;
    }

    /**
     * Get the serverName.
     * @return the serverName
     */
    public String getServerName()
    {
        return _serverName;
    }

    /**
     * Set the name of the logical host this server is also known by, so a provider registered
     * under that identifier is found even though Jetty does not compute it for the context.
     *
     * @param serverName the serverName to set
     */
    public void setServerName(String serverName)
    {
        _serverName = serverName;
    }

    @Override
    public Authenticator getAuthenticator(Server server, ServletContext context, AuthConfiguration configuration, IdentityService identityService, LoginService loginService)
    {
        Authenticator declared = super.getAuthenticator(server, context, configuration, identityService, loginService);

        AuthConfigFactory factory = AuthConfigFactory.getFactory();
        if (factory == null)
            return declared;

        List<String> appContexts = getAppContexts(findServerName(context), context.getVirtualServerName(), context.getContextPath());
        AuthConfigProvider provider = null;
        for (String appContext : appContexts)
        {
            provider = factory.getConfigProvider(MESSAGE_LAYER, appContext, null);
            if (provider != null)
                break;
        }

        if (declared != null)
        {
            // A LoginAuthenticator with no LoginService could never authenticate anybody, so Jakarta Authentication takes the context rather than the start failing.
            if (declared instanceof LoginAuthenticator && configuration.getLoginService() == null)
            {
                LOG.warn("No LoginService for auth-method {} in {}, using Jakarta Authentication instead",
                    configuration.getAuthMethod(), context);
                declared = null;
            }
        }
        else if (provider == null && StringUtil.isNotBlank(configuration.getAuthMethod()))
        {
            // An auth-method neither this factory nor DefaultAuthenticatorFactory knows is another factory's, and SecurityHandler only tries the next discovered factory while this returns null.
            return null;
        }

        // Until a provider is registered the profile leaves the request to constraint processing "without further reliance on this profile", so the declared auth-method stands in.
        return new JaspiAuthenticator(findServiceSubject(server), appContexts,
            ContextHandler.getContextHandler(context), declared);
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
        Collection<Subject> subjects = server.getBeans(Subject.class);
        if (!subjects.isEmpty())
            return subjects.iterator().next();
        return null;
    }

    /**
     * If {@link #setServerName(String)} has not been called, this is the virtual server name the
     * servlet API reports for the context.
     *
     * @param context the context
     * @return the server name
     */
    protected String findServerName(ServletContext context)
    {
        if (_serverName != null)
            return _serverName;

        return context.getVirtualServerName();
    }

    /**
     * The application context identifiers a context answers to, the one it is known by first: the
     * name of the logical host it is deployed on, a blank, and the context path. A context with no
     * logical host name of its own is known by {@code server}, which the reference implementation,
     * Jetty before 13 and the TCK all use. It also answers to the identifier the Servlet Container
     * Profile computes from {@link ServletContext#getVirtualServerName()}, to the one
     * {@link #setServerName(String)} names it, and, with no virtual server name, to the one a
     * module registering for itself builds, which spells the missing name "null".
     *
     * @param serverName the name of the logical host, or null if the context has none
     * @param virtualServerName the virtual server name of the context, or null if it has none
     * @param contextPath the context path
     * @return the application context identifiers, most preferred first
     */
    static List<String> getAppContexts(String serverName, String virtualServerName, String contextPath)
    {
        // A set: the two names are usually the same, and a duplicate identifier would attach the listener twice.
        Set<String> appContexts = new LinkedHashSet<>();
        appContexts.add(getAppContext(serverName, contextPath));
        appContexts.add(getAppContext(virtualServerName, contextPath));
        if (virtualServerName == null)
            appContexts.add(appContext(null, contextPath));
        return List.copyOf(appContexts);
    }

    private static String getAppContext(String serverName, String contextPath)
    {
        return appContext(serverName == null ? DEFAULT_SERVER_NAME : serverName, contextPath);
    }

    private static String appContext(String serverName, String contextPath)
    {
        return serverName + " " + contextPath;
    }
}
