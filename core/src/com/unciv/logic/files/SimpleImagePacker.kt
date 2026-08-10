package com.unciv.logic.files

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.PixmapPacker
import com.unciv.utils.Log
import java.io.File

/**
 * UncivGC: Android 版模组图集打包器.
 *
 * 桌面版用 libgdx TexturePacker (依赖 java.awt, Android 没有); Android 用 gdx 核心的
 * [PixmapPacker] (纯像素操作, 无 AWT 依赖) 把模组 Images 文件夹打包成 game.png + game.atlas,
 * 格式与 TexturePacker 产物一致, ImageGetter 可直接加载.
 */
object SimpleImagePacker {

    private const val maxSize = 2048
    private val imageExtensions = listOf("png", "jpg", "jpeg")

    fun File.listTree(): Sequence<File> = when {
        this.isFile -> sequenceOf(this)
        this.isDirectory -> this.listFiles()?.asSequence()?.flatMap { it.listTree() } ?: emptySequence()
        else -> emptySequence()
    }

    /** atlas 比所有图片新则跳过 (增量打包) */
    fun packIfOutdated(input: String, output: String, packFileName: String) {
        val folder = File(input)
        val atlasFile = File(output, "$packFileName.atlas")
        if (atlasFile.exists() && File(output, "$packFileName.png").exists()) {
            val atlasModTime = atlasFile.lastModified()
            val outdated = folder.listTree().any {
                it.extension in imageExtensions && it.lastModified() > atlasModTime
            }
            if (!outdated) return
        }
        try {
            pack(folder, output, packFileName)
        } catch (e: Throwable) {
            Log.debug("Android 模组图集打包失败 (${folder.name}): ${e.message}")
        }
    }

    private fun pack(folder: File, output: String, packFileName: String) {
        val images = folder.listTree()
            .filter { it.extension in imageExtensions }
            .sortedByDescending { it.length() }
            .toList()
        if (images.isEmpty()) return

        val packer = PixmapPacker(maxSize, maxSize, Pixmap.Format.RGBA8888, 2, true)
        var skipped = 0
        for (img in images) {
            val pixmap: Pixmap
            try {
                pixmap = Pixmap(FileHandle(img.absolutePath))
            } catch (e: Exception) {
                skipped++
                continue
            }
            if (pixmap.width > maxSize || pixmap.height > maxSize) {
                pixmap.dispose()
                skipped++
                continue
            }
            val regionName = relativeName(img, folder)
            try {
                packer.pack(regionName, pixmap)
            } catch (e: Exception) {
                // 画布放不下等 — 跳过该图
                skipped++
            } finally {
                pixmap.dispose()
            }
        }

        val pages = packer.pages
        if (pages.isEmpty) {
            packer.dispose()
            return
        }
        // 只用第一页 (绝大多数模组一页足够; 超页的图放弃打包, 模组自带 atlas 的不受影响)
        val page = pages.first()
        val pagePixmap = page.pixmap
        if (pagePixmap.width == 0 || pagePixmap.height == 0) {
            packer.dispose()
            return
        }
        val pngFile = File(output, "$packFileName.png")
        PixmapIO.writePNG(FileHandle(pngFile.absolutePath), pagePixmap)

        // 生成 libgdx TextureAtlas 文本格式
        val sb = StringBuilder()
        sb.append("$packFileName.png\n")
        sb.append("size: ${pagePixmap.width}, ${pagePixmap.height}\n")
        sb.append("format: RGBA8888\n")
        sb.append("filter: Linear,Linear\n")
        sb.append("repeat: none\n")
        val rects = sortedMapOf<String, com.badlogic.gdx.graphics.g2d.PixmapPacker.PixmapPackerRectangle>()
        page.rects.forEach { entry -> rects[entry.key] = entry.value }
        for ((name, rect) in rects) {
            sb.append(name).append('\n')
            sb.append("  rotate: false\n")
            sb.append("  xy: ${rect.x.toInt()}, ${rect.y.toInt()}\n")
            sb.append("  size: ${rect.width.toInt()}, ${rect.height.toInt()}\n")
            sb.append("  orig: ${rect.width.toInt()}, ${rect.height.toInt()}\n")
            sb.append("  offset: 0, 0\n")
            sb.append("  index: -1\n")
        }
        File(output, "$packFileName.atlas").writeText(sb.toString())

        pagePixmap.dispose()
        packer.dispose()
        if (skipped > 0) Log.debug("Android 图集打包 (${folder.name}): $skipped 张图跳过 (超大/无法解码)")
    }

    /** 相对路径去扩展名, / 分隔 — 与 TexturePacker region 命名一致 */
    private fun relativeName(file: File, folder: File): String {
        var rel = file.absolutePath.removePrefix(folder.absolutePath).removePrefix("/")
        rel = rel.replace('\\', '/')
        val dot = rel.lastIndexOf('.')
        if (dot > 0) rel = rel.substring(0, dot)
        return rel
    }
}
