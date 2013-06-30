/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.riot.web;

import static java.lang.String.format ;

import java.io.IOException ;
import java.io.InputStream ;
import java.io.UnsupportedEncodingException ;
import java.net.URI ;
import java.net.URISyntaxException ;
import java.util.ArrayList ;
import java.util.List ;
import java.util.concurrent.atomic.AtomicLong ;

import org.apache.http.HttpEntity ;
import org.apache.http.HttpResponse ;
import org.apache.http.NameValuePair ;
import org.apache.http.StatusLine ;
import org.apache.http.client.HttpClient ;
import org.apache.http.client.entity.UrlEncodedFormEntity ;
import org.apache.http.client.methods.* ;
import org.apache.http.entity.InputStreamEntity ;
import org.apache.http.entity.StringEntity ;
import org.apache.http.impl.client.AbstractHttpClient ;
import org.apache.http.impl.client.DefaultHttpClient ;
import org.apache.http.impl.client.SystemDefaultHttpClient ;
import org.apache.http.message.BasicNameValuePair ;
import org.apache.http.protocol.BasicHttpContext ;
import org.apache.http.protocol.HttpContext ;
import org.apache.jena.atlas.io.IO ;
import org.apache.jena.atlas.web.HttpException ;
import org.apache.jena.atlas.web.TypedInputStream ;
import org.apache.jena.atlas.web.auth.HttpAuthenticator ;
import org.apache.jena.atlas.web.auth.ServiceAuthenticator ;
import org.apache.jena.riot.WebContent ;
import org.apache.jena.web.HttpSC ;
import org.slf4j.Logger ;
import org.slf4j.LoggerFactory ;

import com.hp.hpl.jena.sparql.ARQException ;
import com.hp.hpl.jena.sparql.ARQInternalErrorException ;
import com.hp.hpl.jena.sparql.engine.http.Params ;
import com.hp.hpl.jena.sparql.engine.http.Params.Pair ;

/**
 * Simplified HTTP operations; simplification means only supporting certain uses
 * of HTTP. The expectation is that the simplified operations in this class can
 * be used by other code to generate more application specific HTTP interactions
 * (e.g. SPARQL queries). For more complictaed requirments of HTTP, then the
 * application wil need to use org.apache.http.client directly.
 * 
 * <p>
 * For HTTP GET, the application supplies a URL, the accept header string, and a
 * list of handlers to deal with different content type responses.
 * <p>
 * For HTTP POST, the application supplies a URL, content, the accept header
 * string, and a list of handlers to deal with different content type responses,
 * or no response is expected.
 * <p>
 * For HTTP PUT, the application supplies a URL, content, the accept header
 * string
 * </p>
 * 
 * @see HttpNames HttpNames, for HTTP related constants
 * @see WebContent WebContent, for content type name constants
 */
public class HttpOp {
    /* Imeplmentation notes:
     * 
     * Test are in Fuseki (need a server to test against)
     * 
     * Pattern of functions provided:
     * 1/ The full operation (includes HttpClient, HttpContext httpContext, HttpAuthenticator)
     *    any of which can be null for "default"
     * 2/ Provide common use options without those three arguments. 
     *    These all become the full operation.
     * 3/ All calls go via exec for logging and debugging.
     */
    
    // See also:
    // Fluent API in HttpClient from v4.2
    static private Logger log = LoggerFactory.getLogger(HttpOp.class);

    static private AtomicLong counter = new AtomicLong(0);

    /**
     * Default authenticator used for HTTP authentication
     */
    static private HttpAuthenticator defaultAuthenticator = new ServiceAuthenticator();

    static private HttpResponseHandler nullHandler = HttpResponseLib.nullResponse ; 
    
    /** Response as a string (UTF-8 assumed) */
    public static class CaptureString implements HttpCaptureResponse<String> {
        String result ;

        @Override
        public void handle(String baseIRI, HttpResponse response) throws IOException {
            HttpEntity entity = response.getEntity() ;
            InputStream instream = entity.getContent() ;
            result = IO.readWholeFileAsUTF8(instream) ;
            instream.close() ;
        }

