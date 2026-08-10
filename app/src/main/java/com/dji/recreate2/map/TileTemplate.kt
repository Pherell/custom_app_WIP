package com.dji.recreate2.map

/**
 * Builds a tile URL from a template, so the map can be pointed at a different server without
 * rebuilding the app.
 *
 * The map used to hardcode an ArcGIS HTTPS address, while `docker-compose.yml` runs a
 * `tileserver-gl` container documented as serving tiles for an air-gapped network. With no
 * internet the map went blank - which is when a tactical map matters most.
 *
 * **CAUTION: the axis order differs between servers, and getting it wrong is worse than a blank
 * map.** ArcGIS World_Imagery serves `/{z}/{y}/{x}`. Standard XYZ servers - tileserver-gl, OSM -
 * serve `/{z}/{x}/{y}`. Swapping only the host would render a map made of correct tiles in the
 * wrong places: it still looks like a map, so the operator would trust it.
 *
 * The template therefore carries the ORDER, not just the host.
 */
object TileTemplate {

    /** The address the app used before this was configurable. Axis order y/x, as ArcGIS wants. */
    const val ARCGIS_SATELLITE =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** A local tileserver-gl. Standard XYZ order. Replace the host for your deployment. */
    const val LOCAL_TILESERVER = "http://10.12.0.15:8080/styles/satellite/{z}/{x}/{y}.png"

    /** True when the template has all three placeholders and can produce a real address. */
    fun isValid(template: String): Boolean =
        template.contains("{z}") && template.contains("{x}") && template.contains("{y}")

    /**
     * Substitutes one tile's coordinates into [template].
     *
     * @return the address, or null when the template is unusable - a caller must fall back to the
     *         default rather than request a malformed URL on every tile.
     */
    fun build(template: String, zoom: Int, x: Int, y: Int): String? {
        if (!isValid(template)) return null
        return template
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
    }

    /**
     * Largest tile index on either axis at [zoom]. Used to bound a pre-cache sweep so it cannot
     * ask for tiles that do not exist.
     */
    fun tilesPerAxis(zoom: Int): Int = 1 shl zoom.coerceIn(0, 22)
}
