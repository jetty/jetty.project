package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.thread.QueuedThreadPool;


/* ------------------------------------------------------------ */
/** 
 */
public class Siege
{
    private static final class ConcurrentExchange extends HttpExchange
    {
        private final long _start=System.currentTimeMillis();
        private final HttpClient _client;
        private final CountDownLatch _latch;
        volatile int _status;
        volatile int _count;
        volatile long _bytes;
        final List<String> _uris;
        final int _repeats;
        int _u;
        int _r;
        
        AtomicBoolean counted=new AtomicBoolean(false);

        public ConcurrentExchange(HttpClient client,CountDownLatch latch, List<String> uris, int repeats)
        {
            _client = client;
            _latch = latch;
            _uris = uris;
            _repeats = repeats;
        }

        @Override
        protected void onConnectionFailed(Throwable ex)
        {
            if (!counted.getAndSet(true))
                _latch.countDown();
            super.onConnectionFailed(ex);
        }

        @Override
        protected void onException(Throwable ex)
        {
            if (!counted.getAndSet(true))
                _latch.countDown();
            super.onException(ex);
        }

        @Override
        protected void onExpire()
        {
            if (!counted.getAndSet(true))
                _latch.countDown();
            super.onExpire();
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            if (_status==200)
                _count++;
            if (!next() && !counted.getAndSet(true))
            {
                _latch.countDown();
                long duration=System.currentTimeMillis()-_start;
                System.err.printf("Got %d/%d with %dB in %dms %d%n",_count,_uris.size()*_repeats,_bytes,duration,_latch.getCount());
            }
        }
        

        /* ------------------------------------------------------------ */
        @Override
        protected void onResponseContent(Buffer content) throws IOException
        {
            _bytes+=content.length();
            super.onResponseContent(content);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.client.HttpExchange#onResponseHeader(org.eclipse.jetty.io.Buffer, org.eclipse.jetty.io.Buffer)
         */
        @Override
        protected void onResponseHeader(Buffer name, Buffer value) throws IOException
        {
            super.onResponseHeader(name,value);                
            if ("Set-Cookie".equalsIgnoreCase(name.toString()))
            {
                String v=value.toString();
                int c = v.indexOf(';');
                if (c>=0)
                    v=v.substring(0,c);
                addRequestHeader("Cookie",v);
            }
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.client.HttpExchange#onResponseHeaderComplete()
         */
        @Override
        protected void onResponseHeaderComplete() throws IOException
        {
            super.onResponseHeaderComplete();
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.client.HttpExchange#onResponseStatus(org.eclipse.jetty.io.Buffer, int, org.eclipse.jetty.io.Buffer)
         */
        @Override
        protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
        {
            _status=status;
            super.onResponseStatus(version,status,reason);
        }

        public boolean next()
        {
            if (_u>=_uris.size())
            {
                _u=0;
                _r++;
                if (_r>=_repeats)
                    return false;
            }
            
            String uri=_uris.get(_u++);
            
            reset();
            setMethod(HttpMethods.GET);
            setURL(uri);

            try
            {
                _client.send(this);
            }
            catch(IOException e)
            {
                e.printStackTrace();
                return false;
            }
            return true;
        }
    }

    public static void main(String[] args)
        throws Exception
    {
        if (args.length==0)
            args=new String[] 
                 { "-c", "2", "-r", "2", "http://localhost:8080/dump", "http://localhost:8080/d.txt"};
        
        int concurrent=1;
        int repeats=1;
        final List<String> uris = new ArrayList<String>();

        for (int i=0; i<args.length; i++)
        {
            String arg=args[i];
            if ("-c".equals(arg))
            {
                concurrent=Integer.parseInt(args[++i]);
                continue;
            }
            
            if ("-r".equals(arg))
            {
                repeats=Integer.parseInt(args[++i]);
                continue;
            }
            
            uris.add(arg);
        }
        
        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setMaxThreads(500);
        pool.setDaemon(true);
        
        HttpClient client = new HttpClient();
        client.setThreadPool(pool);
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        client.setIdleTimeout(30000);
        client.setConnectTimeout(30000);
        client.setMaxConnectionsPerAddress(concurrent*2);
        client.start();
        
        final CountDownLatch latch = new CountDownLatch(concurrent);   
        
        
        for (int i=0;i<concurrent;i++)
        {
            ConcurrentExchange ex = new ConcurrentExchange(client,latch,uris,repeats);
            if (!ex.next())
                latch.countDown();
        }
        
        latch.await();
        client.stop();
        pool.stop();
    }
}
