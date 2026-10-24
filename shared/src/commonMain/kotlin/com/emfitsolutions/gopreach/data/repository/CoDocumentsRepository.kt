package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.remote.ServerReply
import com.emfitsolutions.gopreach.data.remote.SyncApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val coJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

/** The server refused a File Manager / Previous Manual Record call: [message] is its plain-language reason, [body] its full answer (e.g. `needsConfirm`, `duplicate`). */
class CoApiException(message: String, val status: Int, val body: JsonObject? = null) : Exception(message) {
    val needsConfirm get() = body?.get("needsConfirm")?.jsonPrimitive?.booleanOrNull == true
    val isDuplicate get() = body?.get("duplicate")?.jsonPrimitive?.booleanOrNull == true
    val containedFolders get() = body?.get("folders")?.jsonPrimitive?.intOrNull ?: 0
    val containedFiles get() = body?.get("files")?.jsonPrimitive?.intOrNull ?: 0
}

// ---- File Manager ----------------------------------------------------------------------------------------------------------------------------------

@Serializable data class CoCrumb(val id: String, val name: String)

@Serializable
data class CoFolder(val id: String, val name: String, val parentId: String? = null, val createdAt: Long = 0, val updatedAt: Long = 0, val itemCount: Int = 0)

@Serializable
data class CoFile(
    val id: String, val name: String, val ext: String = "", val mime: String = "", val size: Long = 0, val folderId: String? = null, val folderPath: String = "",
    val createdAt: Long = 0, val updatedAt: Long = 0, val previewable: Boolean = false, val owner: String = "",
) {
    val isImage get() = mime.startsWith("image/")
    val isPdf get() = mime == "application/pdf"
    val isText get() = ext == "txt" || ext == "csv"
    val isExcel get() = ext == "xlsx" || ext == "xls" || ext == "csv"
}

@Serializable
data class CoListing(val folderId: String? = null, val path: List<CoCrumb> = emptyList(), val folders: List<CoFolder> = emptyList(), val files: List<CoFile> = emptyList(), val maxFileMb: Int = 25)

@Serializable data class CoTreeFolder(val id: String, val name: String, val parentId: String? = null)
@Serializable data class CoUsage(val usedBytes: Long = 0, val quotaBytes: Long = 0, val files: Int = 0, val trashItems: Int = 0)
@Serializable data class CoTree(val folders: List<CoTreeFolder> = emptyList(), val usage: CoUsage = CoUsage(), val maxFileMb: Int = 25)

@Serializable
data class CoSearchItem(
    val kind: String, val id: String, val name: String, val ext: String = "", val size: Long? = null, val createdAt: Long = 0, val updatedAt: Long = 0,
    val folderId: String? = null, val folderPath: String = "",
) { val isFolder get() = kind == "folder" }

@Serializable data class CoSearchResult(val total: Int = 0, val items: List<CoSearchItem> = emptyList(), val page: Int = 1, val pageSize: Int = 50)

@Serializable
data class CoTrashItem(val kind: String, val id: String, val name: String, val deletedAt: Long = 0, val size: Long? = null, val ext: String = "", val contains: Int = 0) { val isFolder get() = kind == "folder" }

/** A Circuit Overseer account as the Super Admin's picker lists it (immutable [id], circuit code, status and how many files they keep). */
@Serializable
data class CoAccount(
    val id: String, val name: String = "", val username: String = "", val circuitCode: String = "", val status: String = "ACTIVE",
    val fileCount: Int = 0, val folderCount: Int = 0, val bytes: Long = 0,
)

@Serializable data class CoAccountList(val accounts: List<CoAccount> = emptyList())

// ---- Previous Manual Record ----------------------------------------------------------------------------------------------------------------------

@Serializable data class ManualCongregation(val id: String, val name: String)

