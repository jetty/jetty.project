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

package org.eclipse.jetty.server;

/**
 * The compliance level for parsing <code>multiPart/form-data</code>
 */
public enum MultiPartFormDataCompliance
{
    /**
     * Legacy <code>multiPart/form-data</code> parsing which is slow but forgiving.
     * It will accept non-compliant preambles and inconsistent line termination.
     *
     * @see org.eclipse.jetty.server.MultiPartInputStreamParser
     */
    LEGACY,
    /**
     * RFC7578 compliant parsing that is a fast but strict parser.
     *
     * @see org.eclipse.jetty.server.MultiPartFormInputStream
     */
    RFC7578
}
