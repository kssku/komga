package org.gotson.komga.infrastructure.mediacontainer

import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.name

@Service
class ContentDetector(
  private val tika: TikaConfig,
) {
  fun detectMediaType(path: Path): String {
    val metadata =
      Metadata().also {
        it[Metadata.TIKA_MIME_FILE] = path.name
      }

    return TikaInputStream.get(path).use {
      val mediaType = tika.detector.detect(it, metadata)
      mediaType.toString()
    }
  }

  /**
   * Detects the media type of the content of the stream.
   * The stream will not be closed.
   */
  fun detectMediaType(stream: InputStream): String = tika.detector.detect(stream, Metadata()).toString()

  /**
   * Detects the media type from the file NAME only, without opening or reading anything.
   *
   * CUSTOM FORK: on a remote/FUSE mount (CloudDrive2 over 115), opening every archive
   * entry to sniff its magic bytes is the dominant cost of a full-library scan. This
   * method resolves the type from the extension via the Tika MIME repository, which is
   * an in-memory lookup and performs NO I/O.
   *
   * TRADE-OFF: a file whose name does not match its content will be typed by its name.
   * For this deployment the archive contents are homogeneous (all pages are .jpg), so
   * the filename is a reliable type source. Callers that need a trustworthy type for
   * arbitrary content should keep using [detectMediaType].
   *
   * Returns null when the extension is unknown.
   */
  fun detectMediaTypeByName(fileName: String): String? =
    try {
      tika.mimeRepository.getMimeType(fileName)?.toString()
    } catch (e: Exception) {
      null
    }

  fun isImage(mediaType: String): Boolean = mediaType.startsWith("image/")

  fun mediaTypeToExtension(mediaType: String): String? =
    try {
      tika.mimeRepository.forName(mediaType).extension
    } catch (e: Exception) {
      null
    }
}
