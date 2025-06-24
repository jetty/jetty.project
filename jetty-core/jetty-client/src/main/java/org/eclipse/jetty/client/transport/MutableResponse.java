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
 * such as status code, headers, and trailers, as they
 * get parsed, typically from the network bytes.</p>
 */
public interface MutableResponse extends Response
{
    MutableResponse version(HttpVersion version);

    MutableResponse status(int status);

    MutableResponse reason(String reason);

    MutableResponse addHeader(HttpField header);

    MutableResponse headers(Consumer<HttpFields.Mutable> consumer);

    MutableResponse addTrailer(HttpField trailer);

    MutableResponse trailers(Consumer<HttpFields.Mutable> consumer);
}
