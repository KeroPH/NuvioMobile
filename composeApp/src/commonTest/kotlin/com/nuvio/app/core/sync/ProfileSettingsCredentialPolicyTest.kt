package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProfileSettingsCredentialPolicyTest {
    @Test
    fun `credential fields are removed from profile settings payloads`() {
        val payload = buildJsonObject {
            put("debrid_enabled", JsonPrimitive(true))
            put("debrid_torbox_api_key", JsonPrimitive("secret"))
            put("debrid_premiumize_api_key", JsonPrimitive("secret"))
        }

        val sanitized = withoutProfileCredentials(PROFILE_DEBRID_SETTINGS_FEATURE, payload)

        assertFalse("debrid_torbox_api_key" in sanitized)
        assertFalse("debrid_premiumize_api_key" in sanitized)
        assertEquals(JsonPrimitive(true), sanitized["debrid_enabled"])
    }

    @Test
    fun `legacy remote credential fields cannot overwrite local credentials`() {
        val remote = buildJsonObject {
            put("mdblist_enabled", JsonPrimitive(true))
            put("mdblist_api_key", JsonPrimitive("remote"))
        }
        val local = buildJsonObject {
            put("mdblist_api_key", JsonPrimitive("local"))
        }

        val merged = preservingLocalProfileCredentials(PROFILE_MDBLIST_SETTINGS_FEATURE, remote, local)

        assertEquals(JsonPrimitive("local"), merged["mdblist_api_key"])
        assertEquals(JsonPrimitive(true), merged["mdblist_enabled"])
    }

    @Test
    fun `internal or external player choice stays on each device`() {
        val local = buildJsonObject {
            put("external_player_enabled", JsonPrimitive("true"))
            put("external_player_id", JsonPrimitive("vlc"))
            put("resize_mode", JsonPrimitive("fit"))
        }
        val remote = buildJsonObject {
            put("external_player_enabled", JsonPrimitive("false"))
            put("external_player_id", JsonPrimitive("infuse"))
            put("external_player_forward_subtitles", JsonPrimitive("true"))
            put("resize_mode", JsonPrimitive("zoom"))
        }

        val uploaded = withoutProfileCredentials(PROFILE_PLAYER_SETTINGS_FEATURE, local)
        val merged = preservingLocalProfileCredentials(PROFILE_PLAYER_SETTINGS_FEATURE, remote, local)

        assertFalse("external_player_enabled" in uploaded)
        assertFalse("external_player_id" in uploaded)
        assertEquals(JsonPrimitive("fit"), uploaded["resize_mode"])
        assertEquals(JsonPrimitive("true"), merged["external_player_enabled"])
        assertEquals(JsonPrimitive("vlc"), merged["external_player_id"])
        assertFalse("external_player_forward_subtitles" in merged)
        assertEquals(JsonPrimitive("zoom"), merged["resize_mode"])
    }

    @Test
    fun `legacy TMDB credentials are discarded from local and remote settings`() {
        val remote = buildJsonObject {
            put("tmdb_enabled", JsonPrimitive(true))
            put("tmdb_api_key", JsonPrimitive("remote"))
        }
        val local = buildJsonObject {
            put("tmdb_api_key", JsonPrimitive("local"))
        }

        val merged = preservingLocalProfileCredentials(PROFILE_TMDB_SETTINGS_FEATURE, remote, local)
        val sanitized = withoutProfileCredentials(PROFILE_TMDB_SETTINGS_FEATURE, local)

        assertFalse("tmdb_api_key" in merged)
        assertFalse("tmdb_api_key" in sanitized)
        assertEquals(JsonPrimitive(true), merged["tmdb_enabled"])
    }
}
