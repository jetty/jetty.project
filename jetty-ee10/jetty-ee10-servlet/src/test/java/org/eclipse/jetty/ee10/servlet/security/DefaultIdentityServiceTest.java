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

package org.eclipse.jetty.ee10.servlet.security;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

public class DefaultIdentityServiceTest
{
    @Test
    public void testDefaultIdentityService() throws Exception
    {
        Server server = new Server();
        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        TestAuthenticator authenticator = new TestAuthenticator();
        securityHandler.setAuthenticator(authenticator);

        try
        {
            server.setHandler(securityHandler);
            server.start();

            // The DefaultIdentityService should have been created by default.
            assertThat(securityHandler.getIdentityService(), instanceOf(DefaultIdentityService.class));
            assertThat(authenticator.getIdentityService(), instanceOf(DefaultIdentityService.class));
        }
        finally
        {
            server.stop();
        }
    }

    public static class TestAuthenticator implements Authenticator
    {
        private IdentityService _identityService;

        public IdentityService getIdentityService()
        {
            return _identityService;
        }

        @Override
        public void setConfiguration(AuthConfiguration configuration)
        {
            _identityService = configuration.getIdentityService();
        }

        @Override
        public String getAuthMethod()
        {
            return getClass().getSimpleName();
        }

        @Override
        public void prepareRequest(Request request)
        {
        }

        @Override
        public Authentication validateRequest(Request request, Response response, Callback callback, boolean mandatory) throws ServerAuthException
        {
            return null;
        }

        @Override
        public boolean secureResponse(Request request, Response response, Callback callback, boolean mandatory, Authentication.User validatedUser) throws ServerAuthException
        {
            return false;
        }
    }
}
