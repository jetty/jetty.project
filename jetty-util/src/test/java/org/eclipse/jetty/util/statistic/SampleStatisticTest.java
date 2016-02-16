//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;


/* ------------------------------------------------------------ */
public class SampleStatisticTest
{
    private static long[][] data =
    {
        {100,100,100,100,100,100,100,100,100,100},
        {100,100,100,100,100,100,100,100,100,100,90,110},
        {100,100,100,100,100,100,100,100,90,110,95,105,97,103},
        {100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,100,90,110,95,105,97,103},
    };

    private static double[][] results =
    { /* {mean,stddev}*/
        {100.0,0.0},
        {100.0,Math.sqrt((10*10+10*10)/12.0)},
        {100.0,Math.sqrt((10*10+10*10+5*5+5*5+3*3+3*3)/14.0)},
        {100.0,Math.sqrt((10*10+10*10+5*5+5*5+3*3+3*3)/24.0)},
        {100.0,Math.sqrt((10*10+10*10+5*5+5*5+3*3+3*3)/104.0)}
    };


    @Test
    public void testData()
        throws Exception
    {
        SampleStatistic stats = new SampleStatistic();
        for (int d=0;d<data.length;d++)
        {
            stats.reset();
            for (long x : data[d])
                stats.set(x);

            assertEquals("count"+d,data[d].length, (int)stats.getCount());
            assertNearEnough("mean"+d,results[d][0], stats.getMean());
            assertNearEnough("stddev"+d,results[d][1], stats.getStdDev());
        }
    }

    private void assertNearEnough(String test,double expected, double actual)
    {
        Assert.assertThat(actual,Matchers.greaterThan(expected-0.1D));
        Assert.assertThat(actual,Matchers.lessThan(expected+0.1D));
    }

}
