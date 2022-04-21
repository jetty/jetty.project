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

import org.eclipse.jetty.ee10.security.openid.OpenIdAuthenticatorFactory;
import org.eclipse.jetty.ee10.servlet.security.Authenticator;

module org.eclipse.jetty.security.openid
{
    requires org.eclipse.jetty.util.ajax;

    requires transitive org.eclipse.jetty.client;
    requires org.eclipse.jetty.ee10.servlet;
    requires org.slf4j;

    exports org.eclipse.jetty.ee10.security.openid;

    provides Authenticator.Factory with OpenIdAuthenticatorFactory;
}
