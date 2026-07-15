package com.tvviewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistsFragment : Fragment() {

    companion object {
        const val TAG = "PlaylistsFragment"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: PlaylistAdapter

    private lateinit var spinnerLanguage: Spinner
    private lateinit var spinnerCategory: Spinner
    private lateinit var spinnerCountry: Spinner
    private lateinit var spinnerRegion: Spinner
    private lateinit var customSectionLabel: TextView
    private lateinit var builtInSection: View

    private val addPlaylistLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPlaylists()
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        importPlaylistFromUri(uri)
    }

    private fun importPlaylistFromUri(uri: android.net.Uri) {
        // Чтение+запись файла — на IO: раньше readBytes()+writeBytes()
        // многомегабайтного .m3u шли прямо в колбэке OpenDocument на
        // main thread — фриз пропорционально размеру файла, ANR на
        // больших плейлистах с медленной флешки.
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Android Round 353: queryFileName тоже в IO — это
                // cross-process binder-запрос к SAF-провайдеру, облачные
                // провайдеры могут блокировать секунды.
                val name = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    queryFileName(uri)
                } ?: "Imported.m3u"
                val url = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val content = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("empty stream")
                    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                    val playlistDir = java.io.File(ctx.filesDir, "imported_playlists").apply { mkdirs() }
                    val savedFile = java.io.File(playlistDir, "${System.currentTimeMillis()}_$safeName")
                    savedFile.writeBytes(content)
                    "file://${savedFile.absolutePath}"
                }
                val displayName = name.removeSuffix(".m3u8").removeSuffix(".m3u")
                prefs.addCustomPlaylist(displayName, url)
                Toast.makeText(ctx, R.string.playlist_added, Toast.LENGTH_SHORT).show()
                refreshPlaylists()
            } catch (e: Exception) {
                ErrorLogger.logException(ctx, e)
                Toast.makeText(ctx, R.string.load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryFileName(uri: android.net.Uri): String? {
        var name: String? = null
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        recyclerView = view.findViewById(R.id.playlistsRecyclerView)
        emptyText = view.findViewById(R.id.emptyText)
        progressBar = view.findViewById(R.id.progressBar)
        spinnerLanguage = view.findViewById(R.id.spinnerLanguage)
        spinnerCategory = view.findViewById(R.id.spinnerCategory)
        spinnerCountry = view.findViewById(R.id.spinnerCountry)
        spinnerRegion = view.findViewById(R.id.spinnerRegion)
        customSectionLabel = view.findViewById(R.id.customSectionLabel)
        builtInSection = view.findViewById(R.id.builtInSection)

        val btnAdd = view.findViewById<View>(R.id.btnAddPlaylist)
        btnAdd?.setOnClickListener {
            val opts = arrayOf(
                getString(R.string.add_url),
                getString(R.string.import_file),
                getString(R.string.paste_url_from_clipboard),
            )
            FocusableDialog.show(requireContext(),
                getString(R.string.add_playlist), opts, -1) { which ->
                when (which) {
                    0 -> addPlaylistLauncher.launch(Intent(requireContext(), AddPlaylistActivity::class.java))
                    1 -> importFileLauncher.launch(arrayOf("audio/*", "application/octet-stream", "text/*", "*/*"))
                    2 -> pasteUrlFromClipboard()
                }
            }
        }

        adapter = PlaylistAdapter(
            playlists = emptyList(),
            onPlaylistClick = { playlist ->
                (activity as? MainActivity)?.switchToChannels(playlist.first, playlist.second)
            },
            onDeleteClick = { index ->
                // Android Round 366: долгое нажатие раньше УДАЛЯЛО
                // плейлист сразу — теперь меню действий:
                // редактировать / копировать URL / удалить.
                showPlaylistActionsMenu(index)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        setupBuiltInSpinners()
        refreshPlaylists()
    }

    /** Round 220: четыре комбобокса для встроенных подборок iptv-org —
     *  по языку / категории / стране / региону. Раньше всё это было 30+
     *  строк в одном списке. */
    private fun setupBuiltInSpinners() {
        val show = prefs.showBuiltInPlaylists
        builtInSection.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        bindSpinner(spinnerLanguage, "by_language")
        bindSpinner(spinnerCategory, "by_category")
        bindSpinner(spinnerCountry, "by_country")
        bindSpinner(spinnerRegion, "by_region")
    }

    private fun bindSpinner(spinner: Spinner, categoryId: String) {
        val items = BuiltInPlaylists.categories
            .firstOrNull { it.id == categoryId }?.playlists.orEmpty()

        val labels = mutableListOf(getString(R.string.choose_builtin))
        labels.addAll(items.map { it.name })

        val arrayAdapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(0xFFFFFFFF.toInt())
                v.setPadding(24, 16, 24, 16)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(0xFFFFFFFF.toInt())
                v.setPadding(24, 16, 24, 16)
                return v
            }
        }
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = arrayAdapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var firstInvocation = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Skip initial layout-driven selection (position 0 placeholder).
                if (firstInvocation) { firstInvocation = false; return }
                if (position <= 0) return
                val pl = items.getOrNull(position - 1) ?: return
                val url = pl.url ?: return
                (activity as? MainActivity)?.switchToChannels(pl.name, url)
                // Reset to placeholder so the same item can be re-selected later.
                spinner.post { spinner.setSelection(0, false) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun pasteUrlFromClipboard() {
        val ctx = requireContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
        val urls = Regex("""(?i)\bhttps?://\S+\.m3u8?\S*""").findAll(text).map { it.value }.toList()
        val chosen = urls.firstOrNull() ?: text.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
        if (chosen.isNullOrBlank()) {
            Toast.makeText(ctx, R.string.no_url_in_clipboard, Toast.LENGTH_SHORT).show()
            return
        }
        val name = (Regex("""/([^/?#]+\.m3u8?)""").find(chosen)?.groupValues?.get(1)
            ?.removeSuffix(".m3u8")?.removeSuffix(".m3u"))
            ?.takeIf { it.isNotEmpty() }
            ?: "Playlist ${prefs.customPlaylists.size + 1}"
        android.app.AlertDialog.Builder(ctx, R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.add_playlist)
            .setMessage("$name\n$chosen")
            .setPositiveButton(R.string.add) { _, _ ->
                prefs.addCustomPlaylist(name, chosen)
                Toast.makeText(ctx, R.string.playlist_added, Toast.LENGTH_SHORT).show()
                refreshPlaylists()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Built-in toggle may have changed in Settings while we were away.
        setupBuiltInSpinners()
        refreshPlaylists()
        moveFocusToFirstItem()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) moveFocusToFirstItem()
    }

    private fun moveFocusToFirstItem() {
        val v = view ?: return
        v.postDelayed({
            if (!isAdded || !::recyclerView.isInitialized) return@postDelayed
            val first = recyclerView.findViewHolderForAdapterPosition(0)?.itemView
            if (first != null && first.isAttachedToWindow) {
                first.requestFocus()
                return@postDelayed
            }
            recyclerView.requestFocus()
            recyclerView.postDelayed({
                if (!isAdded) return@postDelayed
                recyclerView.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }, 100)
        }, 50)
    }

    private fun refreshPlaylists() {
        if (!isAdded || !::adapter.isInitialized) return
        // Round 220: список показывает ТОЛЬКО свои плейлисты. Встроенные
        // ушли в комбобоксы выше.
        val customs = prefs.customPlaylists
        adapter.updatePlaylists(customs, customs.size)

        val empty = customs.isEmpty()
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        customSectionLabel.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /** Android Round 366: меню действий по долгому нажатию на свой
     *  плейлист — редактировать (имя + URL), копировать URL, удалить.
     *  Раньше долгое нажатие сразу удаляло. */
    private fun showPlaylistActionsMenu(index: Int) {
        val ctx = requireContext()
        val playlist = prefs.customPlaylists.getOrNull(index) ?: return
        val opts = arrayOf(
            getString(R.string.playlist_edit),
            getString(R.string.playlist_copy_url),
            getString(R.string.playlist_delete)
        )
        android.app.AlertDialog.Builder(ctx, R.style.Theme_TVViewer_Dialog)
            .setTitle(playlist.first)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> showEditPlaylistDialog(index)
                    1 -> {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData
                            .newPlainText("playlist_url", playlist.second))
                        Toast.makeText(ctx, R.string.playlist_url_copied,
                            Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        prefs.removeCustomPlaylist(index)
                        refreshPlaylists()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .installFocusListBackground()  // Round 370: видимый фокус в меню
    }

    /** Android Round 366/370: диалог редактирования плейлиста — имя и
     *  URL с предзаполнением. Round 370: поля получили видимый фон,
     *  подписи и подсветку фокуса — на ТВ раньше не было видно, какое
     *  поле активно. */
    private fun showEditPlaylistDialog(index: Int) {
        val ctx = requireContext()
        val playlist = prefs.customPlaylists.getOrNull(index) ?: return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val gap = (10 * resources.displayMetrics.density).toInt()
        val innerPad = (12 * resources.displayMetrics.density).toInt()

        fun label(text: String) = android.widget.TextView(ctx).apply {
            setText(text)
            setTextColor(0xFFB0B0C0.toInt())
            textSize = 12f
        }
        fun field(value: String, hintText: String, selectable: Boolean) =
            android.widget.EditText(ctx).apply {
                hint = hintText
                setText(value)
                setSingleLine()
                // Видимый фон + подсветка фокуса (тот же, что у поиска
                // в оверлее — рамка меняет цвет при фокусе).
                setBackgroundResource(R.drawable.bg_overlay_search)
                setPadding(innerPad, innerPad, innerPad, innerPad)
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0x80FFFFFF.toInt())
                isFocusable = true
                isFocusableInTouchMode = true
                if (selectable) setTextIsSelectable(true)
            }

        val nameEdit = field(playlist.first,
            getString(R.string.playlist_name_hint), false)
        val urlEdit = field(playlist.second,
            getString(R.string.playlist_url_hint), true)

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label(getString(R.string.playlist_name_hint)))
            addView(nameEdit)
            (nameEdit.layoutParams as? android.widget.LinearLayout.LayoutParams)
                ?.bottomMargin = gap
            addView(label(getString(R.string.playlist_url_hint)).apply {
                (layoutParams as? android.widget.LinearLayout.LayoutParams)
                    ?.topMargin = gap
            })
            addView(urlEdit)
        }
        android.app.AlertDialog.Builder(ctx, R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.playlist_edit)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = nameEdit.text?.toString()?.trim().orEmpty()
                val newUrl = urlEdit.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && newUrl.isNotEmpty()) {
                    prefs.updateCustomPlaylist(index, newName, newUrl)
                    refreshPlaylists()
                }
            }
            .setNeutralButton(R.string.playlist_copy_url) { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData
                    .newPlainText("playlist_url",
                        urlEdit.text?.toString() ?: playlist.second))
                Toast.makeText(ctx, R.string.playlist_url_copied,
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        // Фокус на первое поле — сразу видно, что активно.
        nameEdit.post {
            nameEdit.requestFocus()
            nameEdit.setSelection(nameEdit.text?.length ?: 0)
        }
    }
}
