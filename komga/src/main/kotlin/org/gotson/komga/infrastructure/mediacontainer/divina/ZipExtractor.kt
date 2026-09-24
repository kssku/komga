package org.gotson.komga.infrastructure.mediacontainer.divina

import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.gotson.komga.domain.model.MediaContainerEntry
import org.gotson.komga.domain.model.MediaType
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.gotson.komga.infrastructure.util.getZipEntryBytes
import org.gotson.komga.infrastructure.util.use
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class ZipExtractor(
  private val contentDetector: ContentDetector,
) : DivinaExtractor {
  private val natSortComparator: Comparator<String> = CaseInsensitiveSimpleNaturalComparator.getInstance()

  override fun mediaTypes(): List<String> = listOf(MediaType.ZIP.type)

  /**
   * CUSTOM FORK: this implementation performs ZERO file reads per entry.
   *
   * Upstream opened every archive entry (`zip.getInputStream`) to sniff its magic bytes
   * and, when asked, to read the image header for its dimension. On the CloudDrive2/115
   * FUSE mount each of those opens costs hundreds of milliseconds, so a 200-page book
   * meant 200 network round-trips just to build the page list.
   *
   * The page list is now derived entirely from metadata already present in the ZIP
   * central directory:
   *  - mediaType from the entry NAME (in-memory Tika MIME lookup, no I/O)
   *  - fileSize  from `entry.size`
   *  - dimension is NOT read, and is always null
   *
   * `analyzeDimensions` is intentionally not consumed: honouring it would require
   * opening each entry, which is precisely what this fork avoids. Dimension is a
   * display hint only; see ContentDetector.detectMediaTypeByName for the type trade-off.
   */
  override fun getEntries(
    path: Path,
    analyzeDimensions: Boolean,
  ): List<MediaContainerEntry> =
    ZipFile.builder().setPath(path).use { zip ->
      val entries = zip.entries.toList().filter { !it.isDirectory }

      // CUSTOM FORK: encryption is flagged in the central directory (bit 0 of the general
      // purpose flags), so it can be detected with zero I/O. Upstream discovered encrypted
      // archives only by failing to decrypt entry contents while sniffing magic bytes.
      // Now that entries are typed by NAME, that failure never happens, so an encrypted
      // archive would silently be reported as READY and only break at read time. Fail here
      // instead, with the same ERROR status upstream produced (ERR_1008 via the generic
      // exception handler in BookAnalyzer).
      if (entries.any { it.generalPurposeBit.usesEncryption() }) {
        throw IllegalStateException("Encrypted ZIP archives are not supported")
      }

      entries
        .map { entry ->
          val mediaType = contentDetector.detectMediaTypeByName(entry.name)
          val fileSize = if (entry.size == ArchiveEntry.SIZE_UNKNOWN) null else entry.size
          MediaContainerEntry(name = entry.name, mediaType = mediaType, dimension = null, fileSize = fileSize)
        }.sortedWith(compareBy(natSortComparator) { it.name })
    }

  override fun getEntryStream(
    path: Path,
    entryName: String,
  ): ByteArray = getZipEntryBytes(path, entryName)
}
