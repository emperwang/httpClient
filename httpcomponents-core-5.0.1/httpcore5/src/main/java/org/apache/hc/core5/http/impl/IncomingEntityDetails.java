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

package org.apache.hc.core5.http.impl;

import java.util.Collections;
import java.util.Set;

import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.MessageHeaders;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.util.Args;

/**
 * HTTP message entity details.
 *
 * @since 5.0
 */
@Internal
public class IncomingEntityDetails implements EntityDetails {

    private final MessageHeaders message;
    private final long contentLength;

    public IncomingEntityDetails(final MessageHeaders message, final long contentLength) {
        this.message = Args.notNull(message, "Message");
        this.contentLength = contentLength;
    }

    public IncomingEntityDetails(final MessageHeaders message) {
        this(message, -1);
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    public String getContentType() {
        final Header h = message.getFirstHeader(HttpHeaders.CONTENT_TYPE);
        return h != null ? h.getValue() : null;
    }

    @Override
    public String getContentEncoding() {
        final Header h = message.getFirstHeader(HttpHeaders.CONTENT_ENCODING);
        return h != null ? h.getValue() : null;
    }

    @Override
    public boolean isChunked() {
        return contentLength < 0;
    }

    @Override
    public Set<String> getTrailerNames() {
        final Header h = message.getFirstHeader(HttpHeaders.TRAILER);
        if (h == null) {
            return Collections.emptySet();
        }
        return MessageSupport.parseTokens(h);
    }

}
