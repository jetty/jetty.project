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

package org.eclipse.jetty.util.statistic;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SampleStatisticTest
{
    private static long[][] data =
        {
            {100, 100, 100, 100, 100, 100, 100, 100, 100, 100},
            {100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 90, 110},
            {100, 100, 100, 100, 100, 100, 100, 100, 90, 110, 95, 105, 97, 103},
            {
                100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 90, 110, 95, 105, 97,
                103
            },
        };

    private static double[][] results =
        /* {mean,stddev}*/
        {{100.0, 0.0},
        {100.0, Math.sqrt((10 * 10 + 10 * 10) / 12.0)},
        {100.0, Math.sqrt((10 * 10 + 10 * 10 + 5 * 5 + 5 * 5 + 3 * 3 + 3 * 3) / 14.0)},
        {100.0, Math.sqrt((10 * 10 + 10 * 10 + 5 * 5 + 5 * 5 + 3 * 3 + 3 * 3) / 24.0)},
        {100.0, Math.sqrt((10 * 10 + 10 * 10 + 5 * 5 + 5 * 5 + 3 * 3 + 3 * 3) / 104.0)}
        };

    @Test
    public void testData()
        throws Exception
    {
        SampleStatistic stats = new SampleStatistic();
        for (int d = 0; d < data.length; d++)
        {
            stats.reset();
            for (long x : data[d])
            {
                stats.record(x);
            }

            assertEquals(data[d].length, (int)stats.getCount(), "count" + d);
            assertNearEnough("mean" + d, results[d][0], stats.getMean());
            assertNearEnough("stddev" + d, results[d][1], stats.getStdDev());
        }
        System.err.println(stats);
    }

    private void assertNearEnough(String test, double expected, double actual)
    {
        assertThat(actual, Matchers.greaterThan(expected - 0.1D));
        assertThat(actual, Matchers.lessThan(expected + 0.1D));
    }
}
