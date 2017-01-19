//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io.ssl;

import java.util.List;

import javax.net.ssl.SSLEngine;

public interface ALPNProcessor
{
    public interface Server
    {
        public static final ALPNProcessor.Server NOOP = new ALPNProcessor.Server()
        {
        };

        public default void configure(SSLEngine sslEngine)
        {
        }
    }

    public interface Client
    {
        public static final Client NOOP = new Client()
        {
        };

        public default void configure(SSLEngine sslEngine, List<String> protocols)
        {
        }

        public default void process(SSLEngine sslEngine)
        {
        }
    }
}