/** One worksheet of a saved file; [hit] is true when a search found its text. */
@Serializable data class ManualSheetInfo(val index: Int = 0, val name: String = "", val rows: Int = 0, val cols: Int = 0, val hit: Boolean = true)

@Serializable
data class ManualImportInfo(
    val id: String = "", val congregationId: String = "", val congregationName: String = "", val remarks: String = "", val fileName: String = "",
    val sheets: List<ManualSheetInfo> = emptyList(), val createdAt: Long = 0,
)

@Serializable
data class ManualImportResult(val importId: String = "", val sheets: List<ManualSheetInfo> = emptyList(), val truncated: Boolean = false, val congregationName: String = "", val remarks: String = "")

/** The look of a cell exactly as the server copied it from the Excel file (colours are "#RRGGBB"). */
class SheetStyle(
    val bg: String? = null, val fg: String? = null, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val size: Int = 0,
    val ha: String? = null, val va: String? = null, val wrap: Boolean = false, val rot: Int = 0, val top: Boolean = false, val bottom: Boolean = false, val left: Boolean = false, val right: Boolean = false,
)

class SheetCell(val r: Int, val c: Int, val text: String, val style: Int)

/** A worksheet as it looks in Excel: sizes in pixels, merged areas [r1, c1, r2, c2] and the cells that hold text or a fill or a border. */
class SheetSnapshot(
    val rows: Int, val cols: Int, val colWidths: List<Int>, val rowHeights: List<Int>, val merges: List<IntArray>, val styles: List<SheetStyle>, val cells: List<SheetCell>,
)

class ManualSheet(val index: Int, val name: String, val snapshot: SheetSnapshot)

@Serializable data class ManualSummaryColumn(val column: String = "", val label: String = "", val count: Int = 0, val sum: Double = 0.0, val average: Double = 0.0)
@Serializable data class ManualFormula(val cell: String = "", val formula: String = "", val shows: String = "")

/** What the server read from the layout and the data of a worksheet: numbers by column, the formulas it uses and totals worth checking. */
@Serializable
data class ManualSummary(
    val title: String = "", val rows: Int = 0, val cols: Int = 0, val filled: Int = 0, val numeric: Int = 0, val merged: Int = 0, val columns: List<ManualSummaryColumn> = emptyList(),
    val formulaCount: Int = 0, val functions: Map<String, Int> = emptyMap(), val notes: List<String> = emptyList(), val formulas: List<ManualFormula> = emptyList(),
)

// ---- The comparative report: built by the server (backend/src/sheetCompare.js) from the saved cells; the app only shows it and reads it aloud ----

@Serializable data class CompareColumn(val key: String = "", val label: String = "")
@Serializable data class CompareRecommended(val previous: String = "", val current: String = "")
@Serializable
data class CompareGroup(
    val id: String = "", val kind: String = "", val title: String = "", val columns: List<CompareColumn> = emptyList(), val indicators: Int = 0,
    val recommended: CompareRecommended = CompareRecommended(),
)
@Serializable
data class CompareRow(
    val label: String = "", val total: Boolean = false, val unit: String = "", val polarity: Int = 0, val previous: Double? = null, val current: Double? = null,
    val previousCell: String? = null, val currentCell: String? = null, val difference: Double? = null, val percent: Double? = null,
    val direction: String = "unavailable", val note: String = "",
)
@Serializable data class CompareScope(val congregation: String = "", val fileName: String = "", val sheet: String = "", val previous: String = "", val current: String = "")
@Serializable data class CompareCounts(val indicators: Int = 0, val compared: Int = 0, val increased: Int = 0, val decreased: Int = 0, val unchanged: Int = 0, val unavailable: Int = 0)
@Serializable data class CompareChartPoint(val label: String = "", val previous: Double = 0.0, val current: Double = 0.0)
@Serializable data class CompareChart(val type: String = "bar", val previousLabel: String = "", val currentLabel: String = "", val series: List<CompareChartPoint> = emptyList())
@Serializable
data class ComparisonReport(
    val title: String = "", val kind: String = "", val scope: CompareScope = CompareScope(), val counts: CompareCounts = CompareCounts(), val executive: List<String> = emptyList(),
    val table: List<CompareRow> = emptyList(), val improvements: List<String> = emptyList(), val attention: List<String> = emptyList(), val otherChanges: List<String> = emptyList(),
    val unchanged: List<String> = emptyList(), val limitations: List<String> = emptyList(), val method: List<String> = emptyList(), val chart: CompareChart? = null,
    /** What is read aloud, sentence by sentence, in reading order. */
    val voice: List<String> = emptyList(),
)
/** What a worksheet offers to compare, with the recommended report; [message] says why there is nothing to compare. */
@Serializable
data class Comparison(val available: Boolean = false, val message: String = "", val groups: List<CompareGroup> = emptyList(), val report: ComparisonReport? = null)