        @Override
        public String get() {
            return result ;
        }
    } ;
    
    /**
     * TypedInputStream from an HTTP response. The TypedInputStream should be
     * explicitly closed.
     */
    public static class CaptureInput implements HttpCaptureResponse<TypedInputStream> {
        TypedInputStream stream ;

        @Override
        public void handle(String baseIRI, HttpResponse response) throws IOException {

            HttpEntity entity = response.getEntity() ;
            stream = new TypedInputStream(entity.getContent(), entity.getContentType().getValue()) ;
        }

        @Override
        public TypedInputStream get() {
            return stream ;
        }
    } ;
    
    /**
     * Sets the default authenticator used for authenticate requests if no
     * specific authenticator is provided. May be set to null to turn off
     * default authentication, when set to null users must manually configure
     * authentication.
     * 
     * @param authenticator
     *            Authenticator
     */
    public static void setDefaultAuthenticator(HttpAuthenticator authenticator) {
        defaultAuthenticator = authenticator;
    }

    //---- HTTP GET
    //  -- Handler for results
    //  -- TypeInsputStream
    //  -- String
    
    /**
     * Executes a HTTP Get request, handling the response with given handler.
     * <p>
     * HTTP responses 400 and 500 become exceptions.
     * </p>
     * 
     * @param url
     *            URL
     * @param acceptHeader
     *            Accept Header
     * @param handler
     *            Response Handler
     */
    public static void execHttpGet(String url, String acceptHeader, HttpResponseHandler handler) {
        execHttpGet(url, acceptHeader, handler, null, null, null);
    }

    /**
     * Executes a HTTP Get request handling the response with the given handler.
     * <p>
     * HTTP responses 400 and 500 become exceptions.
     * </p>
     * 
     * @param url
     *            URL
     * @param acceptHeader
     *            Accept Header
     * @param handler
     *            Response Handler
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpGet(String url, String acceptHeader, HttpResponseHandler handler, HttpAuthenticator authenticator) {
        execHttpGet(url, acceptHeader, handler, null, null, authenticator);
    }

    /**
     * Executes a HTTP Get request handling the response with one of the given
     * handlers
     * <p>
     * The acceptHeader string is any legal value for HTTP Accept: field.
     * <p>
     * The handlers are the set of content types (without charset), used to
     * dispatch the response body for handling.
     * <p>
     * HTTP responses 400 and 500 become exceptions.
     * 
     * @param url
     *            URL
     * @param acceptHeader
     *            Accept Header
     * @param handler
     *            Response handler called to process the response
     * @param httpClient
     *            HTTP Client
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpGet(String url, String acceptHeader, HttpResponseHandler handler,
                                   HttpClient httpClient, HttpContext httpContext, HttpAuthenticator authenticator) {
            String requestURI = determineRequestURI(url);
            HttpGet httpget = new HttpGet(requestURI);
            exec(url, httpget, acceptHeader, handler, httpClient, httpContext, authenticator) ;
    }

    /**
     * Executes a HTTP GET and return a typed input stream.
     * The stream must be closed after use.
     * <p>
     * The acceptHeader string is any legal value for HTTP Accept: field.
     * </p>
     * 
     * @param url
     *            URL
     * @return Typed Input Stream
     */
    public static TypedInputStream execHttpGet(String url) {
        HttpCaptureResponse<TypedInputStream> handler = new CaptureInput() ;
        execHttpGet(url, null, handler, null, null, null);
        return handler.get();
    }

    /**
     * Executes a HTTP GET and return a typed input stream.
     * The stream must be closed after use.
     * <p>
     * The acceptHeader string is any legal value for HTTP Accept: field.
     * </p>
     * 
     * @param url
     *            URL
     * @param acceptHeader
     *            Accept Header
     * @return Typed Input Stream
     */
    public static TypedInputStream execHttpGet(String url, String acceptHeader) {
        HttpCaptureResponse<TypedInputStream> handler = new CaptureInput() ;
        execHttpGet(url, acceptHeader, handler, null, null, null);
        return handler.get();
    }

