package dev.nyx.injector.serialized

/** Readable names for the class ids we actually meet in bundle work. */
object ClassIds {
    private val names = mapOf(
        1 to "GameObject",
        4 to "Transform",
        21 to "Material",
        23 to "MeshRenderer",
        28 to "Texture2D",
        33 to "MeshFilter",
        43 to "Mesh",
        48 to "Shader",
        49 to "TextAsset",
        74 to "AnimationClip",
        83 to "AudioClip",
        89 to "Cubemap",
        95 to "Animator",
        104 to "RenderSettings",
        108 to "Light",
        114 to "MonoBehaviour",
        115 to "MonoScript",
        128 to "Font",
        142 to "AssetBundle",
        156 to "TerrainData",
        157 to "LightmapSettings",
        198 to "ParticleSystem",
        199 to "ParticleSystemRenderer",
        212 to "SpriteRenderer",
        213 to "Sprite",
        222 to "CanvasRenderer",
        223 to "Canvas",
        224 to "RectTransform",
        240 to "AudioSource",
    )

    fun name(id: Int): String = names[id] ?: "Class$id"
}
