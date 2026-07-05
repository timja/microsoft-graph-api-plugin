/*
 * Downloads the Microsoft Graph OpenAPI description pinned by the graph.metadata.sha
 * property and trims it before Kiota generation:
 *
 * 1. Removes every schema property marked `x-ms-navigationProperty: true`. Navigation
 *    properties are only returned on $expand requests, which this client never issues,
 *    but they make Kiota generate the transitive closure of nearly every Microsoft
 *    Graph model.
 * 2. Prunes discriminator mappings to the @odata.type values listed in the
 *    graph.discriminator.allowlist property. The base `microsoft.graph.entity` schema
 *    maps all ~1100 entity types, which would otherwise all be generated. Responses
 *    with an unmapped @odata.type still deserialize, as the declared base type.
 *
 * Together these reduce the generated client from ~3000 files to ~150.
 */
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

String sha = project.properties['graph.metadata.sha']
String allowlistProp = (project.properties['graph.discriminator.allowlist'] ?: '').toString()
Set<String> allowlist = allowlistProp.split(',')*.trim().findAll { it } as Set
String allowlistKey = allowlist.toList().sort().join(',')

File specDir = new File(project.build.directory as String, "openapi-spec/${sha}")
specDir.mkdirs()
File rawSpec = new File(specDir, 'openapi.yaml')
File trimmedSpec = new File(specDir, 'openapi-trimmed.yaml')
File allowlistMarker = new File(specDir, 'openapi-trimmed.allowlist')

if (trimmedSpec.exists() && allowlistMarker.exists() && allowlistMarker.getText('UTF-8').trim() == allowlistKey) {
    println "Trimmed OpenAPI description is up to date: ${trimmedSpec}"
    return
}

if (!rawSpec.exists()) {
    URL url = new URI("https://raw.githubusercontent.com/microsoftgraph/msgraph-metadata/${sha}/openapi/v1.0/openapi.yaml").toURL()
    File partial = new File(specDir, 'openapi.yaml.part')
    int attempts = 0
    while (true) {
        attempts++
        try {
println "Downloading ${url}"
def conn = url.openConnection()
conn.connectTimeout = 30_000
conn.readTimeout = 300_000
conn.inputStream.withCloseable { input -> partial.withOutputStream { it << input } }
        } catch (IOException e) {
            partial.delete()
            if (attempts >= 3) {
                throw e
            }
            println "Download failed (${e.message}), retrying"
            Thread.sleep(5000L * attempts)
        }
    }
    Files.move(partial.toPath(), rawSpec.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

LoaderOptions loaderOptions = new LoaderOptions()
loaderOptions.codePointLimit = Integer.MAX_VALUE
loaderOptions.maxAliasesForCollections = Integer.MAX_VALUE

println "Parsing ${rawSpec} (${(rawSpec.length() / (1 << 20)).toLong()} MB)"
Map document = rawSpec.withInputStream { new Yaml(new SafeConstructor(loaderOptions)).load(it) }

int navigationPropertiesRemoved = 0
int discriminatorMappingsTrimmed = 0

Closure walk
walk = { node ->
    if (node instanceof Map) {
        def properties = node['properties']
        if (properties instanceof Map) {
def navigation = properties.findAll { it.value instanceof Map && it.value['x-ms-navigationProperty'] == true }
            navigation.keySet().each { properties.remove(it) }
            navigationPropertiesRemoved += navigation.size()
        }
        def discriminator = node['discriminator']
        if (discriminator instanceof Map && discriminator['mapping'] instanceof Map) {
            Map mapping = discriminator['mapping']
            Map kept = mapping.findAll { allowlist.contains(it.key) }
            if (kept.size() != mapping.size()) {
                discriminatorMappingsTrimmed++
                if (kept) {
                    discriminator['mapping'] = kept
                } else {
                    node.remove('discriminator')
                }
            }
        }
        node.values().each(walk)
    } else if (node instanceof List) {
        node.each(walk)
    }
}
walk(document.components?.schemas ?: [:])

DumperOptions dumperOptions = new DumperOptions()
dumperOptions.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
File partial = new File(specDir, 'openapi-trimmed.yaml.part')
partial.withWriter('UTF-8') { new Yaml(dumperOptions).dump(document, it) }
Files.move(partial.toPath(), trimmedSpec.toPath(), StandardCopyOption.REPLACE_EXISTING)

new File(specDir, 'openapi-trimmed.allowlist').withWriter('UTF-8') {
    it << (allowlist.toList().sort().join(','))
}
println "Trimmed OpenAPI description: removed ${navigationPropertiesRemoved} navigation properties, " +
        "trimmed ${discriminatorMappingsTrimmed} discriminator mappings -> ${trimmedSpec}"
