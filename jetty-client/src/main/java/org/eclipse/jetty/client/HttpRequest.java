//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.PathContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Fields;

public class HttpRequest implements Request
{
    private static final AtomicLong ids = new AtomicLong();

    private final HttpFields headers = new HttpFields();
    private final Fields params = new Fields();
    private final Map<String, Object> attributes = new HashMap<>();
    private final List<RequestListener> requestListeners = new ArrayList<>();
    private final List<Response.ResponseListener> responseListeners = new ArrayList<>();
    private final HttpClient client;
    private final long conversation;
    private final String host;
    private final int port;
    private URI uri;
    private String scheme;
    private String path;
    private String query;
    private HttpMethod method;
    private HttpVersion version;
    private long idleTimeout;
    private long timeout;
    private ContentProvider content;
    private boolean followRedirects;
    private volatile Throwable aborted;

    public HttpRequest(HttpClient client, URI uri)
    {
        this(client, ids.incrementAndGet(), uri);
    }

    protected HttpRequest(HttpClient client, long conversation, URI uri)
    {
        this.client = client;
        this.conversation = conversation;
        scheme = uri.getScheme();
        host = client.normalizeHost(uri.getHost());
        port = client.normalizePort(scheme, uri.getPort());
        path = uri.getRawPath();
        query = uri.getRawQuery();
        extractParams(query);
        this.uri = buildURI(true);
        followRedirects(client.isFollowRedirects());
    }

    @Override
    public long getConversationID()
    {
        return conversation;
    }

    @Override
    public String getScheme()
    {
        return scheme;
    }

    @Override
    public Request scheme(String scheme)
    {
        this.scheme = scheme;
        this.uri = buildURI(true);
        return this;
    }

    @Override
    public String getHost()
    {
        return host;
    }

    @Override
    public int getPort()
    {
        return port;
    }

    @Override
    public HttpMethod getMethod()
    {
        return method;
    }

    @Override
    public Request method(HttpMethod method)
    {
        this.method = method;
        return this;
    }

    @Override
    public String getPath()
    {
        return path;
    }

    @Override
    public Request path(String path)
    {
        URI uri = URI.create(path);
        this.path = uri.getRawPath();
        String query = uri.getRawQuery();
        if (query != null)
        {
            this.query = query;
            params.clear();
            extractParams(query);
        }
        this.uri = buildURI(true);
        if (uri.isAbsolute())
            this.path = buildURI(false).toString();
        return this;
    }

    @Override
    public String getQuery()
    {
        return query;
    }

    @Override
    public URI getURI()
    {
        return uri;
    }

    @Override
    public HttpVersion getVersion()
    {
        return version;
    }

    @Override
    public Request version(HttpVersion version)
    {
        this.version = version;
        return this;
    }

    @Override
    public Request param(String name, String value)
    {
        params.add(name, value);
        this.query = buildQuery();
        return this;
    }

    @Override
    public Fields getParams()
    {
        return new Fields(params, true);
    }

    @Override
    public String getAgent()
    {
        return headers.get(HttpHeader.USER_AGENT);
    }

    @Override
    public Request agent(String agent)
    {
        headers.put(HttpHeader.USER_AGENT, agent);
        return this;
    }

    @Override
    public Request header(String name, String value)
    {
        if (value == null)
            headers.remove(name);
        else
            headers.add(name, value);
        return this;
    }

    @Override
    public Request header(HttpHeader header, String value)
    {
        if (value == null)
            headers.remove(header);
        else
            headers.add(header, value);
        return this;
    }

    @Override
    public Request attribute(String name, Object value)
    {
        attributes.put(name, value);
        return this;
    }

    @Override
    public Map<String, Object> getAttributes()
    {
        return attributes;
    }

    @Override
    public HttpFields getHeaders()
    {
        return headers;
    }

    @Override
    public <T extends RequestListener> List<T> getRequestListeners(Class<T> type)
    {
        // This method is invoked often in a request/response conversation,
        // so we avoid allocation if there is no need to filter.
        if (type == null)
            return (List<T>)requestListeners;

        ArrayList<T> result = new ArrayList<>();
        for (RequestListener listener : requestListeners)
            if (type.isInstance(listener))
                result.add((T)listener);
        return result;
    }

