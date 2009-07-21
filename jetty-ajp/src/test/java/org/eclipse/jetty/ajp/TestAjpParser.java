// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.ajp;

import java.io.IOException;

import junit.framework.TestCase;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.util.TypeUtil;

public class TestAjpParser extends TestCase
{

    public void testPacket1() throws Exception
    {
        String packet = "123401070202000f77696474683d20485454502f312e300000122f636f6e74726f6c2f70726f647563742f2200000e3230382e32372e3230332e31323800ffff000c7777772e756c74612e636f6d000050000005a006000a6b6565702d616c69766500a00b000c7777772e756c74612e636f6d00a00e002b4d6f7a696c6c612f342e302028636f6d70617469626c653b20426f726465724d616e6167657220332e302900a0010043696d6167652f6769662c20696d6167652f782d786269746d61702c20696d6167652f6a7065672c20696d6167652f706a7065672c20696d6167652f706d672c202a2f2a00a008000130000600067570726f64310008000a4145533235362d53484100ff";
        byte[] src = TypeUtil.fromHexString(packet);
        
        ByteArrayBuffer buffer= new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
        SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        
        EndPoint endp = new ByteArrayEndPoint(src,Ajp13Packet.MAX_PACKET_SIZE);
        
        Ajp13Parser parser = new Ajp13Parser(buffers,endp);
        parser.setEventHandler(new EH());
        parser.setGenerator(new Ajp13Generator(buffers,endp));
        
        parser.parseAvailable();
        
        assertTrue(true);
    }    
    
    public void testPacket2() throws Exception
    {
        String packet="1234020102020008485454502f312e3100000f2f6363632d7777777777772f61616100000c38382e3838382e38382e383830ffff00116363632e6363636363636363632e636f6d0001bb010009a00b00116363632e6363636363636363632e636f6d00a00e005a4d6f7a696c6c612f352e30202857696e646f77733b20553b2057696e646f7773204e5420352e313b20656e2d55533b2072763a312e382e312e3129204765636b6f2f32303036313230342046697265666f782f322e302e302e3100a0010063746578742f786d6c2c6170706c69636174696f6e2f786d6c2c6170706c69636174696f6e2f7868746d6c2b786d6c2c746578742f68746d6c3b713d302e392c746578742f706c61696e3b713d302e382c696d6167652f706e672c2a2f2a3b713d302e3500a004000e656e2d75732c656e3b713d302e3500a003000c677a69702c6465666c61746500a002001e49534f2d383835392d312c7574662d383b713d302e372c2a3b713d302e3700000a4b6565702d416c69766500000333303000a006000a6b6565702d616c69766500000c4d61782d466f7277617264730000023130000800124448452d5253412d4145533235362d5348410009004039324643303544413043444141443232303137413743443141453939353132413330443938363838423843433041454643364231363035323543433232353341000b0100ff";
        byte[] src=TypeUtil.fromHexString(packet);
        ByteArrayBuffer buffer=new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
        SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        EndPoint endp=new ByteArrayEndPoint(src,Ajp13Packet.MAX_PACKET_SIZE);
        Ajp13Parser parser = new Ajp13Parser(buffers,endp);
        parser.setEventHandler(new EH());
        parser.setGenerator(new Ajp13Generator(buffers,endp));
        parser.parse();
        assertTrue(true);
    }    
    
