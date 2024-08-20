package org.eclipse.jetty.compression.gzip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ConfiguredGzipOutputStreamTest extends AbstractGzipTest
{
    @Test
    public void testGzipOutputStreamParts() throws IOException
    {
        Deflater deflater = new Deflater();
        deflater.setLevel(9);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ConfiguredGzipOutputStream gzipOutputStream = new ConfiguredGzipOutputStream(baos))
        {
            gzipOutputStream.setLevel(Deflater.BEST_COMPRESSION);
            List<String> entries = List.of("Hello", " World", "!");
            for (String entry : entries)
            {
                gzipOutputStream.write(entry.getBytes(UTF_8));
            }
            gzipOutputStream.close();

            byte[] compressed = baos.toByteArray();
            String actual = new String(decompress(compressed), UTF_8);
            assertThat(actual, is("Hello World!"));
        }
    }
}
