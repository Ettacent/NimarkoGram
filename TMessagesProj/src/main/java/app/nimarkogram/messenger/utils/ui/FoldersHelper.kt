 
package app.nimarkogram.messenger.utils.ui

import android.content.Context
import android.graphics.RectF
import android.os.Build
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import app.nimarkogram.messenger.utils.folders.NimarkoFoldersHelper
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor
import org.telegram.ui.Components.blur3.capture.IBlur3Capture
import org.telegram.ui.DialogsActivity

object FoldersHelper {

    fun moveFoldersToBottom() : Boolean {
        return false
    }

    fun getFloatingButtonsOffset(filterTabsView: FilterTabsView?) : Int {
        return 0
    }

    fun setupFilterTabs(
        context: Context,
        contentView: ViewGroup,
        filterTabsView: FilterTabsView,
        resourceProvider: Theme.ResourcesProvider?,
        iBlur3FactoryLiquidGlass: BlurredBackgroundDrawableViewFactory,
        iBlur3FactoryFade: BlurredBackgroundDrawableViewFactory,
        inForwardMode: Boolean
    ) {

    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("FunctionName")
    fun blur3_InvalidateBlur(
        dialogsActivity: DialogsActivity,
        iBlur3Capture: IBlur3Capture,
        iBlur3PositionActionBar: RectF,
        iBlur3PositionFolders: RectF,
        iBlur3PositionMainTabs: RectF,
        iBlur3Positions: ArrayList<RectF>,
        scrollableViewNoiseSuppressor: DownscaleScrollableNoiseSuppressor
    ) {

    }

    fun updateFoldersOffset(
        dialogsActivity: DialogsActivity,
        inForwardMode: Boolean,
    ) {

    }

    fun getFolderColor(folderId: Int, defaultColor: Int): Int =
        NimarkoFoldersHelper.getFolderColor(folderId, defaultColor)

    fun setFolderColor(folderId: Int, color: Int) =
        NimarkoFoldersHelper.setFolderColor(folderId, color)

    fun getFolderBadgeMode(folderId: Int): Int =
        NimarkoFoldersHelper.getFolderBadgeMode(folderId)

    fun setFolderBadgeMode(folderId: Int, mode: Int) =
        NimarkoFoldersHelper.setFolderBadgeMode(folderId, mode)

    fun nextFolderId(accountNum: Int, currentId: Int, direction: Int): Int =
        NimarkoFoldersHelper.nextFolderId(accountNum, currentId, direction)

    fun shouldShowDot(folderId: Int, rawCount: Int): Boolean =
        NimarkoFoldersHelper.shouldShowDot(folderId, rawCount)
}
