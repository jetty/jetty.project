//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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


/* ------------------------------------------------------------ */
/** Interface to pre-encode HttpFields.  Used by {@link PreEncodedHttpField}
 */
public interface HttpFieldPreEncoder
{
    /* ------------------------------------------------------------ */
    /** The major version this encoder is for.  Both HTTP/1.0 and HTTP/1.1
     * use the same field encoding, so the {@link HttpVersion#HTTP_1_0} should
     * be return for all HTTP/1.x encodings.
     * @return The major version this encoder is for.
     */
    HttpVersion getHttpVersion();
    byte[] getEncodedField(HttpHeader header, String headerString, String value);
}