    /**
     * Executes a HTTP GET and return a typed input stream.
     * The stream must be closed after use.
     * <p>
     * The acceptHeader string is any legal value for HTTP Accept: field.
     * </p>
     * 
     * @param url
     *            URL
     * @param acceptHeader
     *            Accept Header
     * @param authenticator
     *            HTTP Authenticator
     * @return Typed Input Stream
     */
    public static TypedInputStream execHttpGet(String url, String acceptHeader, HttpAuthenticator authenticator) {
        return execHttpGet(url, acceptHeader, null, null, authenticator);
    }


    /**
     * Executes a HTTP GET and returns a typed input stream
     * <p>
     * A 404 will result in a null stream being returned, any other error code
     * results in an exception.
     * </p>
     * 
     * @param url
     *            URL
     * @param acceptHeader
     *            Accept Header
     * @param httpClient
     *            HTTP Client
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     * @return TypedInputStream or null if the URL returns 404.
     */
    public static TypedInputStream execHttpGet(String url, String acceptHeader, HttpClient httpClient,
                                               HttpContext httpContext, HttpAuthenticator authenticator) {
        HttpCaptureResponse<TypedInputStream> handler = new CaptureInput() ;
        try {
            execHttpGet(url, acceptHeader, handler, httpClient, httpContext, authenticator);
        } catch (HttpException ex) {
            if ( ex.getResponseCode() == HttpSC.NOT_FOUND_404 )
                return null ;
            throw ex ;
        }
        return handler.get();
    }

    /**
     * Convenience operation to execute a GET with no content negtotiationreturn
     * the response as a string.
     * 
     * @param url URL
     * @return Response as a string
     */
    public static String execHttpGetString(String url) {
        return execHttpGetString(url, null) ;
    }

    /**
     * Convenience operation to execute a GET and return the response as a string
     * 
     * @param url
     *            URL
     * @param acceptHeader
     *            Accept header.
     * @return Response as a string
     */
    public static String execHttpGetString(String url, String acceptHeader) {
        CaptureString handler = new CaptureString() ;
        try {
            execHttpGet(url, acceptHeader, handler) ;
        } catch (HttpException ex) {
            if ( ex.getResponseCode() == HttpSC.NOT_FOUND_404 )
                return null ;
            throw ex ;
        }
        return handler.get() ; 
    }

    //---- HTTP POST
    //  -- Pass in a string
    //  -- Pass in an InoputStream
    //  -- Pass in Entity (see org.apache.http.entityContentProducer) 

    /**
     * Executes a HTTP POST with the given string as the request body and throws
     * away success responses, failure responses will throw an error.
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type to POST
     * @param content
     *            Content to POST
     */
    public static void execHttpPost(String url, String contentType, String content) {
        execHttpPost(url, contentType, content, null, null, defaultAuthenticator);
    }

    /**
     * Executes a simple POST with the given string as the request body and
     * throws away success responses, failure responses will throw an error.
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type to POST
     * @param content
     *            Content to POST
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPost(String url, String contentType, String content, HttpAuthenticator authenticator) {
        execHttpPost(url, contentType, content, null, null, authenticator);
    }

    /**
     * Executes a HTTP POST with a string as the request body and response
     * handling
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type to POST
     * @param content
     *            Content to POST
     * @param acceptType
     *            Accept Type
     * @param handler
     *            Response handler called to process the response
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPost(String url, String contentType, String content, String acceptType,
                                    HttpResponseHandler handler, HttpAuthenticator authenticator) {
        execHttpPost(url, contentType, content, acceptType, handler, null, null, authenticator) ;
    }

    /**
     * Executes a HTTP POST with a string as the request body and response
     * handling
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type to POST
     * @param content
     *            Content to POST
     * @param acceptType
     *            Accept Type
     * @param handler
     *            Response handler called to process the response
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPost(String url, String contentType, String content, String acceptType,
                                    HttpResponseHandler handler, HttpClient httpClient, HttpContext httpContext, HttpAuthenticator authenticator) {
        StringEntity e = null;
        try {
            e = new StringEntity(content, "UTF-8");
            e.setContentType(contentType);
            execHttpPost(url, e, acceptType, handler, httpClient, httpContext, authenticator);
        } catch (UnsupportedEncodingException e1) {
            throw new ARQInternalErrorException("Platform does not support required UTF-8");
        } finally {
            closeEntity(e);
        }
    }

    /**
     * Executes a HTTP POST with a request body from an input stream without
     * response body with no response handling
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type to POST
     * @param input
     *            Input Stream to POST from
     * @param length
     *            Amount of content to POST
     * 
     */
    public static void execHttpPost(String url, String contentType, InputStream input, long length) {
        execHttpPost(url, contentType, input, length, null, null, null, null, defaultAuthenticator);
    }

