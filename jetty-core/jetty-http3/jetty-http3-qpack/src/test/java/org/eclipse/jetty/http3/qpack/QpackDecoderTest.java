//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.qpack.internal.table.Entry;
import org.eclipse.jetty.http3.qpack.util.TestDecoderHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QpackDecoderTest
{
    private QpackDecoder _decoder;
    private TestDecoderHandler _decoderHandler;

    @BeforeEach
    public void before()
    {
        _decoderHandler = new TestDecoderHandler();
        _decoder = new QpackDecoder(_decoderHandler);
        _decoder.setBeginNanoTimeSupplier(NanoTime::now);
    }

    @Test
    public void testDynamicNameReference() throws Exception
    {
        _decoder.setMaxTableCapacity(2048);
        QpackDecoder.InstructionHandler instructionHandler = _decoder.getInstructionHandler();
        instructionHandler.onSetDynamicTableCapacity(2048);

        instructionHandler.onInsertWithLiteralName("name0", "value0");
        instructionHandler.onInsertWithLiteralName("name1", "value1");
        instructionHandler.onInsertWithLiteralName("name2", "value2");
        instructionHandler.onInsertWithLiteralName("name3", "value3");
        instructionHandler.onInsertNameWithReference(5, false, "static0");
        instructionHandler.onInsertWithLiteralName("name4", "value4");
        instructionHandler.onInsertNameWithReference(2, true, "dynamic0");
        instructionHandler.onDuplicate(6);

        // Indexes into the static table are absolute.
        Entry entry = _decoder.getQpackContext().getDynamicTable().get(4);
        assertThat(entry.getHttpField().getName(), equalTo("cookie"));
        assertThat(entry.getHttpField().getValue(), equalTo("static0"));

        // Named reference is relative to the most recently inserted entry.
        entry = _decoder.getQpackContext().getDynamicTable().get(6);
        assertThat(entry.getHttpField().getName(), equalTo("name3"));
        assertThat(entry.getHttpField().getValue(), equalTo("dynamic0"));

        // Duplicate reference is relative to the most recently inserted entry.
        entry = _decoder.getQpackContext().getDynamicTable().get(7);
        assertThat(entry.getHttpField().getName(), equalTo("name0"));
        assertThat(entry.getHttpField().getValue(), equalTo("value0"));
    }
    
    @Test
    public void testDecodeRequest() throws Exception
    {
        _decoder.setMaxTableCapacity(2048);
        QpackDecoder.InstructionHandler instructionHandler = _decoder.getInstructionHandler();
        instructionHandler.onSetDynamicTableCapacity(2048);

        instructionHandler.onInsertNameWithReference(0, false, "licensed.app");
        instructionHandler.onInsertWithLiteralName("sec-ch-ua", "\"Not A(Brand\";v=\"99\", \"Brave\";v=\"121\", \"Chromium\";v=\"121\"");
        instructionHandler.onInsertWithLiteralName("sec-ch-ua-mobile", "?0");
        instructionHandler.onInsertWithLiteralName("sec-ch-ua-platform", "Windows");
        instructionHandler.onInsertWithLiteralName("dnt", "1");
        instructionHandler.onInsertNameWithReference(95, false, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
        instructionHandler.onInsertNameWithReference(29, false, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        instructionHandler.onInsertWithLiteralName("sec-gpc", "1");
        instructionHandler.onInsertWithLiteralName("sec-fetch-site", "none");
        instructionHandler.onInsertWithLiteralName("sec-fetch-mode", "navigate");
        instructionHandler.onInsertWithLiteralName("sec-fetch-user", "?1");
        instructionHandler.onInsertWithLiteralName("sec-fetch-dest", "value=document");
        instructionHandler.onInsertNameWithReference(72, false, "en-US,en;q=0.9");
        instructionHandler.onInsertNameWithReference(8, false, "2024-02-17T02:19:47.4433882Z");
        instructionHandler.onInsertNameWithReference(1, false, "/login/GoogleLogin.js");
        instructionHandler.onInsertNameWithReference(90, false, "https://licensed.app");
        instructionHandler.onInsertNameWithReference(7, true, "same-origin");
        instructionHandler.onInsertNameWithReference(7, true, "cors");
        instructionHandler.onInsertNameWithReference(6, true, "script");
        instructionHandler.onInsertNameWithReference(13, false, "https://licensed.app/");

        assertTrue(_decoder.decode(0, fromHex("1500D193D78592848f918e90Dd8c83828180Df87"), _decoderHandler));
        MetaData metaData = _decoderHandler.getMetaData();

        // Check headers were correctly referenced from dynamic table.
        assertThat(metaData.getHttpFields().get("sec-fetch-site"), equalTo("same-origin"));
        assertThat(metaData.getHttpFields().get("sec-fetch-mode"), equalTo("cors"));
        assertThat(metaData.getHttpFields().get("sec-fetch-dest"), equalTo("script"));
    }
    
    private ByteBuffer fromHex(String hex)
    {
        return BufferUtil.toBuffer(StringUtil.fromHexString(hex));
    }
}
