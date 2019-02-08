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

module org.eclipse.jetty.server
{
    exports org.eclipse.jetty.server;
    exports org.eclipse.jetty.server.handler;
    exports org.eclipse.jetty.server.handler.gzip;
    exports org.eclipse.jetty.server.handler.jmx to org.eclipse.jetty.jmx;
    exports org.eclipse.jetty.server.jmx to org.eclipse.jetty.jmx;
    exports org.eclipse.jetty.server.session;

    requires jetty.servlet.api;
    requires org.eclipse.jetty.http;
    requires org.eclipse.jetty.io;
    requires org.eclipse.jetty.util;

    // Only required if using DatabaseAdaptor/JDBCSessionDataStore.
    requires static java.sql;
    requires static java.naming;
    // Only required if using JMX.
    requires static org.eclipse.jetty.jmx;
}
