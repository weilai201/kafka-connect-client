/**
 * Copyright 2018, 2019, 2020, 2021 SourceLab.org https://github.com/SourceLabOrg/kafka-connect-client
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.sourcelab.kafka.connect.apiclient.rest;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sourcelab.kafka.connect.apiclient.Configuration;
import org.sourcelab.kafka.connect.apiclient.request.JacksonFactory;
import org.sourcelab.kafka.connect.apiclient.request.Request;
import org.sourcelab.kafka.connect.apiclient.rest.exceptions.ConnectionException;
import org.sourcelab.kafka.connect.apiclient.rest.exceptions.ResultParsingException;
import org.sourcelab.kafka.connect.apiclient.rest.handlers.RestResponseHandler;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * RestClient implementation using HTTPClient.
 */
public class HttpClientRestClient implements RestClient {
    private static final Logger logger = LoggerFactory.getLogger(HttpClientRestClient.class);

    /**
     * Default headers included with every request.
     */
    private static final Collection<Header> DEFAULT_HEADERS = Collections.unmodifiableCollection(Arrays.asList(
        new BasicHeader("Accept", "application/json"),
        new BasicHeader("Content-Type", "application/json")
    ));
    
    /**
     * Save a copy of the configuration.
     */
    private Configuration configuration;

    /**
     * Our underlying Http Client.
     */
    private CloseableHttpClient httpClient;

    /**
     * The AuthCache used when creating the HttpClientContext.
     */
    private AuthCache authCache;

    /**
     * The CredentialsProvider used when creating the HttpClientContext.
     */
    private CredentialsProvider credsProvider;

    /**
     * Provides an interface for modifying how the underlying HttpClient instance is created.
     */
    private final HttpClientConfigHooks configHooks;

    /**
     * Constructor.
     */
    public HttpClientRestClient() {
        this(new DefaultHttpClientConfigHooks());
    }

    /**
     * Constructor allowing for injecting configuration hooks.
     * @param configHooks For hooking/overriding into how the underlying HttpClient is configured.
     */
    public HttpClientRestClient(final HttpClientConfigHooks configHooks) {
        this.configHooks = configHooks;
    }

    /**
     * Initialization method.  This takes in the configuration and sets up the underlying
     * http client appropriately.
     * @param configuration The user defined configuration.
     */
    @Override
    public void init(final Configuration configuration) {
        // Save reference to configuration
        this.configuration = configuration;

        // Create https context builder utility.
        final HttpsContextBuilder httpsContextBuilder = configHooks.createHttpsContextBuilder(configuration);

        // Create and setup client builder
        HttpClientBuilder clientBuilder = Objects.requireNonNull(
            configHooks.createHttpClientBuilder(configuration),
            "HttpClientConfigHook::createHttpClientBuilder() must return non-null instance."
        );
        clientBuilder
            // Define timeout
            .setConnectionTimeToLive(configuration.getConnectionTimeToLiveInSeconds(), TimeUnit.SECONDS)

            // Define SSL Socket Factory instance.
            .setSSLSocketFactory(httpsContextBuilder.createSslSocketFactory());

        // Define our RequestConfigBuilder
        RequestConfig.Builder requestConfigBuilder = Objects.requireNonNull(
            configHooks.createRequestConfigBuilder(configuration),
            "HttpClientConfigHook::createRequestConfigBuilder() must return non-null instance."
        );

        requestConfigBuilder.setConnectTimeout(configuration.getRequestTimeoutInSeconds() * 1_000);

        // Define our Credentials Provider
        credsProvider = Objects.requireNonNull(
            configHooks.createCredentialsProvider(configuration),
            "HttpClientConfigHook::createCredentialsProvider() must return non-null instance."
        );

        // Define our auth cache
        authCache = Objects.requireNonNull(
            configHooks.createAuthCache(configuration),
            "HttpClientConfigHook::createAuthCache() must return non-null instance."
        );

        // If we have a configured proxy host
        if (configuration.getProxyHost() != null) {
            // Define proxy host
            final HttpHost proxyHost = new HttpHost(
                configuration.getProxyHost(),
                configuration.getProxyPort(),
                configuration.getProxyScheme()
            );

            // If we have proxy auth enabled
            if (configuration.getProxyUsername() != null) {
                // Add proxy credentials
                credsProvider.setCredentials(
                    new AuthScope(configuration.getProxyHost(), configuration.getProxyPort()),
                    new UsernamePasswordCredentials(configuration.getProxyUsername(), configuration.getProxyPassword())
                );

                // Preemptive load context with authentication.
                authCache.put(
                    new HttpHost(configuration.getProxyHost(), configuration.getProxyPort(), configuration.getProxyScheme()), new BasicScheme()
                );
            }

            // Attach Proxy to request config builder
            requestConfigBuilder.setProxy(proxyHost);
        }

        // If BasicAuth credentials are configured.
        if (configuration.getBasicAuthUsername() != null) {
            try {
                // parse ApiHost for Hostname and port.
                final URL apiUrl = new URL(configuration.getApiHost());

                // Add Kafka-Connect credentials
                credsProvider.setCredentials(
                    new AuthScope(apiUrl.getHost(), apiUrl.getPort()),
                    new UsernamePasswordCredentials(
                        configuration.getBasicAuthUsername(),
                        configuration.getBasicAuthPassword()
                    )
                );

                // Preemptive load context with authentication.
                authCache.put(
                    new HttpHost(apiUrl.getHost(), apiUrl.getPort(), apiUrl.getProtocol()), new BasicScheme()
                );
            } catch (final MalformedURLException exception) {
                throw new RuntimeException(exception.getMessage(), exception);
            }
        }

        // Call Modify hooks
        authCache = Objects.requireNonNull(
            configHooks.modifyAuthCache(configuration, authCache),
            "HttpClientConfigHook::modifyAuthCache() must return non-null instance."
        );
        credsProvider = Objects.requireNonNull(
            configHooks.modifyCredentialsProvider(configuration, credsProvider),
            "HttpClientConfigHook::modifyCredentialsProvider() must return non-null instance."
        );
        requestConfigBuilder = Objects.requireNonNull(
            configHooks.modifyRequestConfig(configuration, requestConfigBuilder),
            "HttpClientConfigHook::modifyRequestConfig() must return non-null instance."
        );

        // Attach Credentials provider to client builder.
        clientBuilder.setDefaultCredentialsProvider(credsProvider);

        // Attach default request config
        clientBuilder.setDefaultRequestConfig(requestConfigBuilder.build());

        // build http client
        clientBuilder = Objects.requireNonNull(
            configHooks.modifyHttpClientBuilder(configuration, clientBuilder),
            "HttpClientConfigHook::modifyHttpClientBuilder() must return non-null instance."
        );
        httpClient = clientBuilder.build();
        
    }

