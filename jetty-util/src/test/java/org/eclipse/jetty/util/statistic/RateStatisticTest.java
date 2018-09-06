//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.statistic;

import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;


/* ------------------------------------------------------------ */
public class RateStatisticTest
{
    @Test
    public void testRate()
        throws Exception
    {
        RateStatistic rs = new RateStatistic(1,TimeUnit.HOURS);
        assertThat(rs.getCount(),equalTo(0L));
        assertThat(rs.getRate(),equalTo(0));
        assertThat(rs.getMax(),equalTo(0L));
        
        rs.record();
        assertThat(rs.getCount(),equalTo(1L));
        assertThat(rs.getRate(),equalTo(1));
        assertThat(rs.getMax(),equalTo(1L));
        
        rs.age(35,TimeUnit.MINUTES);
        assertThat(rs.getCount(),equalTo(1L));
        assertThat(rs.getRate(),equalTo(1));
        assertThat(rs.getMax(),equalTo(1L));
        assertThat(rs.getOldest(TimeUnit.MINUTES),Matchers.is(35L));
        
        rs.record();
        assertThat(rs.getCount(),equalTo(2L));
        assertThat(rs.getRate(),equalTo(2));
        assertThat(rs.getMax(),equalTo(2L));
        
        rs.age(35,TimeUnit.MINUTES);
        assertThat(rs.getCount(),equalTo(2L));
        assertThat(rs.getRate(),equalTo(1));
        assertThat(rs.getMax(),equalTo(2L));
        
        rs.record();
        assertThat(rs.getCount(),equalTo(3L));
        assertThat(rs.getRate(),equalTo(2));
        assertThat(rs.getMax(),equalTo(2L));
        
        rs.age(35,TimeUnit.MINUTES);
        assertThat(rs.getCount(),equalTo(3L));
        assertThat(rs.getRate(),equalTo(1));
        assertThat(rs.getMax(),equalTo(2L));

    }
}
