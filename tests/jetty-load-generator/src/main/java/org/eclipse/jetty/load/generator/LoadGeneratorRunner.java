package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.net.HttpCookie;
import java.util.List;
import java.util.concurrent.Delayed;
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

    private final LoadGeneratorResult loadGeneratorResult;

    private static final HttpCookie HTTP_COOKIE = new HttpCookie( "beer", Long.toString( System.nanoTime() ) );

    public LoadGeneratorRunner( HttpClient httpClient, LoadGenerator loadGenerator, String url,
                                LoadGeneratorResult loadGeneratorResult )
    {
        this.httpClient = httpClient;
        this.loadGenerator = loadGenerator;
        this.url = url;
        this.loadGeneratorResult = loadGeneratorResult;
    }

    @Override
    public void run()
    {
        //int rate = this.loadGenerator.getRequestRate().get();
        //long start  = System.currentTimeMillis();
        //AtomicInteger sent = new AtomicInteger( 0 );

        LoadGeneratorResponseListener loadGeneratorResponseListener =
            new LoadGeneratorResponseListener( loadGenerator.getResultHandlers(), this );

        //final ScheduledThreadPoolExecutor service = new ScheduledThreadPoolExecutor( 1);

        //DelayQueue<DelayedSend> delayedSends = new DelayQueue<>(  );
        //delayedSends.add( new DelayedSend( loadGenerator.getRequestRate().get() ) );

        // FIXME populate loadGeneratorResult with statistics values
        try
        {
            while ( true )
            {

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

                httpClient.newRequest( url ).cookie( HTTP_COOKIE ).send( loadGeneratorResponseListener );

                if ( this.loadGenerator.getStop().get() )
                {
                    break;
                }

                long waitTime = 1000 / loadGenerator.getRequestRate().get();

                waitBlock( waitTime );

            }
        }
        catch ( Exception e )
        {
            LOGGER.warn( "ignoring exception", e );
            // TODO record error in generator report
        }
    }

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

    static class LoadGeneratorResponseListener
        implements Response.CompleteListener
    {
        private final List<ResultHandler> resultHandlers;

        private final LoadGeneratorRunner loadGeneratorRunner;

        public LoadGeneratorResponseListener( List<ResultHandler> resultHandlers, LoadGeneratorRunner loadGeneratorRunner)
        {
            this.resultHandlers = resultHandlers;
            this.loadGeneratorRunner = loadGeneratorRunner;
        }

        @Override
        public void onComplete( Result result )
        {
            for ( ResultHandler resultHandler : resultHandlers )
            {
                resultHandler.onResponse( result );
            }
        }
    }

}