    /**
     * Executes a HTTP POST with a request body from an input stream without
     * response body with no response handling
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type to POST
     * @param input
     *            Input Stream to POST from
     * @param length
     *            Amount of content to POST
     * @param authenticator
     *            HTTP Authenticator
     * 
     */
    public static void execHttpPost(String url, String contentType, InputStream input, long length,
                                    HttpAuthenticator authenticator) {
        execHttpPost(url, contentType, input, length, null, null, null, null, authenticator) ;
    }

    /**
     * Executes a HTTP POST with request body from an input stream and response
     * handling.
     * <p>
     * The input stream is assumed to be UTF-8.
     * </p>
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type to POST
     * @param input
     *            Input Stream to POST content from
     * @param length
     *            Length of content to POST
     * @param acceptType
     *            Accept Type
     * @param handler
     *            Response handler called to process the response
     */
    public static void execHttpPost(String url, String contentType, InputStream input, long length, String acceptType,
                                    HttpResponseHandler handler) {
        execHttpPost(url, contentType, input, length, acceptType, handler, null, null, null) ;
    }

    /**
     * Executes a HTTP POST with request body from an input stream and response
     * handling.
     * <p>
     * The input stream is assumed to be UTF-8.
     * </p>
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type to POST
     * @param input
     *            Input Stream to POST content from
     * @param length
     *            Length of content to POST
     * @param acceptType
     *            Accept Type
     * @param handler
     *            Response handler called to process the response
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPost(String url, String contentType, InputStream input, long length, String acceptType,
                                    HttpResponseHandler handler, 
                                    HttpClient httpClient, HttpContext httpContext, 
                                    HttpAuthenticator authenticator) {
        InputStreamEntity e = new InputStreamEntity(input, length);
        e.setContentType(contentType);
        e.setContentEncoding("UTF-8");
        execHttpPost(url, e, acceptType, handler, httpClient, httpContext, authenticator);
    }

    /**
     * POST 
     * 
     * @param url
     *            URL
     * @param entity
     *            Entity to POST
     */
    public static void execHttpPost(String url, HttpEntity entity) {
        execHttpPost(url, entity, null, null) ;
    }

    public static void execHttpPost(String url, HttpEntity entity, String acceptString, HttpResponseHandler handler) {
        execHttpPost(url, entity, acceptString, handler, null, null, null) ;
    }

