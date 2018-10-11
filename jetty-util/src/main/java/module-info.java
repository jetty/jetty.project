//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

module org.eclipse.jetty.util {
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

    requires static java.desktop;
    requires static java.naming;
    requires static java.logging;
    requires static java.sql;
    requires static java.xml;
    requires static javax.servlet.api;
    requires static org.slf4j;

    uses CredentialProvider;
}