/** The report of two saved files; [report] is null with a [message] when they share no indicators. */
class FileComparison(val report: ComparisonReport?, val message: String)

class ManualSheetView(val sheet: ManualSheet, val summary: ManualSummary, val info: ManualImportInfo? = null, val changed: Int = 0, val comparison: Comparison = Comparison())
class ManualPreview(val fileName: String, val truncated: Boolean, val sheets: List<ManualSheetInfo>, val view: ManualSheetView)

/** One changed cell of a saved worksheet. */
class CellEdit(val r: Int, val c: Int, val v: String)

/** What to pour into an import: the picked file's bytes, or a File Manager file of the Circuit Overseer by id. */
sealed interface ManualSource {
    class Bytes(val name: String, val bytes: ByteArray) : ManualSource
    class Stored(val fileId: String) : ManualSource
}

/**
 * The Circuit Overseer's File Manager and Previous Manual Record. Everything is on the server (private to the signed-in person, see
 * `backend/src/coFiles.js` and `coManual.js`); this class only speaks that REST contract and turns refusals into [CoApiException] with the server's reason.
 */
class CoDocumentsRepository(private val api: SyncApi) {

    // ---- The Super Admin's read-only view of any Circuit Overseer's files (the owner is always the Circuit Overseer's account id) ----

    suspend fun coAccounts(): List<CoAccount> = decode(CoAccountList.serializer(), get("/v1/admin/co-accounts")).accounts

    suspend fun adminList(coId: String, folderId: String?): CoListing =
        decode(CoListing.serializer(), get("/v1/admin/co-files/$coId/list", buildMap { folderId?.let { put("folderId", it) } }))

    suspend fun adminSearch(coId: String, query: String): CoSearchResult = decode(CoSearchResult.serializer(), get("/v1/admin/co-files/$coId/search", mapOf("q" to query)))

    suspend fun adminDownload(coId: String, id: String): ByteArray = require(api.callServer("GET", "/v1/admin/co-files/$coId/$id/download")).bytes

    // ---- File Manager ----

    suspend fun list(folderId: String?, sort: String = "name", descending: Boolean = false): CoListing =
        decode(CoListing.serializer(), get("/v1/co/files/list", buildMap { folderId?.let { put("folderId", it) }; put("sort", sort); if (descending) put("dir", "desc") }))

    suspend fun tree(): CoTree = decode(CoTree.serializer(), get("/v1/co/files/tree"))

    suspend fun search(query: String, page: Int = 1): CoSearchResult = decode(CoSearchResult.serializer(), get("/v1/co/files/search", mapOf("q" to query, "page" to page.toString())))

    suspend fun createFolder(name: String, parentId: String?): CoFolder = decode(CoFolder.serializer(), send("POST", "/v1/co/files/folders", json = buildJsonObject { put("name", name); put("parentId", parentId) }))

    suspend fun renameFolder(id: String, name: String): CoFolder = decode(CoFolder.serializer(), send("PATCH", "/v1/co/files/folders/$id", json = buildJsonObject { put("name", name) }))

