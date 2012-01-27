package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.junit.Assert;
import org.junit.Test;

public class ParserTest
{
    @Test
    public void testSynStream() throws Exception
    {
        // Bytes taken with wireshark from a live chromium request
        String data = "" +
                "80020001010001c40000000100000000000038eadfa251b262e0666083a41706" +
                "7bb80b75302cd6ae4017cdcdb12eb435d0b3d4d1d2d702b32c18f850732c036f" +
                "68889bae850e44da94811f2d0b3308821ca80375a14e714a72065c0d2cd619f8" +
                "52f37443837552f3a076b041628ae1b6a357b01e307681093b37b508a85f0f22" +
                "615b0306a505b97a258949b69979401fe7e4582b64651625ea95e4a7a7e7a426" +
                "01134d763158334c535e625970666e414e6a3040a98945c919aac62eaa46c640" +
                "c1ccf4c492fc22ddb44c60c817e916972681ca2ba0d375338b8b4b53134b4a8a" +
                "32934a4b528b75d38bf24b0b548d9c81da92f37373f3f340a91fe493625d608e" +
                "cb02a67fa07001b0b4c92b812a863a099b17185840e516031f2871e780985616" +
                "0616060c6cb9c0f2323f8581d9dd358481ad1898c57253814a4b4a0a18984151" +
                "c9a8cfc085287f18da7df3ab3273721201d237d53350d0883034b456f0c9cc2b" +
                "ad50a8b0308b3733d154700446676a786a92776689bea9b1a99ea18286b74788" +
                "af8f8e424e6676aa827b6a7276bea6426852695e49a9bea1a19e8189827306b0" +
                "50cd2ccdd53734d633d033b730d233318408a6a2080527a6251665424c656087" +
                "2637060e582a04000000ffff";
        byte[] bytes = new byte[data.length() / 2];
        for (int i = 0; i < data.length(); i += 2)
        {
            String hex = data.substring(i, i + 2);
            bytes[i / 2] = (byte)Integer.parseInt(hex, 16);
        }

        final AtomicReference<ControlFrame> frameRef = new AtomicReference<>();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(new Parser.Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                frameRef.set(frame);
            }
        });
        parser.parse(ByteBuffer.wrap(bytes));

        ControlFrame frame = frameRef.get();
        Assert.assertNotNull(frame);
        Assert.assertEquals(ControlFrameType.SYN_STREAM, frame.getType());
        SynStreamFrame synStream = (SynStreamFrame)frame;
        Assert.assertEquals(2, synStream.getVersion());
        Assert.assertEquals(1, synStream.getStreamId());
        Assert.assertEquals(0, synStream.getAssociatedStreamId());
        Assert.assertEquals(0, synStream.getPriority());
        Assert.assertNotNull(synStream.getHeaders());
        Assert.assertFalse(synStream.getHeaders().isEmpty());

        // TODO: gather bytes for a second identical request to test that compression is working fine
    }
}
