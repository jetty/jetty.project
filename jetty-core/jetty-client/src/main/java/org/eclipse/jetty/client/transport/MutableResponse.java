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

package org.eclipse.jetty.client.transport;

import java.util.function.Consumer;

import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

/**
 * <p>A mutable {@link Response}, used by implementations
 * to set the various components of an HTTP response,
 * such as status code, headers, and trailers.</p>
 */
public interface MutableResponse extends Response
{
    default MutableResponse version(HttpVersion version)
    {
        return this;
    }

    default MutableResponse status(int status)
    {
        return this;
    }

    default MutableResponse reason(String reason)
    {
        return this;
    }

    default MutableResponse addHeader(HttpField header)
    {
        return this;
    }

    default MutableResponse headers(Consumer<HttpFields.Mutable> consumer)
    {
        return this;
    }

    default MutableResponse addTrailer(HttpField trailer)
    {
        return this;
    }

    default MutableResponse trailers(Consumer<HttpFields.Mutable> consumer)
    {
        return this;
    }
}
