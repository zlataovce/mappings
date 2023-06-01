import com.fasterxml.jackson.dataformat.xml.XmlMapper
import kotlinx.coroutines.runBlocking
import me.kcra.takenaka.core.*
import me.kcra.takenaka.core.mapping.MappingsMap
import me.kcra.takenaka.core.mapping.WrappingContributor
import me.kcra.takenaka.core.mapping.adapter.*
import me.kcra.takenaka.core.mapping.analysis.impl.AnalysisOptions
import me.kcra.takenaka.core.mapping.analysis.impl.MappingAnalyzerImpl
import me.kcra.takenaka.core.mapping.resolve.impl.*
import me.kcra.takenaka.core.util.objectMapper
import me.kcra.takenaka.generator.common.ResolvingMappingProvider
import me.kcra.takenaka.generator.common.buildMappingConfig
import me.kcra.takenaka.generator.web.JDK_17_BASE_URL
import me.kcra.takenaka.generator.web.WebGenerator
import me.kcra.takenaka.generator.web.buildWebConfig
import me.kcra.takenaka.generator.web.modularClassSearchIndexOf
import me.kcra.takenaka.generator.web.transformers.Minifier
import net.fabricmc.mappingio.format.Tiny2Writer
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
version = "1.8.8+1.19.4-SNAPSHOT" // change me

/**
 * The root cache workspace.
 */
val cacheWorkspace by lazy {
    compositeWorkspace {
        rootDirectory(project.buildDir.resolve("takenaka/cache"))
        options(DefaultWorkspaceOptions.RELAXED_CACHE)
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
        rootDirectory(project.buildDir.resolve("takenaka/web"))
        options(DefaultWorkspaceOptions.RELAXED_CACHE)
    }
}

/**
 * The root bundle output workspace.
 */
val bundleWorkspace by lazy {
    compositeWorkspace {
        rootDirectory(project.buildDir.resolve("takenaka/bundle"))
        options(DefaultWorkspaceOptions.RELAXED_CACHE)
    }
}

val objectMapper = objectMapper()
val xmlMapper = XmlMapper()

val manifest = objectMapper.versionManifest()
fun buildVersions(older: String, newer: String? = null, block: VersionRangeBuilder.() -> Unit): List<String> =
    VersionRangeBuilder(manifest, older, newer).apply(block).toVersionList().map(Version::id)

val yarnProvider = YarnMetadataProvider(sharedCacheWorkspace, xmlMapper)
val mappingConfig = buildMappingConfig {
    version(buildVersions("1.8.8", "1.19.4") { // change me
        // exclude 1.16 and 1.10.1, they don't have most mappings and are basically not used at all
        // exclude 1.8.9, client-only update - no Spigot mappings, no thank you
        // exclude 1.9.1 and 1.9.3 - no mappings at all
        exclude("1.16", "1.10.1", "1.8.9", "1.9.1", "1.9.3")

        // include only releases, no snapshots
        includeTypes(Version.Type.RELEASE)
    })
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

        val prependedClasses = mutableListOf<String>()

        listOf(
            VanillaMappingContributor(versionWorkspace, mojangProvider),
            MojangServerMappingResolver(versionWorkspace, mojangProvider),
            IntermediaryMappingResolver(versionWorkspace, sharedCacheWorkspace),
            YarnMappingResolver(versionWorkspace, yarnProvider),
            SeargeMappingResolver(versionWorkspace, sharedCacheWorkspace),
            WrappingContributor(SpigotClassMappingResolver(versionWorkspace, xmlMapper, spigotProvider)) {
                // 1.16.5 mappings have been republished with proper packages, even though the reobfuscated JAR does not have those
                // See: https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/commits/80d35549ec67b87a0cdf0d897abbe826ba34ac27
                LegacySpigotMappingPrepender(it, prependedClasses = prependedClasses, prependEverything = versionWorkspace.version.id == "1.16.5")
            },
            WrappingContributor(SpigotMemberMappingResolver(versionWorkspace, xmlMapper, spigotProvider)) {
                LegacySpigotMappingPrepender(it, prependedClasses = prependedClasses)
            }
        )
    }
}

val mappingProvider = ResolvingMappingProvider(mappingConfig, manifest, xmlMapper)
val analyzer = MappingAnalyzerImpl(
    AnalysisOptions(
        innerClassNameCompletionCandidates = setOf("spigot"),
        inheritanceAdditionalNamespaces = setOf("searge") // mojang could be here too for maximal parity, but that's in exchange for a little bit of performance
    )
)

val resolveMappings by tasks.registering {
    group = "takenaka"
    description = "Resolves basic mappings for Mojang-based server development on all defined versions."

    doLast {
        this.extra["mappings"] = runBlocking {
            mappingProvider.get(analyzer)
                .apply { analyzer.acceptResolutions() }
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
        project.buildDir.deleteRecursively()
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
    transformer(Minifier())
    index(objectMapper.modularClassSearchIndexOf(JDK_17_BASE_URL))

    replaceCraftBukkitVersions("spigot")
    friendlyNamespaces("mojang", "spigot", "yarn", "searge", "intermediary", "source")
    namespace("mojang", "Mojang", "#4D7C0F", MojangServerMappingResolver.META_LICENSE)
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
            generator.generate(resolveMappings.get().extra["mappings"] as MappingsMap)
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
                description.set("A mapping bundle with a basic set of mappings for Mojang-based server development.")
                url.set("https://github.com/zlataovce/mappings")
                developers {
                    developer {
                        id.set("zlataovce")
                        name.set("Matouš Kučera")
                        email.set("mk@kcra.me")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/zlataovce/mappings.git")
                    developerConnection.set("scm:git:ssh://github.com/zlataovce/mappings.git")
                    url.set("https://github.com/zlataovce/mappings/tree/master")
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
                username = System.getenv("REPO_USERNAME")
                password = System.getenv("REPO_PASSWORD")
            }
        }
    }
}
