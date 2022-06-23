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

module org.eclipse.jetty.ee10.security.jaspi
{
    requires  jakarta.servlet;
    requires org.slf4j;

    requires transitive jakarta.security.auth.message;
    requires transitive org.eclipse.jetty.ee10.servlet;

    exports org.eclipse.jetty.ee10.security.jaspi;
    exports org.eclipse.jetty.ee10.security.jaspi.callback;
    exports org.eclipse.jetty.ee10.security.jaspi.modules;
    exports org.eclipse.jetty.ee10.security.jaspi.provider;

    provides org.eclipse.jetty.ee10.servlet.security.Authenticator.Factory with
        org.eclipse.jetty.ee10.security.jaspi.JaspiAuthenticatorFactory;
}
