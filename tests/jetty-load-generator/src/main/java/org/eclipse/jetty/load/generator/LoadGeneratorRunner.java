package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class LoadGeneratorRunner
    implements Runnable
{
    private final HttpClient httpClient;

    private AtomicLong requestNumber;

    private final LoadGenerator loadGenerator;

    private final String url;

    private final LoadGeneratorResult loadGeneratorResult;

    public LoadGeneratorRunner( HttpClient httpClient, long requestNumber, LoadGenerator loadGenerator, String url,
                                LoadGeneratorResult loadGeneratorResult )
    {
        this.httpClient = httpClient;
        this.requestNumber = new AtomicLong( requestNumber );
        this.loadGenerator = loadGenerator;
        this.url = url;
        this.loadGeneratorResult = loadGeneratorResult;
    }

    @Override
    public void run()
    {

        LoadGeneratorResponseListener loadGeneratorResponseListener =
            new LoadGeneratorResponseListener( loadGenerator.getResultHandlers(), loadGenerator.getRequestNumber() );
        // FIXME populate loadGeneratorResult with statistics values
        try
        {
            while ( true )
            {
                httpClient.newRequest( url ).send( loadGeneratorResponseListener );

                // olamy: should we decrement on response rather than after request send?
                if ( this.requestNumber.decrementAndGet() < 1 )
                {
                    break;
                }
            }
            int size = httpClient.getRequestBufferSize();
            httpClient.stop();
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

        private final AtomicLong requestNumber;

        public LoadGeneratorResponseListener( List<ResultHandler> resultHandlers, AtomicLong requestNumber )
        {
            this.resultHandlers = resultHandlers;
            this.requestNumber = requestNumber;
        }

        @Override
        public void onComplete( Result result )
        {
            for ( ResultHandler resultHandler : resultHandlers )
            {
                resultHandler.onResponse( result );
            }
            // here we decrement the global request number
            this.requestNumber.decrementAndGet();
        }
    }
}
