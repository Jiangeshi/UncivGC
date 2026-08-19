package com.unciv.ui.screens.modeditor

import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table

/**
 * Table inside a ScrollPane whose cells need expandX/fillX to work.
 *
 * Problem: libGDX ScrollPane sets the widget to its preferred width.
 * If the widget's preferred width is small, expandX has no extra space.
 *
 * Fix: report the parent (ScrollPane) width as the preferred width.
 * The ScrollPane will then set the widget to that width, giving cells
 * room to expand.
 */
class FillWidthTable(skin: Skin) : Table(skin) {
    override fun getPrefWidth(): Float {
        val p = parent
        return if (p != null) p.width else super.getPrefWidth()
    }
}
