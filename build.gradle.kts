import com.fasterxml.jackson.dataformat.xml.XmlMapper
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.kcra.takenaka.core.*
import me.kcra.takenaka.core.mapping.MappingsMap
import me.kcra.takenaka.core.mapping.MutableMappingsMap
import me.kcra.takenaka.core.mapping.adapter.*
import me.kcra.takenaka.core.mapping.analysis.impl.AnalysisOptions
import me.kcra.takenaka.core.mapping.analysis.impl.MappingAnalyzerImpl
import me.kcra.takenaka.core.mapping.ancestry.ConstructorComputationMode
import me.kcra.takenaka.core.mapping.ancestry.impl.collectNamespaceIds
import me.kcra.takenaka.core.mapping.ancestry.impl.computeIndices
import me.kcra.takenaka.core.mapping.resolve.impl.*
import me.kcra.takenaka.core.util.objectMapper
import me.kcra.takenaka.generator.common.provider.impl.*
import me.kcra.takenaka.generator.web.WebGenerator
import me.kcra.takenaka.generator.web.buildWebConfig
import me.kcra.takenaka.generator.web.modularClassSearchIndexOf
import me.kcra.takenaka.generator.web.transformers.CSSInliningTransformer
import me.kcra.takenaka.generator.web.transformers.MinifyingTransformer
import net.fabricmc.mappingio.format.Tiny2Writer
import net.fabricmc.mappingio.tree.MappingTree
import kotlin.io.path.writeText
import kotlin.io.path.writer

buildscript {
    repositories {
        mavenCentral()
        maven("https://repo.screamingsandals.org/public")
    }

    dependencies {
        classpath(libs.generator.web)
    }
}

plugins {
    id("maven-publish")
}

group = "me.kcra.takenaka" // change me
// format: <oldest version>+<newest version>[-SNAPSHOT]
// this is included in META-INF/MANIFEST.MF under Implementation-Version
// be nice to people who use the bundles and don't change the format
version = "1.8.8+1.20.6" // change me

/**
 * A three-way choice of mappings.
 */
enum class PlatformTristate(val wantsClient: Boolean, val wantsServer: Boolean) {
    CLIENT_SERVER(true, true),
    CLIENT(true, false),
    SERVER(false, true)
}

val platform = PlatformTristate.CLIENT_SERVER // change me

/**
 * The root cache workspace.
 */
val cacheWorkspace by lazy {
    compositeWorkspace {
        rootDirectory(layout.buildDirectory.dir("takenaka/cache").get().asFile)
    }
}

/**
 * The shared cache workspace, mainly for manifests and maven-metadata.xml files.
 */
val sharedCacheWorkspace by lazy {
    cacheWorkspace.createWorkspace {
        name = "shared"
    }
}

/**
 * The mapping cache workspace.
 */
val mappingCacheWorkspace by lazy {
    cacheWorkspace.createCompositeWorkspace {
        name = "mappings"
    }
}

/**
 * The root web output workspace.
 */
val webWorkspace by lazy {
    compositeWorkspace {
        rootDirectory(layout.buildDirectory.dir("takenaka/web").get().asFile)
    }
}

/**
 * The root bundle output workspace.
 */
val bundleWorkspace by lazy {
    compositeWorkspace {
        rootDirectory(layout.buildDirectory.dir("takenaka/bundle").get().asFile)
    }
}

val objectMapper = objectMapper()
val xmlMapper = XmlMapper()

