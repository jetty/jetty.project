//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.jaspi;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.ClientAuthConfig;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.module.ServerAuthModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * Support for JASPI 2.0 Servlet Container Profile. 
 */
public class JaspiAuthConfigProvider implements AuthConfigProvider
{

    private static final Logger log = LoggerFactory.getLogger(JaspiAuthConfigProvider.class);

    private Map<String, String> providerProperties;
    private ServerAuthModule serverAuthModule;

    /**
     * Constructor with signature and implementation that's required by API.
     *
     * @param properties properties
     * @param factory    factory
     */
    public JaspiAuthConfigProvider(Map<String, String> properties, AuthConfigFactory factory)
    {
        Objects.requireNonNull(properties);
        if (properties.containsKey("ServerAuthModule"))
        {
            this.serverAuthModule = createServerAuthModule(properties.get("ServerAuthModule"));
        } 
        else 
        {
            throw new IllegalArgumentException("Missing property 'ServerAuthModule', cannot create JaspiAuthConfigProvider");
        }

        this.providerProperties = Map.copyOf(properties);
        
        // API requires self registration if factory is provided.
        if (factory != null)
        {
            // message layer identifier = "HttpServlet" per JSR-196 Chapter 3.1
            
            // appContext (per JSR-196 Chapter 3.2 should be constructed as 'AppContextID ::= hostname blank context-path' where hostname is the logical server name.
            // not available at this point it seems, or may be read from properties?
            factory.registerConfigProvider(this, "HttpServlet", null, "Auto registration");
        }
    }

    /**
     * Convenience constructor.
     * 
     * @param properties properties 
     * @param serverAuthModuleClassName Name of class implementing ServerAuthModule ifc
     */
    public JaspiAuthConfigProvider(Map<String, String> properties, String serverAuthModuleClassName)
    {
        Objects.requireNonNull(serverAuthModuleClassName);
        log.trace("Instantiated with: {}", serverAuthModuleClassName);

        this.serverAuthModule = createServerAuthModule(serverAuthModuleClassName);
        this.providerProperties = properties == null ? Collections.emptyMap() : Map.copyOf(properties);
        
    }
    
    public JaspiAuthConfigProvider(ServerAuthModule serverAuthModule)
    {
        this.serverAuthModule = Objects.requireNonNull(serverAuthModule);
        this.providerProperties = Collections.emptyMap();
        log.trace("Instantiated with: {}", serverAuthModule.getClass().getName());
    }

    /**
     * Not implemented
     */
    @Override
    public ClientAuthConfig getClientAuthConfig(String layer, String appContext, CallbackHandler handler)
            throws AuthException
    {
        return null;
    }

    /**
     * The actual factory method that creates the factory used to eventually obtain
     * the delegate for a SAM.
     */
    @Override
    public ServerAuthConfig getServerAuthConfig(String layer, String appContext, CallbackHandler handler)
            throws AuthException
    {
        log.trace("getServerAuthConfig");
        return new SimpleAuthConfig(layer, appContext, handler, providerProperties, serverAuthModule);
    }

    @Override
    public void refresh()
    {
    }

    private ServerAuthModule createServerAuthModule(String serverAuthModuleClassName)
    {
        try
        {
            return (ServerAuthModule)Class.forName(serverAuthModuleClassName).getDeclaredConstructor()
                    .newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

}
