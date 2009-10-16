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

package org.eclipse.jetty.util.log;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import junit.framework.TestCase;

public class LogTest extends TestCase
{
    PrintStream _orig= System.err;
    ByteArrayOutputStream _out = new ByteArrayOutputStream();
    PrintStream _pout = new PrintStream(_out);
    

    @Override
    public void setUp()
    {
        System.setErr(_pout);
    }
    
    @Override
    public void tearDown()
    {
        System.setErr(_orig);
    }
    
    private void logNotContains(String text)
    {
        _pout.flush();
        String err = _out.toString();
        _out.reset();
        
        if (err.indexOf(text)<0)
            return;
        
        _orig.println("FAIL '"+text+"' in '"+err+"'");
        
        assertTrue(false);
    }
    
    private void logContains(String text)
    {
        _pout.flush();
        String err = _out.toString();
        _out.reset();
        
        if (err.indexOf(text)>=0)
            return;
        
        _orig.println("FAIL '"+text+"' not in '"+err+"'");
        assertTrue(false);
    }
    
    public void testStdErrLogFormat()
    {
        StdErrLog log = new StdErrLog("test");

        log.info("testing:{},{}","test","format");
        logContains("INFO:test:testing:test,format");
        
        log.info("testing:{}","test","format");
        logContains("INFO:test:testing:test format");
        
        log.info("testing","test","format");
        logContains("INFO:test:testing test format");
       
        log.info("testing:{},{}","test",null);
        logContains("INFO:test:testing:test,null");
       
        log.info("testing {} {}",null,null);
        logContains("INFO:test:testing null null");
        
        log.info("testing:{}",null,null);
        logContains("INFO:test:testing:null");
        
        log.info("testing",null,null);
        logContains("INFO:test:testing");
    }

    public void testStdErrLogDebug()
    {
        StdErrLog log = new StdErrLog("xxx");
        
        log.setDebugEnabled(true);
        log.debug("testing {} {}","test","debug");
        logContains("DBUG:xxx:testing test debug");
        
        log.info("testing {} {}","test","info");
        logContains("INFO:xxx:testing test info");
        
        log.warn("testing {} {}","test","warn");
        logContains("WARN:xxx:testing test warn");
        
        log.setDebugEnabled(false);
        log.debug("YOU SHOULD NOT SEE THIS!",null,null);
        logNotContains("YOU SHOULD NOT SEE THIS!");
    }
    
    public void testStdErrLogName()
    {
        StdErrLog log = new StdErrLog("test");
            
        Logger next=log.getLogger("next");
        next.info("testing {} {}","next","info");
        logContains(":test.next:testing next info");
        
    }
    
    public void testStdErrThrowable()
    {
        Throwable th = new Throwable("Message");
        
        th.printStackTrace();
        _pout.flush();
        String ths = _out.toString();
        _out.reset();
        

        StdErrLog log = new StdErrLog("test");
        log.warn("ex",th);

        logContains(ths);
        
        th = new Throwable("Message with \033 escape");

        log.warn("ex",th);
        logNotContains("Message with \033 escape");
        log.info(th.toString());
        logNotContains("Message with \033 escape");
        
        log.warn("ex",th);
        logContains("Message with ? escape");
        log.info(th.toString());
        logContains("Message with ? escape");
        
    }
}
