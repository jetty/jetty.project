package org.eclipse.jetty.compression.gzip;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GzipDecoderSourceTest extends AbstractGzipTest
{
    @ParameterizedTest
    @MethodSource("textResources")
    public void testDecodeText(String textResourceName) throws Exception
    {
        startGzip();
        String compressedName = String.format("%s.%s", textResourceName, gzip.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);

        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = gzip.newDecoderSource(fileSource);

        String result = Content.Source.asString(decoderSource);
        String expected = Files.readString(uncompressed);
        assertEquals(expected, result);
    }
}