    /**
     * POST with response body.
     * <p>
     * The content for the POST body comes from the HttpEntity.
     * <p>
     * Additional headers e.g. for authentication can be injected through an
     * {@link HttpContext}
     * 
     * @param url
     *            URL
     * @param entity
     *            Entity to POST
     * @param acceptHeader 
     * @param handler
     *            Response handler called to process the response
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPost(String url, HttpEntity entity, String acceptHeader, HttpResponseHandler handler,
                                    HttpClient httpClient, HttpContext httpContext, HttpAuthenticator authenticator) {

        try {
            String requestURI = determineRequestURI(url) ;
            HttpPost httppost = new HttpPost(requestURI) ;
            httppost.setEntity(entity);
            exec(url, httppost, acceptHeader, handler, httpClient, httpContext, authenticator) ;
        } finally {
            closeEntity(entity) ;
        }
    }

    //---- HTTP POST as a form.
    //  -- Pass in a string
    //  -- Pass in an InoputStream
    //  -- Pass in Entity (see org.apache.http.entityContentProducer) 

    
    /**
     * Executes a HTTP POST and returns a typed input stream
     * 
     * @param url
     *            URL
     * @param params
     *            Parameters to POST
     * @param acceptHeader
     * @return Typed Input Stream
     */
    public static TypedInputStream execHttpPostForm(String url, Params params, String acceptHeader) {
        return execHttpPostForm(url, params, acceptHeader, null, null, null);
    }

    /**
     * Executes a HTTP GET and returns a typed input stream
     * <p>
     * The acceptHeader string is any legal value for HTTP Accept: field.
     * </p>
     * <p>
     * A 404 will result in a null stream being returned, any other error code
     * results in an exception.
     * </p>
     * 
     * @param url
     *            URL
     * @param acceptHeader
     *            Accept Header
     * @param params
     *            Parameters to POST
     * @param httpClient
     *            HTTP Client
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     * @return Typed Input Stream, null if the URL returns 404
     */
    public static TypedInputStream execHttpPostForm(String url, Params params, String acceptHeader,
                                                    HttpClient httpClient, HttpContext httpContext, HttpAuthenticator authenticator) {
            HttpCaptureResponse<TypedInputStream> handler = new CaptureInput() ;
            try {
                execHttpPostForm(url, params, acceptHeader, handler, httpClient, httpContext, authenticator);
            } catch (HttpException ex) {
                if ( ex.getResponseCode() == HttpSC.NOT_FOUND_404 )
                    return null ;
                throw ex ;
            }
            return handler.get();
        }
    
//        try {
//            long id = counter.incrementAndGet();
//            String requestURI = determineRequestURI(url);
//            //String baseIRI = determineBaseIRI(requestURI);
//
//            HttpPost httppost = new HttpPost(requestURI);
//            if (log.isDebugEnabled())
//                log.debug(format("[%d] %s %s", id, httppost.getMethod(), httppost.getURI().toString()));
//            // Accept
//            if (acceptHeader != null)
//                httppost.addHeader(HttpNames.hAccept, acceptHeader);
//            httppost.setEntity(convertFormParams(params));
//
//            // Prepare and execute
//            httpClient = ensureClient(httpClient);
//            httpContext = ensureContext(httpContext);
//            applyAuthentication(asAbstractClient(httpClient), url, httpContext, authenticator);
//            HttpResponse response = httpClient.execute(httppost, httpContext);
//
//            // Response
//            StatusLine statusLine = response.getStatusLine();
//            if (statusLine.getStatusCode() == 404) {
//                log.debug(format("[%d] %s %s", id, statusLine.getStatusCode(), statusLine.getReasonPhrase()));
//                return null;
//            }
//            if (statusLine.getStatusCode() >= 400) {
//                log.debug(format("[%d] %s %s", id, statusLine.getStatusCode(), statusLine.getReasonPhrase()));
//                throw new HttpException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
//            }
//
//            HttpEntity entity = response.getEntity();
//            if (entity == null) {
//                // No content in the return. Probably a mistake, but not
//                // guaranteed.
//                if (log.isDebugEnabled())
//                    log.debug(format("[%d] %d %s :: (empty)", id, statusLine.getStatusCode(), statusLine.getReasonPhrase()));
//                return null;
//            }
//
//            MediaType mt = MediaType.create(entity.getContentType().getValue());
//            if (log.isDebugEnabled())
//                log.debug(format("[%d] %d %s :: %s", id, statusLine.getStatusCode(), statusLine.getReasonPhrase(), mt));
//
//            return new TypedInputStreamHttp(entity.getContent(), mt, httpClient.getConnectionManager());
//        } catch (IOException ex) {
//            throw new HttpException(ex);
//        }
//    }

