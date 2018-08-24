//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.util;

import org.eclipse.jetty.client.AuthenticationProtocolHandler;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Base64;

public class SpnegoAuthentication implements Authentication
{
        public static final Logger LOG = Log.getLogger(AuthenticationProtocolHandler.class);
        private static final String SPNEGO_OID = "1.3.6.1.5.5.2";

        private GSSCredential gssCredential;
        private boolean useCanonicalHostName;
        private boolean stripPort;

        public SpnegoAuthentication(GSSCredential gssCredential, boolean useCanonicalHostName, boolean stripPort)
        {
                this.gssCredential = gssCredential;
                this.useCanonicalHostName = useCanonicalHostName;
                this.stripPort = stripPort;
        }

        @Override
        public boolean matches(String type, URI uri, String realm)
        {
                return "Negotiate".equals(type);
        }

        @Override
        public Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context)
        {
                String challenge = headerInfo.getBase64();
                if (challenge == null)
                        challenge = "";
                byte[] input = Base64.getDecoder().decode(challenge);
                byte[] token;
                String authServer = request.getHost();

                if (useCanonicalHostName)
                {
                        try
                        {
                                authServer = resolveCanonicalHostname(authServer);
                        }
                        catch (final UnknownHostException ignore)
                        {
                        }
                }

                if (!stripPort)
                {
                        authServer = authServer + ":" + request.getPort();
                }

                GSSContext gssContext = (GSSContext)context.getAttribute(GSSContext.class.getName());
                if (gssContext != null)
                {
                        try
                        {
                                token = gssContext.initSecContext(input, 0, input.length);
                        }
                        catch (GSSException gsse)
                        {
                                throw new IllegalStateException(gsse.getMessage(), gsse);
                        }
                }
                else
                {
                        final GSSManager manager = GSSManager.getInstance();
                        try
                        {
                                gssContext = createGSSContext(manager, new Oid(SPNEGO_OID), authServer);
                                token = gssContext.initSecContext(input, 0, input.length);
                                context.setAttribute(GSSContext.class.getName(), gssContext);
                        }
                        catch (GSSException gsse)
                        {
                                throw new IllegalStateException(gsse.getMessage(), gsse);
                        }
                }

                return new Result()
                {
                        @Override
                        public URI getURI()
                        {
                                // Since Kerberos is connection based authentication, sub-sequence requests won't need to resend the token in header
                                // by return null, the ProtocolHandler won't try to apply this result on sequence requests
                                return null;
                        }

                        @Override
                        public void apply(Request request)
                        {
                                final String tokenstr = Base64.getEncoder().encodeToString(token);
                                if (LOG.isDebugEnabled())
                                {
                                        LOG.info("Sending response '" + tokenstr + "' back to the auth server");
                                }
                                request.header(headerInfo.getHeader().asString(), "Negotiate " + tokenstr);
                        }
                };
        }

        @Override
        public boolean isMultipleRounds()
        {
                return true;
        }

        protected GSSContext createGSSContext(GSSManager manager, Oid oid, String authServer) throws GSSException
        {
                GSSName serverName = manager.createName("HTTP@" + authServer, GSSName.NT_HOSTBASED_SERVICE);
                final GSSContext gssContext = manager.createContext(serverName.canonicalize(oid), oid, gssCredential,
                        GSSContext.DEFAULT_LIFETIME);
                gssContext.requestMutualAuth(true);
                return gssContext;
        }

        private String resolveCanonicalHostname(final String host) throws UnknownHostException
        {
                final InetAddress in = InetAddress.getByName(host);
                final String canonicalServer = in.getCanonicalHostName();
                if (in.getHostAddress().contentEquals(canonicalServer))
                {
                        return host;
                }
                return canonicalServer;
        }
}
