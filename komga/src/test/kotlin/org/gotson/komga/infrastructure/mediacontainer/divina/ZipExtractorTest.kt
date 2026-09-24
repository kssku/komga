package org.gotson.komga.infrastructure.mediacontainer.divina

import org.apache.tika.config.TikaConfig
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

class ZipExtractorTest {
  private val contentDetector = ContentDetector(TikaConfig())
  private val zipExtractor = ZipExtractor(contentDetector)

  @Test
  fun `given zip file when parsing for entries then returns all images`() {
    val fileResource = ClassPathResource("archives/zip.zip")

    val entries = zipExtractor.getEntries(fileResource.file.toPath(), true)

    assertThat(entries).hasSize(1)
    with(entries.first()) {
      assertThat(name).isEqualTo("komga.png")
      assertThat(mediaType).isEqualTo("image/png")
      // CUSTOM FORK: dimension is no longer read (zero I/O per entry), so it is always null.
      // The mediaType is resolved from the entry NAME instead of its content.
      assertThat(dimension).isNull()
      assertThat(fileSize).isEqualTo(3108)
    }
  }

  @Test
  fun `given zip file when parsing for entries without analyzing dimensions then returns all images without dimensions`() {
    val fileResource = ClassPathResource("archives/zip.zip")

    val entries = zipExtractor.getEntries(fileResource.file.toPath(), false)

    assertThat(entries).hasSize(1)
    with(entries.first()) {
      assertThat(name).isEqualTo("komga.png")
      assertThat(mediaType).isEqualTo("image/png")
      assertThat(dimension).isNull()
      assertThat(fileSize).isEqualTo(3108)
    }
  }
}