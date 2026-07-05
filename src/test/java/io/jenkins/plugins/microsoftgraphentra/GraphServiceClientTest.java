package io.jenkins.plugins.microsoftgraph;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.graph.core.requests.GraphClientFactory;
import com.microsoft.kiota.ApiException;
import com.microsoft.kiota.authentication.AnonymousAuthenticationProvider;
import io.jenkins.plugins.microsoftgraph.models.DirectoryObject;
import io.jenkins.plugins.microsoftgraph.models.DirectoryObjectCollectionResponse;
import io.jenkins.plugins.microsoftgraph.models.Group;
import io.jenkins.plugins.microsoftgraph.models.GroupCollectionResponse;
import io.jenkins.plugins.microsoftgraph.models.ProfilePhoto;
import io.jenkins.plugins.microsoftgraph.models.User;
import io.jenkins.plugins.microsoftgraph.models.UserCollectionResponse;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphServiceClientTest {

    private static WireMockServer wireMock;
    private static GraphServiceClient client;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new GraphServiceClient(
                new AnonymousAuthenticationProvider(),
                GraphClientFactory.create().build());
        // Same mechanism consumers use to point the client at sovereign clouds
        client.getRequestAdapter().setBaseUrl(wireMock.baseUrl() + "/v1.0");
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void searchUsersWithAdvancedQueryParameters() {
        wireMock.stubFor(get(urlPathEqualTo("/v1.0/users")).willReturn(okJson("""
                        {
                          "value": [
                            {"id": "user-1", "displayName": "Tim", "userPrincipalName": "tim@example.com", "mail": "tim@example.com"}
                          ]
                        }
                        """)));

        UserCollectionResponse response = client.users().get(requestConfiguration -> {
            requestConfiguration.queryParameters.search = "\"displayName:tim\" OR \"userPrincipalName:tim\"";
            requestConfiguration.queryParameters.select = new String[] {"id", "displayName"};
            requestConfiguration.queryParameters.orderby = new String[] {"displayName"};
            requestConfiguration.headers.add("ConsistencyLevel", "eventual");
        });

        List<User> users = response.getValue();
        assertEquals(1, users.size());
        assertEquals("user-1", users.get(0).getId());
        assertEquals("Tim", users.get(0).getDisplayName());
        assertEquals("tim@example.com", users.get(0).getUserPrincipalName());

        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1.0/users"))
                .withQueryParam("$search", equalTo("\"displayName:tim\" OR \"userPrincipalName:tim\""))
                .withQueryParam("$select", equalTo("id,displayName"))
                .withQueryParam("$orderby", equalTo("displayName"))
                .withHeader("ConsistencyLevel", equalTo("eventual")));
    }

    @Test
    void transitiveMemberOfDeserializesGroupsPolymorphicallyAndPages() {
        String nextLink = wireMock.baseUrl() + "/v1.0/users/user-1/transitiveMemberOf?$skiptoken=page2";
        wireMock.stubFor(get(urlPathEqualTo("/v1.0/users/user-1/transitiveMemberOf"))
                .willReturn(okJson("""
                        {
                          "@odata.nextLink": "%s",
                          "value": [
                            {"@odata.type": "#microsoft.graph.group", "id": "group-1", "displayName": "Some group"},
                            {"@odata.type": "#microsoft.graph.administrativeUnit", "id": "au-1", "displayName": "Not a group"}
                          ]
                        }
                        """.formatted(nextLink))));

        DirectoryObjectCollectionResponse page1 =
                client.users().byUserId("user-1").transitiveMemberOf().get();

        List<DirectoryObject> members = page1.getValue();
        assertEquals(2, members.size());
        Group group = assertInstanceOf(Group.class, members.get(0));
        assertEquals("group-1", group.getId());
        assertEquals("Some group", group.getDisplayName());
        assertFalse(members.get(1) instanceof Group);

        // Manual paging, as done by the Microsoft Entra ID plugin
        wireMock.stubFor(get(urlPathEqualTo("/v1.0/users/user-1/transitiveMemberOf"))
                .withQueryParam("$skiptoken", equalTo("page2"))
                .willReturn(okJson("""
                        {
                          "value": [
                            {"@odata.type": "#microsoft.graph.group", "id": "group-2", "displayName": "Second page group"}
                          ]
                        }
                        """)));

        assertNotNull(page1.getOdataNextLink());
        DirectoryObjectCollectionResponse page2 = client.users()
                .byUserId("user-1")
                .transitiveMemberOf()
                .withUrl(page1.getOdataNextLink())
                .get();
        assertEquals(1, page2.getValue().size());
        assertEquals("group-2", page2.getValue().get(0).getId());
    }

    @Test
    void userProfilePhotoMetadataAndBinaryContent() throws Exception {
        byte[] photoBytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        wireMock.stubFor(get(urlPathEqualTo("/v1.0/users/user-1/photos/48x48")).willReturn(okJson("""
                        {"id": "48x48", "height": 48, "width": 48, "@odata.mediaContentType": "image/jpeg"}
                        """)));
        wireMock.stubFor(get(urlPathEqualTo("/v1.0/users/user-1/photos/48x48/$value"))
                .willReturn(aResponse().withHeader("Content-Type", "image/jpeg").withBody(photoBytes)));

        ProfilePhoto photo = client.users()
                .byUserId("user-1")
                .photos()
                .byProfilePhotoId("48x48")
                .get();
        assertEquals(48, photo.getHeight());
        assertEquals("image/jpeg", photo.getAdditionalData().get("@odata.mediaContentType"));

        try (InputStream content = client.users()
                .byUserId("user-1")
                .photos()
                .byProfilePhotoId("48x48")
                .content()
                .get()) {
            assertArrayEquals(photoBytes, content.readAllBytes());
        }
    }

    @Test
    void groupLookupByFilter() {
        wireMock.stubFor(get(urlPathEqualTo("/v1.0/groups")).willReturn(okJson("""
                        {
                          "value": [
                            {"id": "group-1", "displayName": "Some group"}
                          ]
                        }
                        """)));

        GroupCollectionResponse response = client.groups().get(requestConfiguration -> {
            requestConfiguration.queryParameters.filter = "displayName eq 'Some group'";
            requestConfiguration.queryParameters.select = new String[] {"id", "displayName"};
        });

        assertEquals("group-1", response.getValue().get(0).getId());
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1.0/groups"))
                .withQueryParam("$filter", equalTo("displayName eq 'Some group'")));
    }

    @Test
    void notFoundSurfacesAsApiExceptionWithStatusCode() {
        wireMock.stubFor(get(urlPathEqualTo("/v1.0/users/missing"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error": {"code": "Request_ResourceNotFound", "message": "Resource 'missing' does not exist."}}
                                """)));

        ApiException exception = assertThrows(
                ApiException.class, () -> client.users().byUserId("missing").get());
        assertEquals(404, exception.getResponseStatusCode());
    }
}
