//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

/**
 * The compliance level for parsing <code>multiPart/form-data</code>
 */
public enum MultiPartFormDataCompliance
{
    /**
     * Legacy <code>multiPart/form-data</code> parsing which is slow but forgiving.
     * It will accept non compliant preambles and inconsistent line termination.
     *
     * @see org.eclipse.jetty.util.MultiPartInputStreamParser
     */
    LEGACY,
    /**
     * RFC7578 compliant parsing that is a fast but strict parser.
     *
     * @see org.eclipse.jetty.http.MultiPartFormInputStream
     */
    RFC7578
}
