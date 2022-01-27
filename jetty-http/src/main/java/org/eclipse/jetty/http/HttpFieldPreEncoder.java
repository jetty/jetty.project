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

package org.eclipse.jetty.http;

/**
 * Interface to pre-encode HttpFields.  Used by {@link PreEncodedHttpField}
 */
public interface HttpFieldPreEncoder
{

    /**
     * The major version this encoder is for.  Both HTTP/1.0 and HTTP/1.1
     * use the same field encoding, so the {@link HttpVersion#HTTP_1_0} should
     * be return for all HTTP/1.x encodings.
     *
     * @return The major version this encoder is for.
     */
    HttpVersion getHttpVersion();

    byte[] getEncodedField(HttpHeader header, String headerString, String value);
}
