package io.jenkins.plugins.microsoftgraph;

import com.microsoft.graph.core.requests.BaseGraphRequestAdapter;
import com.microsoft.kiota.RequestAdapter;
import com.microsoft.kiota.authentication.AuthenticationProvider;
import okhttp3.OkHttpClient;

/**
 * The main entry point for Microsoft Graph (Entra ID) API calls, mirroring the construction and
 * accessor surface of the official Microsoft Graph SDK's {@code GraphServiceClient} on top of the
 * minimal client generated at build time.
 *
 * <p>Typical usage: pass a
 * {@code com.microsoft.graph.core.authentication.AzureIdentityAuthenticationProvider} and an
 * OkHttp client built via {@code com.microsoft.graph.core.requests.GraphClientFactory}. For
 * sovereign clouds, override the service root after construction:
 * {@code client.getRequestAdapter().setBaseUrl("https://graph.microsoft.us/v1.0")}.
 */
public class GraphServiceClient extends BaseGraphServiceClient {

    private final RequestAdapter requestAdapter;

    public GraphServiceClient(RequestAdapter requestAdapter) {
        super(requestAdapter);
        this.requestAdapter = requestAdapter;
    }

    public GraphServiceClient(AuthenticationProvider authenticationProvider) {
        this(new BaseGraphRequestAdapter(authenticationProvider));
    }

    public GraphServiceClient(AuthenticationProvider authenticationProvider, OkHttpClient httpClient) {
        this(new BaseGraphRequestAdapter(authenticationProvider, null, httpClient));
    }

    public RequestAdapter getRequestAdapter() {
        return requestAdapter;
    }
}