    public void testPacket3() throws Exception
    {
        String packet="1234028f02020008485454502f312e3100000d2f666f726d746573742e6a737000000d3139322e3136382e342e31383000ffff00107777772e777265636b6167652e6f726700005000000aa0010063746578742f786d6c2c6170706c69636174696f6e2f786d6c2c6170706c69636174696f6e2f7868746d6c2b786d6c2c746578742f68746d6c3b713d302e392c746578742f706c61696e3b713d302e382c696d6167652f706e672c2a2f2a3b713d302e3500a00200075554462d382c2a00a003000c677a69702c6465666c61746500a004000e656e2d67622c656e3b713d302e3500a006000a6b6565702d616c69766500a00900f95048505345535349443d37626361383232616638333466316465373663633630336366636435313938633b20667041757468436f6f6b69653d433035383430394537393344364245434633324230353234344242303039343230383344443645443533304230454637464137414544413745453231313538333745363033454435364332364446353531383635333335423433374531423637414641343533364345304546323342333642323133374243423932333943363631433131443330393842333938414546334546334146454344423746353842443b204a53455353494f4e49443d7365366331623864663432762e6a657474793300a00b00107777772e777265636b6167652e6f726700000a6b6565702d616c69766500000333303000a00e00654d6f7a696c6c612f352e3020285831313b20553b204c696e7578207838365f36343b20656e2d55533b2072763a312e382e302e3929204765636b6f2f3230303631323035202844656269616e2d312e382e302e392d3129204570697068616e792f322e313400a008000130000600066a657474793300ff";
        byte[] src=TypeUtil.fromHexString(packet);
        ByteArrayBuffer buffer=new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
        SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        EndPoint endp=new ByteArrayEndPoint(src,Ajp13Packet.MAX_PACKET_SIZE);
        Ajp13Parser parser = new Ajp13Parser(buffers,endp);
        parser.setEventHandler(new EH());
        parser.setGenerator(new Ajp13Generator(buffers,endp));
        parser.parse();
        assertTrue(true);
    }
    
    
    public void testSSLPacketWithIntegerKeySize() throws Exception
    {
        String packet = "1234025002020008485454502f312e3100000f2f746573742f64756d702f696e666f00000e3139322e3136382e3130302e343000ffff000c776562746964652d746573740001bb01000ca00b000c776562746964652d7465737400a00e005a4d6f7a696c6c612f352e30202857696e646f77733b20553b2057696e646f7773204e5420352e313b20656e2d55533b2072763a312e382e312e3129204765636b6f2f32303036313230342046697265666f782f322e302e302e3100a0010063746578742f786d6c2c6170706c69636174696f6e2f786d6c2c6170706c69636174696f6e2f7868746d6c2b786d6c2c746578742f68746d6c3b713d302e392c746578742f706c61696e3b713d302e382c696d6167652f706e672c2a2f2a3b713d302e3500a004000e656e2d75732c656e3b713d302e3500a003000c677a69702c6465666c61746500a002001e49534f2d383835392d312c7574662d383b713d302e372c2a3b713d302e3700000a4b6565702d416c69766500000333303000a006000a6b6565702d616c69766500a00d001a68747470733a2f2f776562746964652d746573742f746573742f00a00900174a53455353494f4e49443d69326c6e307539773573387300000d43616368652d436f6e74726f6c0000096d61782d6167653d3000000c4d61782d466f7277617264730000023130000800124448452d5253412d4145533235362d5348410009004032413037364245323330433238393130383941414132303631344139384441443131314230323132343030374130363642454531363742303941464337383942000b0100ff";
        byte[] src = TypeUtil.fromHexString(packet);
        
        ByteArrayBuffer buffer= new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
        SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        
        EndPoint endp = new ByteArrayEndPoint(src,Ajp13Packet.MAX_PACKET_SIZE);
        
        Ajp13Parser parser = new Ajp13Parser(buffers,endp);
        parser.setEventHandler(new EH());
        parser.setGenerator(new Ajp13Generator(buffers,endp));
        
        parser.parseAvailable();
        
        assertTrue(true);
    }

