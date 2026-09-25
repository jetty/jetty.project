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

/**
 * <p>ACME (Automatic Certificate Management Environment) support for Jetty.</p>
 * <p>This module provides automatic TLS certificate management using the ACME protocol
 * (RFC 8555), enabling integration with certificate authorities like Let's Encrypt.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Automatic certificate acquisition from ACME-compliant CAs</li>
 *   <li>HTTP-01 challenge support for domain validation</li>
 *   <li>Automatic certificate renewal before expiration</li>
 *   <li>Hot-reload of certificates without server restart</li>
 *   <li>Dry-run mode for testing without contacting ACME servers</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>Enable the ACME module in Jetty:</p>
 * <pre>
 * java -jar start.jar --add-modules=https,acme
 * </pre>
 *
 * <p>Configure in {@code start.d/acme.ini}:</p>
 * <pre>
 * jetty.acme.domains=example.com,www.example.com
 * jetty.acme.accountEmail=admin@example.com
 * jetty.acme.termsOfServiceAgreed=true
 * jetty.acme.dryRun=false
 * </pre>
 *
 * @see org.eclipse.jetty.acme.AcmeCertificateManager
 * @see org.eclipse.jetty.acme.AcmeConfiguration
 */
package org.eclipse.jetty.acme;
