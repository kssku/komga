package org.gotson.komga.infrastructure.configuration

import jakarta.annotation.PostConstruct
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import org.sqlite.SQLiteConfig.JournalMode
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

@Component
@ConfigurationProperties(prefix = "komga")
@Validated
class KomgaProperties {
  @PostConstruct
  private fun makeDirs() {
    try {
      Path(database.file).parent.createDirectories()
      Path(tasksDb.file).parent.createDirectories()
    } catch (_: Exception) {
    }
  }

  @Positive
  var pageHashing: Int = 3

  /**
   * Thumbnail generation mode.
   *
   * CUSTOM FORK: on remote/FUSE mounts (CloudDrive2 over 115), generating a thumbnail
   * on ingest reads the archive contents (to extract the first page), which is exactly
   * what the zero-I/O scan path avoids. `lazy` (default) skips ingest-time generation,
   * so a full-library scan never opens an archive. Thumbnails then come either from
   * external sources or on-demand generation.
   *
   * - `lazy` (default): do NOT generate thumbnails on ingest
   * - `auto`: upstream behaviour, generate on ingest
   *
   * Set via environment variable `KOMGA_THUMBNAIL_MODE`.
   */
  var thumbnailMode: ThumbnailMode = ThumbnailMode.LAZY

  enum class ThumbnailMode {
    LAZY,
    AUTO,
  }

  @Positive
  var epubDivinaLetterCountThreshold: Int = 15

  var oauth2AccountCreation: Boolean = false

  var oidcEmailVerification: Boolean = true

  var database = Database()

  var tasksDb = Database()

  var cors = Cors()

  var lucene = Lucene()

  var configDir: String? = null

  var kobo = Kobo()

  val fonts = Fonts()

  class Cors {
    var allowedOrigins: List<String> = emptyList()
  }

  class Database {
    @get:NotBlank
    var file: String = ""

    @get:Positive
    var batchChunkSize: Int = 1000

    @get:Positive
    var poolSize: Int? = null

    @get:Positive
    var maxPoolSize: Int = 1

    var journalMode: JournalMode? = JournalMode.WAL

    @DurationUnit(ChronoUnit.SECONDS)
    var busyTimeout: Duration? = null

    var pragmas: Map<String, String> = emptyMap()

    var checkLocalFilesystem: Boolean = true
  }

  class Fonts {
    @get:NotBlank
    var dataDirectory: String = ""
  }

  class Lucene {
    @get:NotBlank
    var dataDirectory: String = ""

    var indexAnalyzer = IndexAnalyzer()

    @DurationUnit(ChronoUnit.SECONDS)
    var commitDelay: Duration = Duration.ofSeconds(2)

    class IndexAnalyzer {
      @get:Positive
      var minGram: Int = 3

      @get:Positive
      var maxGram: Int = 10

      var preserveOriginal: Boolean = true
    }
  }

  class Kobo {
    @get:Positive
    var syncItemLimit: Int = 100

    var kepubifyPath: String? = null
  }
}