    suspend fun moveFolder(id: String, parentId: String?): CoFolder = decode(CoFolder.serializer(), send("PATCH", "/v1/co/files/folders/$id", json = buildJsonObject { put("parentId", parentId) }))

    /** Without [confirm] a non-empty folder is refused with [CoApiException.needsConfirm]; with it the folder and everything in it go to the recycle bin. */
    suspend fun deleteFolder(id: String, confirm: Boolean) { send("DELETE", "/v1/co/files/folders/$id", query = if (confirm) mapOf("confirm" to "1") else emptyMap()) }

    suspend fun upload(name: String, bytes: ByteArray, mime: String, folderId: String?): CoFile =
        decode(CoFile.serializer(), send("PUT", "/v1/co/files/upload", query = buildMap { put("name", name); folderId?.let { put("folderId", it) } }, bytes = bytes, mime = mime))

    suspend fun details(id: String): CoFile = decode(CoFile.serializer(), get("/v1/co/files/$id"))

    suspend fun download(id: String): ByteArray = require(api.callServer("GET", "/v1/co/files/$id/download")).bytes

    suspend fun renameFile(id: String, name: String): CoFile = decode(CoFile.serializer(), send("PATCH", "/v1/co/files/$id", json = buildJsonObject { put("name", name) }))

    suspend fun moveFile(id: String, folderId: String?): CoFile = decode(CoFile.serializer(), send("PATCH", "/v1/co/files/$id", json = buildJsonObject { put("folderId", folderId) }))

    suspend fun deleteFile(id: String) { send("DELETE", "/v1/co/files/$id") }

    suspend fun trash(): List<CoTrashItem> = coJson.decodeFromString(ListSerializer(CoTrashItem.serializer()), get("/v1/co/files/trash").jsonObject["items"]?.toString() ?: "[]")

    suspend fun restore(item: CoTrashItem) { send("POST", "/v1/co/files/trash/restore", json = buildJsonObject { put("kind", item.kind); put("id", item.id) }) }

    suspend fun deleteForever(item: CoTrashItem) { send("DELETE", "/v1/co/files/trash/${item.kind}/${item.id}") }

    suspend fun emptyTrash() { send("DELETE", "/v1/co/files/trash") }

    // ---- Previous Manual Record ----

    suspend fun congregations(): List<ManualCongregation> =
        coJson.decodeFromString(ListSerializer(ManualCongregation.serializer()), get("/v1/co/manual/congregations").jsonObject["congregations"]?.toString() ?: "[]")

