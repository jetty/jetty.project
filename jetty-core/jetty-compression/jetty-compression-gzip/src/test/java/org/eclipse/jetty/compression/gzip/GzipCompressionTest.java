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

package org.eclipse.jetty.compression.gzip;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class GzipCompressionTest extends AbstractGzipTest
{
    @Test
    public void testStripSuffixes() throws Exception
    {
        startGzip();
        assertThat(gzip.stripSuffixes("12345"), is("12345"));
        assertThat(gzip.stripSuffixes("12345, 666" + gzip.getEtagSuffix()), is("12345, 666"));
        assertThat(gzip.stripSuffixes("12345, 666" + gzip.getEtagSuffix() + ",W/\"9999" + gzip.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }
}
