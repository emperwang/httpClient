/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.entity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.http.HttpEntity;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link EntityTemplate}.
 */
public class TestEntityTemplate {

    @Test
    public void testBasics() throws Exception {

        final HttpEntity httpentity = new EntityTemplate(new ContentProducer() {

            @Override
            public void writeTo(final OutputStream outStream) throws IOException {
                outStream.write('a');
            }

        });

        Assert.assertEquals(-1, httpentity.getContentLength());
        Assert.assertTrue(httpentity.isRepeatable());
        Assert.assertFalse(httpentity.isStreaming());
    }

    @Test
    public void testIllegalConstructor() throws Exception {
        try {
            new EntityTemplate(null);
            Assert.fail("IllegalArgumentException should have been thrown");
        } catch (final IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testWriteTo() throws Exception {
        final HttpEntity httpentity = new EntityTemplate(new ContentProducer() {

            @Override
            public void writeTo(final OutputStream outStream) throws IOException {
                outStream.write('a');
            }

        });

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        httpentity.writeTo(out);
        final byte[] bytes2 = out.toByteArray();
        Assert.assertNotNull(bytes2);
        Assert.assertEquals(1, bytes2.length);

        try {
            httpentity.writeTo(null);
            Assert.fail("IllegalArgumentException should have been thrown");
        } catch (final IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testgetContent() throws Exception {
        final HttpEntity httpentity = new EntityTemplate(new ContentProducer() {

            @Override
            public void writeTo(final OutputStream outStream) throws IOException {
                outStream.write('a');
            }

        });
        final InputStream inStream = httpentity.getContent();
        Assert.assertNotNull(inStream);
        final String s = EntityUtils.toString(httpentity);
        Assert.assertEquals("a", s);
    }

}