    /**
     * Executes a HTTP POST form operation
     * 
     * @param url
     *            URL
     * @param params
     *            Form parameters to POST
     * @param handler
     *            Response handler called to process the response
     */
    public static void execHttpPostForm(String url, Params params, String acceptString, HttpResponseHandler handler) {
        execHttpPostForm(url, params, acceptString, handler, null, null, null);
    }

    /**
     * Executes a HTTP POST form operation
     * 
     * @param url
     *            URL
     * @param params
     *            Form parameters to POST
     * @param handler
     *            Response handler called to process the response
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPostForm(String url, Params params, String acceptHeader, HttpResponseHandler handler,
                                        HttpClient httpClient, HttpContext httpContext, HttpAuthenticator authenticator) {
        
        String requestURI = url;
        HttpPost httppost = new HttpPost(requestURI);
        httppost.setEntity(convertFormParams(params));
        exec(url, httppost, acceptHeader, handler, httpClient, httpContext, authenticator) ;
    }

    /**
     * Executes a HTTP PUT operation
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type for the PUT
     * @param content
     *            Content for the PUT
     * @param httpContext
     *            HTTP Context
     */
    public static void execHttpPut(String url, String contentType, String content, HttpContext httpContext) {
        execHttpPut(url, contentType, content, httpContext, defaultAuthenticator);
    }

    /**
     * Executes a HTTP PUT operation
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type for the PUT
     * @param content
     *            Content for the PUT
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPut(String url, String contentType, String content, HttpContext httpContext,
            HttpAuthenticator authenticator) {
        StringEntity e = null;
        try {
            e = new StringEntity(content, "UTF-8");
            e.setContentType(contentType.toString());
            execHttpPut(url, e, httpContext, authenticator);
        } catch (UnsupportedEncodingException e1) {
            throw new ARQInternalErrorException("Platform does not support required UTF-8");
        } finally {
            closeEntity(e);
        }
    }

    /**
     * Executes a HTTP PUT operation
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type for the PUT
     * @param input
     *            Input Stream to read PUT content from
     * @param length
     *            Amount of content to PUT
     * @param httpContext
     *            HTTP Context
     */
    public static void execHttpPut(String url, String contentType, InputStream input, long length, HttpContext httpContext) {
        execHttpPut(url, contentType, input, length, httpContext, defaultAuthenticator);
    }

    /**
     * Executes a HTTP PUT operation
     * 
     * @param url
     *            URL
     * @param contentType
     *            Content Type for the PUT
     * @param input
     *            Input Stream to read PUT content from
     * @param length
     *            Amount of content to PUT
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPut(String url, String contentType, InputStream input, long length, HttpContext httpContext,
            HttpAuthenticator authenticator) {
        InputStreamEntity e = new InputStreamEntity(input, length);
        e.setContentType(contentType);
        e.setContentEncoding("UTF-8");
        try {
            execHttpPut(url, e, httpContext, authenticator);
        } finally {
            closeEntity(e);
        }
    }

    /**
     * Executes a HTTP PUT operation
     * 
     * @param url
     *            URL
     * @param entity
     *            HTTP Entity to PUT
     * @param httpContext
     *            HTTP Context
     */
    public static void execHttpPut(String url, HttpEntity entity, HttpContext httpContext) {
        execHttpPut(url, entity, httpContext, defaultAuthenticator);
    }

