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

package org.eclipse.jetty.http2.api;

import org.junit.Ignore;
import org.junit.Test;

public class UsageTest
{
    @Ignore
    @Test
    public void test() throws Exception
    {
//        HTTP2Client client = new HTTP2Client();
//        client.connect("localhost", 8080, new Promise.Adapter<Session>()
//        {
//            @Override
//            public void succeeded(Session session)
//            {
//                session.newStream(new HeadersFrame(info, null, true), new Stream.Listener.Adapter()
//                {
//                    @Override
//                    public void onData(Stream stream, DataFrame frame)
//                    {
//                        System.out.println("received frame = " + frame);
//                    }
//                }, new Adapter<Stream>()
//                {
//                    @Override
//                    public void succeeded(Stream stream)
//                    {
//                        DataFrame frame = new DataFrame(stream.getId(), ByteBuffer.wrap("HELLO".getBytes(StandardCharsets.UTF_8)), true);
//                        stream.data(frame, new Callback.Adapter());
//                    }
//                });
//            }
//        });

        // KINDA CALLBACK HELL ABOVE.
        // BELOW USING COMPLETABLES:

//        client.connect("localhost", 8080).then(session -> session.newStream(...)).then(stream -> stream.data(...));
    }
}
