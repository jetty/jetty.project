//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void testCaseInsensitiveHeaderParsing()
    {
        assertFalse(_authenticator.isAuthSchemeNegotiate(null));
        assertFalse(_authenticator.isAuthSchemeNegotiate(""));
        assertFalse(_authenticator.isAuthSchemeNegotiate("Basic"));
        assertFalse(_authenticator.isAuthSchemeNegotiate("basic"));
        assertFalse(_authenticator.isAuthSchemeNegotiate("Digest"));
        assertFalse(_authenticator.isAuthSchemeNegotiate("LotsandLotsandLots of nonsense"));
        assertFalse(_authenticator.isAuthSchemeNegotiate("Negotiat asdfasdf"));
        assertFalse(_authenticator.isAuthSchemeNegotiate("Negotiated"));
        assertFalse(_authenticator.isAuthSchemeNegotiate("Negotiate-and-more"));

        assertTrue(_authenticator.isAuthSchemeNegotiate("Negotiate"));
        assertTrue(_authenticator.isAuthSchemeNegotiate("negotiate"));
        assertTrue(_authenticator.isAuthSchemeNegotiate("negOtiAte"));
    }

    @Test
    public void testExtractAuthScheme()
    {
        assertEquals("", _authenticator.getAuthSchemeFromHeader(null));
        assertEquals("", _authenticator.getAuthSchemeFromHeader(""));
        assertEquals("", _authenticator.getAuthSchemeFromHeader("   "));
        assertEquals("Basic", _authenticator.getAuthSchemeFromHeader(" Basic asdfasdf"));
        assertEquals("Basicasdf", _authenticator.getAuthSchemeFromHeader("Basicasdf asdfasdf"));
        assertEquals("basic", _authenticator.getAuthSchemeFromHeader(" basic asdfasdf "));
        assertEquals("Negotiate", _authenticator.getAuthSchemeFromHeader("Negotiate asdfasdf"));
        assertEquals("negotiate", _authenticator.getAuthSchemeFromHeader("negotiate asdfasdf"));
        assertEquals("negotiate", _authenticator.getAuthSchemeFromHeader(" negotiate  asdfasdf"));
        assertEquals("negotiated", _authenticator.getAuthSchemeFromHeader(" negotiated  asdfasdf"));
    }
}
