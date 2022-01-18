package org.eclipse.jetty.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.eclipse.jetty.util.BufferUtil.toBuffer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class GunzipContentProviderTest
{
    public static Stream<Arguments> providers() throws Exception
    {
        List<Arguments> data = new ArrayList<>();
        data.add(Arguments.of(new StaticContentProvider(Arrays.asList(new StaticContent(toBuffer(TEXT)), Content.EOF))));
        data.add(Arguments.of(new GunzipContentProvider(new StaticContentProvider(Arrays.asList(new StaticContent(toGzippedBuffer(TEXT)), Content.EOF)))));
        data.add(Arguments.of(new DelayingContentProvider(new StaticContentProvider(Arrays.asList(new StaticContent(toBuffer(TEXT)), Content.EOF)))));
        data.add(Arguments.of(new GunzipContentProvider(new DelayingContentProvider(new StaticContentProvider(Arrays.asList(new StaticContent(toGzippedBuffer(TEXT)), Content.EOF))))));
        return data.stream();
    }

    @ParameterizedTest
    @MethodSource("providers")
    public void testProviders(Content.Provider contentProvider)
    {
        StringBuilder result = new StringBuilder();
        while (true)
        {
            Content content = contentProvider.readContent();
            if (content != null)
            {
                if (content.isSpecial())
                    break;
                result.append(consumeToString(content.getByteBuffer()));
            }
        }
        assertThat(result.toString(), is(TEXT));

        Content contentEnd = contentProvider.readContent();
        assertThat(contentEnd.isLast(), is(true));
    }

    static class StaticContentProvider implements Content.Provider
    {
        private final List<Content> contents;
        private int index;

        public StaticContentProvider(List<Content> contents)
        {
            this.contents = contents;
        }

        @Override
        public Content readContent()
        {
            Content content = contents.get(index);
            if (content == null || !content.isSpecial())
                index++;
            return content;
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            onContentAvailable.run();
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName();
        }
    }

    static class DelayingContentProvider implements Content.Provider
    {
        private final Content.Provider source;
        private Content currentContent;
        private boolean nullCase;

        public DelayingContentProvider(Content.Provider source)
        {
            this.source = source;
        }

        @Override
        public Content readContent()
        {
            // always return special content as-is
            if (currentContent != null && currentContent.isSpecial())
                return currentContent;

            // return null every other call
            if (nullCase)
            {
                nullCase = false;
                return null;
            }
            nullCase = true;


            while (true)
            {
                if (currentContent == null)
                    currentContent = source.readContent();
                if (currentContent == null)
                    return null;
                if (currentContent.isSpecial())
                    return currentContent;
                if (currentContent.isEmpty())
                {
                    currentContent.release();
                    currentContent = null;
                }
                else
                {
                    break;
                }
            }

            byte b = currentContent.getByteBuffer().get();
            return new StaticContent(ByteBuffer.wrap(new byte[]{b}));
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            onContentAvailable.run();
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + " delaying from " + source;
        }
    }

    static class StaticContent extends Content.Abstract
    {
        private final ByteBuffer buffer;

        public StaticContent(ByteBuffer buffer)
        {
            super(false, false);
            this.buffer = buffer;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return buffer;
        }
    }

    private static ByteBuffer toGzippedBuffer(String text) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos))
        {
            IO.copy(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), gzos);
        }
        return ByteBuffer.wrap(baos.toByteArray());
    }

    static String consumeToString(ByteBuffer buffer)
    {
        byte[] to = new byte[buffer.remaining()];
        buffer.get(to);
        return new String(to, 0, to.length, StandardCharsets.UTF_8);
    }

    private static final String TEXT =
        "What is Lorem Ipsum?\n" +
        "\n" +
        "Lorem Ipsum is simply dummy text of the printing and typesetting industry. Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to make a type specimen book. It has survived not only five centuries, but also the leap into electronic typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker including versions of Lorem Ipsum.\n" +

        "Why do we use it?\n" +
        "\n" +
        "It is a long established fact that a reader will be distracted by the readable content of a page when looking at its layout. The point of using Lorem Ipsum is that it has a more-or-less normal distribution of letters, as opposed to using 'Content here, content here', making it look like readable English. Many desktop publishing packages and web page editors now use Lorem Ipsum as their default model text, and a search for 'lorem ipsum' will uncover many web sites still in their infancy. Various versions have evolved over the years, sometimes by accident, sometimes on purpose (injected humour and the like).\n" +

        "Where does it come from?\n" +
        "\n" +
        "Contrary to popular belief, Lorem Ipsum is not simply random text. It has roots in a piece of classical Latin literature from 45 BC, making it over 2000 years old. Richard McClintock, a Latin professor at Hampden-Sydney College in Virginia, looked up one of the more obscure Latin words, consectetur, from a Lorem Ipsum passage, and going through the cites of the word in classical literature, discovered the undoubtable source. Lorem Ipsum comes from sections 1.10.32 and 1.10.33 of \"de Finibus Bonorum et Malorum\" (The Extremes of Good and Evil) by Cicero, written in 45 BC. This book is a treatise on the theory of ethics, very popular during the Renaissance. The first line of Lorem Ipsum, \"Lorem ipsum dolor sit amet..\", comes from a line in section 1.10.32.\n" +
        "\n" +
        "The standard chunk of Lorem Ipsum used since the 1500s is reproduced below for those interested. Sections 1.10.32 and 1.10.33 from \"de Finibus Bonorum et Malorum\" by Cicero are also reproduced in their exact original form, accompanied by English versions from the 1914 translation by H. Rackham.\n" +

        "Where can I get some?\n" +
        "\n" +
        "There are many variations of passages of Lorem Ipsum available, but the majority have suffered alteration in some form, by injected humour, or randomised words which don't look even slightly believable. If you are going to use a passage of Lorem Ipsum, you need to be sure there isn't anything embarrassing hidden in the middle of text. All the Lorem Ipsum generators on the Internet tend to repeat predefined chunks as necessary, making this the first true generator on the Internet. It uses a dictionary of over 200 Latin words, combined with a handful of model sentence structures, to generate Lorem Ipsum which looks reasonable. The generated Lorem Ipsum is therefore always free from repetition, injected humour, or non-characteristic words etc.";
}