    public void testSSLPacketWithStringKeySize() throws Exception
    {
        String packet = "1234025002020008485454502f312e3100000f2f746573742f64756d702f696e666f00000e3139322e3136382e3130302e343000ffff000c776562746964652d746573740001bb01000ca00b000c776562746964652d7465737400a00e005a4d6f7a696c6c612f352e30202857696e646f77733b20553b2057696e646f7773204e5420352e313b20656e2d55533b2072763a312e382e312e3129204765636b6f2f32303036313230342046697265666f782f322e302e302e3100a0010063746578742f786d6c2c6170706c69636174696f6e2f786d6c2c6170706c69636174696f6e2f7868746d6c2b786d6c2c746578742f68746d6c3b713d302e392c746578742f706c61696e3b713d302e382c696d6167652f706e672c2a2f2a3b713d302e3500a004000e656e2d75732c656e3b713d302e3500a003000c677a69702c6465666c61746500a002001e49534f2d383835392d312c7574662d383b713d302e372c2a3b713d302e3700000a4b6565702d416c69766500000333303000a006000a6b6565702d616c69766500a00d001a68747470733a2f2f776562746964652d746573742f746573742f00a00900174a53455353494f4e49443d69326c6e307539773573387300000d43616368652d436f6e74726f6c0000096d61782d6167653d3000000c4d61782d466f7277617264730000023130000800124448452d5253412d4145533235362d5348410009004032413037364245323330433238393130383941414132303631344139384441443131314230323132343030374130363642454531363742303941464337383942000b000332353600ff";
        byte[] src = TypeUtil.fromHexString(packet);
        
        ByteArrayBuffer buffer= new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
        SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        
        EndPoint endp = new ByteArrayEndPoint(src,Ajp13Packet.MAX_PACKET_SIZE);
        
        Ajp13Parser parser = new Ajp13Parser(buffers,endp);
        parser.setEventHandler(new EH());
        parser.setGenerator(new Ajp13Generator(buffers,endp));
        
        parser.parseAvailable();
        
        assertTrue(true);
    }

    public void testSSLPacketFragment() throws Exception
    {
        String packet = "1234025002020008485454502f312e3100000f2f746573742f64756d702f696e666f00000e3139322e3136382e3130302e343000ffff000c776562746964652d746573740001bb01000ca00b000c776562746964652d7465737400a00e005a4d6f7a696c6c612f352e30202857696e646f77733b20553b2057696e646f7773204e5420352e313b20656e2d55533b2072763a312e382e312e3129204765636b6f2f32303036313230342046697265666f782f322e302e302e3100a0010063746578742f786d6c2c6170706c69636174696f6e2f786d6c2c6170706c69636174696f6e2f7868746d6c2b786d6c2c746578742f68746d6c3b713d302e392c746578742f706c61696e3b713d302e382c696d6167652f706e672c2a2f2a3b713d302e3500a004000e656e2d75732c656e3b713d302e3500a003000c677a69702c6465666c61746500a002001e49534f2d383835392d312c7574662d383b713d302e372c2a3b713d302e3700000a4b6565702d416c69766500000333303000a006000a6b6565702d616c69766500a00d001a68747470733a2f2f776562746964652d746573742f746573742f00a00900174a53455353494f4e49443d69326c6e307539773573387300000d43616368652d436f6e74726f6c0000096d61782d6167653d3000000c4d61782d466f7277617264730000023130000800124448452d5253412d4145533235362d5348410009004032413037364245323330433238393130383941414132303631344139384441443131314230323132343030374130363642454531363742303941464337383942000b0100ff";
        byte[] src = TypeUtil.fromHexString(packet);
        
        for (int f=1;f<src.length;f++)
        {
            byte[] frag0=new byte[src.length-f];
            byte[] frag1=new byte[f];
            
            System.arraycopy(src,0,frag0,0,src.length-f);
            System.arraycopy(src,src.length-f,frag1,0,f);
        
            ByteArrayBuffer buffer= new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
            SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        
            ByteArrayEndPoint endp = new ByteArrayEndPoint(frag0,Ajp13Packet.MAX_PACKET_SIZE);
            endp.setNonBlocking(true);
        
            Ajp13Parser parser = new Ajp13Parser(buffers,endp);
            parser.setEventHandler(new EH());
            parser.setGenerator(new Ajp13Generator(buffers,endp));
            parser.parseNext();
            
            endp.setIn(new ByteArrayBuffer(frag1));
            parser.parseAvailable();
        }
        
        assertTrue(true);
    }
    
    
    
