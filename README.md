# Microsoft Graph API Plugin

## Introduction

This API plugin provides a minimal [Microsoft Graph](https://learn.microsoft.com/en-us/graph/overview)
(Entra ID) Java client to other Jenkins plugins, such as the
[Microsoft Entra ID plugin](https://github.com/jenkinsci/azure-ad-plugin).
It is not for direct use by end users.

The official `com.microsoft.graph:microsoft-graph` v6 SDK covers the entire Graph API surface and
weighs ~50MB. Following
[Microsoft's guidance for size-sensitive applications](https://learn.microsoft.com/en-us/graph/sdks/generate-with-kiota),
this plugin instead generates a client with [Kiota](https://learn.microsoft.com/en-us/openapi/kiota/)
at build time, covering only the endpoints Jenkins plugins actually use:

- `GET /users` (including `$search`/`$filter`/`$select`/`$orderby` advanced queries)
- `GET /users/{id}`
- `GET /users/{id}/transitiveMemberOf`
- `GET /users/{id}/photos/{size}` and `GET /users/{id}/photos/{size}/$value`
- `GET /groups` and `GET /groups/{id}`

The generated code uses the same conventions as the official SDK, so consuming code is a
near-drop-in migration (`com.microsoft.graph.models.User` →
`io.jenkins.plugins.microsoftgraph.models.User`, etc.). The plugin also bundles
`microsoft-graph-core` and the Microsoft Kiota Java runtime; `azure-identity`, OkHttp, and Gson are
provided by the `azure-sdk`, `okhttp-api`, and `gson-api` plugins.

Before generation, the OpenAPI description is trimmed by
[`src/build/trim-openapi-spec.groovy`](src/build/trim-openapi-spec.groovy): navigation properties
(only returned on `$expand`, which this client never issues) are removed, and discriminator
mappings are pruned to the `@odata.type` values in the `graph.discriminator.allowlist` property.
Without this, the base `microsoft.graph.entity` schema's discriminator mapping pulls all ~1100
Graph entity types into the generated client (~3000 files); with it, ~150 files are generated.
Responses with a trimmed `@odata.type` still deserialize as the declared base type.

Need an endpoint that isn't generated? Open a pull request adding it to the `includePath` list in
[`pom.xml`](pom.xml) — regeneration happens automatically on the next build. If the new endpoint
relies on `$expand` or on polymorphic deserialization of additional types, extend
`graph.discriminator.allowlist` in the same pull request.

If it adds lots of new endpoints, we will need to convert this to a multi-module build and publish separate artifacts for each API surface. For now, the plugin is intentionally minimal to keep the
artifact size small and avoid unnecessary dependencies in plugins that don't need the full Graph API.

## Versioning and releases

The plugin version is `1.<metadata build>` where `<metadata build>` is the build number of the
[microsoftgraph/msgraph-metadata](https://github.com/microsoftgraph/msgraph-metadata) OpenAPI
description (`openapi/v1.0/openapi.yaml`) the client was generated from, pinned by commit SHA in
`pom.xml` for reproducible builds.

A [scheduled workflow](.github/workflows/update-graph-metadata.yaml) opens a pull request when a
newer Graph OpenAPI description is published; merging it triggers an automated release via
[JEP-229 continuous delivery](https://www.jenkins.io/doc/developer/publishing/releasing-cd/).

## Consuming the client

```xml
<dependency>
  <groupId>io.jenkins.plugins</groupId>
  <artifactId>microsoft-graph-api</artifactId>
</dependency>
```

```java
import com.microsoft.graph.core.authentication.AzureIdentityAuthenticationProvider;
import com.microsoft.graph.core.requests.GraphClientFactory;
import io.jenkins.plugins.microsoftgraph.GraphServiceClient;

var authProvider = new AzureIdentityAuthenticationProvider(
        tokenCredential, new String[] {"graph.microsoft.com"}, "https://graph.microsoft.com/.default");
var client = new GraphServiceClient(authProvider, GraphClientFactory.create().build());
// sovereign clouds:
// client.getRequestAdapter().setBaseUrl("https://graph.microsoft.us/v1.0");

var user = client.users().byUserId(userId).get();
```

## Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
