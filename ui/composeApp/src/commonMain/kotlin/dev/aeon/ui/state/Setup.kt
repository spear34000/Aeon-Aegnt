package dev.aeon.ui.state

import dev.aeon.ui.api.Profile

/**
 * First-run flow, in order. Splash is not a step: it is shown briefly on every start while state is restored.
 * Connect is the existing pairing screen; it sits between the terms and the coach marks because the coach marks
 * run on the real main screen and the profile is saved on the server.
 */
enum class SetupStep { Intro, Welcome, Terms, Connect, Coach, Profile, Done }

const val INTRO_PAGES = 4
const val PROFILE_PAGES = 3

/**
 * What the owner agreed to on this device. [service] and [data] are required. [online] is only the choice made
 * during setup: the setting itself lives on the server (config.onlineLookups). [background] is per device.
 */
data class Consents(
    val service: Boolean = false,
    val data: Boolean = false,
    val online: Boolean = false,
    val background: Boolean = false,
    val acceptedAt: Long = 0,
) {
    val requiredOk: Boolean get() = service && data

    fun encode(): String = listOf(service, data, online, background).joinToString("") { if (it) "1" else "0" } + ":" + acceptedAt

    companion object {
        fun decode(s: String?): Consents {
            val flags = s?.substringBefore(':').orEmpty()
            if (flags.length != 4 || flags.any { it != '0' && it != '1' }) return Consents()
            return Consents(flags[0] == '1', flags[1] == '1', flags[2] == '1', flags[3] == '1', s?.substringAfter(':', "0")?.toLongOrNull() ?: 0)
        }
    }
}

data class SetupState(
    /** False until the saved progress has been read; the splash stays up until then. */
    val loaded: Boolean = false,
    val step: SetupStep = SetupStep.Intro,
    val introPage: Int = 0,
    val consents: Consents = Consents(),
    val profileDraft: Profile = Profile(),
    val profilePage: Int = 0,
    val coachIndex: Int = 0,
    /** The profile is saved on this device but the server has not received it yet; it is sent when the server answers. */
    val profilePending: Boolean = false,
    /** The choice about internet lookups still has to be applied to the server. */
    val onlinePending: Boolean = false,
    val saving: Boolean = false,
    /** Why the last save to the server failed, shown on the profile step with a way to retry or continue. */
    val error: String? = null,
    /** This device could not write its own settings store; progress is kept in memory only. */
    val storageError: Boolean = false,
    /** The intro or the coach marks are being shown again from the settings, not as part of the first run. */
    val replayIntro: Boolean = false,
    val replayCoach: Boolean = false,
) {
    val done: Boolean get() = step == SetupStep.Done
}
