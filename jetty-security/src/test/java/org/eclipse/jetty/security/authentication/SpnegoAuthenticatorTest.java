/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eclipse.jetty.security.authentication;

import static org.junit.Assert.assertEquals;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link SpnegoAuthenticator}.
 */
public class SpnegoAuthenticatorTest {
    private SpnegoAuthenticator _authenticator;

    @Before
    public void setup() throws Exception
    {
        _authenticator = new SpnegoAuthenticator();
    }

    @Test
    public void testChallengeSentWithNoAuthorization() throws Exception
    {
        HttpChannel channel = new HttpChannel(null, new HttpConfiguration(), null, null)
        {
            @Override
            public Server getServer()
            {
                return null;
            }
        };
        Request req = new Request(channel, null);
        HttpOutput out = new HttpOutput(channel)
        {
            @Override
            public void close()
            {
                return;
            }
        };
        Response res = new Response(channel, out);
        MetaData.Request metadata = new MetaData.Request(new HttpFields());
        metadata.setURI(new HttpURI("http://localhost"));
        req.setMetaData(metadata);
        
        assertEquals(Authentication.SEND_CONTINUE, _authenticator.validateRequest(req, res, true));
        assertEquals(HttpHeader.NEGOTIATE.asString(), res.getHeader(HttpHeader.WWW_AUTHENTICATE.asString()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
    }

    @Test
    public void testChallengeSentWithUnhandledAuthorization() throws Exception
    {
        HttpChannel channel = new HttpChannel(null, new HttpConfiguration(), null, null)
        {
            @Override
            public Server getServer()
            {
                return null;
            }
        };
        Request req = new Request(channel, null);
        HttpOutput out = new HttpOutput(channel)
        {
            @Override
            public void close()
            {
                return;
            }
        };
        Response res = new Response(channel, out);
        HttpFields http_fields = new HttpFields();
        // Create a bogus Authorization header. We don't care about the actual credentials.
        http_fields.add(HttpHeader.AUTHORIZATION, "Basic asdf");
        MetaData.Request metadata = new MetaData.Request(http_fields);
        metadata.setURI(new HttpURI("http://localhost"));
        req.setMetaData(metadata);

        assertEquals(Authentication.SEND_CONTINUE, _authenticator.validateRequest(req, res, true));
        assertEquals(HttpHeader.NEGOTIATE.asString(), res.getHeader(HttpHeader.WWW_AUTHENTICATE.asString()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
    }
}
