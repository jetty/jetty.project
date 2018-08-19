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

package org.eclipse.jetty.client;

import org.ietf.jgss.*;

import java.io.InputStream;
import java.io.OutputStream;

public class MockGSSContext implements GSSContext
{

        @Override
        public byte[] initSecContext(byte[] inputBuf, int offset, int len) throws GSSException
        {
                return new byte[0];
        }

        @Override
        public int initSecContext(InputStream inStream, OutputStream outStream) throws GSSException
        {
                return 0;
        }

        @Override
        public byte[] acceptSecContext(byte[] inToken, int offset, int len) throws GSSException
        {
                return new byte[0];
        }

        @Override
        public void acceptSecContext(InputStream inStream, OutputStream outStream) throws GSSException
        {

        }

        @Override
        public boolean isEstablished()
        {
                return false;
        }

        @Override
        public void dispose() throws GSSException
        {

        }

        @Override
        public int getWrapSizeLimit(int qop, boolean confReq, int maxTokenSize) throws GSSException
        {
                return 0;
        }

        @Override
        public byte[] wrap(byte[] inBuf, int offset, int len, MessageProp msgProp) throws GSSException
        {
                return new byte[0];
        }

        @Override
        public void wrap(InputStream inStream, OutputStream outStream, MessageProp msgProp) throws GSSException
        {

        }

        @Override
        public byte[] unwrap(byte[] inBuf, int offset, int len, MessageProp msgProp) throws GSSException
        {
                return new byte[0];
        }

        @Override
        public void unwrap(InputStream inStream, OutputStream outStream, MessageProp msgProp) throws GSSException
        {

        }

        @Override
        public byte[] getMIC(byte[] inMsg, int offset, int len, MessageProp msgProp) throws GSSException
        {
                return new byte[0];
        }

        @Override
        public void getMIC(InputStream inStream, OutputStream outStream, MessageProp msgProp) throws GSSException
        {

        }

        @Override
        public void verifyMIC(byte[] inToken, int tokOffset, int tokLen, byte[] inMsg, int msgOffset, int msgLen, MessageProp msgProp) throws GSSException
        {

        }

        @Override
        public void verifyMIC(InputStream tokStream, InputStream msgStream, MessageProp msgProp) throws GSSException
        {

        }

        @Override
        public byte[] export() throws GSSException
        {
                return new byte[0];
        }

        @Override
        public void requestMutualAuth(boolean state) throws GSSException
        {

        }

        @Override
        public void requestReplayDet(boolean state) throws GSSException
        {

        }

        @Override
        public void requestSequenceDet(boolean state) throws GSSException
        {

        }

        @Override
        public void requestCredDeleg(boolean state) throws GSSException
        {

        }

        @Override
        public void requestAnonymity(boolean state) throws GSSException
        {

        }

        @Override
        public void requestConf(boolean state) throws GSSException
        {

        }

        @Override
        public void requestInteg(boolean state) throws GSSException
        {

        }

        @Override
        public void requestLifetime(int lifetime) throws GSSException
        {

        }

        @Override
        public void setChannelBinding(ChannelBinding cb) throws GSSException
        {

        }

        @Override
        public boolean getCredDelegState()
        {
                return false;
        }

        @Override
        public boolean getMutualAuthState()
        {
                return false;
        }

        @Override
        public boolean getReplayDetState()
        {
                return false;
        }

        @Override
        public boolean getSequenceDetState()
        {
                return false;
        }

        @Override
        public boolean getAnonymityState()
        {
                return false;
        }

        @Override
        public boolean isTransferable() throws GSSException
        {
                return false;
        }

        @Override
        public boolean isProtReady()
        {
                return false;
        }

        @Override
        public boolean getConfState()
        {
                return false;
        }

        @Override
        public boolean getIntegState()
        {
                return false;
        }

        @Override
        public int getLifetime()
        {
                return 0;
        }

        @Override
        public GSSName getSrcName() throws GSSException
        {
                return null;
        }

        @Override
        public GSSName getTargName() throws GSSException
        {
                return null;
        }

        @Override
        public Oid getMech() throws GSSException
        {
                return null;
        }

        @Override
        public GSSCredential getDelegCred() throws GSSException
        {
                return null;
        }

        @Override
        public boolean isInitiator() throws GSSException
        {
                return false;
        }
}
