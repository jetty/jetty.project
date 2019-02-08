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

// This module is a mixed bag of things.
// There are some utility classes that only depend on Servlet APIs,
// but other utility classes that depend on some Jetty module.
module org.eclipse.jetty.servlets
{
    exports org.eclipse.jetty.servlets;

    requires jetty.servlet.api;

    // Only required if using CloseableDoSFilter.
    requires static org.eclipse.jetty.io;
    // Only required if using DoSFilter, PushCacheFilter, etc.
    requires static org.eclipse.jetty.http;
    requires static org.eclipse.jetty.server;
    // Only required if using CrossOriginFilter, DoSFilter, etc.
    requires static org.eclipse.jetty.util;
}
