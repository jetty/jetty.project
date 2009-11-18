package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.Buffer;


/* ------------------------------------------------------------ */
/** A simple test http client like curl.
 * <p>
 * Usage is java -cp $CLASSPATH org.eclipse.jetty.client.Curl [ option | URL ] ...
 * Options supported are: <ul>
 * <li>--async   : The following URLs are fetched in parallel (default)
 * <li>--sync    : The following URLs are fetched in sequence
 * <li>--dump    : The content is dumped to stdout
 * <li>--nodump  : The content is suppressed (default)
 * </ul>
 */
public class Curl
{
    public static void main(String[] args)
        throws Exception
    {
        if (args.length==0)
            args=new String[] 
                 { "--sync", "http://www.sun.com/robots.txt", "http://www.sun.com/favicon.ico" , "--dump", "http://www.sun.com/robots.txt"};
        
        HttpClient client = new HttpClient();
        client.setIdleTimeout(2000);
        client.start();
        boolean async=true;
        boolean dump= false;
        
        final CountDownLatch latch = new CountDownLatch(args.length);
        
        for (String arg : args)
        {
            if ("--sync".equals(arg))
            {
                async=false;
                continue;
            }
            
            if ("--async".equals(arg))
            {
                async=true;
                continue;
            }

            if ("--dump".equals(arg))
            {
                dump=true;
                continue;
            }
            
            if ("--nodump".equals(arg))
            {
                dump=false;
                continue;
            }

            final boolean d = dump;
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
                    if (d)
                        System.out.print(content.toString());
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

            System.err.println("\nSending "+ex);
            client.send(ex);
            
            if (!async)
            {
                System.err.println("waiting...");
                ex.waitForDone();
                System.err.println("Done");
            }
            
        }
        
        latch.await();
    }
}
