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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Helper class for SOCKS5 proxying.</p>
 *
 * @see Socks5Proxy
 */
public class Socks5
{
    /**
     * The SOCKS protocol version: {@value}.
     */
    public static final byte VERSION = 0x05;
    /**
     * The SOCKS5 {@code CONNECT} command used in SOCKS5 connect requests.
     */
    public static final byte COMMAND_CONNECT = 0x01;
    /**
     * The reserved byte value: {@value}.
     */
    public static final byte RESERVED = 0x00;
    /**
     * The address type for IPv4 used in SOCKS5 connect requests and responses.
     */
    public static final byte ADDRESS_TYPE_IPV4 = 0x01;
    /**
     * The address type for domain names used in SOCKS5 connect requests and responses.
     */
    public static final byte ADDRESS_TYPE_DOMAIN = 0x03;
    /**
     * The address type for IPv6 used in SOCKS5 connect requests and responses.
     */
    public static final byte ADDRESS_TYPE_IPV6 = 0x04;

    private Socks5()
    {
    }

    /**
     * <p>A SOCKS5 authentication method.</p>
     * <p>Implementations should send and receive the bytes that
     * are specific to the particular authentication method.</p>
     */
    public interface Authentication
    {
        /**
         * <p>Performs the authentication send and receive bytes
         * exchanges specific for this {@link Authentication}.</p>
         *
         * @param endPoint the {@link EndPoint} to send to and receive from the SOCKS5 server
         * @param callback the callback to complete when the authentication is complete
         */
        void authenticate(EndPoint endPoint, Callback callback);

        /**
         * A factory for {@link Authentication}s.
         */
        interface Factory
        {
            /**
             * @return the authentication method defined by RFC 1928
             */
            byte getMethod();

            /**
             * @return a new {@link Authentication}
             */
            Authentication newAuthentication();
        }
    }

    /**
     * <p>The implementation of the {@code NO AUTH} authentication method defined in
     * <a href="https://datatracker.ietf.org/doc/html/rfc1928">RFC 1928</a>.</p>
     */
    public static class NoAuthenticationFactory implements Authentication.Factory
    {
        public static final byte METHOD = 0x00;

        @Override
        public byte getMethod()
        {
            return METHOD;
        }

        @Override
        public Authentication newAuthentication()
        {
            return (endPoint, callback) -> callback.succeeded();
        }
    }

    /**
     * <p>The implementation of the {@code USERNAME/PASSWORD} authentication method defined in
     * <a href="https://datatracker.ietf.org/doc/html/rfc1929">RFC 1929</a>.</p>
     */
    public static class UsernamePasswordAuthenticationFactory implements Authentication.Factory
    {
        public static final byte METHOD = 0x02;
        public static final byte VERSION = 0x01;
        private static final Logger LOG = LoggerFactory.getLogger(UsernamePasswordAuthenticationFactory.class);

        private final String userName;
        private final String password;
        private final Charset charset;

        public UsernamePasswordAuthenticationFactory(String userName, String password)
        {
            this(userName, password, StandardCharsets.US_ASCII);
        }

        public UsernamePasswordAuthenticationFactory(String userName, String password, Charset charset)
        {
            this.userName = Objects.requireNonNull(userName);
            this.password = Objects.requireNonNull(password);
            this.charset = Objects.requireNonNull(charset);
        }

        @Override
        public byte getMethod()
        {
            return METHOD;
        }

        @Override
        public Authentication newAuthentication()
        {
            return new UsernamePasswordAuthentication(this);
        }

        private static class UsernamePasswordAuthentication implements Authentication, Callback
        {
            private final ByteBuffer byteBuffer = BufferUtil.allocate(2);
            private final UsernamePasswordAuthenticationFactory factory;
            private EndPoint endPoint;
            private Callback callback;

            private UsernamePasswordAuthentication(UsernamePasswordAuthenticationFactory factory)
            {
                this.factory = factory;
            }

            @Override
            public void authenticate(EndPoint endPoint, Callback callback)
            {
                this.endPoint = endPoint;
                this.callback = callback;

                byte[] userNameBytes = factory.userName.getBytes(factory.charset);
                byte[] passwordBytes = factory.password.getBytes(factory.charset);
                ByteBuffer byteBuffer = ByteBuffer.allocate(3 + userNameBytes.length + passwordBytes.length)
                    .put(VERSION)
                    .put((byte)userNameBytes.length)
                    .put(userNameBytes)
                    .put((byte)passwordBytes.length)
                    .put(passwordBytes)
                    .flip();
                endPoint.write(Callback.from(this::authenticationSent, this::failed), byteBuffer);
            }

            private void authenticationSent()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Written SOCKS5 username/password authentication request");
                endPoint.fillInterested(this);
            }

            @Override
            public void succeeded()
            {
                try
                {
                    int filled = endPoint.fill(byteBuffer);
                    if (filled < 0)
                        throw new ClosedChannelException();
                    if (byteBuffer.remaining() < 2)
                    {
                        endPoint.fillInterested(this);
                        return;
                    }
                    if (LOG.isDebugEnabled())
                        LOG.debug("Received SOCKS5 username/password authentication response");
                    byte version = byteBuffer.get();
                    if (version != VERSION)
                        throw new IOException("Unsupported username/password authentication version: " + version);
                    byte status = byteBuffer.get();
                    if (status != 0)
                        throw new IOException("SOCK5 username/password authentication failure");
                    if (LOG.isDebugEnabled())
                        LOG.debug("SOCKS5 username/password authentication succeeded");
                    callback.succeeded();
                }
                catch (Throwable x)
                {
                    failed(x);
                }
            }

            @Override
            public void failed(Throwable x)
            {
                callback.failed(x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }
        }
    }
}
