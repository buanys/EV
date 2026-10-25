package ev.buan.hostcore.dependency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dependency declarations are a wire contract with plugin authors (an APK metadata key and a sidecar
 * key), and the resolver trusts the list it is given, so these tests pin the key names and the
 * normalisation rules.
 */
class DependencyMetadataParserTest {

    @Test
    fun `APK 与 sidecar 的键名是插件契约`() {
        assertEquals("cordis.dependencies", DependencyMetadataParser.APK_METADATA_KEY)
        assertEquals("dependencies", DependencyMetadataParser.JAR_SIDECAR_KEY)
    }

    @Test
    fun `缺少声明表示不依赖任何插件`() {
        assertEquals(emptyList(), DependencyMetadataParser.normalize(null))
        assertEquals(emptyList(), DependencyMetadataParser.normalize(emptyList()))
    }

    @Test
    fun `空白项被丢弃且声明顺序保留`() {
        assertEquals(
            listOf("ev.buan.plugins.agent.api", "ev.buan.plugins.ping.api"),
            DependencyMetadataParser.normalize(
                listOf(" ev.buan.plugins.agent.api ", "", "   ", "ev.buan.plugins.ping.api"),
            ),
        )
    }

    @Test
    fun `重复声明去重且保留首次出现的位置`() {
        assertEquals(
            listOf("b", "a"),
            DependencyMetadataParser.normalize(listOf(" b ", "a", "b", " a ")),
        )
    }
}
