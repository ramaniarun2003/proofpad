package com.mathnotes.solver.data

import java.io.File

/**
 * Deletes files the app wrote and no longer needs. Nothing else ever removed them, so every
 * capture left a multi-megabyte camera original in the cache and every abandoned or deleted note
 * left its images behind.
 *
 * What goes, and when:
 * - `cache/capture_*.jpg`, `cache/picked_*.jpg` (camera and picked originals): once older than
 *   [ORIGINAL_AGE]. A successful recognition deletes its own original straight away; this catches
 *   the ones from failed or abandoned captures.
 * - `files/images/` (preprocessed photos) that no note points at, older than [ORPHAN_AGE].
 * - `files/handwriting/` (setup wizard thumbnails) that no saved sample points at, older than [ORPHAN_AGE].
 * - `cache/exports/` (shared PDFs) older than [EXPORT_AGE].
 * - `files/debug/` (preprocessing stages, an opt-in setting) older than [DEBUG_AGE].
 *
 * Never touched: anything in [protect] (the capture on screen right now), anything a note or a
 * handwriting sample references, and anything outside those five places.
 */
object StorageJanitor {
    const val HOUR = 60L * 60 * 1000
    const val ORIGINAL_AGE = 1 * HOUR
    const val ORPHAN_AGE = 1 * HOUR
    const val EXPORT_AGE = 24 * HOUR
    const val DEBUG_AGE = 7 * 24 * HOUR

    data class Report(val files: Int = 0, val bytes: Long = 0) {
        val megabytes: Double get() = bytes / (1024.0 * 1024.0)
    }

    /**
     * @param noteImages every `imagePath` stored on a note
     * @param sampleImages every `cropPath` stored on a handwriting sample
     * @param protect files in use by the capture in progress, whatever their age
     * @param minAge overrides every age above when given (Settings "Free up space" passes 0)
     */
    fun sweep(
        cacheDir: File,
        filesDir: File,
        noteImages: Set<String>,
        sampleImages: Set<String>,
        protect: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
        minAge: Long? = null,
    ): Report {
        val doomed = mutableListOf<File>()
        fun old(f: File, age: Long) = now - f.lastModified() >= (minAge ?: age)
        fun kept(f: File) = f.absolutePath in protect

        cacheDir.listFiles().orEmpty()
            .filter { it.isFile && (it.name.startsWith("capture_") || it.name.startsWith("picked_")) && it.name.endsWith(".jpg") }
            .filterTo(doomed) { !kept(it) && old(it, ORIGINAL_AGE) }
        File(filesDir, "images").listFiles().orEmpty()
            .filterTo(doomed) { it.isFile && it.absolutePath !in noteImages && !kept(it) && old(it, ORPHAN_AGE) }
        File(filesDir, "handwriting").listFiles().orEmpty()
            .filterTo(doomed) { it.isFile && it.absolutePath !in sampleImages && !kept(it) && old(it, ORPHAN_AGE) }
        File(cacheDir, "exports").listFiles().orEmpty()
            .filterTo(doomed) { it.isFile && old(it, EXPORT_AGE) }
        File(filesDir, "debug").listFiles().orEmpty()
            .filterTo(doomed) { it.isFile && old(it, DEBUG_AGE) }

        var files = 0
        var bytes = 0L
        for (f in doomed) {
            val size = f.length()
            if (f.delete()) { files++; bytes += size }
        }
        return Report(files, bytes)
    }

    /** Bytes the app's photos, thumbnails, exports and debug images take up right now. */
    fun usage(cacheDir: File, filesDir: File): Long {
        val dirs = listOf(File(filesDir, "images"), File(filesDir, "handwriting"), File(filesDir, "debug"), File(cacheDir, "exports"))
        val originals = cacheDir.listFiles().orEmpty().filter { it.isFile && (it.name.startsWith("capture_") || it.name.startsWith("picked_")) }
        return dirs.sumOf { d -> d.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() } } + originals.sumOf { it.length() }
    }
}