    public void testPacketWithBody() throws Exception
    {
        String packet=getTestHeader();
        byte[] src=TypeUtil.fromHexString(packet);
        ByteArrayBuffer buffer=new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
        SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        ByteArrayEndPoint endp=new ByteArrayEndPoint(src,Ajp13Packet.MAX_PACKET_SIZE);
        endp.setNonBlocking(true);
        
        final int count[]={0};
        Ajp13Generator gen = new Ajp13Generator(buffers,endp)
        {
            public void getBodyChunk() throws IOException
            {
                count[0]++;
                super.getBodyChunk();
            }
        };
        Ajp13Parser parser = new Ajp13Parser(buffers,endp);
        parser.setEventHandler(new EH());
        parser.setGenerator(gen);
        
        parser.parseNext();
        assertEquals(1,parser.getState());
        assertEquals(0,count[0]);
        
        endp.setIn(new ByteArrayBuffer(TypeUtil.fromHexString(getTestShortBody())));

        parser.parseNext();
        assertEquals(1,parser.getState());
        assertEquals(1,count[0]);
        
        endp.setIn(new ByteArrayBuffer(TypeUtil.fromHexString(getTestTinyBody())));

        parser.parseNext();
        parser.parseNext();
        assertEquals(0,parser.getState());
        assertEquals(1,count[0]);
        
        assertTrue(true);
    }


    
    public void testPacketWithChunkedBody() throws Exception
    {
        String packet="123400ff02040008485454502f312e3100000f2f746573742f64756d702f696e666f0000093132372e302e302e3100ffff00096c6f63616c686f7374000050000007a00e000d4a6176612f312e352e305f313100a00b00096c6f63616c686f737400a0010034746578742f68746d6c2c20696d6167652f6769662c20696d6167652f6a7065672c202a3b20713d2e322c202a2f2a3b20713d2e3200a006000a6b6565702d616c69766500a00700216170706c69636174696f6e2f782d7777772d666f726d2d75726c656e636f6465640000115472616e736665722d456e636f64696e670000076368756e6b656400000c4d61782d466f727761726473000002313000ff";
        byte[] src=TypeUtil.fromHexString(packet);
        ByteArrayBuffer buffer=new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
        SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        ByteArrayEndPoint endp=new ByteArrayEndPoint(src,Ajp13Packet.MAX_PACKET_SIZE);
        endp.setNonBlocking(true);
        
        final int count[]={0};
        Ajp13Generator gen = new Ajp13Generator(buffers,endp)
        {
            public void getBodyChunk() throws IOException
            {
                count[0]++;
                super.getBodyChunk();
            }
        };
        Ajp13Parser parser = new Ajp13Parser(buffers,endp);
        parser.setEventHandler(new EH());
        parser.setGenerator(gen);
        
        parser.parseNext();
        assertEquals(1,parser.getState());
        assertEquals(1,count[0]);
        
        endp.setIn(new ByteArrayBuffer(TypeUtil.fromHexString("1234007e007c7468656e616d653d746865253230717569636b25323062726f776e253230666f782532306a756d70732532306f766572253230746f2532307468652532306c617a79253230646f67253230544845253230515549434b25323042524f574e253230464f582532304a554d50532532304f564552253230544f25323054")));

        while (parser.parseNext()>0);
        assertEquals(1,parser.getState());
        assertEquals(2,count[0]);
        
        endp.setIn(new ByteArrayBuffer(TypeUtil.fromHexString("12340042004048452532304c415a59253230444f472532302676616c75656f66323d6162636465666768696a6b6c6d6e6f707172737475767778797a31323334353637383930")));

        while (parser.parseNext()>0);
        assertEquals(1,parser.getState());
        assertEquals(3,count[0]);
        
        endp.setIn(new ByteArrayBuffer(TypeUtil.fromHexString("123400020000")));

        while (parser.getState()!=0 && parser.parseNext()>0);
        assertEquals(0,parser.getState());
        assertEquals(3,count[0]);
        
        assertTrue(true);
    }

    

    
    public void testPacketFragment() throws Exception
    {
        String packet = "123401070202000f77696474683d20485454502f312e300000122f636f6e74726f6c2f70726f647563742f2200000e3230382e32372e3230332e31323800ffff000c7777772e756c74612e636f6d000050000005a006000a6b6565702d616c69766500a00b000c7777772e756c74612e636f6d00a00e002b4d6f7a696c6c612f342e302028636f6d70617469626c653b20426f726465724d616e6167657220332e302900a0010043696d6167652f6769662c20696d6167652f782d786269746d61702c20696d6167652f6a7065672c20696d6167652f706a7065672c20696d6167652f706d672c202a2f2a00a008000130000600067570726f64310008000a4145533235362d53484100ff";
        byte[] src = TypeUtil.fromHexString(packet);
        
        for (int f=1;f<src.length;f++)
        {
            byte[] frag0=new byte[src.length-f];
            byte[] frag1=new byte[f];
            
            System.arraycopy(src,0,frag0,0,src.length-f);
            System.arraycopy(src,src.length-f,frag1,0,f);
        
            ByteArrayBuffer buffer= new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
            SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        
            ByteArrayEndPoint endp = new ByteArrayEndPoint(frag0,Ajp13Packet.MAX_PACKET_SIZE);
            endp.setNonBlocking(true);
        
            Ajp13Parser parser = new Ajp13Parser(buffers,endp);
            parser.setEventHandler(new EH());
            parser.setGenerator(new Ajp13Generator(buffers,endp));
            parser.parseNext();
            
            endp.setIn(new ByteArrayBuffer(frag1));
            parser.parseAvailable();
        }
        
        assertTrue(true);
    }
    
    
    
