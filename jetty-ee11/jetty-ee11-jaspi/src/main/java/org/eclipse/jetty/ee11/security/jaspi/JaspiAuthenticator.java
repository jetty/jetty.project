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

package org.eclipse.jetty.ee11.security.jaspi;

import java.util.Map;
import javax.security.auth.Subject;

import jakarta.security.auth.message.config.ServerAuthConfig;
import org.eclipse.jetty.ee.security.jaspi.ServletCallbackHandler;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;

/**
 * Implementation of Jetty {@link LoginAuthenticator} that is a bridge from Jakarta Authentication to Jetty Security.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JaspiAuthenticator extends org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticator
{
    public JaspiAuthenticator(Subject serviceSubject, String appContext, boolean allowLazyAuthentication)
    {
        super(serviceSubject, appContext, allowLazyAuthentication);
    }

    public JaspiAuthenticator(ServerAuthConfig authConfig, Map authProperties, ServletCallbackHandler callbackHandler, Subject serviceSubject, boolean allowLazyAuthentication, IdentityService identityService)
    {
        super(authConfig, authProperties, callbackHandler, serviceSubject, allowLazyAuthentication, identityService);
    }
}
