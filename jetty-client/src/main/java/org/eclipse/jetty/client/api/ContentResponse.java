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

package org.eclipse.jetty.client.api;

/**
 * A specialized {@link Response} that can hold a limited content in memory.
 */
public interface ContentResponse extends Response
{
    /**
     * @return the media type of the content, such as "text/html" or "application/octet-stream"
     */
    String getMediaType();

    /**
     * @return the encoding of the content, such as "UTF-8"
     */
    String getEncoding();

    /**
     * @return the response content
     */
    byte[] getContent();

    /**
     * @return the response content as a string, decoding the bytes using the charset
     * provided by the {@code Content-Type} header, if any, or UTF-8.
     */
    String getContentAsString();
}
