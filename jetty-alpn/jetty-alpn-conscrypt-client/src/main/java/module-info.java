//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

import org.eclipse.jetty.alpn.conscrypt.client.ConscryptClientALPNProcessor;
import org.eclipse.jetty.io.ssl.ALPNProcessor;

module org.eclipse.jetty.alpn.conscrypt.client
{
    requires org.conscrypt;
    requires transitive org.eclipse.jetty.alpn.client;
    requires org.slf4j;

    provides ALPNProcessor.Client with ConscryptClientALPNProcessor;
}