    public void testPacketFragmentWithBody() throws Exception
    {
        String packet = getTestHeader()+getTestBody();
        byte[] src = TypeUtil.fromHexString(packet);
        
        for (int f=1;f<src.length;f++)
        {
            byte[] frag0=new byte[src.length-f];
            byte[] frag1=new byte[f];
            
            System.arraycopy(src,0,frag0,0,src.length-f);
            System.arraycopy(src,src.length-f,frag1,0,f);
        
            ByteArrayBuffer buffer= new ByteArrayBuffer(Ajp13Packet.MAX_PACKET_SIZE);
            SimpleBuffers buffers=new SimpleBuffers(buffer,null);
        
            ByteArrayEndPoint endp = new ByteArrayEndPoint(frag0,Ajp13Packet.MAX_PACKET_SIZE);
            endp.setNonBlocking(true);
            
            Ajp13Parser parser = new Ajp13Parser(buffers,endp);
            parser.setEventHandler(new EH());
            parser.setGenerator(new Ajp13Generator(buffers,endp));
            parser.parseNext();
            
            endp.setIn(new ByteArrayBuffer(frag1));
            parser.parseAvailable();
        }
        
        assertTrue(true);
    }
    
    
    private String getTestHeader()
    {
        StringBuffer header = new StringBuffer("");
        
        
        header.append("1234026902040008485454502f31");
        header.append("2e310000162f61646d696e2f496d6167");
        header.append("6555706c6f61642e68746d00000a3130");
        header.append("2e34382e31302e3100ffff000a31302e");
        header.append("34382e31302e3200005000000da00b00");
        header.append("0a31302e34382e31302e3200a00e005a");
        header.append("4d6f7a696c6c612f352e30202857696e");
        header.append("646f77733b20553b2057696e646f7773");
        header.append("204e5420352e313b20656e2d55533b20");
        header.append("72763a312e382e312e3129204765636b");
        header.append("6f2f3230303631323034204669726566");
        header.append("6f782f322e302e302e3100a001006374");
        header.append("6578742f786d6c2c6170706c69636174");
        header.append("696f6e2f786d6c2c6170706c69636174");
        header.append("696f6e2f7868746d6c2b786d6c2c7465");
        header.append("78742f68746d6c3b713d302e392c7465");
        header.append("78742f706c61696e3b713d302e382c69");
        header.append("6d6167652f706e672c2a2f2a3b713d30");
        header.append("2e3500a004000e656e2d75732c656e3b");
        header.append("713d302e3500a003000c677a69702c64");
        header.append("65666c61746500a002001e49534f2d38");
        header.append("3835392d312c7574662d383b713d302e");
        header.append("372c2a3b713d302e3700000a4b656570");
        header.append("2d416c69766500000333303000a00600");
        header.append("0a6b6565702d616c69766500a00d003f");
        header.append("687474703a2f2f31302e34382e31302e");
        header.append("322f61646d696e2f496d61676555706c");
        header.append("6f61642e68746d3f6964303d4974656d");
        header.append("266964313d32266964323d696d673200");
        header.append("a00900174a53455353494f4e49443d75");
        header.append("383977733070696168746d00a0070046");
        header.append("6d756c7469706172742f666f726d2d64");
        header.append("6174613b20626f756e646172793d2d2d");
        header.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        header.append("2d2d2d2d2d2d2d2d2d39343338333235");
        header.append("34323630383700a00800033735390000");
        header.append("0c4d61782d466f727761726473000002");
        header.append("3130000500176964303d4974656d2669");
        header.append("64313d32266964323d696d673200ff");

        
        return header.toString();
        
    }

