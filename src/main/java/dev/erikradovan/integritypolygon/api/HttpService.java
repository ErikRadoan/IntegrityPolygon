package dev.erikradovan.integritypolygon.api;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Shared HTTP client service for making outbound requests.
 * Provides both synchronous and asynchronous convenience methods.
 *
 * <p>Modules access this via the service registry:
 * <pre>{@code
 * HttpService http = context.getServiceRegistry()
 *     .get(HttpService.class).orElseThrow();
 *
 * // Async GET
 * http.getAsync("https://api.example.com/data")
 *     .thenAccept(response -> {
 *         String body = response.body();
 *         // process...
 *     });
 *
 * // Blocking GET
 * HttpService.Response resp = http.get("https://api.example.com/data");
 * }</pre>
 */
public interface HttpService {

    /**
     * Perform a blocking GET request.
     *
     * @param url the target URL
     * @return the response
     * @throws Exception if the request fails
     */
    Response get(String url) throws Exception;

    /**
     * Perform a blocking GET request with custom headers.
     *
     * @param url     the target URL
     * @param headers request headers
     * @return the response
     * @throws Exception if the request fails
     */
    Response get(String url, Map<String, String> headers) throws Exception;

    /**
     * Perform a blocking POST request with a JSON body.
     *
     * @param url  the target URL
     * @param json the JSON body string
     * @return the response
     * @throws Exception if the request fails
     */
    Response post(String url, String json) throws Exception;

    /**
     * Perform a blocking POST request with a JSON body and custom headers.
     *
     * @param url     the target URL
     * @param json    the JSON body string
     * @param headers request headers
     * @return the response
     * @throws Exception if the request fails
     */
    Response post(String url, String json, Map<String, String> headers) throws Exception;

    /**
     * Perform an asynchronous GET request.
     *
     * @param url the target URL
     * @return a future that completes with the response
     */
    CompletableFuture<Response> getAsync(String url);

    /**
     * Perform an asynchronous POST request with a JSON body.
     *
     * @param url  the target URL
     * @param json the JSON body string
     * @return a future that completes with the response
     */
    CompletableFuture<Response> postAsync(String url, String json);

    /**
     * Simple response wrapper.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body as a string
     */
    record Response(int statusCode, String body) {

        /**
         * @return true if status is 2xx
         */
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}

