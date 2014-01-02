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

package org.eclipse.jetty.websocket.mux;

public final class MuxOp
{
    public static final byte ADD_CHANNEL_REQUEST = 0;
    public static final byte ADD_CHANNEL_RESPONSE = 1;
    public static final byte FLOW_CONTROL = 2;
    public static final byte DROP_CHANNEL = 3;
    public static final byte NEW_CHANNEL_SLOT = 4;
}
