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

package org.eclipse.jetty.ee9.security;

import java.io.Serializable;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.PrivilegedAction;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee9.nested.UserIdentity;
import org.eclipse.jetty.ee9.security.authentication.AuthorizationService;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A configurable (as opposed to using system properties) SPNEGO LoginService.</p>
 * <p>At startup, this LoginService will login via JAAS the service principal, composed
 * of the {@link #getServiceName() service name} and the {@link #getHostName() host name},
 * for example {@code HTTP/wonder.com}, using a {@code keyTab} file as the service principal
 * credentials.</p>
 * <p>Upon receiving an HTTP request, the server tries to authenticate the client
 * calling {@link #login(String, Object, ServletRequest)} where the GSS APIs are used to
 * verify client tokens and (perhaps after a few round-trips) a {@code GSSContext} is
 * established.</p>
 */
public class ConfigurableSpnegoLoginService extends ContainerLifeCycle implements LoginService
{
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurableSpnegoLoginService.class);

    private final GSSManager _gssManager = GSSManager.getInstance();
    private final String _realm;
    private final AuthorizationService _authorizationService;
    private IdentityService _identityService = new DefaultIdentityService();
    private String _serviceName;
    private Path _keyTabPath;
    private String _hostName;
    private SpnegoContext _context;

    public ConfigurableSpnegoLoginService(String realm, AuthorizationService authorizationService)
    {
        _realm = realm;
        _authorizationService = authorizationService;
    }

    /**
     * @return the realm name
     */
    @Override
    public String getName()
    {
        return _realm;
    }

    /**
     * @return the path of the keyTab file containing service credentials
     */
    public Path getKeyTabPath()
    {
        return _keyTabPath;
    }

    /**
     * @param keyTabFile the path of the keyTab file containing service credentials
     */
    public void setKeyTabPath(Path keyTabFile)
    {
        _keyTabPath = keyTabFile;
    }

    /**
     * @return the service name, typically "HTTP"
     * @see #getHostName()
     */
    public String getServiceName()
    {
        return _serviceName;
    }

    /**
     * @param serviceName the service name
     * @see #setHostName(String)
     */
    public void setServiceName(String serviceName)
    {
        _serviceName = serviceName;
    }

    /**
     * @return the host name of the service
     * @see #setServiceName(String)
     */
    public String getHostName()
    {
        return _hostName;
    }

    /**
     * @param hostName the host name of the service
     */
    public void setHostName(String hostName)
    {
        _hostName = hostName;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_hostName == null)
            _hostName = InetAddress.getLocalHost().getCanonicalHostName();
        if (LOG.isDebugEnabled())
            LOG.debug("Retrieving credentials for service {}/{}", getServiceName(), getHostName());
        LoginContext loginContext = new LoginContext("", null, null, new SpnegoConfiguration());
        loginContext.login();
        Subject subject = loginContext.getSubject();
        _context = Subject.doAs(subject, newSpnegoContext(subject));
        super.doStart();
    }

    private PrivilegedAction<SpnegoContext> newSpnegoContext(Subject subject)
    {
        return () ->
        {
            try
            {
                GSSName serviceName = _gssManager.createName(getServiceName() + "@" + getHostName(), GSSName.NT_HOSTBASED_SERVICE);
                Oid kerberosOid = new Oid("1.2.840.113554.1.2.2");
                Oid spnegoOid = new Oid("1.3.6.1.5.5.2");
                Oid[] mechanisms = new Oid[]{kerberosOid, spnegoOid};
                GSSCredential serviceCredential = _gssManager.createCredential(serviceName, GSSCredential.DEFAULT_LIFETIME, mechanisms, GSSCredential.ACCEPT_ONLY);
                SpnegoContext context = new SpnegoContext();
                context._subject = subject;
                context._serviceCredential = serviceCredential;
                return context;
            }
            catch (GSSException x)
            {
                throw new RuntimeException(x);
            }
        };
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest req)
    {
        Subject subject = _context._subject;
        HttpServletRequest request = (HttpServletRequest)req;
        HttpSession httpSession = request.getSession(false);
        GSSContext gssContext = null;
        if (httpSession != null)
        {
            GSSContextHolder holder = (GSSContextHolder)httpSession.getAttribute(GSSContextHolder.ATTRIBUTE);
            gssContext = holder == null ? null : holder.gssContext;
        }
        if (gssContext == null)
            gssContext = Subject.doAs(subject, newGSSContext());

        byte[] input = Base64.getDecoder().decode((String)credentials);
        byte[] output = Subject.doAs(_context._subject, acceptGSSContext(gssContext, input));
        String token = Base64.getEncoder().encodeToString(output);

        String userName = toUserName(gssContext);
        // Save the token in the principal so it can be sent in the response.
        SpnegoUserPrincipal principal = new SpnegoUserPrincipal(userName, token);
        if (gssContext.isEstablished())
        {
            if (httpSession != null)
                httpSession.removeAttribute(GSSContextHolder.ATTRIBUTE);

            UserIdentity roles = _authorizationService.getUserIdentity(request, userName);
            return new SpnegoUserIdentity(subject, principal, roles);
        }
        else
        {
            // The GSS context is not established yet, save it into the HTTP session.
            if (httpSession == null)
                httpSession = request.getSession(true);
            GSSContextHolder holder = new GSSContextHolder(gssContext);
            httpSession.setAttribute(GSSContextHolder.ATTRIBUTE, holder);

            // Return an unestablished UserIdentity.
            return new SpnegoUserIdentity(subject, principal, null);
        }
    }

    private PrivilegedAction<GSSContext> newGSSContext()
    {
        return () ->
        {
            try
            {
                return _gssManager.createContext(_context._serviceCredential);
            }
            catch (GSSException x)
            {
                throw new RuntimeException(x);
            }
        };
    }

    private PrivilegedAction<byte[]> acceptGSSContext(GSSContext gssContext, byte[] token)
    {
        return () ->
        {
            try
            {
                return gssContext.acceptSecContext(token, 0, token.length);
            }
            catch (GSSException x)
            {
                throw new RuntimeException(x);
            }
        };
    }

    private String toUserName(GSSContext gssContext)
    {
        try
        {
            String name = gssContext.getSrcName().toString();
            int at = name.indexOf('@');
            if (at < 0)
                return name;
            return name.substring(0, at);
        }
        catch (GSSException x)
        {
            throw new RuntimeException(x);
        }
    }

    @Override
    public boolean validate(UserIdentity user)
    {
        return false;
    }

    @Override
    public IdentityService getIdentityService()
    {
        return _identityService;
    }

    @Override
    public void setIdentityService(IdentityService identityService)
    {
        _identityService = identityService;
    }

    @Override
    public void logout(UserIdentity user)
    {
    }

    private class SpnegoConfiguration extends Configuration
    {
        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name)
        {
            final String principal = getServiceName() + "/" + getHostName();
            Map<String, Object> options = new HashMap<>();
            if (LOG.isDebugEnabled())
                options.put("debug", "true");
            options.put("doNotPrompt", "true");
            options.put("refreshKrb5Config", "true");
            options.put("principal", principal);
            options.put("useKeyTab", "true");
            Path keyTabPath = getKeyTabPath();
            if (keyTabPath != null)
                options.put("keyTab", keyTabPath.toAbsolutePath().toString());
            // This option is required to store the service credentials in
            // the Subject, so that it can be later used by acceptSecContext().
            options.put("storeKey", "true");
            options.put("isInitiator", "false");
            String moduleClass = "com.sun.security.auth.module.Krb5LoginModule";
            AppConfigurationEntry config = new AppConfigurationEntry(moduleClass, AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options);
            return new AppConfigurationEntry[]{config};
        }
    }

    private static class SpnegoContext
    {
        private Subject _subject;
        private GSSCredential _serviceCredential;
    }

    private static class GSSContextHolder implements Serializable
    {
        public static final String ATTRIBUTE = GSSContextHolder.class.getName();

        private final transient GSSContext gssContext;

        private GSSContextHolder(GSSContext gssContext)
        {
            this.gssContext = gssContext;
        }
    }
}
