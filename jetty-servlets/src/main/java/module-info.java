//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

// This module is a mixed bag of things.
// There are some utility classes that only depend on Servlet APIs,
// but other utility classes that depend on some Jetty module.
module org.eclipse.jetty.servlets
{
    exports org.eclipse.jetty.servlets;

    requires transitive jetty.servlet.api;
    requires org.slf4j;

    // Only required if using CloseableDoSFilter.
    requires static org.eclipse.jetty.io;
    // Only required if using DoSFilter, PushCacheFilter, etc.
    requires static org.eclipse.jetty.http;
    requires static org.eclipse.jetty.server;
    // Only required if using CrossOriginFilter, DoSFilter, etc.
    requires static org.eclipse.jetty.util;
}
