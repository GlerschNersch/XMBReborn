package dev.xmbreborn.xmb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folders the user made in a column ("Streaming" in Video, say), each holding apps by item id.
 * A folder belongs to one column: an app filed away in Video still shows in Apps. Stored with the
 * launcher's portable settings, so Backup and Android's backup carry them.
 */
class UserFolders(context: Context) {
    private val p = context.getSharedPreferences("xmb", Context.MODE_PRIVATE)

    class Folder(val id: String, val name: String, val apps: List<String>)

    fun inColumn(column: String): List<Folder> = load()[column].orEmpty()

    /** Makes a folder in [column] holding [appId] (taken out of any other folder there). */
    fun create(column: String, name: String, appId: String): String {
        val id = "userfolder:$column:${System.currentTimeMillis().toString(36)}"
        edit(column) { folders -> folders.map { it.without(appId) } + Folder(id, name, listOf(appId)) }
        return id
    }

    fun moveInto(column: String, folderId: String, appId: String) = edit(column) { folders ->
        folders.map { f -> if (f.id == folderId) Folder(f.id, f.name, (f.apps - appId) + appId) else f.without(appId) }
    }

    fun takeOut(column: String, appId: String) = edit(column) { folders -> folders.map { it.without(appId) } }

    fun rename(column: String, folderId: String, name: String) = edit(column) { folders ->
        folders.map { f -> if (f.id == folderId) Folder(f.id, name, f.apps) else f }
    }

    /** Deletes the folder; its apps go back into the column. */
    fun delete(column: String, folderId: String) = edit(column) { folders -> folders.filter { it.id != folderId } }

    private fun Folder.without(appId: String) = if (appId in apps) Folder(id, name, apps - appId) else this

    private fun edit(column: String, change: (List<Folder>) -> List<Folder>) {
        val all = load().toMutableMap()
        // A folder emptied of its last app goes away with it.
        all[column] = change(all[column].orEmpty()).filter { it.apps.isNotEmpty() }
        save(all)
    }

    private fun load(): Map<String, List<Folder>> = runCatching {
        val o = JSONObject(p.getString(KEY, "{}")!!)
        o.keys().asSequence().associateWith { column ->
            val arr = o.getJSONArray(column)
            (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                val apps = f.getJSONArray("apps")
                Folder(f.getString("id"), f.getString("name"), (0 until apps.length()).map(apps::getString))
            }
        }
    }.getOrDefault(emptyMap())

    private fun save(all: Map<String, List<Folder>>) {
        val o = JSONObject()
        for ((column, folders) in all) {
            if (folders.isEmpty()) continue
            o.put(column, JSONArray(folders.map { f ->
                JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps))
            }))
        }
        p.edit().putString(KEY, o.toString()).apply()
    }

    /** The raw JSON, for Backup. */
    var json: String
        get() = p.getString(KEY, "{}")!!
        set(v) = p.edit().putString(KEY, v).apply()

    companion object {
        const val KEY = "userFolders"

        /**
         * [items] with each folder's apps gathered into one folder row. The folder takes the place
         * of its first app in [items]' order (so it sorts as its apps do); apps that have gone are
         * skipped, and a folder with none left isn't shown.
         */
        fun apply(items: List<XmbItem>, folders: List<Folder>, folderItem: (Folder, List<XmbItem>) -> XmbItem): List<XmbItem> {
            if (folders.isEmpty()) return items
            val folderOf = HashMap<String, Folder>()
            for (f in folders) for (a in f.apps) folderOf.putIfAbsent(a, f)
            val members = folders.associateWith { f -> items.filter { folderOf[it.id] === f } }
            val placed = HashSet<String>()
            val out = ArrayList<XmbItem>()
            for (item in items) {
                val f = folderOf[item.id]
                if (f == null) { out.add(item); continue }
                if (placed.add(f.id)) out.add(folderItem(f, members.getValue(f)))
            }
            return out
        }
    }
}
