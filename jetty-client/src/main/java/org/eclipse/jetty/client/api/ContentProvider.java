//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.client.util.PathContentProvider;

/**
 * {@link ContentProvider} provides a repeatable source of request content.
 * <p />
 * Implementations should return a new "view" over the same content every time {@link #iterator()} is invoked.
 * <p />
 * Applications should rely on utility classes such as {@link ByteBufferContentProvider}
 * or {@link PathContentProvider}.
 */
public interface ContentProvider extends Iterable<ByteBuffer>
{
    /**
     * @return the content length, if known, or -1 if the content length is unknown
     */
    long getLength();
}