    /**
     * Executes a HTTP PUT operation
     * 
     * @param url
     *            URL
     * @param entity
     *            HTTP Entity to PUT
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpPut(String url, HttpEntity entity, HttpContext httpContext, HttpAuthenticator authenticator) {
        try {
            //XXX
            long id = counter.incrementAndGet();
            String requestURI = determineRequestURI(url) ;
            String baseIRI = determineBaseIRI(requestURI);
            HttpPut httpput = new HttpPut(requestURI);
            if (log.isDebugEnabled())
                log.debug(format("[%d] %s %s", id, httpput.getMethod(), httpput.getURI().toString()));

            httpput.setEntity(entity);

            // Prepare and Execute
            DefaultHttpClient httpclient = new SystemDefaultHttpClient();
            httpContext = ensureContext(httpContext);
            applyAuthentication(httpclient, url, httpContext, authenticator);
            HttpResponse response = httpclient.execute(httpput, httpContext);

            httpResponse(id, response, baseIRI, null);
            httpclient.getConnectionManager().shutdown();
        } catch (IOException ex) {
            throw new HttpException(ex);
        }
    }
    
    /**
     * Executes a HTTP HEAD operation
     * 
     * @param url
     *            URL
     */
    public static void execHttpHead(String url) {
        execHttpHead(url, null, null) ;
    }
    
    /**
     * Executes a HTTP HEAD operation
     * 
     * @param url
     *            URL
     */
    public static void execHttpHead(String url, String acceptString, HttpResponseHandler handler) {
        execHttpHead(url, acceptString, handler, null, null, null) ;
    }

    /**
     * Executes a HTTP HEAD operation
     * 
     * @param url
     *            URL
     */

    public static void execHttpHead(String url, String acceptString, HttpResponseHandler handler,
                                    HttpClient httpClient, HttpContext httpContext, HttpAuthenticator authenticator) {
        String requestURI = determineRequestURI(url) ;
        HttpHead httpHead = new HttpHead(requestURI) ;
        exec(url, httpHead, acceptString, handler, httpClient, httpContext, authenticator) ;
    }
    
    /**
     * Executes a HTTP DELETE operation
     * 
     * @param url
     *            URL
     */
    public static void execHttpDelete(String url) {
        execHttpDelete(url, nullHandler) ;
    }
    
    /**
     * Executes a HTTP DELETE operation
     * 
     * @param url
     *            URL
     */
    public static void execHttpDelete(String url, HttpResponseHandler handler) {
        execHttpDelete(url, handler, null, defaultAuthenticator) ;
    }

    /**
     * Executes a HTTP DELETE operation
     * 
     * @param url
     *            URL
     * @param httpContext
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void execHttpDelete(String url, HttpResponseHandler handler, HttpContext httpContext, HttpAuthenticator authenticator) {
            HttpUriRequest httpDelete = new HttpDelete(url) ;
            exec(url, httpDelete, null, handler, null, httpContext, authenticator) ;
    }

    // Perform the operation!
    // With logging.
    
    private static void exec(String url, HttpUriRequest request, String acceptHeader,HttpResponseHandler handler, 
                             HttpClient httpClient, HttpContext httpContext, HttpAuthenticator authenticator)
    {
        if ( httpClient == null )
            httpClient = new DefaultHttpClient() ;
        try {
            long id = counter.incrementAndGet();
            String requestURI = determineRequestURI(url);
            if (log.isDebugEnabled())
                log.debug(format("[%d] %s %s", id, request.getMethod(), request.getURI().toString()));
            // Accept
            if (acceptHeader != null)
                request.addHeader(HttpNames.hAccept, acceptHeader);

            // Prepare and execute
            httpContext = ensureContext(httpContext);
            applyAuthentication(asAbstractClient(httpClient), url, httpContext, authenticator);
            HttpResponse response = httpClient.execute(request, httpContext);

            // Response
            StatusLine statusLine = response.getStatusLine();
            int statusCode = statusLine.getStatusCode() ; 
            if ( HttpSC.isClientError(statusCode) || HttpSC.isServerError(statusCode) ) {
                log.debug(format("[%d] %s %s", id, statusLine.getStatusCode(), statusLine.getReasonPhrase()));
                throw new HttpException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
            }
            // Redirects are followed by HttpClient.
            if ( handler != null )
                handler.handle(requestURI, response) ;
        } catch (IOException ex) {
            throw new HttpException(ex);
        }
    }    

    /**
     * Ensures that a HTTP Client is non-null
     * 
     * @param client
     *            HTTP Client
     * @return HTTP Client
     */
    private static HttpClient ensureClient(HttpClient client) {
        return client != null ? client : new SystemDefaultHttpClient();
    }

