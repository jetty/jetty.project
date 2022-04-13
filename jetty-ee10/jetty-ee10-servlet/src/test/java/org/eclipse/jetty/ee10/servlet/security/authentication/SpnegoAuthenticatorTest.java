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

package org.eclipse.jetty.ee10.servlet.security.authentication;

import java.io.IOException;

import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

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
        /*
        HttpChannel channel = new HttpChannel(new MockConnector(), new HttpConfiguration(), null, null)
        {
            @Override
            public Server getServer()
            {
                return null;
            }

            @Override
            public boolean failed(Throwable x)
            {
                return false;
            }

            @Override
            protected boolean eof()
            {
                return false;
            }

            @Override
            public boolean needContent()
            {
                return false;
            }

            @Override
            public HttpInput.Content produceContent()
            {
                return null;
            }

            @Override
            public boolean failAllContent(Throwable failure)
            {
                return false;
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
        MetaData.Request metadata = new MetaData.Request(null, HttpURI.build("http://localhost"), null, HttpFields.EMPTY);
        req.setMetaData(metadata);

        assertThat(channel.getState().handling(), is(HttpChannelState.Action.DISPATCH));
        assertEquals(Authentication.SEND_CONTINUE, _authenticator.validateRequest(req, res, true));
        assertEquals(HttpHeader.NEGOTIATE.asString(), res.getHeader(HttpHeader.WWW_AUTHENTICATE.asString()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
         */
        fail("re-write test case");
    }

    @Test
    public void testChallengeSentWithUnhandledAuthorization() throws Exception
    {
        /*
        HttpChannel channel = new HttpChannel(new MockConnector(), new HttpConfiguration(), null, null)
        {
            @Override
            public Server getServer()
            {
                return null;
            }

            @Override
            public boolean failed(Throwable x)
            {
                return false;
            }

            @Override
            protected boolean eof()
            {
                return false;
            }

            @Override
            public boolean needContent()
            {
                return false;
            }

            @Override
            public HttpInput.Content produceContent()
            {
                return null;
            }

            @Override
            public boolean failAllContent(Throwable failure)
            {
                return false;
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

        // Create a bogus Authorization header. We don't care about the actual credentials.

        MetaData.Request metadata = new MetaData.Request(null, HttpURI.build("http://localhost"), null,
            HttpFields.build().add(HttpHeader.AUTHORIZATION, "Basic asdf"));
        req.setMetaData(metadata);

        assertThat(channel.getState().handling(), is(HttpChannelState.Action.DISPATCH));
        assertEquals(Authentication.SEND_CONTINUE, _authenticator.validateRequest(req, res, true));
        assertEquals(HttpHeader.NEGOTIATE.asString(), res.getHeader(HttpHeader.WWW_AUTHENTICATE.asString()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
         */
        fail("re-write test case");
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

