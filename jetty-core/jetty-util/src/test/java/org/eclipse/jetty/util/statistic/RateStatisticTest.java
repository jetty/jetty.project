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

import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class RateStatisticTest
{
    @Test
    public void testRate()
        throws Exception
    {
        RateStatistic rs = new RateStatistic(1, TimeUnit.HOURS);
        assertThat(rs.getCount(), equalTo(0L));
        assertThat(rs.getRate(), equalTo(0));
        assertThat(rs.getMax(), equalTo(0L));

        rs.record();
        assertThat(rs.getCount(), equalTo(1L));
        assertThat(rs.getRate(), equalTo(1));
        assertThat(rs.getMax(), equalTo(1L));

        rs.age(35, TimeUnit.MINUTES);
        assertThat(rs.getCount(), equalTo(1L));
        assertThat(rs.getRate(), equalTo(1));
        assertThat(rs.getMax(), equalTo(1L));
        assertThat(rs.getOldest(TimeUnit.MINUTES), Matchers.is(35L));

        rs.record();
        assertThat(rs.getCount(), equalTo(2L));
        assertThat(rs.getRate(), equalTo(2));
        assertThat(rs.getMax(), equalTo(2L));

        rs.age(35, TimeUnit.MINUTES);
        assertThat(rs.getCount(), equalTo(2L));
        assertThat(rs.getRate(), equalTo(1));
        assertThat(rs.getMax(), equalTo(2L));

        rs.record();
        assertThat(rs.getCount(), equalTo(3L));
        assertThat(rs.getRate(), equalTo(2));
        assertThat(rs.getMax(), equalTo(2L));

        rs.age(35, TimeUnit.MINUTES);
        assertThat(rs.getCount(), equalTo(3L));
        assertThat(rs.getRate(), equalTo(1));
        assertThat(rs.getMax(), equalTo(2L));
    }
}