    @Override
    public void close() {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (final IOException e) {
                logger.error("Error closing: {}", e.getMessage(), e);
            }
        }
        httpClient = null;
    }

    /**
     * Create the HttpClientBuilder which is used to create the HttpClient.
     * This method allows users to extend this class and use a custom builder if needed.
     * @return The HttpClientBuilder to use for creating the HttpClient.
     */
    protected HttpClientBuilder createHttpClientBuilder() {
        return HttpClientBuilder.create();
    }

    /**
     * Make a request against the Pardot API.
     * @param request The request to submit.
     * @return The response, in UTF-8 String format.
     * @throws RestException if something goes wrong.
     */
    @Override
    public RestResponse submitRequest(final Request request) throws RestException {
        final String url = constructApiUrl(request.getApiEndpoint());
        final ResponseHandler<RestResponse> responseHandler = new RestResponseHandler();

        try {
            switch (request.getRequestMethod()) {
                case GET:
                    return submitGetRequest(url, Collections.emptyMap(), responseHandler);
                case POST:
                    return submitPostRequest(url, request.getRequestBody(), responseHandler);
                case PUT:
                    return submitPutRequest(url, request.getRequestBody(), responseHandler);
                case DELETE:
                    return submitDeleteRequest(url, request.getRequestBody(), responseHandler);
                default:
                    throw new IllegalArgumentException("Unknown Request Method: " + request.getRequestMethod());
            }
        } catch (final IOException exception) {
            throw new RestException(exception.getMessage(), exception);
        }
    }

    /**
     * Internal GET method.
     * @param url Url to GET to.
     * @param getParams GET parameters to include in the request
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T submitGetRequest(final String url, final Map<String, String> getParams, final ResponseHandler<T> responseHandler) throws IOException {
        try {
            // Construct URI including our request parameters.
            final URIBuilder uriBuilder = new URIBuilder(url)
                .setCharset(StandardCharsets.UTF_8);

            // Attach submitRequest params
            for (final Map.Entry<String, String> entry : getParams.entrySet()) {
                uriBuilder.setParameter(entry.getKey(), entry.getValue());
            }

            // Build Get Request
            final HttpGet get = new HttpGet(uriBuilder.build());

            // Add default headers.
            DEFAULT_HEADERS.forEach(get::addHeader);

            logger.debug("Executing request {}", get.getRequestLine());

            // Execute and return
            return execute(get, responseHandler);
        } catch (final ClientProtocolException | SocketException | URISyntaxException | SSLHandshakeException connectionException) {
            // Typically this is a connection or certificate issue.
            throw new ConnectionException(connectionException.getMessage(), connectionException);
        } catch (final IOException ioException) {
            // Typically this is a parse error.
            throw new ResultParsingException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Internal POST method.
     * @param url Url to POST to.
     * @param requestBody POST entity include in the request body
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T submitPostRequest(final String url, final Object requestBody, final ResponseHandler<T> responseHandler) throws IOException {
        try {
            final HttpPost post = new HttpPost(url);

            // Add default headers.
            DEFAULT_HEADERS.forEach(post::addHeader);

            // Convert to Json
            final String jsonPayloadStr = JacksonFactory.newInstance().writeValueAsString(requestBody);
            
            post.setEntity(new StringEntity(jsonPayloadStr, configuration.getEncoding()));

            logger.debug("Executing request {} with {}", post.getRequestLine(), jsonPayloadStr);

            // Execute and return
            return execute(post, responseHandler);
        } catch (final ClientProtocolException | SocketException | SSLHandshakeException connectionException) {
            // Typically this is a connection issue.
            throw new ConnectionException(connectionException.getMessage(), connectionException);
        } catch (final IOException ioException) {
            // Typically this is a parse error.
            throw new ResultParsingException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Internal PUT method.
     * @param url Url to POST to.
     * @param requestBody POST entity include in the request body
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T submitPutRequest(final String url, final Object requestBody, final ResponseHandler<T> responseHandler) throws IOException {
        try {
            final HttpPut put = new HttpPut(url);

            // Add default headers.
            DEFAULT_HEADERS.forEach(put::addHeader);

            // Convert to Json and submit as payload.
            final String jsonPayloadStr = JacksonFactory.newInstance().writeValueAsString(requestBody);
            put.setEntity(new StringEntity(jsonPayloadStr, configuration.getEncoding()));

            logger.debug("Executing request {} with {}", put.getRequestLine(), jsonPayloadStr);

            // Execute and return
            return execute(put, responseHandler);
        } catch (final ClientProtocolException | SocketException | SSLHandshakeException connectionException) {
            // Typically this is a connection issue.
            throw new ConnectionException(connectionException.getMessage(), connectionException);
        } catch (final IOException ioException) {
            // Typically this is a parse error.
            throw new ResultParsingException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Internal DELETE method.
     * @param url Url to DELETE to.
     * @param requestBody POST entity include in the request body
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T submitDeleteRequest(final String url, final Object requestBody, final ResponseHandler<T> responseHandler) throws IOException {
        try {
            final HttpDelete delete = new HttpDelete(url);

            // Add default headers.
            DEFAULT_HEADERS.forEach(delete::addHeader);

            // Convert to Json
            final String jsonPayloadStr = JacksonFactory.newInstance().writeValueAsString(requestBody);

            logger.debug("Executing request {} with {}", delete.getRequestLine(), jsonPayloadStr);

            // Execute and return
            return execute(delete, responseHandler);
        } catch (final ClientProtocolException | SocketException | SSLHandshakeException connectionException) {
            // Typically this is a connection issue.
            throw new ConnectionException(connectionException.getMessage(), connectionException);
        } catch (final IOException ioException) {
            // Typically this is a parse error.
            throw new ResultParsingException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Creates an HttpClientContext and executes the HTTP request.
     *
     * @param request The request to execute
     * @param responseHandler The response Handler to use to parse the response
     * @param <T> The type that ResponseHandler returns.
     * @return Parsed response.
     */
    private <T> T execute(final HttpUriRequest request, final ResponseHandler<T> responseHandler) throws IOException {
        return httpClient.execute(request, responseHandler, createHttpClientContext());
    }

    /**
     * Internal helper method for generating URLs w/ the appropriate API host and API version.
     * @param endPoint The end point you want to hit.
     * @return Constructed URL for the end point.
     */
    private String constructApiUrl(final String endPoint) {
        return configuration.getApiHost() + endPoint;
    }

    /**
     * Creates a new HttpClientContext with the authCache and credsProvider.
     * @return the created HttpClientContext.
     */
    private HttpClientContext createHttpClientContext() {
        // Define our context
        final HttpClientContext httpClientContext = configHooks.createHttpClientContext(configuration);

        // Configure context.
        httpClientContext.setAuthCache(authCache);
        httpClientContext.setCredentialsProvider(credsProvider);

        return configHooks.modifyHttpClientContext(configuration, httpClientContext);
    }
}
