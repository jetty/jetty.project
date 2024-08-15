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

package org.eclipse.jetty.compression.zstandard;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ZstandardCompressionTest extends AbstractZstdTest
{
    @Test
    public void testStripSuffixes() throws Exception
    {
        startZstd();
        assertThat(zstd.stripSuffixes("12345"), is("12345"));
        assertThat(zstd.stripSuffixes("12345, 666" + zstd.getEtagSuffix()), is("12345, 666"));
        assertThat(zstd.stripSuffixes("12345, 666" + zstd.getEtagSuffix() + ",W/\"9999" + zstd.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }
}
