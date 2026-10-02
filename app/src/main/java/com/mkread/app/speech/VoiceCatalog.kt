package com.mkread.app.speech

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey

enum class VoiceGroup(val label: String) {
    FAST("极速"),
    HIGH_QUALITY("高音质"),
    MULTI_FEMALE("多音色 · 女声"),
    MULTI_MALE("多音色 · 男声"),
    CLONE("声音克隆"),
}

/** One selectable narration voice. Ids are stable because they are persisted. */
data class VoiceOption(
    val id: String,
    val model: VoiceModel,
    val displayName: String,
    val description: String,
    val group: VoiceGroup,
    val speakerId: Int = 0,
    /** For cloned voices: the installed voice package id passed to [InstalledVoiceProvider]. */
    val cloneVoiceId: String? = null,
)

object VoiceCatalog {
    const val DEFAULT_VOICE_ID = "matcha"
    private const val CLONE_PREFIX = "clone:"
    private const val KOKORO_PREFIX = "kokoro:"

    val matcha = VoiceOption(
        id = DEFAULT_VOICE_ID,
        model = VoiceModel.MATCHA,
        displayName = "标准女声",
        description = "速度最快，任何手机都能即点即读",
        group = VoiceGroup.FAST,
    )

    private val melo = VoiceOption(
        id = "melo",
        model = VoiceModel.MELO,
        displayName = "清亮女声",
        description = "44.1kHz 高音质，中高端手机流畅",
        group = VoiceGroup.HIGH_QUALITY,
    )

    /** Kokoro v1.1-zh speaker ids: 3..57 are female (zf_*), 58..102 are male (zm_*). */
    private val kokoro: List<VoiceOption> = buildList {
        for (sid in 3..57) add(kokoroVoice(sid, "女声 ${sid - 2}", VoiceGroup.MULTI_FEMALE))
        for (sid in 58..102) add(kokoroVoice(sid, "男声 ${sid - 57}", VoiceGroup.MULTI_MALE))
    }

    val builtInClone = VoiceOption(
        id = "${CLONE_PREFIX}${InstalledVoiceProvider.BUILT_IN_VOICE_ID}",
        model = VoiceModel.ZIPVOICE,
        displayName = "示例克隆音色",
        description = "声音克隆，较慢，需要等待生成",
        group = VoiceGroup.CLONE,
        cloneVoiceId = InstalledVoiceProvider.BUILT_IN_VOICE_ID,
    )

    val presets: List<VoiceOption> = listOf(matcha, melo) + kokoro + builtInClone

    fun cloneOption(voice: InstalledVoiceSummary) = VoiceOption(
        id = CLONE_PREFIX + voice.id,
        model = VoiceModel.ZIPVOICE,
        displayName = voice.displayName,
        description = "声音克隆 · ${voice.emotions.size} 种情绪",
        group = VoiceGroup.CLONE,
        cloneVoiceId = voice.id,
    )

    /** Resolves a persisted id; unknown or malformed ids fall back to the default voice. */
    fun find(id: String?): VoiceOption {
        if (id == null) return matcha
        presets.firstOrNull { it.id == id }?.let { return it }
        if (id.startsWith(CLONE_PREFIX) && id.length > CLONE_PREFIX.length) {
            val cloneId = id.removePrefix(CLONE_PREFIX)
            return builtInClone.copy(id = id, displayName = cloneId, cloneVoiceId = cloneId)
        }
        return matcha
    }

    private fun kokoroVoice(sid: Int, name: String, group: VoiceGroup) = VoiceOption(
        id = "$KOKORO_PREFIX$sid",
        model = VoiceModel.KOKORO,
        displayName = name,
        description = "多音色模型，较慢，会提前生成",
        group = group,
        speakerId = sid,
    )
}

/** Persists the selected narration voice in the app's preferences. */
class NarrationVoiceSettings(private val dataStore: DataStore<Preferences>) {
    val selectedVoiceId: Flow<String> = dataStore.data.map { preferences ->
        preferences[SELECTED_VOICE_KEY] ?: VoiceCatalog.DEFAULT_VOICE_ID
    }

    suspend fun select(voiceId: String) {
        dataStore.edit { preferences -> preferences[SELECTED_VOICE_KEY] = voiceId }
    }

    private companion object {
        val SELECTED_VOICE_KEY = stringPreferencesKey("narration_voice_id")
    }
}

/**
 * Maps catalog ids to concrete [NarrationVoice]s. Preset voices need no reference recording;
 * cloned voices are delegated to [InstalledVoiceProvider]. The fallback voice is Matcha because
 * it is small, always installed and fast enough to recover from any failure.
 */
class CatalogVoiceProvider(
    private val installed: InstalledVoiceProvider,
) : NarrationVoiceProvider {
    override suspend fun resolve(voiceId: String?, styleId: String): Result<NarrationVoice> {
        val option = VoiceCatalog.find(voiceId)
        val cloneId = option.cloneVoiceId ?: return Result.success(option.toPresetVoice())
        return installed.resolveInstalled(cloneId, styleId).map { voice ->
            voice.copy(model = VoiceModel.ZIPVOICE)
        }
    }

    override suspend fun builtInNeutral(): Result<NarrationVoice> =
        Result.success(VoiceCatalog.matcha.toPresetVoice())

    private fun VoiceOption.toPresetVoice() = NarrationVoice(
        id = id,
        packageSha256 = sha256("${model.id}@${model.revision}#$speakerId"),
        styleId = NEUTRAL_STYLE,
        reference = null,
        model = model,
        speakerId = speakerId,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val NEUTRAL_STYLE = "neutral"
    }
}
