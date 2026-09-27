package com.cardprice.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons not in material-icons-core (paths from Material Icons, Apache 2.0). */
object AppIcons {
    val Camera: ImageVector by lazy {
        icon(
            "Camera",
            "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0",
            "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9z" +
                "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
        )
    }

    /** Gallery view (Material "grid_view"). */
    val GridView: ImageVector by lazy {
        icon("GridView", "M3,3v8h8V3H3zM9,9H5V5h4V9zM3,13v8h8v-8H3zM9,19H5v-4h4V19zM13,3v8h8V3H13zM19,9h-4V5h4V9zM13,13v8h8v-8H13zM19,19h-4v-4h4V19z")
    }

    /** List view (Material "view_list"). */
    val ViewList: ImageVector by lazy {
        icon("ViewList", "M3,14h4v-4H3V14zM3,19h4v-4H3V19zM3,9h4V5H3V9zM8,14h13v-4H8V14zM8,19h13v-4H8V19zM8,5v4h13V5H8z")
    }

    /** Inventory (Material "inventory_2"). */
    val Inventory: ImageVector by lazy {
        icon(
            "Inventory",
            "M20,2H4C3,2 2,2.9 2,4v3.01c0,0.72 0.43,1.34 1,1.69V20c0,1.1 1.1,2 2,2h14c0.9,0 2,-0.9 2,-2V8.7c0.57,-0.35 1,-0.97 1,-1.69V4" +
                "C22,2.9 21,2 20,2zM19,20H5V9h14V20zM20,7H4V4h16V7z",
            "M9,12h6v2h-6z",
        )
    }

    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { builder.addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
        return builder.build()
    }
}
