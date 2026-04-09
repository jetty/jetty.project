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

package org.eclipse.jetty.ee11.security.jaspi;

import jakarta.security.auth.message.config.AuthConfigFactory;
import org.eclipse.jetty.security.Authenticator;

/**
 * Jakarta Authentication (JASPI) Authenticator Factory.
 * 
 * This is used to link a jetty-security {@link Authenticator.Factory} to a Jakarta Authentication {@link AuthConfigFactory}.
 * <p>
 * This should be initialized with the provided {@link DefaultAuthConfigFactory} to set up Jakarta Authentication {@link AuthConfigFactory} before use. 
 * (A different {@link AuthConfigFactory} may also be provided using the same steps below)
 * <p>
 * To initialize either:
 * <ul>
 * <li>invoke {@link AuthConfigFactory#setFactory(AuthConfigFactory)}</li>
 * <li>Alternatively: set {@link AuthConfigFactory#DEFAULT_FACTORY_SECURITY_PROPERTY}</li>
 * </ul>
 *
 */
public class JaspiAuthenticatorFactory extends org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticatorFactory
{
}
