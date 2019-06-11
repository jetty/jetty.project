//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

module org.eclipse.jetty.client
{
    exports org.eclipse.jetty.client;
    exports org.eclipse.jetty.client.api;
    exports org.eclipse.jetty.client.dynamic;
    exports org.eclipse.jetty.client.http;
    exports org.eclipse.jetty.client.jmx to org.eclipse.jetty.jmx;
    exports org.eclipse.jetty.client.proxy;
    exports org.eclipse.jetty.client.util;

    requires org.eclipse.jetty.http;
    requires org.eclipse.jetty.io;
    requires org.eclipse.jetty.util;

    // Only required if using SPNEGO.
    requires static java.security.jgss;
    // Only required if using the dynamic transport.
    requires static org.eclipse.jetty.alpn.client;
    // Only required if using JMX.
    requires static org.eclipse.jetty.jmx;
}