    /** Opens a workbook WITHOUT saving it: the worksheets, and one of them as it looks in Excel. */
    suspend fun preview(source: ManualSource, sheet: Int?): ManualPreview {
        val o = manualCall("/v1/co/manual/preview", source, buildMap { sheet?.let { put("sheet", it.toString()) } })
        return ManualPreview(
            o["fileName"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["truncated"]?.jsonPrimitive?.booleanOrNull == true,
            coJson.decodeFromString(ListSerializer(ManualSheetInfo.serializer()), o["sheets"]?.toString() ?: "[]"), sheetView(o),
        )
    }

    /** Saves the WHOLE file (every worksheet, as it looks) for one congregation, with optional remarks. */
    suspend fun save(source: ManualSource, congregationId: String, remarks: String, allowDuplicate: Boolean = false): ManualImportResult =
        decode(ManualImportResult.serializer(), manualCall("/v1/co/manual/import", source, buildMap {
            put("congregationId", congregationId); if (remarks.isNotBlank()) put("remarks", remarks.trim()); if (allowDuplicate) put("allowDuplicate", "1")
        }))

    /** The saved files, by congregation A-Z; with [query] only the files whose text holds every word (each worksheet says if it does). */
    suspend fun imports(query: String = "", congregationId: String? = null): List<ManualImportInfo> =
        coJson.decodeFromString(ListSerializer(ManualImportInfo.serializer()), get("/v1/co/manual/imports", buildMap {
            if (query.isNotBlank()) put("q", query.trim()); congregationId?.let { put("congregationId", it) }
        }).jsonObject["imports"]?.toString() ?: "[]")

    suspend fun openSheet(importId: String, index: Int): ManualSheetView = sheetView(get("/v1/co/manual/imports/$importId/sheets/$index"))

    /** The report for a chosen group of columns and pair of them in a saved worksheet. */
    suspend fun compare(importId: String, index: Int, group: String, previous: String, current: String): ComparisonReport =
        decode(ComparisonReport.serializer(), get("/v1/co/manual/imports/$importId/sheets/$index/compare", mapOf("group" to group, "a" to previous, "b" to current)).jsonObject["report"]?.jsonObject ?: throw CoApiException("The server sent no report.", 500))

    /**
     * The verified analysis of a saved worksheet (cards, bar-chart data, findings, data-quality notes). With [withAi] the server's free self-hosted AI also words it;
     * whatever happens to the AI, the answer is the calculated analysis plus an honest [AnalysisAi] status.
     */
    suspend fun analysis(importId: String, index: Int, group: String = "", previous: String = "", current: String = "", withAi: Boolean = false): ManualAnalysis {
        val query = buildMap { if (group.isNotBlank()) put("group", group); if (previous.isNotBlank()) put("a", previous); if (current.isNotBlank()) put("b", current); if (withAi) put("ai", "1") }
        val body = get("/v1/co/manual/imports/$importId/sheets/$index/analysis", query).jsonObject["analysis"]?.jsonObject ?: throw CoApiException("The server sent no analysis.", 500)
        return decode(ManualAnalysis.serializer(), body)
    }

    /** Two saved worksheets compared by indicator name; [earlier] and [later] are (import id, worksheet index). */
    suspend fun compareFiles(earlier: Pair<String, Int>, later: Pair<String, Int>): FileComparison {
        val o = get("/v1/co/manual/compare-imports", mapOf("a" to earlier.first, "sheetA" to earlier.second.toString(), "b" to later.first, "sheetB" to later.second.toString()))
        val report = o["report"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { coJson.decodeFromJsonElement(ComparisonReport.serializer(), it) }
        return FileComparison(report, o["message"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    /** Changes cells of a saved worksheet and saves them; the answer is the worksheet as it is now. */
    suspend fun editCells(importId: String, index: Int, edits: List<CellEdit>): ManualSheetView =
        sheetView(send("PATCH", "/v1/co/manual/imports/$importId/sheets/$index", json = buildJsonObject {
            putJsonArray("edits") { edits.forEach { e -> add(buildJsonObject { put("r", e.r); put("c", e.c); put("v", e.v) }) } }
        }))

    /** The Excel file exactly as it was uploaded (404 for records saved before the original was kept). */
    suspend fun downloadImport(id: String): ByteArray = require(api.callServer("GET", "/v1/co/manual/imports/$id/download")).bytes

    suspend fun deleteImport(id: String) { send("DELETE", "/v1/co/manual/imports/$id") }

    /** Changes the remarks of a saved file; returns the remarks as the server kept them. */
    suspend fun editRemarks(importId: String, remarks: String): String =
        send("PATCH", "/v1/co/manual/imports/$importId", json = buildJsonObject { put("remarks", remarks) })["remarks"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun sheetView(o: JsonObject): ManualSheetView {
        val sheet = o["sheet"]?.jsonObject ?: throw CoApiException("The server sent no worksheet.", 500)
        return ManualSheetView(
            ManualSheet(sheet["index"]?.jsonPrimitive?.intOrNull ?: 0, sheet["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), parseSnapshot(sheet["snapshot"]?.jsonObject ?: JsonObject(emptyMap()))),
            o["summary"]?.let { coJson.decodeFromJsonElement(ManualSummary.serializer(), it) } ?: ManualSummary(),
            o["import"]?.let { coJson.decodeFromJsonElement(ManualImportInfo.serializer(), it) }, o["changed"]?.jsonPrimitive?.intOrNull ?: 0,
            o["comparison"]?.let { runCatching { coJson.decodeFromJsonElement(Comparison.serializer(), it) }.getOrNull() } ?: Comparison(),
        )
    }

    private fun parseSnapshot(o: JsonObject): SheetSnapshot {
        fun ints(key: String) = o[key]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList()
        val styles = o["styles"]?.jsonArray?.map { el ->
            val s = el.jsonObject
            fun flag(k: String) = (s[k]?.jsonPrimitive?.intOrNull ?: 0) != 0
            SheetStyle(
                s["bg"]?.jsonPrimitive?.contentOrNull, s["fg"]?.jsonPrimitive?.contentOrNull, flag("b"), flag("i"), flag("u"), s["sz"]?.jsonPrimitive?.intOrNull ?: 0,
                s["ha"]?.jsonPrimitive?.contentOrNull, s["va"]?.jsonPrimitive?.contentOrNull, flag("wrap"), s["rot"]?.jsonPrimitive?.intOrNull ?: 0, flag("bt"), flag("bb"), flag("bl"), flag("br"),
            )
        } ?: listOf(SheetStyle())
        val cells = o["cells"]?.jsonArray?.map { a -> val x = a.jsonArray; SheetCell(x[0].jsonPrimitive.int, x[1].jsonPrimitive.int, x[2].jsonPrimitive.content, x[3].jsonPrimitive.int) } ?: emptyList()
        val merges = o["merges"]?.jsonArray?.map { m -> m.jsonArray.map { it.jsonPrimitive.int }.toIntArray() } ?: emptyList()
        return SheetSnapshot(o["rows"]?.jsonPrimitive?.intOrNull ?: 1, o["cols"]?.jsonPrimitive?.intOrNull ?: 1, ints("colWidths"), ints("rowHeights"), merges, styles, cells)
    }

    // ---- plumbing ----

    private suspend fun manualCall(path: String, source: ManualSource, extra: Map<String, String>): JsonObject = when (source) {
        is ManualSource.Bytes -> send("POST", path, query = extra + ("name" to source.name), bytes = source.bytes, mime = "application/octet-stream")
        is ManualSource.Stored -> send("POST", path, query = extra + ("fileId" to source.fileId))
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonObject = send("GET", path, query)

    private suspend fun send(method: String, path: String, query: Map<String, String> = emptyMap(), json: JsonObject? = null, bytes: ByteArray? = null, mime: String? = null): JsonObject {
        val reply = require(api.callServer(method, path, query, json, bytes, mime))
        return runCatching { coJson.parseToJsonElement(reply.text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    private fun require(reply: ServerReply): ServerReply {
        if (reply.ok) return reply
        val body = runCatching { coJson.parseToJsonElement(reply.text) as? JsonObject }.getOrNull()
        val reason = body?.get("error")?.jsonPrimitive?.contentOrNull
        // The server's catch-all answer for an address it does not have: this feature has not been installed on that server yet (our own "not found" answers always name the thing).
        if (reply.status == 404 && reason == "Not found") throw CoApiException("The GoPreach server has not been updated for this feature yet. Ask the administrator to install the latest server update, then try again.", 404, body)
        throw CoApiException(
            reason ?: when (reply.status) {
                401 -> "Please sign in again."
                403 -> "This is for the Circuit Overseer."
                404 -> "This was not found."
                413 -> "The file is too large."
                else -> "The server answered ${reply.status}."
            },
            reply.status, body,
        )
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, body: JsonObject): T = coJson.decodeFromJsonElement(serializer, body)

}
