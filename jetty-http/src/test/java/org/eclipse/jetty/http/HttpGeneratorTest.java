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

package org.eclipse.jetty.http;


import java.io.IOException;

import junit.framework.TestCase;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.io.View;

/**
 * 
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class HttpGeneratorTest extends TestCase
{
    public final static String CONTENT="The quick brown fox jumped over the lazy dog.\nNow is the time for all good men to come to the aid of the party\nThe moon is blue to a fish in love.\n";
    public final static String[] connect={null,"keep-alive","close","TE, close"};

    public HttpGeneratorTest(String arg0)
    {
        super(arg0);
    }
    
    public void testHTTP()
    	throws Exception
    {
        Buffer bb=new ByteArrayBuffer(8096);
        Buffer sb=new ByteArrayBuffer(1500);
        HttpFields fields = new HttpFields();
        ByteArrayEndPoint endp = new ByteArrayEndPoint(new byte[0],4096);
        HttpGenerator hb = new HttpGenerator(new SimpleBuffers(sb,bb),endp);
        Handler handler = new Handler();
        HttpParser parser=null;
        
        // For HTTP version
        for (int v=9;v<=11;v++)
        {
            // For each test result
            for (int r=0;r<tr.length;r++)
            {
                // chunks = 1 to 3
                for (int chunks=1;chunks<=6;chunks++)
                {
                    // For none, keep-alive, close
                    for (int c=0;c<(v==11?connect.length:(connect.length-1));c++)
                    {
                        
                        String t="v="+v+",r="+r+",chunks="+chunks+",connect="+connect[c]+",tr="+tr[r];
                        // System.err.println(t);
                        
                        hb.reset(true);
                        endp.reset();
                        fields.clear();
                        
                        tr[r].build(v,hb,"OK\r\nTest",connect[c],null,chunks, fields);
                        String response=endp.getOut().toString();
                        // System.out.println("RESPONSE: "+t+"\n"+response+(hb.isPersistent()?"...\n":"---\n"));
                        
                        if (v==9)
                        {
                            assertFalse(t,hb.isPersistent());
                            if (tr[r].body!=null)
                                assertEquals(t,tr[r].body, response);
                            continue;
                        }
                        
                        parser=new HttpParser(new ByteArrayBuffer(response.getBytes()), handler);
                        try
                        {
                            parser.parse();
                        }
                        catch(IOException e)
                        {
                            if (tr[r].body!=null)
                                throw e;
                            continue;
                        }
                        
                        if (tr[r].body!=null)
                            assertEquals(t,tr[r].body, this.content);
                        if (v==10)
                            assertTrue(t,hb.isPersistent() || tr[r].values[1]==null || c==2 || c==0);
                        else
                            assertTrue(t,hb.isPersistent() ||  c==2 || c==3);
                        
                        if (v>9)
                            assertEquals("OK  Test",f2);
                        
                        assertTrue(t,tr[r].values[1]==null || content.length()==Integer.parseInt(tr[r].values[1]));
                    }
                }
            }
        }
    }

    

    static final String[] headers= { "Content-Type","Content-Length","Connection","Transfer-Encoding","Other"};
    class TR
    {
        int code;
        String[] values=new String[headers.length];
        String body;
        
        TR(int code,String ct, String cl ,String content)
        {
            this.code=code;
            values[0]=ct;
            values[1]=cl;
            values[4]="value";
            this.body=content;
        }
        
        void build(int version,HttpGenerator hb,String reason, String connection, String te, int chunks, HttpFields fields)
        	throws Exception
        {
            values[2]=connection;
            values[3]=te;
            hb.setVersion(version);
            hb.setResponse(code,reason);
            
            for (int i=0;i<headers.length;i++)
            {
                if (values[i]==null)	
                    continue;
                fields.put(new ByteArrayBuffer(headers[i]),new ByteArrayBuffer(values[i]));
            }
                        
            if (body!=null)
            {
                int inc=1+body.length()/chunks;
                Buffer buf=new ByteArrayBuffer(body);
                View view = new View(buf);
                for (int i=1;i<chunks;i++)
                {
                    view.setPutIndex(i*inc);
                    view.setGetIndex((i-1)*inc);
                    hb.addContent(view,Generator.MORE);
                    if (hb.isBufferFull() && hb.isState(AbstractGenerator.STATE_HEADER))
                        hb.completeHeader(fields, Generator.MORE);
                    if (i%2==0)
                    {
                        if (hb.isState(AbstractGenerator.STATE_HEADER))
                            hb.completeHeader(fields, Generator.MORE);
                        hb.flushBuffer();
                    }
                }
                view.setPutIndex(buf.putIndex());
                view.setGetIndex((chunks-1)*inc);
                hb.addContent(view,Generator.LAST);
                if(hb.isState(AbstractGenerator.STATE_HEADER))
                    hb.completeHeader(fields, Generator.LAST);
            }
            else
            {
                hb.completeHeader(fields, Generator.LAST);
            }
            hb.complete();
        }
        
        @Override
        public String toString()
        {
            return "["+code+","+values[0]+","+values[1]+","+(body==null?"none":"_content")+"]";
        }
    }
    
    private TR[] tr =
    {
      /* 0 */  new TR(200,null,null,null),
      /* 1 */  new TR(200,null,null,CONTENT),
      /* 2 */  new TR(200,null,""+CONTENT.length(),null),
      /* 3 */  new TR(200,null,""+CONTENT.length(),CONTENT),
      /* 4 */  new TR(200,"text/html",null,null),
      /* 5 */  new TR(200,"text/html",null,CONTENT),
      /* 6 */  new TR(200,"text/html",""+CONTENT.length(),null),
      /* 7 */  new TR(200,"text/html",""+CONTENT.length(),CONTENT),
    };
    
    String content;
    String f0;
    String f1;
    String f2;
    String[] hdr;
    String[] val;
    int h;
    
    class Handler extends HttpParser.EventHandler
    {   
        int index=0;
        
        @Override
        public void content(Buffer ref)
        {
            if (index == 0)
                content= "";
            content= content.substring(0, index) + ref;
            index+=ref.length();
        }


        @Override
        public void startRequest(Buffer tok0, Buffer tok1, Buffer tok2)
        {
            h= -1;
            hdr= new String[9];
            val= new String[9];
            f0= tok0.toString();
            f1= tok1.toString();
            if (tok2!=null)
                f2= tok2.toString();
            else
                f2=null;
            index=0;
            // System.out.println(f0+" "+f1+" "+f2);
        }


        /* (non-Javadoc)
         * @see org.eclipse.jetty.EventHandler#startResponse(org.eclipse.io.Buffer, int, org.eclipse.io.Buffer)
         */
        @Override
        public void startResponse(Buffer version, int status, Buffer reason)
        {
            h= -1;
            hdr= new String[9];
            val= new String[9];
            f0= version.toString();
            f1= ""+status;
            if (reason!=null)
                f2= reason.toString();
            else
                f2=null;
            index=0;
        }

        @Override
        public void parsedHeader(Buffer name,Buffer value)
        {
            hdr[++h]= name.toString();
            val[h]= value.toString();
        }

        @Override
        public void headerComplete()
        {
            content= null;
        }

        @Override
        public void messageComplete(long contentLength)
        {
        }


    }

}
