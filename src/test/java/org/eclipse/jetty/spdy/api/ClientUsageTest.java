package org.eclipse.jetty.spdy.api;

import java.nio.charset.Charset;

import org.eclipse.jetty.spdy.StandardSession;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class ClientUsageTest
{
    @Test
    public void testClientRequestResponseNoBody() throws Exception
    {
        Session session = new StandardSession(null, 1, null, null);

        session.syn((short)2, new SynInfo(false), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // Do something with the response
                replyInfo.getHeaders().get("host");

                // Then issue another similar request
                stream.getSession().syn((short)2, new SynInfo(true), this);
            }
        });
    }

    @Test
    public void testClientRequestWithBodyAndResponseWithBody() throws Exception
    {
        Session session = new StandardSession(null, 1, null, null);

        Stream stream = session.syn((short)2, new SynInfo(false), new Stream.FrameListener.Adapter()
        {
            // The good of passing the listener here is that you can safely accumulate info
            // from the headers to be used in the data, e.g. content-type, charset
            // In BWTP the listener was attached to the session, not passed to syn(), so could
            // not accumulate if not adding attributes to the stream (which is a good idea anyway)

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // This style is similar to the new async channel API in JDK 7

                // Do something with the response
                int contentLength = replyInfo.getHeaders().get("content-length").valueAsInt();
                //                stream.setAttribute("content-length", contentLength);

                // Then issue another similar request
                stream.getSession().syn((short)2, new SynInfo(true), this);
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                //                StringBuilder builder = new StringBuilder();
                //                builder.append(Charset.forName("UTF-8").decode(data));
                if (dataInfo.isClose())
                {
                    //                    System.err.println("data = " + builder);
                    //                    assert builder.toString().getBytes().length == stream.getAttribute("content-length");
                }

            }
        });

        stream.data(new BytesDataInfo("wee".getBytes(Charset.forName("UTF-8")), false));
        stream.data(new StringDataInfo("foo", false));
        stream.data(new ByteBufferDataInfo(Charset.forName("UTF-8").encode("bar"), false));
//        stream.data(new InputStreamDataInfo(new ByteArrayInputStream("baz".getBytes(Charset.forName("UTF-8"))), false));

        //
        // In CometD the style is different, but in bayeux the frame IS the message,
        // while in SPDY the message is composed of several frames of different types
        // e.g. synReply+data vs a single bayeux message
        // That is why the listeners in Bayeux are simpler: you can only receive messages

        // However, we can mimic Bayeux's behavior with SPDY if we add another layer on top of it
        // that produces a Message that has an input stream (so that arbitrarily long bodies can be
        // read without exhausting the memory).



    }
}
