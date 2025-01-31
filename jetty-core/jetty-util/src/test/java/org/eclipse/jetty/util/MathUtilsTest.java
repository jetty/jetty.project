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

package org.eclipse.jetty.util;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MathUtilsTest
{
    @Test
    public void testCeilNextPowerOfTwo()
    {
        assertThrows(IllegalArgumentException.class, () -> MathUtils.ceilToNextPowerOfTwo(-1));
        assertThat(MathUtils.ceilToNextPowerOfTwo(0), is(1));
        assertThat(MathUtils.ceilToNextPowerOfTwo(1), is(1));
        assertThat(MathUtils.ceilToNextPowerOfTwo(2), is(2));
        assertThat(MathUtils.ceilToNextPowerOfTwo(3), is(4));
        assertThat(MathUtils.ceilToNextPowerOfTwo(4), is(4));
        assertThat(MathUtils.ceilToNextPowerOfTwo(5), is(8));
        assertThat(MathUtils.ceilToNextPowerOfTwo(Integer.MAX_VALUE - 1), is(Integer.MAX_VALUE));
    }

    @Test
    public void testCeilLog2()
    {
        assertThrows(IllegalArgumentException.class, () -> MathUtils.ceilLog2(-1));
        assertThat(MathUtils.ceilLog2(0), is(0));
        assertThat(MathUtils.ceilLog2(800), is(10));
        assertThat(MathUtils.ceilLog2(1024), is(10));
        assertThat(MathUtils.ceilLog2(Integer.MAX_VALUE), is(30));
    }
}
