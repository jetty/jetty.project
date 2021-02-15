//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
