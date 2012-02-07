// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util;


import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.junit.Test;


/* ------------------------------------------------------------ */
/** Util meta Tests.
 * 
 */
public class DateCacheTest
{
    /* ------------------------------------------------------------ */
    @Test
    public void testDateCache() throws Exception
    {
        //@WAS: Test t = new Test("org.eclipse.jetty.util.DateCache");
        //                            012345678901234567890123456789
        DateCache dc = new DateCache("EEE, dd MMM yyyy HH:mm:ss zzz ZZZ",Locale.US);
        dc.setTimeZone(TimeZone.getTimeZone("GMT"));
        
        Thread.sleep(2000);

        long now=System.currentTimeMillis();
        long end=now+3000;
        String f=dc.format(now);
        String last=f;
        
        int hits=0;
        int misses=0;
        
        while (now<end)
        {
            last=f;
            f=dc.format(now);
            // System.err.printf("%s %s%n",f,last==f);
            if (last==f)
                hits++;
            else
                misses++;
            
            TimeUnit.MILLISECONDS.sleep(50);
            now=System.currentTimeMillis();
        }
        Assert.assertTrue(hits/10 > misses);
    }

}
