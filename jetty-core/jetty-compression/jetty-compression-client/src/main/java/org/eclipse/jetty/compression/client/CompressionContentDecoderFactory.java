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

package org.eclipse.jetty.compression.client;

import java.util.Objects;

import org.eclipse.jetty.client.ContentDecoder;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.Content;

/**
 * <p>Utility class that applications can use to explicitly configure
 * a {@link Compression} implementation in
 * {@link ContentDecoder.Factories#put(ContentDecoder.Factory)}.</p>
 */
public class CompressionContentDecoderFactory extends ContentDecoder.Factory
{
    private final Compression compression;

    public CompressionContentDecoderFactory(Compression compression)
    {
        this(compression, NO_WEIGHT);
    }

    public CompressionContentDecoderFactory(Compression compression, float weight)
    {
        super(compression.getEncodingName(), weight);
        this.compression = Objects.requireNonNull(compression);
        installBean(compression);
    }

    @Override
    public Content.Source newDecoderContentSource(Content.Source contentSource)
    {
        return compression.newDecoderSource(contentSource);
    }
}
