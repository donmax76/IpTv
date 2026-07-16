package com.tvviewer

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/**
 * Android Round 366: родительский контроль.
 *
 * PIN хранится как SHA-256 хэш в SharedPreferences. Блокировки двух
 * видов: по URL канала (точечные) и по имени категории (group-title —
 * закрывает сразу все каналы группы). Проверка isLocked() дешёвая
 * (set-membership) и зовётся перед стартом воспроизведения.
 *
 * После правильного PIN блокировки снимаются ДО КОНЦА СЕССИИ
 * (sessionUnlocked) — иначе зэппинг через заблокированную зону
 * спрашивал бы PIN на каждый канал. Перезапуск приложения снова
 * включает защиту.
 */
object ParentalControl {

    @Volatile var sessionUnlocked = false

    /** Round 371/375: поле ввода PIN с видимым фоном/фокусом.
     *  imeOptions=actionDone — «OK» на экранной клавиатуре подтверждает
     *  ввод (сам submit-слушатель вешают askPin/askNewPin). */
    private fun pinField(activity: Activity, hintText: String): EditText =
        EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            hint = hintText
            setBackgroundResource(R.drawable.bg_overlay_search)
            val p = (12 * activity.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x80FFFFFF.toInt())
            isFocusableInTouchMode = true
        }

    private fun wrapField(activity: Activity, field: android.view.View): android.view.View {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        return android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
    }

    fun isEnabled(prefs: AppPreferences): Boolean =
        !prefs.parentalPinHash.isNullOrBlank() ||
        !prefs.parentalPin.isNullOrBlank()  // legacy plain-PIN (мигрируется)

    fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun checkPin(prefs: AppPreferences, pin: String): Boolean {
        val hash = prefs.parentalPinHash
        if (!hash.isNullOrBlank()) return hash == sha256(pin)
        // Legacy: старый пункт Настроек хранил PIN открытым текстом и
        // ничего не блокировал. Принимаем его и мигрируем в хэш.
        val legacy = prefs.parentalPin
        if (!legacy.isNullOrBlank() && legacy == pin) {
            prefs.parentalPinHash = sha256(pin)
            prefs.parentalPin = null
            return true
        }
        return false
    }

    /** Точечная блокировка канала по URL (независимо от категории). */
    fun isChannelUrlLocked(prefs: AppPreferences, channel: Channel): Boolean =
        channel.url in prefs.lockedChannelUrls

    /** Настроена ли блокировка канала (точечно ИЛИ через категорию).
     *  НЕ учитывает sessionUnlocked — используется для отрисовки
     *  иконки замка и подписи кнопки: замок показываем всегда, пока
     *  блокировка настроена, даже если в этой сессии PIN уже вводили. */
    fun isChannelConfiguredLocked(prefs: AppPreferences, channel: Channel): Boolean {
        if (!isEnabled(prefs)) return false
        if (channel.url in prefs.lockedChannelUrls) return true
        val lockedCats = prefs.lockedCategories
        if (lockedCats.isEmpty()) return false
        // group-title бывает составным ("Кино;HD") — как в фильтрах.
        val groups = channel.group?.split(';', ',')?.map { it.trim() } ?: return false
        return groups.any { it.isNotEmpty() && it in lockedCats }
    }

    /** Нужен ли PIN перед просмотром (гейт воспроизведения). Настроенная
     *  блокировка + защита включена + PIN в этой сессии ещё не вводили. */
    fun isLocked(prefs: AppPreferences, channel: Channel): Boolean {
        if (sessionUnlocked) return false
        return isChannelConfiguredLocked(prefs, channel)
    }

    /** Диалог ввода PIN. onSuccess зовётся на main после верного PIN
     *  (sessionUnlocked при этом ставится автоматически, если
     *  unlockSession=true). onCancel — по отмене/неверному PIN нет
     *  колбэка, диалог просто даёт попробовать ещё раз. */
    fun askPin(activity: Activity, prefs: AppPreferences,
               unlockSession: Boolean = true,
               onCancel: (() -> Unit)? = null,
               onSuccess: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val input = pinField(activity, activity.getString(R.string.parental_enter_pin))
        val dlg = AlertDialog.Builder(activity, R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.parental_control)
            .setView(wrapField(activity, input))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.cancel) { d, _ ->
                d.dismiss(); onCancel?.invoke()
            }
            .setOnCancelListener { onCancel?.invoke() }
            .create()
        dlg.show()
        // Общая проверка — из кнопки OK и из IME-Done (OK на клавиатуре).
        val submit = {
            val pin = input.text?.toString() ?: ""
            if (checkPin(prefs, pin)) {
                if (unlockSession) sessionUnlocked = true
                dlg.dismiss()
                onSuccess()
            } else {
                input.error = activity.getString(R.string.parental_wrong_pin)
                input.setText("")
            }
        }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
        input.post { input.requestFocus() }
    }

    /** Диалог установки нового PIN (4-8 цифр). */
    fun askNewPin(activity: Activity, prefs: AppPreferences,
                  onDone: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return
        val input = pinField(activity, activity.getString(R.string.parental_new_pin))
        val dlg = AlertDialog.Builder(activity, R.style.Theme_TVViewer_Dialog)
            .setTitle(R.string.parental_set_pin)
            .setView(wrapField(activity, input))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        val submit = {
            val pin = input.text?.toString() ?: ""
            if (pin.length in 4..8 && pin.all { it.isDigit() }) {
                prefs.parentalPinHash = sha256(pin)
                sessionUnlocked = false
                dlg.dismiss()
                Toast.makeText(activity, R.string.parental_pin_set,
                    Toast.LENGTH_SHORT).show()
                onDone?.invoke()
            } else {
                input.error = activity.getString(R.string.parental_new_pin)
            }
        }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
        input.post { input.requestFocus() }
    }
}
