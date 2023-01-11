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

module org.eclipse.jetty.client
{
    requires org.eclipse.jetty.alpn.client;
    requires org.slf4j;

    requires transitive org.eclipse.jetty.http;

    // Only required if using JMX.
    requires static java.management;
    // Only required if using SPNEGO.
    requires static java.security.jgss;
    requires static org.eclipse.jetty.jmx;

    exports org.eclipse.jetty.client;
    exports org.eclipse.jetty.client.transport;

    exports org.eclipse.jetty.client.jmx to
        org.eclipse.jetty.jmx;

    exports org.eclipse.jetty.client.internal to
        org.eclipse.jetty.fcgi.client,
        org.eclipse.jetty.http2.client.transport,
        org.eclipse.jetty.http3.client.transport;
}
