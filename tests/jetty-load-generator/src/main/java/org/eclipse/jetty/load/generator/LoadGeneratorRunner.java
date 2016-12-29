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

package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.toolchain.perf.PlatformTimer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.net.HttpCookie;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class LoadGeneratorRunner
    implements Runnable
{

    private static final Logger LOGGER = Log.getLogger(LoadGeneratorRunner.class);

    private final HttpClient httpClient;

    private final LoadGenerator loadGenerator;

    private final String url;

    private final LoadGeneratorResultHandler loadGeneratorResultHandler;

    private final HttpCookie HTTP_COOKIE = new HttpCookie( "XXX-Jetty-LoadGenerator", //
                                                                  Long.toString( System.nanoTime() ) );

    private static final PlatformTimer PLATFORM_TIMER = PlatformTimer.detect();

    public LoadGeneratorRunner( HttpClient httpClient, LoadGenerator loadGenerator, String url,
                                LoadGeneratorResultHandler loadGeneratorResultHandler )
    {
        this.httpClient = httpClient;
        this.loadGenerator = loadGenerator;
        this.url = url;
        this.loadGeneratorResultHandler = loadGeneratorResultHandler;
    }

    @Override
    public void run()
    {
        //int rate = this.loadGenerator.getRequestRate().get();
        //long start  = System.currentTimeMillis();
        //AtomicInteger sent = new AtomicInteger( 0 );

        //final ScheduledThreadPoolExecutor service = new ScheduledThreadPoolExecutor( 1);

        //DelayQueue<DelayedSend> delayedSends = new DelayQueue<>(  );
        //delayedSends.add( new DelayedSend( loadGenerator.getRequestRate().get() ) );

        // FIXME populate loadGeneratorResult with statistics values
        try
        {
            while ( true )
            {
                if ( this.loadGenerator.getStop().get() || httpClient.isStopped() )
                {
                    break;
                }


                //Thread.sleep( 1000 );
                /*
                int delay = 1000;//000 * service.getQueue().size() / loadGenerator.getRequestRate().get();
                service.schedule( () -> {

                    sent.incrementAndGet();
                    LOGGER.info( "sent request" );
                }, delay, TimeUnit.MILLISECONDS);
                */
                /*
                DelayedSend delayedSend = delayedSends.poll();

                if (delayedSend != null) {
                    delayedSends.add( new DelayedSend( loadGenerator.getRequestRate().get() ) );
                    httpClient.newRequest( url ).cookie( HTTP_COOKIE ).send( loadGeneratorResponseListener );
                    sent++;
                }
                */

                Request request = httpClient.newRequest( url ) //
                    .cookie( HTTP_COOKIE ) //
                    .header( "X-Download", Integer.toString( loadGenerator.getResponseSize() ) ) //
                    .method( loadGenerator.getMethod() );


                if (loadGenerator.getPayloadSize() > 0) {
                    request.content( new BytesContentProvider(new byte[loadGenerator.getPayloadSize()]) );
                }

                request.send( loadGeneratorResultHandler );

                loadGeneratorResultHandler.getLoadGeneratorResult().getTotalRequest().incrementAndGet();

                long waitTime = 1000 / loadGenerator.getRequestRate();

                //waitBlock( waitTime );
                PLATFORM_TIMER.sleep( TimeUnit.MILLISECONDS.toMicros( waitTime ) );

            }
        } catch ( RejectedExecutionException e )
        {
            // can happen if the client has been stopped
            LOGGER.debug( "ignore RejectedExecutionException", e );
        }
        catch ( Throwable e )
        {
            LOGGER.warn( "ignoring exception", e );
            // TODO record error in generator report
        }
    }

    /**
     * really naive implementation of wait x millis
     * @param timeToWait
     */
    private void waitBlock( long timeToWait )
    {
        long start = System.nanoTime();
        while ( true )
        {
            if ( TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - start ) >= timeToWait ) //
            {
                return;
            }
        }
    }

    private static class DelayedSend implements Delayed
    {
        private long requestRate;

        private DelayedSend( long requestRate) {
            this.requestRate = requestRate;
        }

        @Override
        public long getDelay( TimeUnit unit )
        {
            return unit.convert( requestRate, TimeUnit.SECONDS );
        }

        @Override
        public int compareTo( Delayed o )
        {
            // we don't mind
            return 0;
        }
    }


}
