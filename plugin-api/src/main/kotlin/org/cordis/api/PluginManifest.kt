package org.cordis.api

import org.cordis.ServiceKey

/**
 * What a plugin can do, declared by the plugin itself.
 *
 * The host used to learn this by reading the code: which service keys a plugin published, which
 * tools it registered, which permissions it asked for. That is why the settings page could only say
 * "已安装" and could not answer "this one gives the model a shell". A declaration moves that
 * knowledge into the container, where it can be shown, searched and checked.
 *
 * The rules are the usual contract rules: fields may be added with defaults, nothing is removed or
 * renamed, and an enum value must not be added unless every consumer handles it.
 */
data class PluginManifest(
    /** Container id; must equal the installed plugin's id or the host ignores the declaration. */
    val id: String,
    val name: String,
    val version: String,
    /** One sentence, for a list row. */
    val description: String,
    /** What it puts into the world, in the order a person should read it. */
    val capabilities: List<PluginCapability> = emptyList(),
    /** Configuration this plugin accepts; the shell renders it, the plugin reads it. */
    val settings: List<PluginSetting> = emptyList(),
    /** System permissions it declares in its container metadata. */
    val permissions: List<String> = emptyList(),
) {
    /** Long-form text for the detail page: why this plugin exists, not just what it publishes. */
    val notes: String get() = ""
}

/** One thing a plugin provides. */
data class PluginCapability(
    /** Stable id inside the plugin, e.g. the tool name or the service key. */
    val id: String,
    val kind: Kind,
    val title: String,
    val description: String = "",
) {
    enum class Kind {
        /** Publishes a service other plugins can resolve. */
        SERVICE,

        /** Contributes a tool the model can call. */
        TOOL,

        /** Draws UI: a page, the home surface, a theme. */
        UI,

        /** Background work: scheduling, watches, continuous listening. */
        BACKGROUND,
    }
}

/**
 * One configuration entry.
 *
 * Deliberately not a form builder: four kinds cover what the current plugins need, and a plugin that
 * wants something richer should publish its own page instead of stretching this into a schema.
 */
data class PluginSetting(
    val key: String,
    val title: String,
    val kind: Kind,
    val description: String = "",
    /** Rendered as the current value; a plugin may ignore it and read from its own storage. */
    val value: String = "",
    /** For [Kind.SELECT]. */
    val options: List<String> = emptyList(),
    /** For [Kind.ACTION]: the label of the button. */
    val actionLabel: String = "",
) {
    enum class Kind { BOOLEAN, TEXT, SELECT, ACTION }
}

/** Implemented by a plugin's entry class when it wants a declaration shown in the host UI. */
interface ManifestProvider {
    val manifest: PluginManifest
}

/**
 * Every declaration the host currently holds, for UI that wants to show or search them.
 *
 * The host owns this: a plugin cannot publish a manifest for another plugin, because the id is
 * checked against the container that produced it.
 */
interface PluginCatalogService {
    fun manifests(): List<PluginManifest>

    fun manifestOf(id: String): PluginManifest?

    companion object {
        val Key = ServiceKey<PluginCatalogService>("host/plugin-catalog")
    }
}
