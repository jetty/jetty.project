package org.eclipse.jetty.util;//
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class SocketAddressUtils
{
    private SocketAddressUtils() {}

    static List<SocketAddress> createSocketAddressList(InetAddress[] addresses, int port)
    {
        ArrayList<SocketAddress> socketAddresses = new ArrayList<>(addresses.length);
        for (InetAddress address : addresses)
            socketAddresses.add(new InetSocketAddress(address, port));
        return Collections.unmodifiableList(socketAddresses);
    }
}