    @Override
    public Request listener(Request.Listener listener)
    {
        this.requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onRequestQueued(QueuedListener listener)
    {
        this.requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onRequestBegin(BeginListener listener)
    {
        this.requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onRequestHeaders(HeadersListener listener)
    {
        this.requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onRequestCommit(CommitListener listener)
    {
        this.requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onRequestContent(ContentListener listener)
    {
        this.requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onRequestSuccess(SuccessListener listener)
    {
        this.requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onRequestFailure(FailureListener listener)
    {
        this.requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onResponseBegin(Response.BeginListener listener)
    {
        this.responseListeners.add(listener);
        return this;
    }

    @Override
    public Request onResponseHeader(Response.HeaderListener listener)
    {
        this.responseListeners.add(listener);
        return this;
    }

    @Override
    public Request onResponseHeaders(Response.HeadersListener listener)
    {
        this.responseListeners.add(listener);
        return this;
    }

    @Override
    public Request onResponseContent(Response.ContentListener listener)
    {
        this.responseListeners.add(listener);
        return this;
    }

    @Override
    public Request onResponseSuccess(Response.SuccessListener listener)
    {
        this.responseListeners.add(listener);
        return this;
    }

    @Override
    public Request onResponseFailure(Response.FailureListener listener)
    {
        this.responseListeners.add(listener);
        return this;
    }

    @Override
    public ContentProvider getContent()
    {
        return content;
    }

    @Override
    public Request content(ContentProvider content)
    {
        return content(content, null);
    }

    @Override
    public Request content(ContentProvider content, String contentType)
    {
        if (contentType != null)
            header(HttpHeader.CONTENT_TYPE, contentType);
        this.content = content;
        return this;
    }

    @Override
    public Request file(Path file) throws IOException
    {
        return file(file, "application/octet-stream");
    }

    @Override
    public Request file(Path file, String contentType) throws IOException
    {
        if (contentType != null)
            header(HttpHeader.CONTENT_TYPE, contentType);
        return content(new PathContentProvider(file));
    }

    @Override
    public boolean isFollowRedirects()
    {
        return followRedirects;
    }

    @Override
    public Request followRedirects(boolean follow)
    {
        this.followRedirects = follow;
        return this;
    }

    @Override
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    @Override
    public Request idleTimeout(long timeout, TimeUnit unit)
    {
        this.idleTimeout = unit.toMillis(timeout);
        return this;
    }

    @Override
    public long getTimeout()
    {
        return timeout;
    }

    @Override
    public Request timeout(long timeout, TimeUnit unit)
    {
        this.timeout = unit.toMillis(timeout);
        return this;
    }

    @Override
    public ContentResponse send() throws InterruptedException, TimeoutException, ExecutionException
    {
        FutureResponseListener listener = new FutureResponseListener(this);
        send(this, listener);

        long timeout = getTimeout();
        if (timeout <= 0)
            return listener.get();

        try
        {
            return listener.get(timeout, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException | TimeoutException x)
        {
            // Differently from the Future, the semantic of this method is that if
            // the send() is interrupted or times out, we abort the request.
            abort(x);
            throw x;
        }
    }

    @Override
    public void send(Response.CompleteListener listener)
    {
        if (getTimeout() > 0)
        {
            TimeoutCompleteListener timeoutListener = new TimeoutCompleteListener(this);
            timeoutListener.schedule(client.getScheduler());
            responseListeners.add(timeoutListener);
        }
        send(this, listener);
    }

    private void send(Request request, Response.CompleteListener listener)
    {
        if (listener != null)
            responseListeners.add(listener);
        client.send(request, responseListeners);
    }

    @Override
    public boolean abort(Throwable cause)
    {
        aborted = Objects.requireNonNull(cause);
        // The conversation may be null if it is already completed
        HttpConversation conversation = client.getConversation(getConversationID(), false);
        return conversation != null && conversation.abort(cause);
    }

    @Override
    public Throwable getAbortCause()
    {
        return aborted;
    }

    private String buildQuery()
    {
        StringBuilder result = new StringBuilder();
        for (Iterator<Fields.Field> iterator = params.iterator(); iterator.hasNext();)
        {
            Fields.Field field = iterator.next();
            String[] values = field.values();
            for (int i = 0; i < values.length; ++i)
            {
                if (i > 0)
                    result.append("&");
                result.append(field.name()).append("=");
                result.append(urlEncode(values[i]));
            }
            if (iterator.hasNext())
                result.append("&");
        }
        return result.toString();
    }

    private String urlEncode(String value)
    {
        String encoding = "UTF-8";
        try
        {
            return URLEncoder.encode(value, encoding);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new UnsupportedCharsetException(encoding);
        }
    }

    private void extractParams(String query)
    {
        if (query != null)
        {
            for (String nameValue : query.split("&"))
            {
                String[] parts = nameValue.split("=");
                param(parts[0], parts.length < 2 ? "" : urlDecode(parts[1]));
            }
        }
    }

    private String urlDecode(String value)
    {
        String charset = "UTF-8";
        try
        {
            return URLDecoder.decode(value, charset);
        }
        catch (UnsupportedEncodingException x)
        {
            throw new UnsupportedCharsetException(charset);
        }
    }

    private URI buildURI(boolean withQuery)
    {
        String path = getPath();
        String query = getQuery();
        if (query != null && withQuery)
            path += "?" + query;
        URI result = URI.create(path);
        if (!result.isAbsolute())
            result = URI.create(client.address(getScheme(), getHost(), getPort()) + path);
        return result;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %s %s]@%x", HttpRequest.class.getSimpleName(), getMethod(), getPath(), getVersion(), hashCode());
    }
}
