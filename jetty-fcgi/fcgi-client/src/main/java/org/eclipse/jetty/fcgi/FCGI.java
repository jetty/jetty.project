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

package org.eclipse.jetty.fcgi;

public class FCGI
{
    private FCGI()
    {
    }

    public enum Role
    {
        RESPONDER(1), AUTHORIZER(2), FILTER(3);

        public static Role from(int code)
        {
            switch (code)
            {
                case 1:
                    return RESPONDER;
                case 2:
                    return AUTHORIZER;
                case 3:
                    return FILTER;
                default:
                    throw new IllegalArgumentException();
            }
        }

        public final int code;

        private Role(int code)
        {
            this.code = code;
        }
    }

    public enum FrameType
    {
        BEGIN_REQUEST(1),
        ABORT_REQUEST(2),
        END_REQUEST(3),
        PARAMS(4),
        STDIN(5),
        STDOUT(6),
        STDERR(7),
        DATA(8),
        GET_VALUES(9),
        GET_VALUES_RESULT(10);

        public static FrameType from(int code)
        {
            switch (code)
            {
                case 1:
                    return BEGIN_REQUEST;
                case 2:
                    return ABORT_REQUEST;
                case 3:
                    return END_REQUEST;
                case 4:
                    return PARAMS;
                case 5:
                    return STDIN;
                case 6:
                    return STDOUT;
                case 7:
                    return STDERR;
                case 8:
                    return DATA;
                case 9:
                    return GET_VALUES;
                case 10:
                    return GET_VALUES_RESULT;
                default:
                    throw new IllegalArgumentException();
            }
        }

        public final int code;

        private FrameType(int code)
        {
            this.code = code;
        }
    }

    public enum StreamType
    {
        STD_IN, STD_OUT, STD_ERR
    }

    public static class Headers
    {
        public static final String AUTH_TYPE = "AUTH_TYPE";
        public static final String CONTENT_LENGTH = "CONTENT_LENGTH";
        public static final String CONTENT_TYPE = "CONTENT_TYPE";
        public static final String DOCUMENT_ROOT = "DOCUMENT_ROOT";
        public static final String DOCUMENT_URI = "DOCUMENT_URI";
        public static final String GATEWAY_INTERFACE = "GATEWAY_INTERFACE";
        public static final String HTTPS = "HTTPS";
        public static final String PATH_INFO = "PATH_INFO";
        public static final String QUERY_STRING = "QUERY_STRING";
        public static final String REMOTE_ADDR = "REMOTE_ADDR";
        public static final String REMOTE_PORT = "REMOTE_PORT";
        public static final String REQUEST_METHOD = "REQUEST_METHOD";
        public static final String REQUEST_URI = "REQUEST_URI";
        public static final String SCRIPT_FILENAME = "SCRIPT_FILENAME";
        public static final String SCRIPT_NAME = "SCRIPT_NAME";
        public static final String SERVER_ADDR = "SERVER_ADDR";
        public static final String SERVER_NAME = "SERVER_NAME";
        public static final String SERVER_PORT = "SERVER_PORT";
        public static final String SERVER_PROTOCOL = "SERVER_PROTOCOL";
        public static final String SERVER_SOFTWARE = "SERVER_SOFTWARE";

        private Headers()
        {
        }
    }
}
