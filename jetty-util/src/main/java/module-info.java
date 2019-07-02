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

import org.eclipse.jetty.util.security.CredentialProvider;

module org.eclipse.jetty.util
{
    exports org.eclipse.jetty.util;
    exports org.eclipse.jetty.util.annotation;
    exports org.eclipse.jetty.util.component;
    exports org.eclipse.jetty.util.log;
    exports org.eclipse.jetty.util.preventers;
    exports org.eclipse.jetty.util.resource;
    exports org.eclipse.jetty.util.security;
    exports org.eclipse.jetty.util.ssl;
    exports org.eclipse.jetty.util.statistic;
    exports org.eclipse.jetty.util.thread;
    exports org.eclipse.jetty.util.thread.strategy;
    exports org.eclipse.jetty.util.compression;

    // Only required if using AppContextLeakPreventer/AWTLeakPreventer.
    requires static java.desktop;
    // Only required if using X509.
    requires static java.naming;
    // Only required if using JavaUtilLog.
    requires static java.logging;
    // Only required if using DriverManagerLeakPreventer.
    requires static java.sql;
    // Only required if using DOMLeakPreventer.
    requires static java.xml;
    // Only required if using Slf4jLog.
    requires static org.slf4j;

    uses CredentialProvider;
}
