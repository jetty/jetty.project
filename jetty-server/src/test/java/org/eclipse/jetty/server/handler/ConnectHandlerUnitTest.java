//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import static org.mockito.Mockito.when;
import static org.mockito.Matchers.*;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/* ------------------------------------------------------------ */
/**
 */
@RunWith(MockitoJUnitRunner.class)
public class ConnectHandlerUnitTest
{
    @Mock
    private EndPoint endPoint;

    @Test
    public void testPartialWritesWithNonFullBuffer() throws IOException
    {
        ConnectHandler connectHandler = new ConnectHandler();
        final byte[] bytes = "foo bar".getBytes();
        Buffer buffer = new ByteArrayBuffer(bytes.length * 2);
        buffer.put(bytes);
        when(endPoint.flush(buffer)).thenAnswer(new Answer<Object>()
        {
            public Object answer(InvocationOnMock invocation)
            {
                Object[] args = invocation.getArguments();
                Buffer buffer = (Buffer)args[0];
                int skip = bytes.length/2;
                buffer.skip(skip);
                return skip;
            }
        });
        when(endPoint.blockWritable(anyInt())).thenReturn(true);
        
        // method to test
        connectHandler.write(endPoint,buffer,null);
        
        assertThat(buffer.length(),is(0));
    }

}
