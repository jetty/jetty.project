package org.eclipse.jetty.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class ResetStreamTest extends AbstractTest
{
    @Test
    public void testResetStreamIsRemoved() throws Exception
    {
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()), null);

        Stream stream = session.syn(new SynInfo(false), null).get(5, TimeUnit.SECONDS);
        session.rst(new RstInfo(stream.getId(), StreamStatus.CANCEL_STREAM)).get(5, TimeUnit.SECONDS);

        Assert.assertEquals(0, session.getStreams().size());
    }

    @Test
    public void testRefusedStreamIsRemoved() throws Exception
    {
        final AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch rstLatch = new CountDownLatch(1);
        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Session serverSession = stream.getSession();
                serverSessionRef.set(serverSession);
                serverSession.rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM));
                synLatch.countDown();
                return null;
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                rstLatch.countDown();
            }
        });

        clientSession.syn(new SynInfo(false), null).get(5, TimeUnit.SECONDS);

        Assert.assertTrue(synLatch.await(5, TimeUnit.SECONDS));
        Session serverSession = serverSessionRef.get();
        Assert.assertEquals(0, serverSession.getStreams().size());

        Assert.assertTrue(rstLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, clientSession.getStreams().size());
    }

    @Test
    public void testRefusedStreamIgnoresData() throws Exception
    {
        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        final CountDownLatch rstLatch = new CountDownLatch(1);
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                try
                {
                    // Refuse the stream, we must ignore data frames
                    Assert.assertTrue(synLatch.await(5, TimeUnit.SECONDS));
                    stream.getSession().rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM));
                    return new StreamFrameListener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataInfo dataInfo)
                        {
                            dataLatch.countDown();
                        }
                    };
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                    return null;
                }
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                rstLatch.countDown();
            }
        });

        Stream stream = session.syn(new SynInfo(false), null).get(5, TimeUnit.SECONDS);
        stream.data(new StringDataInfo("data", true), 5, TimeUnit.SECONDS, new Handler.Adapter<Void>()
        {
            @Override
            public void completed(Void context)
            {
                synLatch.countDown();
            }
        });

        Assert.assertTrue(rstLatch.await(5, TimeUnit.SECONDS));
        Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));
    }
}
