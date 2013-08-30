//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi;

public class FCGI
{
    private FCGI()
    {
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
}
