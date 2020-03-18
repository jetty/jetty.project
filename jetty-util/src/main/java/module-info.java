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

import org.eclipse.jetty.util.security.CredentialProvider;

module org.eclipse.jetty.util
{
    exports org.eclipse.jetty.util;
    exports org.eclipse.jetty.util.annotation;
    exports org.eclipse.jetty.util.component;
    exports org.eclipse.jetty.util.compression;
    exports org.eclipse.jetty.util.log;
    exports org.eclipse.jetty.util.preventers;
    exports org.eclipse.jetty.util.resource;
    exports org.eclipse.jetty.util.security;
    exports org.eclipse.jetty.util.ssl;
    exports org.eclipse.jetty.util.statistic;
    exports org.eclipse.jetty.util.thread;
    exports org.eclipse.jetty.util.thread.strategy;

    // Required by SSL code (for X509).
    requires transitive java.naming;

    // Standard Jetty Logging now.
    requires org.slf4j;

    // Only required if using AppContextLeakPreventer/AWTLeakPreventer.
    requires static java.desktop;
    // Only required if using JavaUtilLog.
    requires static java.logging;
    // Only required if using DriverManagerLeakPreventer.
    requires static java.sql;
    // Only required if using DOMLeakPreventer.
    requires static java.xml;

    uses CredentialProvider;
}
