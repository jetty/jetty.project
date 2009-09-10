package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.Buffer;

public class Curl
{
    public static void main(String[] args)
        throws Exception
    {
        HttpClient client = new HttpClient();
        client.start();
        
        final CountDownLatch latch = new CountDownLatch(args.length);
        
        for (String arg : args)
        {
            HttpExchange ex = new HttpExchange()
            {
                AtomicBoolean counted=new AtomicBoolean(false);

                @Override
                protected void onConnectionFailed(Throwable ex)
                {
                    if (!counted.getAndSet(true))
                        latch.countDown();
                    super.onConnectionFailed(ex);
                }

                @Override
                protected void onException(Throwable ex)
                {
                    if (!counted.getAndSet(true))
                        latch.countDown();
                    super.onException(ex);
                }

                @Override
                protected void onExpire()
                {
                    if (!counted.getAndSet(true))
                        latch.countDown();
                    super.onExpire();
                }

                @Override
                protected void onResponseComplete() throws IOException
                {
                    if (!counted.getAndSet(true))
                        latch.countDown();
                    super.onResponseComplete();
                }

                @Override
                protected void onResponseContent(Buffer content) throws IOException
                {
                    super.onResponseContent(content);
                    System.err.println("got "+content.length());
                }

                /* ------------------------------------------------------------ */
                /**
                 * @see org.eclipse.jetty.client.HttpExchange#onResponseHeader(org.eclipse.jetty.io.Buffer, org.eclipse.jetty.io.Buffer)
                 */
                @Override
                protected void onResponseHeader(Buffer name, Buffer value) throws IOException
                {
                    super.onResponseHeader(name,value);
                    System.err.println(name+": "+value);
                }

                /* ------------------------------------------------------------ */
                /**
                 * @see org.eclipse.jetty.client.HttpExchange#onResponseHeaderComplete()
                 */
                @Override
                protected void onResponseHeaderComplete() throws IOException
                {
                    super.onResponseHeaderComplete();
                    System.err.println();
                }

                /* ------------------------------------------------------------ */
                /**
                 * @see org.eclipse.jetty.client.HttpExchange#onResponseStatus(org.eclipse.jetty.io.Buffer, int, org.eclipse.jetty.io.Buffer)
                 */
                @Override
                protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
                {
                    super.onResponseStatus(version,status,reason);
                    System.err.println(version+" "+status+" "+reason);
                }
            };
            
            ex.setMethod(HttpMethods.GET);
            ex.setURL(arg);
            
            client.send(ex);
        }
        
        latch.await();
    }
}