val manifest = objectMapper.versionManifest()
val yarnProvider = YarnMetadataProvider(sharedCacheWorkspace, xmlMapper)
val mappingConfig = buildMappingConfig {
    version(
        manifest
            .range("1.8.8", "1.20.6") { // change me
                // exclude 1.20.1 and 1.20.5 - hotfix versions                
                // exclude 1.16 and 1.10.1, they don't have most mappings and are basically not used at all
                // exclude 1.8.9, client-only update - no Spigot mappings, no thank you
                // exclude 1.9.1 and 1.9.3 - no mappings at all
                exclude("1.16", "1.10.1", "1.8.9", "1.9.1", "1.9.3", "1.20.1", "1.20.5")

                // include only releases, no snapshots
                includeTypes(Version.Type.RELEASE)
            }
            .map(Version::id)
    )
    workspace(mappingCacheWorkspace)

    // remove Searge's ID namespace, it's not necessary
    intercept { v ->
        NamespaceFilter(v, "searge_id")
    }
    // remove static initializers, not needed in the documentation
    intercept(::StaticInitializerFilter)
    // remove overrides of java/lang/Object, they are implicit
    intercept(::ObjectOverrideFilter)
    // remove obfuscated method parameter names, they are a filler from Searge
    intercept(::MethodArgSourceFilter)

    contributors { versionWorkspace ->
        val mojangProvider = MojangManifestAttributeProvider(versionWorkspace, objectMapper)
        val spigotProvider = SpigotManifestProvider(versionWorkspace, objectMapper)

        buildList {
            if (platform.wantsServer) {
                add(VanillaServerMappingContributor(versionWorkspace, mojangProvider))
                add(MojangServerMappingResolver(versionWorkspace, mojangProvider))
            }
            if (platform.wantsClient) {
                add(VanillaClientMappingContributor(versionWorkspace, mojangProvider))
                add(MojangClientMappingResolver(versionWorkspace, mojangProvider))
            }

            add(IntermediaryMappingResolver(versionWorkspace, sharedCacheWorkspace))
            add(YarnMappingResolver(versionWorkspace, yarnProvider))
            add(SeargeMappingResolver(versionWorkspace, sharedCacheWorkspace))

            // Spigot resolvers have to be last
            if (platform.wantsServer) {
                val link = LegacySpigotMappingPrepender.Link()

                add(
                    // 1.16.5 mappings have been republished with proper packages, even though the reobfuscated JAR does not have those
                    // See: https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/commits/80d35549ec67b87a0cdf0d897abbe826ba34ac27
                    link.createPrependingContributor(
                        SpigotClassMappingResolver(versionWorkspace, xmlMapper, spigotProvider),
                        prependEverything = versionWorkspace.version.id == "1.16.5"
                    )
                )
                add(link.createPrependingContributor(SpigotMemberMappingResolver(versionWorkspace, xmlMapper, spigotProvider)))
            }
        }
    }

    joinedOutputPath { workspace ->
        val fileName = when {
            platform.wantsClient && platform.wantsServer -> "client+server.tiny"
            platform.wantsClient -> "client.tiny"
            else -> "server.tiny"
        }

        workspace[fileName]
    }
}

val mappingProvider = ResolvingMappingProvider(mappingConfig, manifest, xmlMapper)
val analyzer = MappingAnalyzerImpl(
    AnalysisOptions(
        innerClassNameCompletionCandidates = setOf("spigot"),
        inheritanceAdditionalNamespaces = setOf("searge") // mojang could be here too for maximal parity, but that's in exchange for a little bit of performance
    )
)

val ancestryIndexNs = "takenaka_node"
val ancestryNamespaces = listOf("mojang", "spigot", "searge", "intermediary")

val ancestryProvider = CachedAncestryProvider(SimpleAncestryProvider(null, ancestryNamespaces))

val resolveMappings by tasks.registering {
    group = "takenaka"
    description = "Resolves basic mappings for Mojang-based server development on all defined versions."

    doLast {
        this.extra["mappings"] = runBlocking {
            mappingProvider.get(analyzer)
                .apply {
                    analyzer.acceptResolutions()

                    // add ancestry indices
                    runBlocking {
                        val tree = ancestryProvider.klass<_, MappingTree.ClassMapping>(this@apply)

                        val namespaceIds = tree.collectNamespaceIds(ancestryIndexNs)
                        launch(Dispatchers.Default + CoroutineName("klass-coro")) {
                            tree.computeIndices(namespaceIds)
                        }

                        tree.forEach { node ->
                            launch(Dispatchers.Default + CoroutineName("field-coro")) {
                                ancestryProvider.field<_, _, MappingTree.FieldMapping>(node).computeIndices(namespaceIds)
                            }
                            launch(Dispatchers.Default + CoroutineName("method-coro")) {
                                ancestryProvider.method<_, _, MappingTree.MethodMapping>(node, constructorMode = ConstructorComputationMode.INCLUDE).computeIndices(namespaceIds)
                            }
                        }
                    }
                }
        }
    }

    // make bundleable mappings
    doLast {
        @Suppress("UNCHECKED_CAST")
        val mappings = this.extra["mappings"] as MappingsMap

        mappings.forEach { (version, tree) ->
            Tiny2Writer(bundleWorkspace["${version.id}.tiny"].writer(), false)
                .use { w -> tree.accept(MissingDescriptorFilter(w)) }
        }
    }
}

