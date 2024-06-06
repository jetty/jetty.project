//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

import com.sun.net.httpserver.spi.HttpServerProvider;
import org.eclipse.jetty.http.spi.JettyHttpServerProvider;

module org.eclipse.jetty.http.spi
{
    requires transitive jdk.httpserver;
    requires transitive java.xml;
    requires transitive org.eclipse.jetty.server;
    requires transitive org.eclipse.jetty.util;

    exports org.eclipse.jetty.http.spi;

    provides HttpServerProvider with JettyHttpServerProvider;
}
