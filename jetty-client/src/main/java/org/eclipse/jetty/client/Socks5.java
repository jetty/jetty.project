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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class Socks5
{

    public enum RequestStage
    {
        INIT,
        AUTH,
        CONNECTING
    }

    public enum ResponseStage
    {
        INIT,
        AUTH,
        CONNECTING,
        CONNECTED_IPV4,
        CONNECTED_DOMAIN_NAME,
        CONNECTED_IPV6,
        READ_REPLY_VARIABLE
    }

    public interface SockConst
    {
        byte VER = 0x05;
        byte USER_PASS_VER = 0x01;
        byte RSV = 0x00;
        byte SUCCEEDED = 0x00;
        byte AUTH_FAILED = 0x01;
    }

    public interface AuthType
    {
        byte NO_AUTH = 0x00;
        byte USER_PASS = 0x02;
        byte NO_ACCEPTABLE = -1;
    }

    public interface Command
    {

        byte CONNECT = 0x01;
        byte BIND = 0x02;
        byte UDP = 0x03;
    }

    public interface Reply
    {

        byte GENERAL = 0x01;
        byte RULE_BAN = 0x02;
        byte NETWORK_UNREACHABLE = 0x03;
        byte HOST_UNREACHABLE = 0x04;
        byte CONNECT_REFUSE = 0x05;
        byte TTL_TIMEOUT = 0x06;
        byte CMD_UNSUPPORTED = 0x07;
        byte ATYPE_UNSUPPORTED = 0x08;
    }

    public interface AddrType
    {
        byte IPV4 = 0x01;
        byte DOMAIN_NAME = 0x03;
        byte IPV6 = 0x04;
    }

    public interface Authentication
    {
        /**
         * get supported authentication type
         * @see AuthType
         * @return
         */
        byte getAuthType();

        /**
         * write authorize command
         * @return
         */
        ByteBuffer authorize();
    }

    public static class NoAuthentication implements Authentication
    {

        @Override
        public byte getAuthType() 
        {
            return AuthType.NO_AUTH;
        }

        @Override
        public ByteBuffer authorize() 
        {
            throw new UnsupportedOperationException("authorize error");
        }

    }

    public static class UsernamePasswordAuthentication implements Authentication
    {
        private String username;
        private String password;

        public UsernamePasswordAuthentication(String username, String password)
        {
            this.username = username;
            this.password = password;
        }

        @Override
        public byte getAuthType() 
        {
            return AuthType.USER_PASS;
        }

        @Override
        public ByteBuffer authorize() 
        {
            byte uLen = (byte)username.length();
            byte pLen = (byte)(password == null ? 0 : password.length());
            ByteBuffer userPass = ByteBuffer.allocate(3 + uLen + pLen);
            userPass.put(SockConst.USER_PASS_VER)
                .put(uLen)
                .put(username.getBytes(StandardCharsets.UTF_8))
                .put(pLen);
            if (password != null)
            {
                userPass.put(password.getBytes(StandardCharsets.UTF_8));
            }
            userPass.flip();
            return userPass;
        }
    }
}
