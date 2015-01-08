//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2;

public interface ErrorCodes
{
    public static final int NO_ERROR = 0;
    public static final int PROTOCOL_ERROR = 1;
    public static final int INTERNAL_ERROR = 2;
    public static final int FLOW_CONTROL_ERROR = 3;
    public static final int SETTINGS_TIMEOUT_ERROR = 4;
    public static final int STREAM_CLOSED_ERROR = 5;
    public static final int FRAME_SIZE_ERROR = 6;
    public static final int REFUSED_STREAM_ERROR = 7;
    public static final int CANCEL_STREAM_ERROR = 8;
    public static final int COMPRESSION_ERROR = 9;
    public static final int HTTP_CONNECT_ERROR = 10;
    public static final int ENHANCE_YOUR_CALM_ERROR = 11;
    public static final int INADEQUATE_SECURITY_ERROR = 12;
}
