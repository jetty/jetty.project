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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;


/* ------------------------------------------------------------ */
public class CounterStatisticTest
{

    @Test
    public void testCounter()
        throws Exception
    {
        CounterStatistic count = new CounterStatistic();
        
        assertThat(count.getCurrent(),equalTo(0L));
        assertThat(count.getMax(),equalTo(0L));
        assertThat(count.getTotal(),equalTo(0L));
        
        count.increment();
        count.increment();
        count.decrement();
        count.add(4);
        count.add(-2);

        assertThat(count.getCurrent(),equalTo(3L));
        assertThat(count.getMax(),equalTo(5L));
        assertThat(count.getTotal(),equalTo(6L));
        
        count.reset();
        assertThat(count.getCurrent(),equalTo(3L));
        assertThat(count.getMax(),equalTo(3L));
        assertThat(count.getTotal(),equalTo(3L));

        count.increment();
        count.decrement();
        count.add(-2);
        count.decrement();
        assertThat(count.getCurrent(),equalTo(0L));
        assertThat(count.getMax(),equalTo(4L));
        assertThat(count.getTotal(),equalTo(4L));
     
        count.decrement();
        assertThat(count.getCurrent(),equalTo(-1L));
        assertThat(count.getMax(),equalTo(4L));
        assertThat(count.getTotal(),equalTo(4L));

        count.increment();
        assertThat(count.getCurrent(),equalTo(0L));
        assertThat(count.getMax(),equalTo(4L));
        assertThat(count.getTotal(),equalTo(5L));
    }

}
