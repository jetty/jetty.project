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

package org.eclipse.jetty.security;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.internal.DefaultUserIdentity;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;

public class AuthenticationTestHandler extends Handler.Abstract
{
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Fields parameters = Request.extractQueryParameters(request);
        List<String> actions = parameters.getValues("action");
        Deque<String> usernames = new LinkedList<>(parameters.getValuesOrEmpty("username"));
        Deque<String> passwords = new LinkedList<>(parameters.getValuesOrEmpty("password"));

        StringBuilder out = new StringBuilder();
        if (actions != null)
        {
            for (String action : actions)
            {
                switch (action)
                {
                    case "authenticate" ->
                    {
                        AuthenticationState.Succeeded succeeded = AuthenticationState.authenticate(request);
                        out.append(succeeded == null ? "-" : succeeded.getUserIdentity().getUserPrincipal());
                    }

                    case "challenge" ->
                    {
                        AuthenticationState.Succeeded succeeded = AuthenticationState.authenticate(request, response, callback);
                        if (succeeded == null)
                            return true;
                        out.append(succeeded.getUserIdentity().getUserPrincipal());
                    }

                    case "login" ->
                    {
                        AuthenticationState.Succeeded succeeded = AuthenticationState.login(usernames.pop(), passwords.pop(), request, response);
                        out.append(succeeded == null ? "-" : succeeded.getUserIdentity().getUserPrincipal());
                    }

                    case "logout" -> out.append(AuthenticationState.logout(request, response));

                    case "thread" -> out.append(TestIdentityService.USER_IDENTITY.get());

                    case "session" -> out.append(request.getSession(true).getId());

                    case "form" ->
                    {
                        Fields fields = FormFields.from(request).get();
                        String d = "";
                        for (Fields.Field field : fields)
                        {
                            out.append(d).append(field.getName()).append(":").append(field.getValue());
                            d = ",";
                        }
                    }

                    default -> out.append("???");
                }

                out.append(',');
            }
        }

        AuthenticationState authenticationState = AuthenticationState.getAuthenticationState(request);
        if (authenticationState instanceof AuthenticationState.Succeeded succeeded)
            out.append(succeeded.getUserIdentity().getUserPrincipal()).append(" is OK");
        else if (authenticationState instanceof AuthenticationState.Deferred)
            out.append("Deferred");
        else if (authenticationState == null)
            out.append("Unauthenticated");
        else
            out.append(authenticationState).append(" is not OK");

        response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/plain");
        Content.Sink.write(response, true, out.toString(), callback);
        return true;
    }

    public static class TestIdentityService extends DefaultIdentityService
    {
        public static final ThreadLocal<String> USER_IDENTITY = new ThreadLocal<>();

        @Override
        public Association associate(UserIdentity user, RunAsToken runAsToken)
        {
            USER_IDENTITY.set(user == null ? null : user.getUserPrincipal().getName());
            return () -> USER_IDENTITY.set(null);
        }

        @Override
        public void onLogout(UserIdentity user)
        {
            USER_IDENTITY.set(null);
        }
    }

    static class CustomLoginService implements LoginService
    {
        private final IdentityService identityService;

        public CustomLoginService(IdentityService identityService)
        {
            this.identityService = identityService;
        }

        @Override
        public String getName()
        {
            return "name";
        }

        @Override
        public UserIdentity login(String username, Object credentials, Request request, Function<Boolean, Session> getOrCreateSession)
        {
            if ("admin".equals(username) && "password".equals(credentials))
                return new DefaultUserIdentity(null, new UserPrincipal("admin", null), new String[]{"admin"});
            if ("user".equals(username) && "password".equals(credentials))
                return new DefaultUserIdentity(null, new UserPrincipal("user", null), new String[]{"user"});
            return null;
        }

        @Override
        public boolean validate(UserIdentity user)
        {
            return true;
        }

        @Override
        public IdentityService getIdentityService()
        {
            return identityService;
        }

        @Override
        public void setIdentityService(IdentityService service)
        {
        }

        @Override
        public void logout(UserIdentity user)
        {
        }
    }
}
