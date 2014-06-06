//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.frames;

public interface Flag
{
    public static final int END_STREAM = 0x01;
    public static final int ACK = END_STREAM;
    public static final int END_SEGMENT = 0x02;
    public static final int END_HEADERS = 0x04;
    public static final int PADDING_LOW = 0x08;
    public static final int PADDING_HIGH = 0x10;
    public static final int COMPRESS = 0x20;
    public static final int PRIORITY = COMPRESS;
}
