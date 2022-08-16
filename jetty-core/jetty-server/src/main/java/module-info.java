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

module org.eclipse.jetty.server
{
    requires transitive org.eclipse.jetty.http;
    requires transitive org.slf4j;

    // Only required if using JMX.
    requires static org.eclipse.jetty.jmx;

    // TODO needed for testing??
    requires static java.xml;

    exports org.eclipse.jetty.server;
    exports org.eclipse.jetty.server.handler;
    exports org.eclipse.jetty.server.handler.gzip;

    exports org.eclipse.jetty.server.handler.jmx to
         org.eclipse.jetty.jmx;

    exports org.eclipse.jetty.server.jmx to
         org.eclipse.jetty.jmx;

    // TODO required for testing ????
    exports org.eclipse.jetty.server.ssl;

    // expose jetty-dir.css to ee9/ee8
    opens org.eclipse.jetty.server to
        org.eclipse.jetty.ee9.nested,
        org.eclipse.jetty.ee8.nested;
}
