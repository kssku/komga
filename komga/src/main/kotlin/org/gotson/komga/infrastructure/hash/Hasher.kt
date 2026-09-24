package org.gotson.komga.infrastructure.hash

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.URL
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream
import kotlin.io.path.toPath

private val logger = KotlinLogging.logger {}

private const val DEFAULT_BUFFER_SIZE = 8192
private const val HASH_ALGORITHM = "SHA-1"

@Component
class Hasher {
  fun computeHash(path: Path): String {
    logger.debug { "Hashing: $path" }

    return computeHash(path.inputStream())
  }

  /**
   * Hash an arbitrary string.
   *
   * CUSTOM FORK (feat/sha1-relpath-hash): explicit UTF-8 encoding.
   * The upstream implementation used `string.byteInputStream()`, which relies on
   * the platform default charset. Filenames in this library are frequently
   * non-ASCII, so an implicit charset would make hashes depend on the JVM's
   * default encoding instead of the data.
   */
  fun computeHash(string: String): String = computeHash(string.toByteArray(Charsets.UTF_8).inputStream())

  /**
   * Hash the PATH of a book, relative to the library root's PARENT directory,
   * producing the same value as LANraragi's `compute_id`.
   *
   * CUSTOM FORK (feat/sha1-relpath-hash): this is the zero-I/O file identity used
   * for `BOOK.FILE_HASH`. On a remote/FUSE mount (CloudDrive2 over 115), the
   * upstream behaviour of reading the file contents made a full-library scan
   * infeasible. Hashing the path instead removes every per-book read.
   *
   * The hash is byte-identical to LANraragi's for the same file, so the two
   * systems can recognise each other's archives:
   *
   *   LRR   : SHA1( UTF-8( path relative to /home/koyomi/lanraragi/content ) )
   *   Komga : SHA1( UTF-8( path relative to <library root>/.. ) )
   *
   * Both yield e.g. `wnacg/<shard>/<file>.cbz`.
   *
   * The relative path deliberately KEEPS its leading directory (the `wnacg/`
   * mount name) because that is exactly what LANraragi hashes. Dropping it
   * would break the interop.
   *
   * KNOWN FRAGILITY: the shard directory names (`1-50000`, `300001-350000`) are
   * pure functions of the item id produced by the ingestion tooling. If that
   * tooling changes its sharding range, every hash changes. Upgrade path: strip
   * `^\d+-\d+$` segments before hashing, then re-run the migration.
   */
  fun computePathHash(relativePath: String): String = computeHash(relativePath)

  /**
   * Hash a book's path relative to [libraryRoot]'s PARENT directory.
   *
   * The parent directory is used deliberately: in this deployment the library root is
   * `.../comic/wnacg/`, so the parent-relative path becomes `wnacg/<shard>/<file>.cbz` —
   * which is exactly the string LANraragi hashes. Using the root itself would yield
   * `<shard>/<file>.cbz` and every hash would differ.
   *
   * This rule is deliberately kept in one place; it is the single most fragile part of
   * the interop and must not be duplicated across call sites.
   */
  fun computePathHash(path: Path, libraryRoot: URL): String {
    val root = libraryRoot.toURI().toPath().normalize()
    val base = root.parent ?: root
    val relative = base.relativize(path.normalize()).toString()

    return computePathHash(relative)
  }

  fun computeHash(stream: InputStream): String {
    val hash = MessageDigest.getInstance(HASH_ALGORITHM)

    stream.use {
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      var len: Int

      do {
        len = it.read(buffer)
        if (len >= 0) hash.update(buffer, 0, len)
      } while (len >= 0)
    }

    return hash.digest().toHexString()
  }

  @OptIn(ExperimentalUnsignedTypes::class)
  fun ByteArray.toHexString(): String =
    asUByteArray().joinToString("") {
      it.toString(16).padStart(2, '0')
    }
}