    private String getTestBody()
    {
        StringBuffer body = new StringBuffer("");
        
        
        
        body.append("123402f902f72d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d3934333833323534323630");
        body.append("38370d0a436f6e74656e742d44697370");
        body.append("6f736974696f6e3a20666f726d2d6461");
        body.append("74613b206e616d653d227265636f7264");
        body.append("4964220d0a0d0a320d0a2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d393433383332353432");
        body.append("363038370d0a436f6e74656e742d4469");
        body.append("73706f736974696f6e3a20666f726d2d");
        body.append("646174613b206e616d653d226e616d65");
        body.append("220d0a0d0a4974656d0d0a2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d3934333833323534");
        body.append("32363038370d0a436f6e74656e742d44");
        body.append("6973706f736974696f6e3a20666f726d");
        body.append("2d646174613b206e616d653d22746e49");
        body.append("6d674964220d0a0d0a696d67320d0a2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d39343338");
        body.append("3332353432363038370d0a436f6e7465");
        body.append("6e742d446973706f736974696f6e3a20");
        body.append("666f726d2d646174613b206e616d653d");
        body.append("227468756d624e61696c496d61676546");
        body.append("696c65223b2066696c656e616d653d22");
        body.append("6161612e747874220d0a436f6e74656e");
        body.append("742d547970653a20746578742f706c61");
        body.append("696e0d0a0d0a61616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("0d0a2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d39");
        body.append("3433383332353432363038370d0a436f");
        body.append("6e74656e742d446973706f736974696f");
        body.append("6e3a20666f726d2d646174613b206e61");
        body.append("6d653d226c61726765496d6167654669");
        body.append("6c65223b2066696c656e616d653d2261");
        body.append("61612e747874220d0a436f6e74656e74");
        body.append("2d547970653a20746578742f706c6169");
        body.append("6e0d0a0d0a6161616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("6161616161616161616161616161610d");
        body.append("0a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d3934");
        body.append("33383332353432363038372d2d0d0a");
        
       
        return  body.toString();
        
    }
    
    
    private String getTestShortBody()
    {
        StringBuffer body = new StringBuffer("");
        
        body.append("123402f702f52d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d3934333833323534323630");
        body.append("38370d0a436f6e74656e742d44697370");
        body.append("6f736974696f6e3a20666f726d2d6461");
        body.append("74613b206e616d653d227265636f7264");
        body.append("4964220d0a0d0a320d0a2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d393433383332353432");
        body.append("363038370d0a436f6e74656e742d4469");
        body.append("73706f736974696f6e3a20666f726d2d");
        body.append("646174613b206e616d653d226e616d65");
        body.append("220d0a0d0a4974656d0d0a2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d3934333833323534");
        body.append("32363038370d0a436f6e74656e742d44");
        body.append("6973706f736974696f6e3a20666f726d");
        body.append("2d646174613b206e616d653d22746e49");
        body.append("6d674964220d0a0d0a696d67320d0a2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d39343338");
        body.append("3332353432363038370d0a436f6e7465");
        body.append("6e742d446973706f736974696f6e3a20");
        body.append("666f726d2d646174613b206e616d653d");
        body.append("227468756d624e61696c496d61676546");
        body.append("696c65223b2066696c656e616d653d22");
        body.append("6161612e747874220d0a436f6e74656e");
        body.append("742d547970653a20746578742f706c61");
        body.append("696e0d0a0d0a61616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("0d0a2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d39");
        body.append("3433383332353432363038370d0a436f");
        body.append("6e74656e742d446973706f736974696f");
        body.append("6e3a20666f726d2d646174613b206e61");
        body.append("6d653d226c61726765496d6167654669");
        body.append("6c65223b2066696c656e616d653d2261");
        body.append("61612e747874220d0a436f6e74656e74");
        body.append("2d547970653a20746578742f706c6169");
        body.append("6e0d0a0d0a6161616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("61616161616161616161616161616161");
        body.append("6161616161616161616161616161610d");
        body.append("0a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d");
        body.append("2d2d2d2d2d2d2d2d2d2d2d2d2d2d3934");
        body.append("33383332353432363038372d2d");
        
       
        return  body.toString();
        
    }
    private String getTestTinyBody()
    {
        StringBuffer body = new StringBuffer("");
        
        body.append("123400042d2d0d0a");
        
        return  body.toString();
        
    }
    
    
    private static class EH implements Ajp13Parser.EventHandler
    {

        public void content(Buffer ref) throws IOException
        {
            // System.err.println(ref);
        }

        public void headerComplete() throws IOException
        {
            // System.err.println("--");
        }

        public void messageComplete(long contextLength) throws IOException
        {
            // System.err.println("==");
        }

        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
            // System.err.println(name+": "+value);
        }

        public void parsedMethod(Buffer method) throws IOException
        {
            // System.err.println(method);
        }

        public void parsedProtocol(Buffer protocol) throws IOException
        {
            // System.err.println(protocol);
            
        }

        public void parsedQueryString(Buffer value) throws IOException
        {
            // System.err.println("?"+value);
        }

        public void parsedRemoteAddr(Buffer addr) throws IOException
        {
            // System.err.println("addr="+addr);
            
        }

        public void parsedRemoteHost(Buffer host) throws IOException
        {
            // System.err.println("host="+host);
            
        }

        public void parsedRequestAttribute(String key, Buffer value) throws IOException
        {
            // System.err.println(key+":: "+value);
        }

        public void parsedServerName(Buffer name) throws IOException
        {
            // System.err.println("Server:: "+name); 
        }

        public void parsedServerPort(int port) throws IOException
        {
            // System.err.println("Port:: "+port);
        }

        public void parsedSslSecure(boolean secure) throws IOException
        {
            // System.err.println("Secure:: "+secure);     
        }

        public void parsedUri(Buffer uri) throws IOException
        {
            // System.err.println(uri);
        }

        public void startForwardRequest() throws IOException
        {
            // System.err.println("..");
        }

        public void parsedAuthorizationType(Buffer authType) throws IOException
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        public void parsedRemoteUser(Buffer remoteUser) throws IOException
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        public void parsedServletPath(Buffer servletPath) throws IOException
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        public void parsedContextPath(Buffer context) throws IOException
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        public void parsedSslCert(Buffer sslCert) throws IOException
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        public void parsedSslCipher(Buffer sslCipher) throws IOException
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        public void parsedSslSession(Buffer sslSession) throws IOException
        {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        public void parsedSslKeySize(int keySize) throws IOException
        {
            // System.err.println(key+":: "+value);
        }
        
        public void parsedRequestAttribute(String key, int value) throws IOException
        {
            // System.err.println(key+":: "+value);
        }
        
    }


}
