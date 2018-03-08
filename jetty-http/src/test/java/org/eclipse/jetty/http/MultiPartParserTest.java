//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.MultiPartParser.State;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class MultiPartParserTest
{

    @Test
    public void testEmptyPreamble()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler(){},"BOUNDARY");
        ByteBuffer data = BufferUtil.toBuffer("");
        
        parser.parse(data,false);
        assertTrue(parser.isState(State.PREAMBLE));
    }

    @Test
    public void testNoPreamble()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler(){},"BOUNDARY");
        ByteBuffer data = BufferUtil.toBuffer("");
        
        data = BufferUtil.toBuffer("--BOUNDARY   \r\n");
        parser.parse(data,false);
        assertTrue(parser.isState(State.BODY_PART));
        assertThat(data.remaining(),is(0));
    }
    
    @Test
    public void testPreamble()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler(){},"BOUNDARY");
        ByteBuffer data;
        
        data = BufferUtil.toBuffer("This is not part of a part\r\n");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.PREAMBLE));
        assertThat(data.remaining(),is(0));
        
        data = BufferUtil.toBuffer("More data that almost includes \n--BOUNDARY but no CR before.");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.PREAMBLE));
        assertThat(data.remaining(),is(0));
        
        data = BufferUtil.toBuffer("Could be a boundary \r\n--BOUNDAR");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.PREAMBLE));
        assertThat(data.remaining(),is(0));
        
        data = BufferUtil.toBuffer("but not it isn't");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.PREAMBLE));
        assertThat(data.remaining(),is(0));
    }

    @Test
    public void testPreambleCompleteBoundary()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler(){},"BOUNDARY");
        ByteBuffer data;
        
        data = BufferUtil.toBuffer("This is not part of a part\r\n--BOUNDARY  \r\n");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.BODY_PART));
        assertThat(data.remaining(),is(0));
    }
    
    @Test
    public void testPreambleSplitBoundary()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler(){},"BOUNDARY");
        ByteBuffer data;

        data = BufferUtil.toBuffer("This is not part of a part\r\n");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.PREAMBLE));
        assertThat(data.remaining(),is(0));
        data = BufferUtil.toBuffer("-");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.PREAMBLE));
        assertThat(data.remaining(),is(0));
        data = BufferUtil.toBuffer("-");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.PREAMBLE));
        assertThat(data.remaining(),is(0));
        data = BufferUtil.toBuffer("B");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.PREAMBLE));
        assertThat(data.remaining(),is(0));
        data = BufferUtil.toBuffer("OUNDARY-");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.DELIMITER_CLOSE));
        assertThat(data.remaining(),is(0));
        data = BufferUtil.toBuffer("ignore\r");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.DELIMITER_PADDING));
        assertThat(data.remaining(),is(0));
        data = BufferUtil.toBuffer("\n");
        parser.parse(data,false);
        assertThat(parser.getState(),is(State.BODY_PART));
        assertThat(data.remaining(),is(0));
    }

    
}
