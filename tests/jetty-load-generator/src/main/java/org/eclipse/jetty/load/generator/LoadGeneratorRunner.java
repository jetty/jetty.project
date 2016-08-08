package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class LoadGeneratorRunner
    implements Runnable
{
    private final HttpClient httpClient;

    private AtomicInteger requestRate;

    private final LoadGenerator loadGenerator;

    private final String url;

    private final LoadGeneratorResult loadGeneratorResult;

    public LoadGeneratorRunner( HttpClient httpClient, AtomicInteger requestRate, LoadGenerator loadGenerator, String url,
                                LoadGeneratorResult loadGeneratorResult )
    {
        this.httpClient = httpClient;
        this.requestRate = requestRate;
        this.loadGenerator = loadGenerator;
        this.url = url;
        this.loadGeneratorResult = loadGeneratorResult;
    }

    @Override
    public void run()
    {

        LoadGeneratorResponseListener loadGeneratorResponseListener =
            new LoadGeneratorResponseListener( loadGenerator.getResultHandlers() );
        // FIXME populate loadGeneratorResult with statistics values
        try
        {
            while ( true )
            {
                httpClient.newRequest( url ).send( loadGeneratorResponseListener );

                if ( this.loadGenerator.getStop().get() )
                {
                    break;
                }
            }
        }
        catch ( Exception e )
        {
            // TODO record error in generator report
        }
    }

    static class LoadGeneratorResponseListener
        implements Response.CompleteListener
    {
        private final List<ResultHandler> resultHandlers;

        public LoadGeneratorResponseListener( List<ResultHandler> resultHandlers)
        {
            this.resultHandlers = resultHandlers;
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
