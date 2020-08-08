package org.gotson.komga.infrastructure.mediacontainer

import mu.KotlinLogging
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.FilenameUtils
import org.gotson.komga.domain.model.MediaContainerEntry
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

@Service
class EpubExtractor(
  private val zipExtractor: ZipExtractor,
  private val contentDetector: ContentDetector,
  private val imageAnalyzer: ImageAnalyzer
) : MediaContainerExtractor {

  override fun mediaTypes(): List<String> = listOf("application/epub+zip")

  override fun getEntries(path: Path): List<MediaContainerEntry> {
    ZipFile(path.toFile()).use { zip ->
      try {
        val opfFile = getPackagePath(zip)

        val opfDoc = zip.getInputStream(zip.getEntry(opfFile)).use { Jsoup.parse(it, null, "") }
        val opfDir = Paths.get(opfFile).parent

        val manifest = opfDoc.select("manifest > item")
          .associate { it.attr("id") to ManifestItem(it.attr("id"), it.attr("href"), it.attr("media-type")) }

        val pages = opfDoc.select("spine > itemref").map { it.attr("idref") }
          .mapNotNull { manifest[it] }
          .map { it.href }

        val images = pages
          .map { opfDir?.resolve(it)?.normalize() ?: Paths.get(it) }
          .flatMap { pagePath ->
            val doc = zip.getInputStream(zip.getEntry(pagePath.separatorsToUnix())).use { Jsoup.parse(it, null, "") }
            doc.getElementsByTag("img")
              .map { it.attr("src") } //get the src, which can be a relative path
              .map { pagePath.parentOrEmpty().resolve(it).normalize() } //resolve it against the page folder
          }

        return images.map { image ->
          val name = image.separatorsToUnix()
          val mediaType = manifest.values.first {
            it.href == (opfDir?.relativize(image) ?: image).separatorsToUnix()
          }.mediaType
          val dimension = if (contentDetector.isImage(mediaType))
            imageAnalyzer.getDimension(zip.getInputStream(zip.getEntry(name)))
          else
            null
          MediaContainerEntry(name = name, mediaType = mediaType, dimension = dimension)
        }
      } catch (e: Exception) {
        logger.error(e) { "File is not a proper Epub, treating it as a zip file" }
        return zipExtractor.getEntries(path)
      }
    }
  }

  override fun getEntryStream(path: Path, entryName: String): ByteArray {
    return zipExtractor.getEntryStream(path, entryName)
  }

  private fun getPackagePath(zip: ZipFile): String =
    zip.getEntry("META-INF/container.xml").let { entry ->
      val container = zip.getInputStream(entry).use { Jsoup.parse(it, null, "") }
      container.getElementsByTag("rootfile").first().attr("full-path")
    }

  fun getPackageFile(path: Path): String? =
    ZipFile(path.toFile()).use { zip ->
      try {
        zip.getInputStream(zip.getEntry(getPackagePath(zip))).reader().use { it.readText() }
      } catch (e: Exception) {
        null
      }
    }

  fun Path.parentOrEmpty(): Path = parent ?: Paths.get("")

  fun Path.separatorsToUnix(): String = FilenameUtils.separatorsToUnix(this.toString())

  private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String
  )
}