val clean by tasks.registering {
    group = "takenaka"
    description = "Removes all build artifacts."

    doLast {
        layout.buildDirectory.get().asFile.deleteRecursively()
    }
}

val createBundle by tasks.registering(Jar::class) {
    group = "takenaka"
    description = "Creates a JAR bundle of mappings for all defined versions."

    dependsOn(resolveMappings)

    from(bundleWorkspace.rootDirectory)
    archiveBaseName.set("bundle") // overridden by the Maven publication, doesn't matter
    destinationDirectory.set(project.layout.buildDirectory.dir("takenaka"))

    manifest {
        attributes(mapOf(
            "Implementation-Version" to project.version
        ))
    }
}

val webConfig = buildWebConfig {
    val chosenMappings = when {
        platform.wantsClient && platform.wantsServer -> "client- and server-side"
        platform.wantsClient -> "client-side"
        else -> "server-side"
    }

    welcomeMessage(
        """
            <h1>Welcome to the browser for Minecraft: Java Edition $chosenMappings mappings!</h1>
            <br/>
            <p>
                You can move through this site by following links to specific versions/packages/classes/...
                or use the nifty search field in the top right corner (appears when in a versioned page!).
            </p>
            <br/>
            <p>
                It is possible that there are errors in mappings displayed here, but we've tried to make them as close as possible to the runtime naming.<br/>
                If you run into such an error, please report it at <a href="https://github.com/zlataovce/takenaka/issues/new">the issue tracker</a>!
            </p>
            <br/>
            <strong>NOTE: This build of the site excludes synthetic members (generated by the compiler, i.e. not in the source code).</strong>
        """.trimIndent()
    )

    transformer(CSSInliningTransformer("cdn.jsdelivr.net"))
    transformer(MinifyingTransformer())
    index(objectMapper.modularClassSearchIndexOf("https://docs.oracle.com/en/java/javase/21/docs/api"))

    replaceCraftBukkitVersions("spigot")
    friendlyNamespaces("mojang", "spigot", "yarn", "searge", "intermediary", "source")
    namespace("mojang", "Mojang", "#4D7C0F", AbstractMojangMappingResolver.META_LICENSE)
    namespace("spigot", "Spigot", "#CA8A04", AbstractSpigotMappingResolver.META_LICENSE)
    namespace("yarn", "Yarn", "#626262", YarnMappingResolver.META_LICENSE)
    namespace("searge", "Searge", "#B91C1C", SeargeMappingResolver.META_LICENSE)
    namespace("intermediary", "Intermediary", "#0369A1", IntermediaryMappingResolver.META_LICENSE)
    namespace("source", "Obfuscated", "#581C87")
}

val generator = WebGenerator(webWorkspace, webConfig)
val buildWeb by tasks.registering {
    group = "takenaka"
    description = "Builds a web documentation site for mappings of all defined versions."

    dependsOn(resolveMappings)
    doLast {
        runBlocking {
            @Suppress("UNCHECKED_CAST")
            generator.generate(
                SimpleMappingProvider(resolveMappings.get().extra["mappings"] as MutableMappingsMap),
                ancestryProvider
            )
        }
    }
    doLast {
        webWorkspace[".nojekyll"].writeText("")
        webWorkspace["CNAME"].writeText("mappings.cephx.dev") // change me, remove if you want to build for a *.github.io domain
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenBundle") {
            artifact(createBundle)
            pom {
                name.set("mappings")
                description.set("A mapping bundle with a basic set of mappings for Mojang-based server and client development.")
                url.set("https://github.com/zlataovce/mappings") // change me
                developers {
                    developer {
                        id.set("zlataovce")
                        name.set("Matouš Kučera")
                        email.set("mk@kcra.me")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/zlataovce/mappings.git") // change me
                    developerConnection.set("scm:git:ssh://github.com/zlataovce/mappings.git") // change me
                    url.set("https://github.com/zlataovce/mappings/tree/master") // change me
                }
            }
        }
    }

    repositories {
        maven {
            url = uri(
                if ((project.version as String).endsWith("-SNAPSHOT")) {
                    "https://repo.screamingsandals.org/snapshots" // change me
                } else {
                    "https://repo.screamingsandals.org/releases" // change me
                }
            )
            credentials {
                // make sure to add the `REPO_USERNAME` and `REPO_PASSWORD` secrets to the repository
                username = System.getenv("REPO_USERNAME")
                password = System.getenv("REPO_PASSWORD")
            }
        }
    }
}
