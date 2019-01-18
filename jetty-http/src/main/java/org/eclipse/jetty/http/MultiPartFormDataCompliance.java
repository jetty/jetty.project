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

package org.eclipse.jetty.http;

/**
 * The compliance level for parsing <code>multiPart/form-data</code>
 */
public class MultiPartFormDataCompliance
{
    public static final MultiPartFormDataCompliance LEGACY = new MultiPartFormDataCompliance(false);
    public static final MultiPartFormDataCompliance RFC7578 = new MultiPartFormDataCompliance(true);
    private final boolean isRFC7578;

    public MultiPartFormDataCompliance(boolean isRFC7578)
    {
        this.isRFC7578 = isRFC7578;
    }

    /**
     * RFC7578 compliant parsing that is a fast but strict parser.
     *
     * @see org.eclipse.jetty.http.MultiPartFormInputStream
     */
    public boolean isRFC7578()
    {
        return isRFC7578;
    }
}