    private static AbstractHttpClient asAbstractClient(HttpClient client) {
        if (AbstractHttpClient.class.isAssignableFrom(client.getClass())) {
            return (AbstractHttpClient) client;
        }
        return null;
    }

    /**
     * Ensures that a context is non-null
     * 
     * @param context
     *            HTTP Context
     * @return Non-null HTTP Context
     */
    private static HttpContext ensureContext(HttpContext context) {
        return context != null ? context : new BasicHttpContext();
    }

    /**
     * Applies authentication to the given client as appropriate
     * <p>
     * If a null authenticator is provided this method tries to use the
     * registered default authenticator which may be set via the
     * {@link HttpOp#setDefaultAuthenticator(HttpAuthenticator)} method.
     * </p>
     * 
     * @param client
     *            HTTP Client
     * @param target
     *            Target URI
     * @param context
     *            HTTP Context
     * @param authenticator
     *            HTTP Authenticator
     */
    public static void applyAuthentication(AbstractHttpClient client, String target, HttpContext context,
            HttpAuthenticator authenticator) {
        // Cannot apply to null client
        if (client == null)
            return;

        // Fallback to default authenticator if null authenticator provided
        if (authenticator == null)
            authenticator = defaultAuthenticator;

        // Authenticator could still be null even if we fell back to default
        if (authenticator == null)
            return;

        try {
            // Apply the authenticator
            URI uri = new URI(target);
            authenticator.apply(client, context, uri);
        } catch (URISyntaxException e) {
            throw new ARQException("Invalid request URI", e);
        } catch (NullPointerException e) {
            throw new ARQException("Null request URI", e);
        }
    }

    private static HttpEntity convertFormParams(Params params) {
        try {
            List<NameValuePair> nvps = new ArrayList<NameValuePair>();
            for (Pair p : params.pairs())
                nvps.add(new BasicNameValuePair(p.getName(), p.getValue()));
            HttpEntity e = new UrlEncodedFormEntity(nvps, "UTF-8");
            return e;
        } catch (UnsupportedEncodingException e) {
            throw new ARQInternalErrorException("Platform does not support required UTF-8");
        }
    }

    private static void closeEntity(HttpEntity entity) {
        if (entity == null)
            return;
        try {
            entity.getContent().close();
        } catch (Exception e) {
        }
    }

    private static String determineRequestURI(String url) {
        String requestURI = url;
        if (requestURI.contains("#")) {
            // No frag ids.
            int i = requestURI.indexOf('#');
            requestURI = requestURI.substring(0, i);
        }
        return requestURI;
    }

    private static String determineBaseIRI(String requestURI) {
        // Technically wrong, but including the query string is "unhelpful"
        String baseIRI = requestURI;
        if (requestURI.contains("?")) {
            // No frag ids.
            int i = requestURI.indexOf('?');
            baseIRI = requestURI.substring(0, i);
        }
        return baseIRI;
    }

    private static void httpResponse(long id, HttpResponse response, String baseIRI, HttpResponseHandler handler)
            throws IllegalStateException, IOException {
        if (response == null)
            return;
        if (handler == null)
            handler = HttpResponseLib.nullResponse ;
        try {
            StatusLine statusLine = response.getStatusLine();
            log.debug(format("[%d] %s %s", id, statusLine.getStatusCode(), statusLine.getReasonPhrase()));
            if (statusLine.getStatusCode() >= 400)
                throw new HttpException(statusLine.getStatusCode(), statusLine.getReasonPhrase());

            if ( HttpSC.isSuccess(statusLine.getStatusCode()))
                handler.handle(baseIRI, response);
            else
                // 300s - we should have followed them already.
                log.warn(format("[%d] Not handled: %s %s", id, statusLine.getStatusCode(), statusLine.getReasonPhrase()));
        } finally {
            closeEntity(response.getEntity());
        }
    }
}
