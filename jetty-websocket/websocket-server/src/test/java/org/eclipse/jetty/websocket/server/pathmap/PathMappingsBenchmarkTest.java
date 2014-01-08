//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.pathmap;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.annotation.Stress;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class PathMappingsBenchmarkTest
{
    public static abstract class AbstractPathMapThread extends Thread
    {
        private int iterations;
        private CyclicBarrier barrier;
        @SuppressWarnings("unused")
        private long success;
        @SuppressWarnings("unused")
        private long error;

        public AbstractPathMapThread(int iterations, CyclicBarrier barrier)
        {
            this.iterations = iterations;
            this.barrier = barrier;
        }

        public abstract String getMatchedResource(String path);

        @Override
        public void run()
        {
            int llen = LOOKUPS.length;
            String path;
            String expectedResource;
            String matchedResource;
            await(barrier);
            for (int iter = 0; iter < iterations; iter++)
            {
                for (int li = 0; li < llen; li++)
                {
                    path = LOOKUPS[li][0];
                    expectedResource = LOOKUPS[li][1];
                    matchedResource = getMatchedResource(path);
                    if (matchedResource.equals(expectedResource))
                    {
                        success++;
                    }
                    else
                    {
                        error++;
                    }
                }
            }
            await(barrier);
        }
    }

    public static class PathMapMatchThread extends AbstractPathMapThread
    {
        private PathMap<String> pathmap;

        public PathMapMatchThread(PathMap<String> pathmap, int iters, CyclicBarrier barrier)
        {
            super(iters,barrier);
            this.pathmap = pathmap;
        }

        @Override
        public String getMatchedResource(String path)
        {
            return pathmap.getMatch(path).getValue();
        }
    }

    public static class PathMatchThread extends AbstractPathMapThread
    {
        private PathMappings<String> pathmap;

        public PathMatchThread(PathMappings<String> pathmap, int iters, CyclicBarrier barrier)
        {
            super(iters,barrier);
            this.pathmap = pathmap;
        }

        @Override
        public String getMatchedResource(String path)
        {
            return pathmap.getMatch(path).getResource();
        }
    }

    private static final Logger LOG = Log.getLogger(PathMappingsBenchmarkTest.class);
    private static final String[][] LOOKUPS;
    private int runs = 20;
    private int threads = 200;
    private int iters = 10000;

    static
    {
        LOOKUPS = new String[][]
        {
        // @formatter:off
         { "/abs/path", "path" },
         { "/abs/path/longer","longpath" },
         { "/abs/path/foo","default" },
         { "/main.css","default" },
         { "/downloads/script.gz","gzipped" },
         { "/downloads/distribution.tar.gz","tarball" },
         { "/downloads/readme.txt","default" },
         { "/downloads/logs.tgz","default" },
         { "/animal/horse/mustang","animals" },
         { "/animal/bird/eagle/bald","birds" },
         { "/animal/fish/shark/hammerhead","fishes" },
         { "/animal/insect/ladybug","animals" },
        // @formatter:on
        };
    }

    private static void await(CyclicBarrier barrier)
    {
        try
        {
            barrier.await();
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    @Stress("High CPU")
    @Test
    public void testServletPathMap()
    {
        // Setup (old) PathMap

        PathMap<String> p = new PathMap<>();

        p.put("/abs/path","path");
        p.put("/abs/path/longer","longpath");
        p.put("/animal/bird/*","birds");
        p.put("/animal/fish/*","fishes");
        p.put("/animal/*","animals");
        p.put("*.tar.gz","tarball");
        p.put("*.gz","gzipped");
        p.put("/","default");

        final CyclicBarrier barrier = new CyclicBarrier(threads + 1);

        for (int r = 0; r < runs; r++)
        {
            for (int t = 0; t < threads; t++)
            {
                PathMapMatchThread thread = new PathMapMatchThread(p,iters,barrier);
                thread.start();
            }
            await(barrier);
            long begin = System.nanoTime();
            await(barrier);
            long end = System.nanoTime();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
            int totalMatches = threads * iters * LOOKUPS.length;
            LOG.info("jetty-http/PathMap (Servlet only) threads:{}/iters:{}/total-matches:{} => {} ms",threads,iters,totalMatches,elapsed);
        }
    }

    @Stress("High CPU")
    @Test
    public void testServletPathMappings()
    {
        // Setup (new) PathMappings

        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/abs/path"),"path");
        p.put(new ServletPathSpec("/abs/path/longer"),"longpath");
        p.put(new ServletPathSpec("/animal/bird/*"),"birds");
        p.put(new ServletPathSpec("/animal/fish/*"),"fishes");
        p.put(new ServletPathSpec("/animal/*"),"animals");
        p.put(new ServletPathSpec("*.tar.gz"),"tarball");
        p.put(new ServletPathSpec("*.gz"),"gzipped");
        p.put(new ServletPathSpec("/"),"default");

        final CyclicBarrier barrier = new CyclicBarrier(threads + 1);

        for (int r = 0; r < runs; r++)
        {
            for (int t = 0; t < threads; t++)
            {
                PathMatchThread thread = new PathMatchThread(p,iters,barrier);
                thread.start();
            }
            await(barrier);
            long begin = System.nanoTime();
            await(barrier);
            long end = System.nanoTime();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
            int totalMatches = threads * iters * LOOKUPS.length;
            LOG.info("jetty-websocket/PathMappings (Servlet only) threads:{}/iters:{}/total-matches:{} => {} ms",threads,iters,totalMatches,elapsed);

        }
    }
}
