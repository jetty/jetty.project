//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
        HttpFields http_fields = new HttpFields();
        // Create a bogus Authorization header. We don't care about the actual credentials.
        http_fields.add(HttpHeader.AUTHORIZATION, "Basic asdf");
        MetaData.Request metadata = new MetaData.Request(http_fields);
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
            super(new Server() , null, null, null, 0);
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

    class MockConnector extends AbstractConnector
    {
        public MockConnector()
        {
            super(new Server() , null, null, null, 0);
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
