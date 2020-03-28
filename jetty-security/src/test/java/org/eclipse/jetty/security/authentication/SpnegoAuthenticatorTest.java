//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.authentication;

import java.io.IOException;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SpnegoAuthenticatorTest
{
    private ConfigurableSpnegoAuthenticator _authenticator;

    @BeforeEach
    public void setup()
    {
        _authenticator = new ConfigurableSpnegoAuthenticator();
    }

    @Test
    public void testChallengeSentWithNoAuthorization() throws Exception
    {
        HttpChannel channel = new HttpChannel(new MockConnector(), new HttpConfiguration(), null, null)
        {
            @Override
            public Server getServer()
            {
                return null;
            }

            @Override
            protected HttpOutput newHttpOutput()
            {
                return new HttpOutput(this)
                {
                    @Override
                    public void close() {}

                    @Override
                    public void flush() throws IOException {}
                };
            }
        };
        Request req = channel.getRequest();
        Response res = channel.getResponse();
        MetaData.Request metadata = new MetaData.Request(new HttpFields());
        metadata.setURI(new HttpURI("http://localhost"));
        req.setMetaData(metadata);

        assertThat(channel.getState().handling(), is(HttpChannelState.Action.DISPATCH));
        assertEquals(Authentication.SEND_CONTINUE, _authenticator.validateRequest(req, res, true));
        assertEquals(HttpHeader.NEGOTIATE.asString(), res.getHeader(HttpHeader.WWW_AUTHENTICATE.asString()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
    }

    @Test
    public void testChallengeSentWithUnhandledAuthorization() throws Exception
    {
        HttpChannel channel = new HttpChannel(new MockConnector(), new HttpConfiguration(), null, null)
        {
            @Override
            public Server getServer()
            {
                return null;
            }

            @Override
            protected HttpOutput newHttpOutput()
            {
                return new HttpOutput(this)
                {
                    @Override
                    public void close() {}

                    @Override
                    public void flush() throws IOException {}
                };
            }
        };
        Request req = channel.getRequest();
        Response res = channel.getResponse();
        HttpFields httpFields = new HttpFields();
        // Create a bogus Authorization header. We don't care about the actual credentials.
        httpFields.add(HttpHeader.AUTHORIZATION, "Basic asdf");
        MetaData.Request metadata = new MetaData.Request(httpFields);
        metadata.setURI(new HttpURI("http://localhost"));
        req.setMetaData(metadata);

        assertThat(channel.getState().handling(), is(HttpChannelState.Action.DISPATCH));
        assertEquals(Authentication.SEND_CONTINUE, _authenticator.validateRequest(req, res, true));
        assertEquals(HttpHeader.NEGOTIATE.asString(), res.getHeader(HttpHeader.WWW_AUTHENTICATE.asString()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
    }

    class MockConnector extends AbstractConnector
    {
        public MockConnector()
        {
            super(new Server(), null, null, null, 0);
        }

        @Override
        protected void accept(int acceptorID) throws IOException, InterruptedException
        {
        }

        @Override
        public Object getTransport()
        {
            return null;
        }

        @Override
        public String dumpSelf()
        {
            return null;
        }
    }
}

